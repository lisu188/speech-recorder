package pl.lisu188.speechrecorder

import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        File(context.noBackupFilesDir, "recordings").deleteRecursively()
        context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit().clear().commit()
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
            audio.seek(4)
            audio.writeInt(0)
            audio.seek(40)
            audio.writeInt(0)
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

    @Test fun activeSinkKeepsExclusiveLockUntilClosed() {
        val file = RecordingStorage.newFile(context)
        val sink = RecorderService.WavSink(file, 16000)
        sink.write(shortArrayOf(1, -2, 300), 3)
        RandomAccessFile(file, "rw").use { audio ->
            assertThrows(OverlappingFileLockException::class.java) { audio.channel.tryLock() }
        }
        sink.closeAndGetFile()
        RandomAccessFile(file, "rw").use { audio ->
            audio.channel.tryLock().use { assertNotNull(it) }
        }
    }

    @Test fun workerNeverAcceptsPathTraversal() {
        assertEquals(ListenableWorker.Result.failure(), worker("../../secret.wav").doWork())
    }

    @Test fun missingFileIsIdempotentSuccess() {
        assertEquals(ListenableWorker.Result.success(), worker("speech_missing.wav").doWork())
    }

    @Test fun headerOnlyRecordingIsRemovedWithoutCloudAccess() {
        val file = recording(ByteArray(0))
        assertEquals(ListenableWorker.Result.success(), worker(file.name).doWork())
        assertFalse(file.exists())
    }

    @Test fun validRecordingIsRetainedUntilOneDriveIsConfigured() {
        val file = recording()
        val original = file.readBytes()
        assertEquals(ListenableWorker.Result.retry(), worker(file.name).doWork())
        assertTrue(file.exists())
        assertArrayEquals(original, file.readBytes())
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
        val result = service.onStartCommand(
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_START),
            0,
            1,
        )
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertFalse(context.getSharedPreferences("recorder", Context.MODE_PRIVATE).getBoolean("enabled", true))
        assertFalse(RecorderService.isRunning)
        controller.destroy()
    }

    private fun worker(name: String): RecordingPublishWorker = TestWorkerBuilder.from(
        context,
        RecordingPublishWorker::class.java,
        Executor { it.run() },
    ).setInputData(Data.Builder().putString(RecordingStorage.INPUT_FILE, name).build()).build()

    private fun recording(pcm: ByteArray = ByteArray(320) { 9 }): File =
        RecordingStorage.newFile(context).apply { writeBytes(wav(pcm)) }

    private fun wav(pcm: ByteArray): ByteArray =
        ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(pcm.size).put(pcm)
        }.array()
}
