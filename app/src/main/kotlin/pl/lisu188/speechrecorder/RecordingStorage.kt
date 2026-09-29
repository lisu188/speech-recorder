package pl.lisu188.speechrecorder

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object RecordingStorage {
    const val ACTION_LIBRARY_CHANGED = "pl.lisu188.speechrecorder.LIBRARY_CHANGED"
    const val INPUT_FILE = "recording_file"
    private const val LEGACY_RELATIVE_PATH = "Music/SpeechRecorder/"
    private val recoveryExecutor = Executors.newSingleThreadExecutor()
    private val migrationExecutor = Executors.newSingleThreadExecutor()
    private val recoveryLock = Any()

    data class StoredRecording(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    fun directory(context: Context): File = File(context.noBackupFilesDir, "recordings").also {
        if (!it.isDirectory && !it.mkdirs()) throw IOException("Unable to create recording storage")
    }

    fun newFile(context: Context, timestamp: Long = System.currentTimeMillis()): File {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))
        return File(directory(context), "speech_${time}_${UUID.randomUUID()}.wav")
    }

    fun enqueue(context: Context, file: File) {
        try {
            val work = OneTimeWorkRequestBuilder<RecordingPublishWorker>()
                .setInputData(Data.Builder().putString(INPUT_FILE, file.name).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("speech-recorder-onedrive")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "onedrive_${file.name}",
                ExistingWorkPolicy.REPLACE,
                work,
            )
        } catch (_: Exception) {
            reportError(context, "Nagranie zachowano lokalnie. Otwórz aplikację, aby ponowić zapis do OneDrive.")
        }
    }

    fun recover(context: Context) {
        val app = context.applicationContext
        recoveryExecutor.execute {
            synchronized(recoveryLock) {
                try {
                    migrateLegacy(app)
                    val pending = directory(app).listFiles().orEmpty()
                        .filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) }
                    if (pending.isNotEmpty() && !CloudFolderAccess.hasAccess(app)) {
                        reportError(app, "Nagrania czekają lokalnie. Wybierz folder OneDrive w Ustawieniach.")
                        return@synchronized
                    }
                    pending.forEach { enqueue(app, it) }
                } catch (_: Exception) {
                    reportError(app, "Nie udało się wznowić zapisu nagrań. Sprawdź pamięć telefonu i dostęp do OneDrive.")
                }
            }
        }
    }

    fun migrateMediaStore(context: Context) {
        val app = context.applicationContext
        migrationExecutor.execute {
            if (!CloudFolderAccess.hasAccess(app)) return@execute
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
            )
            try {
                app.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.IS_PENDING}=0",
                    arrayOf(LEGACY_RELATIVE_PATH),
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn) ?: continue
                        if (!name.endsWith(".wav", ignoreCase = true)) continue
                        val source = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                        val expectedSize = cursor.getLong(sizeColumn)
                        val copied = app.contentResolver.openInputStream(source)?.use { input ->
                            copyToCloud(app, name, input, expectedSize)
                        } ?: false
                        if (copied) app.contentResolver.delete(source, null, null)
                    }
                }
                libraryChanged(app)
            } catch (_: Exception) {
                reportError(app, "Nie udało się przenieść części starszych nagrań do OneDrive.")
            }
        }
    }

    fun listPublished(context: Context): List<StoredRecording> =
        CloudFolderAccess.listAudio(context).map {
            StoredRecording(it.uri, it.name, it.sizeBytes, it.lastModifiedMs)
        }

    fun deletePublished(context: Context, uri: Uri): Boolean {
        val deleted = CloudFolderAccess.delete(context, uri)
        if (deleted) libraryChanged(context)
        return deleted
    }

    internal fun migrateLegacy(context: Context) {
        val legacy = listOfNotNull(
            context.cacheDir,
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { File(it, "SpeechRecorder") },
        )
        legacy.flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.matches(Regex("speech_[a-zA-Z0-9_-]+\\.wav")) }
            .forEach { source ->
                val id = UUID.nameUUIDFromBytes(source.absolutePath.toByteArray(Charsets.UTF_8))
                val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(source.lastModified()))
                val target = File(directory(context), "speech_${time}_recovered_$id.wav")
                if (!target.exists()) {
                    val pending = File(target.parentFile, "${target.name}.tmp")
                    try {
                        FileOutputStream(pending).use { output ->
                            source.inputStream().use { it.copyTo(output) }
                            output.fd.sync()
                        }
                        if (!pending.renameTo(target)) throw IOException("Unable to recover recording")
                        target.setLastModified(source.lastModified())
                    } finally {
                        pending.delete()
                    }
                }
                if (target.length() == source.length()) source.delete()
            }
    }

    internal fun repairHeader(audio: RandomAccessFile): Long {
        if (audio.length() < 44L) throw IOException("Incomplete WAV header")
        val header = ByteArray(44)
        audio.seek(0)
        audio.readFully(header)
        fun text(offset: Int) = String(header, offset, 4, Charsets.US_ASCII)
        fun little(offset: Int, count: Int): Long = (0 until count).fold(0L) { value, index ->
            value or ((header[offset + index].toLong() and 255L) shl (8 * index))
        }
        if (text(0) != "RIFF" || text(8) != "WAVE" || text(12) != "fmt " ||
            text(36) != "data" || little(16, 4) != 16L || little(20, 2) != 1L ||
            little(22, 2) != 1L || little(24, 4) != 16000L || little(28, 4) != 32000L ||
            little(32, 2) != 2L || little(34, 2) != 16L
        ) throw IOException("Unsupported recording WAV header")
        val dataSize = (audio.length() - 44L) / 2L * 2L
        if (dataSize > 0xffffffffL - 36L) throw IOException("Recording exceeds WAV size limit")
        audio.setLength(44L + dataSize)
        fun writeSize(offset: Long, value: Long) {
            audio.seek(offset)
            repeat(4) { audio.write(((value ushr (8 * it)) and 255L).toInt()) }
        }
        writeSize(4, 36L + dataSize)
        writeSize(40, dataSize)
        audio.fd.sync()
        return dataSize
    }

    internal fun publish(context: Context, wavFile: File): Boolean {
        if (!wavFile.exists()) return true
        if (wavFile.length() <= 44L) return wavFile.delete()
        if (!CloudFolderAccess.hasAccess(context)) {
            reportError(context, "Nagranie zachowano lokalnie. Wybierz folder OneDrive w Ustawieniach.")
            return false
        }

        return try {
            val expectedSize = wavFile.length()
            wavFile.inputStream().use { input ->
                if (!copyToCloud(context, wavFile.name, input, expectedSize)) {
                    throw IOException("OneDrive write failed")
                }
            }
            if (!wavFile.delete()) throw IOException("Unable to remove local staging file")
            clearStorageErrorIfEmpty(context)
            libraryChanged(context)
            true
        } catch (_: Exception) {
            reportError(context, "Nagranie zachowano lokalnie. Zapis do OneDrive zostanie ponowiony.")
            false
        }
    }

    private fun copyToCloud(context: Context, name: String, input: InputStream, expectedSize: Long): Boolean {
        val target = CloudFolderAccess.openOrCreateAudio(context, name)
        val written = context.contentResolver.openOutputStream(target, "wt")?.buffered()?.use { output ->
            input.copyTo(output, 32768)
        } ?: throw IOException("OneDrive output stream unavailable")
        if (expectedSize > 0L && written != expectedSize) {
            throw IOException("OneDrive write was incomplete")
        }
        return true
    }

    private fun clearStorageErrorIfEmpty(context: Context) {
        if (directory(context).listFiles().orEmpty().none { it.name.endsWith(".wav", ignoreCase = true) }) {
            context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit().remove("storage_error").apply()
        }
    }

    fun libraryChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_LIBRARY_CHANGED).setPackage(context.packageName))
    }

    private fun reportError(context: Context, message: String) {
        Log.w("SpeechRecorder", message)
        context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit()
            .putString("storage_error", message)
            .apply()
        libraryChanged(context)
    }
}

class RecordingPublishWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val name = inputData.getString(RecordingStorage.INPUT_FILE) ?: return Result.failure()
        if (!name.matches(Regex("speech_[a-zA-Z0-9_-]+\\.wav"))) return Result.failure()
        return try {
            val file = File(RecordingStorage.directory(applicationContext), name)
            if (!file.isFile) return Result.success()
            RandomAccessFile(file, "rw").use { audio ->
                val lock = try {
                    audio.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) return Result.retry()
                lock.use {
                    val bytes = RecordingStorage.repairHeader(audio)
                    if (bytes == 0L) {
                        return if (file.delete()) Result.success() else Result.retry()
                    }
                    if (RecordingStorage.publish(applicationContext, file)) Result.success() else Result.retry()
                }
            }
        } catch (_: IOException) {
            Result.retry()
        } catch (_: SecurityException) {
            Result.retry()
        }
    }
}
