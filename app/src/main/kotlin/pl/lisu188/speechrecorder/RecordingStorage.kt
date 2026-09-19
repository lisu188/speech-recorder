package pl.lisu188.speechrecorder

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
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
    private val recoveryExecutor = Executors.newSingleThreadExecutor()
    private val recoveryLock = Any()

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
                .addTag("speech-recorder-publication")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "publish_${file.name}", ExistingWorkPolicy.KEEP, work,
            )
        } catch (_: Exception) {
            reportError(context, "Nagranie zachowano lokalnie. Otwórz aplikację ponownie, aby wznowić zapis.")
        }
    }

    fun recover(context: Context) {
        val app = context.applicationContext
        recoveryExecutor.execute {
            synchronized(recoveryLock) {
                try {
                    migrateLegacy(app)
                    directory(app).listFiles().orEmpty()
                        .filter { it.isFile && it.name.endsWith(".wav") }
                        .forEach { enqueue(app, it) }
                } catch (_: Exception) {
                    reportError(app, "Nie udało się wznowić zapisu nagrań. Sprawdź wolne miejsce na telefonie.")
                }
            }
        }
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

    internal fun publish(
        context: Context,
        wavFile: File,
        schedule: (Uri) -> Unit = { TranscriptionScheduler.enqueue(context, it); Unit },
    ): Boolean {
        if (!wavFile.exists()) return true
        if (wavFile.length() <= 44L) return wavFile.delete()
        val resolver = context.contentResolver
        val receipt = AtomicFile(File(wavFile.parentFile, "${wavFile.name}.media"))
        var uri: Uri? = null
        var published = false
        try {
            val recordedUri = try {
                Uri.parse(receipt.openRead().bufferedReader().use { it.readText() })
            } catch (_: IOException) { null }
            uri = recordedUri?.takeIf {
                it.scheme == "content" && it.authority == "media" && publicationState(context, it) != null
            }
                ?: findPendingPublication(context, wavFile.name)
            if (uri == null) {
                uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, wavFile.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, TranscriptStore.RELATIVE_PATH)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }) ?: throw IOException("MediaStore insert failed")
            }
            val destination = uri
            val pending = receipt.startWrite()
            try {
                pending.write(destination.toString().toByteArray(Charsets.UTF_8))
                receipt.finishWrite(pending)
            } catch (error: Exception) {
                receipt.failWrite(pending)
                throw error
            }
            published = publicationState(context, destination) == false
            if (!published) {
                val stream = resolver.openOutputStream(destination, "wt")
                    ?: throw IOException("MediaStore output stream unavailable")
                stream.buffered().use { output -> wavFile.inputStream().use { it.copyTo(output, 32768) } }
                val changed = resolver.update(destination, ContentValues().apply {
                    put(MediaStore.Audio.Media.DURATION, (wavFile.length() - 44L) * 1000L / 32000L)
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }, null, null)
                if (changed <= 0) throw IOException("MediaStore publication failed")
                published = true
            }
            try {
                schedule(destination)
            } catch (_: Exception) {
                Log.w("SpeechRecorder", "Recording saved; transcription could not be queued")
            }
            if (wavFile.delete()) receipt.delete()
            if (directory(context).listFiles().orEmpty().none { it.name.endsWith(".wav") }) {
                context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit().remove("storage_error").apply()
            }
            libraryChanged(context)
            return !wavFile.exists()
        } catch (_: Exception) {
            if (!published) {
                try {
                    if (uri != null && resolver.delete(uri, null, null) > 0) receipt.delete()
                } catch (_: Exception) {
                }
            }
            reportError(context, "Nagranie zachowano lokalnie. Zapis zostanie ponowiony; sprawdź wolne miejsce.")
            return false
        }
    }

    private fun publicationState(context: Context, uri: Uri): Boolean? {
        queryIncludingPending(context, uri, arrayOf(MediaStore.Audio.Media.IS_PENDING))?.use {
            if (it.moveToFirst()) return it.getInt(0) != 0
        }
        return null
    }

    private fun findPendingPublication(context: Context, name: String): Uri? {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        queryIncludingPending(
            context, collection, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH}=? AND ${MediaStore.Audio.Media.DISPLAY_NAME}=?",
            arrayOf(TranscriptStore.RELATIVE_PATH_QUERY, name),
        )?.use { if (it.moveToFirst()) return ContentUris.withAppendedId(collection, it.getLong(0)) }
        return null
    }

    @Suppress("DEPRECATION")
    private fun queryIncludingPending(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        arguments: Array<String>? = null,
    ): Cursor? = if (Build.VERSION.SDK_INT >= 30) {
        context.contentResolver.query(uri, projection, Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments)
        }, null)
    } else {
        context.contentResolver.query(MediaStore.setIncludePending(uri), projection, selection, arguments, null)
    }

    fun libraryChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_LIBRARY_CHANGED).setPackage(context.packageName))
    }

    private fun reportError(context: Context, message: String) {
        Log.w("SpeechRecorder", message)
        context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit()
            .putString("storage_error", message).apply()
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
                val lock = try { audio.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
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
