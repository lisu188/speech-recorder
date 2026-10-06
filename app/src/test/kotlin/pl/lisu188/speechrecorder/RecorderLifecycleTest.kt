package pl.lisu188.speechrecorder

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import androidx.compose.runtime.State
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.android.controller.ServiceController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingMonitorShadow::class])
class RecorderLifecycleTest {
    private lateinit var context: Context
    private val services = mutableListOf<ServiceController<RecorderService>>()
    private val records = mutableListOf<AudioRecord>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        prefs().edit().clear().commit()
        activeOwner().set(null)
        RecordingMonitorShadow.configuration = null
        RecordingMonitorShadow.callback = null
    }

    @After fun cleanup() {
        services.asReversed().forEach { it.destroy() }
        records.forEach { it.release() }
        activeOwner().set(null)
    }

    @Test fun mirrorInitializationFailureKeepsPrimaryAudioWritableAndReleasesItsLock() {
        val file = RecordingStorage.newFile(context)
        val failures = mutableListOf<Exception>()
        val clip = RecorderService.RecordingClip(RecorderService.WavSink(file, 16000), failures::add)
        clip.attachMirror { throw IOException("mirror initialization failed") }

        clip.write(shortArrayOf(100, -200, 300), 3)
        assertSame(file, clip.closeAndGetFile())

        assertEquals(1, failures.size)
        assertPcmAndUnlocked(file, shortArrayOf(100, -200, 300))
    }

    @Test fun mirrorCloseFailureStillFinalizesPrimaryHeaderAndReleasesItsLock() {
        val file = RecordingStorage.newFile(context)
        val failures = mutableListOf<Exception>()
        val clip = RecorderService.RecordingClip(RecorderService.WavSink(file, 16000), failures::add)
        clip.attachMirror {
            object : RecorderService.AudioMirror {
                override fun write(samples: ShortArray, length: Int) = Unit
                override fun close() { throw IOException("mirror close failed") }
            }
        }

        clip.write(shortArrayOf(10, -20), 2)
        clip.closeAndGetFile()

        assertEquals(1, failures.size)
        assertPcmAndUnlocked(file, shortArrayOf(10, -20))
    }

    @Test fun mirrorWriteFailureIsDetachedWithoutInterruptingPrimaryCapture() {
        val file = RecordingStorage.newFile(context)
        val failures = mutableListOf<Exception>()
        var writes = 0
        var closes = 0
        val clip = RecorderService.RecordingClip(RecorderService.WavSink(file, 16000), failures::add)
        clip.attachMirror {
            object : RecorderService.AudioMirror {
                override fun write(samples: ShortArray, length: Int) {
                    writes++
                    throw IOException("mirror write failed")
                }
                override fun close() { closes++ }
            }
        }

        clip.write(shortArrayOf(123), 1)
        clip.write(shortArrayOf(456), 1)
        clip.closeAndGetFile()

        assertEquals(1, writes)
        assertEquals(1, closes)
        assertEquals(1, failures.size)
        assertPcmAndUnlocked(file, shortArrayOf(123, 456))
    }

    @Test fun failureReporterExceptionCannotPreventPrimaryClosure() {
        val file = RecordingStorage.newFile(context)
        val clip = RecorderService.RecordingClip(RecorderService.WavSink(file, 16000)) {
            throw IllegalStateException("failure reporter failed")
        }
        clip.attachMirror {
            object : RecorderService.AudioMirror {
                override fun write(samples: ShortArray, length: Int) = Unit
                override fun close() { throw IOException("mirror close failed") }
            }
        }
        clip.write(shortArrayOf(321), 1)

        try {
            clip.closeAndGetFile()
        } catch (_: IllegalStateException) {
        }

        assertPcmAndUnlocked(file, shortArrayOf(321))
    }

    @Test fun initialSilencedConfigurationAndCallbackChangesAreVisibleAndRecoverAutomatically() {
        val service = newService()
        val record = newRecord()
        activate(service, record)
        prefs().edit().putBoolean("speech_active", true).commit()
        val callback = service.registerRecordingMonitor(record)
        assertSame(callback, RecordingMonitorShadow.callback)
        RecordingMonitorShadow.configuration = configuration(record.audioSessionId, true)

        service.refreshMicrophoneState(record)

        assertTrue(prefs().getBoolean(RecorderService.KEY_CAPTURE_SILENCED, false))
        assertFalse(prefs().getBoolean("speech_active", true))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            assertEquals(RecorderMode.INTERRUPTED, screenState(activity.get()).mode)
            assertEquals(0, screenState(activity.get()).level)
            assertTrue(screenState(activity.get()).error.orEmpty().contains("Android wyciszył mikrofon"))

            callback.onRecordingConfigChanged(mutableListOf(configuration(record.audioSessionId, false)))
            invoke(activity.get(), "renderState")

            assertFalse(prefs().getBoolean(RecorderService.KEY_CAPTURE_SILENCED, true))
            assertTrue(prefs().getBoolean("enabled", false))
            assertEquals(RecorderMode.LISTENING, screenState(activity.get()).mode)
            assertFalse(screenState(activity.get()).error.orEmpty().contains("Android wyciszył mikrofon"))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun obsoleteWorkerCleanupCannotOverwriteReplacementStateOrBroadcast() {
        val old = newService()
        activate(old, newRecord())
        services.first().destroy()
        services.removeAt(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val cleanup = executor.submit {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            invoke(old, "updateNotification", false)
            invoke(old, "sendLevelBroadcast", 0, false)
            invoke(old, "rememberSpeech", 1L)
            invoke(old, "stopWithError", "obsolete worker error")
            old.onTaskRemoved(null)
        }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val replacement = newService()
            activate(replacement, newRecord())
            prefs().edit().putBoolean("speech_active", true).putLong("last_speech", 1000L).commit()
            val before = levelBroadcasts().size
            release.countDown()
            cleanup.get(3, TimeUnit.SECONDS)

            assertTrue(prefs().getBoolean("enabled", false))
            assertTrue(prefs().getBoolean("speech_active", false))
            assertEquals(1000L, prefs().getLong("last_speech", 0L))
            assertFalse(prefs().contains("capture_error"))
            assertEquals(before, levelBroadcasts().size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun duplicateStartPreservesCurrentSystemSilencingState() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = newService()
        val record = newRecord()
        activate(service, record)
        service.updateMicrophoneSilenced(record, true)

        service.onStartCommand(Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_START), 0, 2)

        assertTrue(prefs().getBoolean(RecorderService.KEY_CAPTURE_SILENCED, false))
        assertFalse(prefs().getBoolean("speech_active", true))
    }

    @Test fun lateCallbacksFromOldRecorderOrServiceCannotSilenceCurrentCapture() {
        val old = newService()
        val oldRecord = newRecord()
        activate(old, oldRecord)
        val replacement = newService()
        val currentRecord = newRecord()
        activate(replacement, currentRecord)
        prefs().edit().putBoolean("speech_active", true).commit()
        val before = levelBroadcasts().size

        old.updateMicrophoneSilenced(oldRecord, true)
        replacement.updateMicrophoneSilenced(oldRecord, true)

        assertFalse(prefs().getBoolean(RecorderService.KEY_CAPTURE_SILENCED, true))
        assertTrue(prefs().getBoolean("speech_active", false))
        assertEquals(before, levelBroadcasts().size)
    }

    private fun newService(): RecorderService = Robolectric.buildService(RecorderService::class.java)
        .create().also(services::add).get()

    private fun newRecord(): AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        4096,
    ).also(records::add)

    private fun activate(service: RecorderService, record: AudioRecord) {
        activeOwner().set(field(service, "serviceToken"))
        (field(service, "running") as AtomicBoolean).set(true)
        setField(service, "claimedStatus", true)
        setField(service, "audioRecord", record)
        prefs().edit().putBoolean("enabled", true).putBoolean(RecorderService.KEY_CAPTURE_SILENCED, false).commit()
    }

    private fun configuration(sessionId: Int, silenced: Boolean): AudioRecordingConfiguration {
        val format = AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val constructor = AudioRecordingConfiguration::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            AudioFormat::class.java,
            AudioFormat::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(0, sessionId, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            format, format, 0, context.packageName).also {
            ReflectionHelpers.setField(it, "mClientSilenced", silenced)
        }
    }

    private fun assertPcmAndUnlocked(file: File, samples: ShortArray) {
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + samples.size * 2, header.getInt(4))
        assertEquals(samples.size * 2, header.getInt(40))
        val expected = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(expected::putShort)
        assertArrayEquals(expected.array(), bytes.copyOfRange(44, bytes.size))
        RandomAccessFile(file, "rw").use { audio -> audio.channel.tryLock().use { assertNotNull(it) } }
    }

    private fun prefs() = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)

    private fun levelBroadcasts() = shadowOf(context as Application).broadcastIntents
        .filter { it.action == RecorderService.ACTION_LEVEL }

    @Suppress("UNCHECKED_CAST")
    private fun activeOwner(): AtomicReference<Any?> = RecorderService::class.java.getDeclaredField("activeService")
        .apply { isAccessible = true }.get(null) as AtomicReference<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun screenState(activity: MainActivity): RecorderScreenState =
        (field(activity, "screenState") as State<RecorderScreenState>).value

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(instance)

    private fun setField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun invoke(instance: Any, name: String, vararg arguments: Any) {
        instance.javaClass.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
            .apply { isAccessible = true }.invoke(instance, *arguments)
    }
}

@Implements(AudioRecord::class)
class RecordingMonitorShadow {
    @Implementation fun __constructor__(source: Int, sampleRate: Int, channelMask: Int, encoding: Int, bufferSize: Int) = Unit

    @Implementation fun getAudioSessionId(): Int = 42

    @Implementation fun stop() = Unit

    @Implementation fun release() = Unit

    @Implementation fun getActiveRecordingConfiguration(): AudioRecordingConfiguration? = configuration

    @Implementation fun registerAudioRecordingCallback(executor: Executor, recordingCallback: AudioManager.AudioRecordingCallback) {
        callback = recordingCallback
    }

    companion object {
        var configuration: AudioRecordingConfiguration? = null
        var callback: AudioManager.AudioRecordingCallback? = null
    }
}
