package pl.lisu188.speechrecorder

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingStorageTest {
    private lateinit var context: Context
    private lateinit var provider: PublicationProvider

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        File(context.noBackupFilesDir, "recordings").deleteRecursively()
        context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit().clear().commit()
        provider = PublicationProvider(File(context.cacheDir, "media-provider").apply { mkdirs() })
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun recordingsUseDurableStorageAndCollisionFreeNames() {
        val first = RecordingStorage.newFile(context, 1234567890000)
        val second = RecordingStorage.newFile(context, 1234567890000)
        assertNotEquals(first.name, second.name)
        assertTrue(first.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(first.canonicalPath.startsWith(context.cacheDir.canonicalPath))
    }

    @Test fun repairsUnfinishedHeaderWithoutChangingAudio() {
        val pcm = ByteArray(640) { (it % 127).toByte() }
        val file = recording(pcm)
        RandomAccessFile(file, "rw").use { audio ->
            audio.seek(4); audio.writeInt(0)
            audio.seek(40); audio.writeInt(0)
            assertEquals(640L, RecordingStorage.repairHeader(audio))
        }
        val restored = file.readBytes()
        assertArrayEquals(pcm, restored.copyOfRange(44, restored.size))
        assertEquals(676, ByteBuffer.wrap(restored).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertEquals(640, ByteBuffer.wrap(restored).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test fun dropsOnlyIncompleteFinalSample() {
        val file = recording(byteArrayOf(1, 2, 3, 4, 5))
        RandomAccessFile(file, "rw").use { assertEquals(4L, RecordingStorage.repairHeader(it)) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes().drop(44).toByteArray())
    }

    @Test fun unsupportedHeaderIsNotDestroyed() {
        val file = recording()
        val original = file.readBytes().apply { this[24] = 1 }
        file.writeBytes(original)
        RandomAccessFile(file, "rw").use { audio ->
            assertThrows(IOException::class.java) { RecordingStorage.repairHeader(audio) }
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun truncatedHeaderIsPreserved() {
        val file = RecordingStorage.newFile(context).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        RandomAccessFile(file, "rw").use { audio ->
            assertThrows(IOException::class.java) { RecordingStorage.repairHeader(audio) }
        }
        assertEquals(3L, file.length())
    }

    @Test fun openSinkExcludesRecoveryUntilClosed() {
        val file = RecordingStorage.newFile(context)
        val sink = RecorderService.WavSink(file, 16000)
        sink.write(shortArrayOf(1, -2, 300), 3)
        RandomAccessFile(file, "rw").use { audio ->
            assertThrows(OverlappingFileLockException::class.java) { audio.channel.tryLock() }
        }
        assertEquals(ListenableWorker.Result.retry(), worker(file.name).doWork())
        assertTrue(file.exists())
        sink.closeAndGetFile()
        RandomAccessFile(file, "rw").use { audio ->
            audio.channel.tryLock().use { assertNotNull(it) }
        }
        assertEquals(ListenableWorker.Result.success(), worker(file.name).doWork())
        assertFalse(file.exists())
        assertEquals(1, provider.insertions)
    }

    @Test fun workerPublishesRecoveredAudioAndSetsDuration() {
        val file = recording(ByteArray(32000) { 11 })
        RandomAccessFile(file, "rw").use { it.seek(40); it.writeInt(0) }
        assertEquals(ListenableWorker.Result.success(), worker(file.name).doWork())
        assertFalse(file.exists())
        assertEquals(1000L, provider.rows.values.single().values.getAsLong(MediaStore.Audio.Media.DURATION))
        assertEquals(0, provider.rows.values.single().values.getAsInteger(MediaStore.Audio.Media.IS_PENDING))
        assertEquals(32044L, provider.rows.values.single().file.length())
    }

    @Test fun workerNeverAcceptsPathTraversal() {
        assertEquals(ListenableWorker.Result.failure(), worker("../../secret.wav").doWork())
        assertEquals(0, provider.insertions)
    }

    @Test fun missingFileIsIdempotentSuccess() {
        assertEquals(ListenableWorker.Result.success(), worker("speech_missing.wav").doWork())
        assertEquals(0, provider.insertions)
    }

    @Test fun headerOnlyRecordingIsRemovedWithoutPublishing() {
        val file = recording(ByteArray(0))
        assertEquals(ListenableWorker.Result.success(), worker(file.name).doWork())
        assertFalse(file.exists())
        assertEquals(0, provider.insertions)
    }

    @Test fun failedPublicationRetainsAudioForSuccessfulRetry() {
        provider.failPublication = true
        val file = recording()
        val original = file.readBytes()
        assertEquals(ListenableWorker.Result.retry(), worker(file.name).doWork())
        assertArrayEquals(original, file.readBytes())
        assertTrue(provider.rows.isEmpty())
        provider.failPublication = false
        assertEquals(ListenableWorker.Result.success(), worker(file.name).doWork())
        assertFalse(file.exists())
        assertEquals(1, provider.rows.size)
    }

    @Test fun failedInsertRetainsLocalCopy() {
        provider.failInsert = true
        val file = recording()
        assertFalse(RecordingStorage.publish(context, file) { fail("Incomplete audio was queued") })
        assertTrue(file.exists())
    }

    @Test fun schedulingFailureNeverDeletesPublishedAudio() {
        val file = recording()
        assertTrue(RecordingStorage.publish(context, file) { throw IllegalStateException("queue failed") })
        assertEquals(1, provider.rows.size)
        assertEquals(0, provider.rows.values.single().values.getAsInteger(MediaStore.Audio.Media.IS_PENDING))
        assertFalse(file.exists())
    }

    @Test fun retryReusesPublishedReceiptEvenAfterTranscriptRenamesAudio() {
        val file = recording()
        val bytes = file.readBytes()
        val uri = provider.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Already_transcribed.wav")
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        })!!
        provider.rows.getValue(ContentUris.parseId(uri)).file.writeBytes(bytes)
        File(file.parentFile, "${file.name}.media").writeText(uri.toString())
        assertTrue(RecordingStorage.publish(context, file) {})
        assertEquals(1, provider.insertions)
        assertArrayEquals(bytes, provider.rows.values.single().file.readBytes())
        assertEquals("Already_transcribed.wav", provider.rows.values.single().values.getAsString(MediaStore.Audio.Media.DISPLAY_NAME))
    }

    @Test fun retryFindsPendingInsertBeforeReceiptWasWritten() {
        val file = recording()
        provider.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        })
        assertTrue(RecordingStorage.publish(context, file) {})
        assertEquals(1, provider.insertions)
        assertEquals(0, provider.rows.values.single().values.getAsInteger(MediaStore.Audio.Media.IS_PENDING))
    }

    @Test fun legacyCacheAndFallbackFilesAreMigratedWithoutOverwriting() {
        val first = File(context.cacheDir, "speech_legacy.wav").apply { writeBytes(wav(byteArrayOf(1, 2))) }
        val fallback = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "SpeechRecorder")
            .apply { mkdirs() }
        val second = File(fallback, "speech_legacy.wav").apply { writeBytes(wav(byteArrayOf(3, 4))) }
        RecordingStorage.migrateLegacy(context)
        RecordingStorage.migrateLegacy(context)
        val recovered = RecordingStorage.directory(context).listFiles()!!.filter { it.extension == "wav" }
        assertEquals(2, recovered.size)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals(setOf(1.toByte(), 3.toByte()), recovered.map { it.readBytes()[44] }.toSet())
    }

    @Test fun missingMicrophonePermissionStopsServiceWithoutCrash() {
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(Intent(context, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertFalse(context.getSharedPreferences("recorder", Context.MODE_PRIVATE).getBoolean("enabled", true))
        assertFalse(RecorderService.isRunning)
        controller.destroy()
    }

    private fun worker(name: String): RecordingPublishWorker = TestWorkerBuilder.from(
        context, RecordingPublishWorker::class.java, Executor { it.run() },
    ).setInputData(Data.Builder().putString(RecordingStorage.INPUT_FILE, name).build()).build()

    private fun recording(pcm: ByteArray = ByteArray(320) { 9 }): File =
        RecordingStorage.newFile(context).apply { writeBytes(wav(pcm)) }

    private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        put("data".toByteArray()).putInt(pcm.size).put(pcm)
    }.array()

    private class PublicationProvider(private val directory: File) : ContentProvider() {
        data class Row(val values: ContentValues, val file: File)
        val rows = linkedMapOf<Long, Row>()
        var insertions = 0
        var failPublication = false
        var failInsert = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (failInsert) return null
            val id = (++insertions).toLong()
            rows[id] = Row(ContentValues(values), File(directory, "$id.wav"))
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            if (failPublication) return 0
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            row.values.putAll(values)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val row = rows.remove(ContentUris.parseId(uri)) ?: return 0
            row.file.delete()
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            rows.getValue(ContentUris.parseId(uri)).file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val columns = projection ?: arrayOf(MediaStore.Audio.Media._ID)
            val id = uri.lastPathSegment?.toLongOrNull()
            return MatrixCursor(columns).apply {
                rows.forEach { (key, row) ->
                    if (id != null && key != id) return@forEach
                    if (id == null && args?.getOrNull(1) != row.values.getAsString(MediaStore.Audio.Media.DISPLAY_NAME)) return@forEach
                    addRow(columns.map { if (it == MediaStore.Audio.Media._ID) key else row.values.get(it) }.toTypedArray())
                }
            }
        }
        override fun getType(uri: Uri) = "audio/wav"
    }
}
