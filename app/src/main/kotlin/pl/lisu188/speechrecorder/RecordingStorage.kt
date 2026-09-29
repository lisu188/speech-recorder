package pl.lisu188.speechrecorder

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RecordingStorage {
    const val ACTION_LIBRARY_CHANGED = "pl.lisu188.speechrecorder.LIBRARY_CHANGED"
    const val INPUT_FILE = "recording_file"
    private const val LEGACY_RELATIVE_PATH = "Music/SpeechRecorder/"
    private const val LIVE_PREFIX = "__sr_live_"
    private const val ARCHIVE_WORK = "speech-recorder-archive-maintenance"
    private const val ARCHIVE_NOW_WORK = "speech-recorder-archive-now"
    private const val ARCHIVE_AFTER_DAYS = 30L
    private const val ARCHIVE_AFTER_MS = ARCHIVE_AFTER_DAYS * 24L * 60L * 60L * 1000L
    private const val MAX_ARCHIVES_PER_RUN = 8
    private val recoveryExecutor = Executors.newSingleThreadExecutor()
    private val migrationExecutor = Executors.newSingleThreadExecutor()
    private val cloudUploadExecutor = Executors.newSingleThreadExecutor()
    private val recoveryLock = Any()
    private val archiveLock = Any()

    data class StoredRecording(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val archived: Boolean,
    )

    fun directory(context: Context): File = File(context.noBackupFilesDir, "recordings").also {
        if (!it.isDirectory && !it.mkdirs()) throw IOException("Unable to create recording storage")
    }

    fun liveDirectory(context: Context): File = File(context.noBackupFilesDir, "live-parts").also {
        if (!it.isDirectory && !it.mkdirs()) throw IOException("Unable to create live recording storage")
    }

    fun newFile(context: Context, timestamp: Long = System.currentTimeMillis()): File {
        val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))
        return File(directory(context), "speech_${time}_${UUID.randomUUID()}.wav")
    }

    fun newLivePartFile(context: Context, finalName: String, part: Int): File =
        File(liveDirectory(context), livePartName(finalName, part))

    internal fun livePartName(finalName: String, part: Int): String {
        require(finalName.matches(FINAL_NAME_REGEX))
        require(part > 0)
        return "$LIVE_PREFIX${finalName.removeSuffix(".wav")}_part${part.toString().padStart(4, '0')}.wav"
    }

    internal fun finalNameForLivePart(name: String): String? =
        LIVE_NAME_REGEX.matchEntire(name)?.groupValues?.get(1)?.let { "$it.wav" }

    internal fun livePrefix(finalName: String): String =
        "$LIVE_PREFIX${finalName.removeSuffix(".wav")}_part"

    internal fun commitMarkerName(finalName: String): String {
        require(finalName.matches(FINAL_NAME_REGEX))
        return "commit_${finalName.removeSuffix(".wav")}.ok"
    }

    internal fun archiveName(name: String): String = "$name.zip"

    internal fun recordingTimestamp(name: String, lastModifiedMs: Long): Long =
        timestampFromName(name).takeIf { it > 0L } ?: lastModifiedMs.takeIf { it > 0L } ?: 0L

    internal fun shouldArchive(name: String, lastModifiedMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!name.endsWith(".wav", ignoreCase = true) || name.startsWith(LIVE_PREFIX)) return false
        val timestamp = recordingTimestamp(name, lastModifiedMs)
        return timestamp > 0L && nowMs - timestamp >= ARCHIVE_AFTER_MS
    }

    internal fun archiveConstraints(): Constraints =
        Constraints.Builder()
            .setRequiresCharging(true)
            .build()

    fun enqueue(context: Context, file: File) {
        try {
            val work = OneTimeWorkRequestBuilder<RecordingPublishWorker>()
                .setInputData(Data.Builder().putString(INPUT_FILE, file.name).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("speech-recorder-onedrive")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                chainName(file.name),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work,
            )
        } catch (_: Exception) {
            reportError(context, "Nagranie zachowano lokalnie. Otwórz aplikację, aby ponowić zapis do OneDrive.")
        }
    }

    fun enqueueCompleted(context: Context, file: File) {
        val app = context.applicationContext
        try {
            cloudUploadExecutor.execute {
                if (!publish(app, file)) enqueue(app, file)
            }
        } catch (_: Exception) {
            enqueue(app, file)
        }
    }

    fun enqueueLivePart(context: Context, file: File) {
        val app = context.applicationContext
        try {
            cloudUploadExecutor.execute {
                if (!publishLivePart(app, file)) scheduleLivePartWorker(app, file)
            }
        } catch (_: Exception) {
            scheduleLivePartWorker(app, file)
        }
    }

    private fun scheduleLivePartWorker(context: Context, file: File) {
        try {
            val finalName = finalNameForLivePart(file.name) ?: return
            val work = OneTimeWorkRequestBuilder<LivePartPublishWorker>()
                .setInputData(Data.Builder().putString(INPUT_FILE, file.name).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("speech-recorder-live-onedrive")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                chainName(finalName),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work,
            )
        } catch (_: Exception) {
            reportError(context, "Fragment nagrania czeka lokalnie na zapis do OneDrive.")
        }
    }

    fun scheduleArchiveMaintenance(context: Context) {
        if (!CloudFolderAccess.hasAccess(context)) return
        try {
            val periodic = PeriodicWorkRequestBuilder<ArchiveMaintenanceWorker>(24, TimeUnit.HOURS)
                .setConstraints(archiveConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("speech-recorder-archive")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                ARCHIVE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
            enqueueArchiveNow(context)
        } catch (_: Exception) {
        }
    }

    fun enqueueArchiveNow(context: Context) {
        if (!CloudFolderAccess.hasAccess(context)) return
        try {
            val work = OneTimeWorkRequestBuilder<ArchiveMaintenanceWorker>()
                .setConstraints(archiveConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("speech-recorder-archive")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ARCHIVE_NOW_WORK,
                ExistingWorkPolicy.KEEP,
                work,
            )
        } catch (_: Exception) {
        }
    }

    fun recover(context: Context) {
        val app = context.applicationContext
        recoveryExecutor.execute {
            synchronized(recoveryLock) {
                try {
                    migrateLegacy(app)
                    val liveParts = liveDirectory(app).listFiles().orEmpty()
                        .filter { it.isFile && finalNameForLivePart(it.name) != null }
                        .sortedBy { it.name }
                    val pending = directory(app).listFiles().orEmpty()
                        .filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) }
                    if ((pending.isNotEmpty() || liveParts.isNotEmpty()) && !CloudFolderAccess.hasAccess(app)) {
                        reportError(app, "Nagrania czekają lokalnie. Wybierz folder OneDrive w Ustawieniach.")
                        return@synchronized
                    }
                    liveParts.forEach { scheduleLivePartWorker(app, it) }
                    pending.forEach { enqueue(app, it) }
                    scheduleArchiveMaintenance(app)
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
                            try {
                                copyToCloudVerified(app, name, "audio/wav", input, expectedSize)
                                true
                            } catch (_: Exception) {
                                false
                            }
                        } ?: false
                        if (copied) app.contentResolver.delete(source, null, null)
                    }
                }
                libraryChanged(app)
                scheduleArchiveMaintenance(app)
            } catch (_: Exception) {
                reportError(app, "Nie udało się przenieść części starszych nagrań do OneDrive.")
            }
        }
    }

    fun listPublished(context: Context): List<StoredRecording> =
        CloudFolderAccess.listAudio(context).map {
            StoredRecording(it.uri, it.name, it.sizeBytes, it.lastModifiedMs, it.archived)
        }

    fun deletePublished(context: Context, uri: Uri, name: String? = null): Boolean {
        val deleted = CloudFolderAccess.delete(context, uri)
        if (deleted) {
            name?.takeIf { it.matches(FINAL_NAME_REGEX) }?.let {
                CloudFolderAccess.deleteLiveExact(context, commitMarkerName(it))
                CloudFolderAccess.deleteLiveByPrefix(context, livePrefix(it))
            }
            libraryChanged(context)
        }
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

        val marker = commitMarkerName(wavFile.name)
        return try {
            if (!CloudFolderAccess.liveExists(context, marker)) {
                val expectedSize = wavFile.length()
                wavFile.inputStream().use { input ->
                    copyToCloudVerified(context, wavFile.name, "audio/wav", input, expectedSize)
                }
                writeCommitReceipt(context, marker, expectedSize)
            }
            CloudFolderAccess.deleteLiveByPrefix(context, livePrefix(wavFile.name))
            CloudFolderAccess.deleteLegacyRootByPrefix(context, livePrefix(wavFile.name))
            liveDirectory(context).listFiles().orEmpty()
                .filter { it.name.startsWith(livePrefix(wavFile.name)) }
                .forEach { it.delete() }
            if (!wavFile.delete()) throw IOException("Unable to remove local staging file")
            clearStorageErrorIfEmpty(context)
            libraryChanged(context)
            true
        } catch (_: Exception) {
            reportError(context, "Nagranie zachowano lokalnie. Zapis do OneDrive zostanie ponowiony.")
            false
        }
    }

    internal fun publishLivePart(context: Context, file: File): Boolean {
        val finalName = finalNameForLivePart(file.name) ?: return false
        if (!file.exists()) return true
        if (!CloudFolderAccess.hasAccess(context)) return false
        val marker = commitMarkerName(finalName)
        return try {
            if (!CloudFolderAccess.liveExists(context, marker)) {
                val expectedSize = file.length()
                file.inputStream().use { input ->
                    copyLiveToCloudVerified(context, file.name, "audio/wav", input, expectedSize)
                }
                if (CloudFolderAccess.liveExists(context, marker)) {
                    CloudFolderAccess.deleteLiveByPrefix(context, livePrefix(finalName))
                    CloudFolderAccess.deleteLegacyRootByPrefix(context, livePrefix(finalName))
                }
            } else {
                CloudFolderAccess.deleteLiveByPrefix(context, livePrefix(finalName))
                CloudFolderAccess.deleteLegacyRootByPrefix(context, livePrefix(finalName))
            }
            if (!file.delete()) throw IOException("Unable to remove uploaded live part")
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun archiveOldRecordings(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(archiveLock) {
            if (!CloudFolderAccess.hasAccess(context)) return@synchronized true
            val candidates = listPublished(context)
                .filter { !it.archived && shouldArchive(it.name, it.lastModifiedMs, nowMs) }
                .sortedBy { recordingTimestamp(it.name, it.lastModifiedMs) }
                .take(MAX_ARCHIVES_PER_RUN)
            var success = true
            var changed = false
            candidates.forEach { recording ->
                if (archiveRecording(context, recording)) changed = true else success = false
            }
            if (changed) libraryChanged(context)
            success
        }

    internal fun archiveRecording(context: Context, recording: StoredRecording): Boolean {
        val archiveUri = try {
            CloudFolderAccess.openOrCreateFile(context, archiveName(recording.name), "application/zip")
        } catch (_: Exception) {
            return false
        }
        return try {
            val input = context.contentResolver.openInputStream(recording.uri)
                ?: throw IOException("OneDrive source stream unavailable")
            val rawOutput = context.contentResolver.openOutputStream(archiveUri, "wt")
                ?: throw IOException("OneDrive archive stream unavailable")
            val countedOutput = CountingOutputStream(rawOutput)
            val copied = input.use { source -> countedOutput.use { target -> writeZip(source, target, recording.name) } }
            if (recording.sizeBytes > 0L && copied != recording.sizeBytes) {
                throw IOException("Archive input was incomplete")
            }
            val archivedBytes = countedOutput.bytesWritten
            val remoteSize = CloudFolderAccess.fileSize(context, archiveUri)
                ?: throw IOException("OneDrive did not report archive size")
            if (archivedBytes <= 0L || remoteSize != archivedBytes) {
                throw IOException("OneDrive archive size verification failed")
            }
            if (!CloudFolderAccess.delete(context, recording.uri)) {
                throw IOException("Unable to remove archived WAV")
            }
            CloudFolderAccess.deleteLiveExact(context, commitMarkerName(recording.name))
            CloudFolderAccess.deleteLiveByPrefix(context, livePrefix(recording.name))
            CloudFolderAccess.deleteLegacyRootByPrefix(context, livePrefix(recording.name))
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun writeZip(input: InputStream, output: OutputStream, entryName: String): Long {
        var copied = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(entryName))
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                zip.write(buffer, 0, read)
                copied += read
            }
            zip.closeEntry()
            zip.finish()
        }
        return copied
    }

    private fun copyToCloudVerified(
        context: Context,
        name: String,
        mimeType: String,
        input: InputStream,
        expectedSize: Long,
    ): Uri {
        val target = CloudFolderAccess.openOrCreateFile(context, name, mimeType)
        copyAndVerify(context, target, input, expectedSize)
        return target
    }

    private fun copyLiveToCloudVerified(
        context: Context,
        name: String,
        mimeType: String,
        input: InputStream,
        expectedSize: Long,
    ): Uri {
        val target = CloudFolderAccess.openOrCreateLiveFile(context, name, mimeType)
        copyAndVerify(context, target, input, expectedSize)
        return target
    }

    private fun copyAndVerify(context: Context, target: Uri, input: InputStream, expectedSize: Long) {
        val written = context.contentResolver.openOutputStream(target, "wt")?.buffered()?.use { output ->
            input.copyTo(output, 32768)
        } ?: throw IOException("OneDrive output stream unavailable")
        if (written != expectedSize) {
            throw IOException("OneDrive write was incomplete")
        }
        val remoteSize = awaitRemoteSize(context, target, expectedSize)
        if (remoteSize != expectedSize) {
            throw IOException("OneDrive file size verification failed")
        }
    }

    private fun writeCommitReceipt(context: Context, markerName: String, expectedSize: Long) {
        val payload = expectedSize.toString().toByteArray(Charsets.US_ASCII)
        val target = CloudFolderAccess.openOrCreateLiveFile(context, markerName, "application/octet-stream")
        payload.inputStream().use { input -> copyAndVerify(context, target, input, payload.size.toLong()) }
    }

    private fun awaitRemoteSize(context: Context, target: Uri, expectedSize: Long): Long? {
        repeat(4) { attempt ->
            val size = CloudFolderAccess.fileSize(context, target)
            if (size == expectedSize) return size
            if (attempt < 3) {
                try {
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return size
                }
            }
        }
        return CloudFolderAccess.fileSize(context, target)
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var bytesWritten = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            bytesWritten++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }
    }

    private fun clearStorageErrorIfEmpty(context: Context) {
        val fullPending = directory(context).listFiles().orEmpty().any { it.name.endsWith(".wav", ignoreCase = true) }
        val livePending = liveDirectory(context).listFiles().orEmpty().any { finalNameForLivePart(it.name) != null }
        if (!fullPending && !livePending) {
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

    private fun chainName(fileName: String): String {
        val finalName = finalNameForLivePart(fileName) ?: fileName
        return "onedrive_${finalName.removeSuffix(".wav")}"
    }

    private fun timestampFromName(name: String): Long {
        val match = Regex("^speech_(\\d{8})_(\\d{6})").find(name) ?: return 0L
        return try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { isLenient = false }
                .parse("${match.groupValues[1]}_${match.groupValues[2]}")
                ?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private val FINAL_NAME_REGEX = Regex("speech_[a-zA-Z0-9_-]+\\.wav")
    private val LIVE_NAME_REGEX = Regex("^${Regex.escape(LIVE_PREFIX)}(speech_[a-zA-Z0-9_-]+)_part\\d{4}\\.wav$")
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

class LivePartPublishWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val name = inputData.getString(RecordingStorage.INPUT_FILE) ?: return Result.failure()
        if (RecordingStorage.finalNameForLivePart(name) == null) return Result.failure()
        val file = File(RecordingStorage.liveDirectory(applicationContext), name)
        if (!file.isFile) return Result.success()
        return if (RecordingStorage.publishLivePart(applicationContext, file)) Result.success() else Result.retry()
    }
}

class ArchiveMaintenanceWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result =
        try {
            if (RecordingStorage.archiveOldRecordings(applicationContext)) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
}
