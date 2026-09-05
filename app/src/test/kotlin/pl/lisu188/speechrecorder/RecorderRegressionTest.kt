package pl.lisu188.speechrecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderRegressionTest {
    private lateinit var provider: RecordingProvider

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        provider = RecordingProvider(File(context.cacheDir, "published.wav"))
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun schedulerFailureKeepsPublishedRecording() {
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        val audio = File(service.cacheDir, "test.wav").apply { writeBytes(ByteArray(100) { 7 }) }
        service.publish(audio) { throw IllegalStateException("queue unavailable") }
        assertTrue(provider.published)
        assertFalse(provider.deleted)
        assertFalse(audio.exists())
        assertArrayEquals(ByteArray(100) { 7 }, provider.file.readBytes())
        controller.destroy()
    }

    @Test fun publicationFailureDoesNotScheduleTranscription() {
        provider.failPublication = true
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        val audio = File(service.cacheDir, "test.wav").apply { writeBytes(ByteArray(100) { 7 }) }
        service.publish(audio) { fail("Scheduled incomplete recording") }
        assertTrue(provider.deleted)
        val fallback = File(service.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "SpeechRecorder")
        assertTrue(audio.exists() || fallback.listFiles().orEmpty().any { it.length() == 100L })
        controller.destroy()
    }

    @Test fun allScreensOpenWithoutMicrophoneOrApiKey() {
        Robolectric.buildActivity(MainActivity::class.java).create().start().resume().pause().stop().destroy()
        Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().pause().stop().destroy()
        Robolectric.buildActivity(RecordingsActivity::class.java).create().start().resume().pause().stop().destroy()
    }

    private class RecordingProvider(val file: File) : ContentProvider() {
        var published = false
        var deleted = false
        var failPublication = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri = Uri.parse("content://media/external/audio/media/1")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            published = !failPublication
            return if (failPublication) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleted = true
            file.delete()
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun getType(uri: Uri): String = "audio/wav"
    }
}
