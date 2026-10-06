package pl.lisu188.speechrecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class RecordingPlaybackRegressionTest {
    private lateinit var context: Context
    private lateinit var provider: DelayedProvider
    private val executor = Executors.newSingleThreadExecutor()
    private val players = mutableListOf<FakePlayer>()
    private val states = mutableListOf<Pair<Uri?, Uri?>>()
    private val acquisitionFailures = CopyOnWriteArrayList<Exception>()
    private var errors = 0
    private var failure: Failure? = null
    private lateinit var playback: RecordingPlaybackController
    private val uri = Uri.parse("content://speech-recorder-ui-tests/document/recording")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowToast.reset()
        provider = Robolectric.buildContentProvider(DelayedProvider::class.java)
            .create(ProviderInfo().apply { authority = AUTHORITY })
            .get()
        playback = RecordingPlaybackController(
            executor = executor,
            acquireSource = { source, cancellation ->
                try {
                    context.contentResolver.openAssetFileDescriptor(source, "r", cancellation)
                } catch (error: Exception) {
                    acquisitionFailures += error
                    throw error
                }
            },
            onState = { preparing, playing -> states += preparing to playing },
            onError = { errors++ },
            playerFactory = { FakePlayer(failure).also { players += it } },
        )
    }

    @After fun teardown() {
        playback.close()
        provider.unblock()
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        provider.sources.forEach { it.close() }
    }

    @Test fun delayedProviderDoesNotBlockMainAndPlayerHasOneOwningThread() {
        provider.blockOpen = true
        playback.toggle(uri)
        assertProviderEntered()
        assertNotEquals(Looper.getMainLooper().thread, provider.openThread)
        assertEquals(uri to null, states.last())
        assertTrue(players.isEmpty())
        assertMainThreadResponsive()
        provider.unblock()
        awaitMain { players.size == 1 && players.single().prepared }
        val player = players.single()
        assertFalse(provider.sources.single().fileDescriptor.valid())
        player.onPrepared()
        assertEquals(null to uri, states.last())
        playback.stop()
        assertEquals(1, player.starts)
        assertEquals(1, player.releases)
        assertTrue(player.threads.all { it == Looper.getMainLooper().thread })
    }

    @Test fun sourceFailureCreatesNoPlayerAndReportsOneError() {
        provider.failOpen = true
        playback.toggle(uri)
        assertProviderEntered()
        awaitMain { errors == 1 }
        assertTrue(players.isEmpty())
        assertEquals(null to null, states.last())
    }

    @Test fun initializationFailuresReleaseEachCandidateExactlyOnce() {
        for (stage in listOf(Failure.SOURCE, Failure.PREPARE)) {
            failure = stage
            val expectedErrors = errors + 1
            playback.toggle(uri)
            awaitMain { errors == expectedErrors }
            val player = players.last()
            assertEquals(1, player.releases)
            player.onError()
            player.onCompletion()
            playback.stop()
            assertEquals(1, player.releases)
            assertEquals(expectedErrors, errors)
        }
    }

    @Test fun startFailureReleasesThePreparedCandidateExactlyOnce() {
        failure = Failure.START
        playback.toggle(uri)
        awaitMain { players.size == 1 && players.single().prepared }
        val player = players.single()
        player.onPrepared()
        player.onError()
        playback.close()
        assertEquals(1, errors)
        assertEquals(1, player.releases)
    }

    @Test fun asynchronousErrorAndCompletionReleaseOnceAndClearPlayback() {
        playback.toggle(uri)
        awaitMain { players.size == 1 && players.single().prepared }
        val player = players.single()
        player.onPrepared()
        player.onError()
        player.onCompletion()
        playback.close()
        assertEquals(1, errors)
        assertEquals(1, player.releases)
        assertEquals(null to null, states.last())
    }

    @Test fun completionThenDestroyReleasesExactlyOnce() {
        playback.toggle(uri)
        awaitMain { players.size == 1 && players.single().prepared }
        val player = players.single()
        player.onPrepared()
        player.onCompletion()
        playback.close()
        player.onCompletion()
        assertEquals(1, player.releases)
        assertEquals(0, errors)
    }

    @Test fun replacementIgnoresLateCallbacksFromOldPlayer() {
        playback.toggle(uri)
        awaitMain { players.size == 1 && players.single().prepared }
        val first = players.single()
        first.onPrepared()
        val secondUri = uri.buildUpon().appendPath("second").build()
        playback.toggle(secondUri)
        first.onPrepared()
        first.onCompletion()
        first.onError()
        awaitMain { players.size == 2 && players.last().prepared }
        val second = players.last()
        second.onPrepared()
        assertEquals(null to secondUri, states.last())
        assertEquals(1, first.starts)
        assertEquals(1, first.releases)
        assertEquals(0, errors)
        playback.close()
        assertEquals(1, second.releases)
    }

    @Test fun destroyCancelsProviderAcquisitionAndSuppressesLateCallbacks() {
        provider.blockOpen = true
        playback.toggle(uri)
        assertProviderEntered()
        playback.close()
        val stateCount = states.size
        assertTrue(provider.cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(provider.openFinished.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(players.isEmpty())
        assertEquals(0, errors)
        assertEquals(stateCount, states.size)
    }

    @Test fun destroyClosesDescriptorAlreadyQueuedForTheMainThread() {
        playback.toggle(uri)
        assertTrue(provider.openFinished.await(5, TimeUnit.SECONDS))
        executor.submit {}.get(5, TimeUnit.SECONDS)
        playback.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(players.isEmpty())
        assertFalse(provider.sources.single().fileDescriptor.valid())
    }

    @Test fun failedReleaseIsNotAttemptedAgain() {
        failure = Failure.RELEASE
        playback.toggle(uri)
        awaitMain { players.size == 1 && players.single().prepared }
        val player = players.single()
        playback.stop()
        playback.close()
        player.onCompletion()
        assertEquals(1, player.releases)
    }

    @Test fun delayedFolderMetadataDoesNotBlockSettingsAndIsCancelledOnPause() {
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit()
            .putString("recording_tree_uri", tree.toString()).commit()
        provider.blockQuery = true
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            assertTrue(provider.queryEntered.await(5, TimeUnit.SECONDS))
            assertNotEquals(Looper.getMainLooper().thread, provider.queryThread)
            assertMainThreadResponsive()
            activity.pause().stop().destroy()
            assertTrue(provider.queryCancelled.await(5, TimeUnit.SECONDS))
            assertTrue(provider.queryFinished.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            provider.unblock()
        }
    }

    @Test fun delayedDeleteDoesNotBlockUiOrPublishAfterActivityDestruction() {
        provider.blockDelete = true
        val activity = Robolectric.buildActivity(RecordingsActivity::class.java).create().start().resume()
        try {
            activity.get().deleteRecording(uri, "external.wav")
            assertTrue(provider.deleteEntered.await(5, TimeUnit.SECONDS))
            assertNotEquals(Looper.getMainLooper().thread, provider.deleteThread)
            assertMainThreadResponsive()
            activity.get().deleteRecording(uri, "external.wav")
            assertEquals(1, provider.deleteCount)
            activity.pause().stop().destroy()
            provider.unblock()
            assertTrue(provider.deleteFinished.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(null, ShadowToast.getLatestToast())
        } finally {
            provider.unblock()
        }
    }

    private fun assertMainThreadResponsive() {
        var dispatched = false
        Handler(Looper.getMainLooper()).post { dispatched = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dispatched)
    }

    private fun assertProviderEntered() {
        val entered = provider.openEntered.await(5, TimeUnit.SECONDS)
        assertTrue("Provider not entered: ${acquisitionFailures.joinToString { it.stackTraceToString() }}", entered)
    }

    private fun awaitMain(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(ready())
    }

    private enum class Failure { SOURCE, PREPARE, START, RELEASE }

    private class FakePlayer(private val failure: Failure?) : RecordingPlayer {
        var onPrepared: () -> Unit = {}
        var onCompletion: () -> Unit = {}
        var onError: () -> Unit = {}
        var prepared = false
        var starts = 0
        var releases = 0
        val threads = mutableListOf<Thread>()

        override fun setDataSource(source: AssetFileDescriptor) {
            threads += Thread.currentThread()
            assertTrue(source.fileDescriptor.valid())
            if (failure == Failure.SOURCE) throw IOException("Source failure")
        }

        override fun setOnPreparedListener(listener: () -> Unit) { onPrepared = listener }
        override fun setOnCompletionListener(listener: () -> Unit) { onCompletion = listener }
        override fun setOnErrorListener(listener: () -> Unit) { onError = listener }

        override fun prepareAsync() {
            threads += Thread.currentThread()
            if (failure == Failure.PREPARE) throw IOException("Preparation failure")
            prepared = true
        }

        override fun start() {
            threads += Thread.currentThread()
            starts++
            if (failure == Failure.START) throw IllegalStateException("Start failure")
        }

        override fun release() {
            threads += Thread.currentThread()
            releases++
            if (failure == Failure.RELEASE) throw IllegalStateException("Release failure")
        }
    }

    class DelayedProvider : ContentProvider() {
        var blockOpen = false
        var blockQuery = false
        var blockDelete = false
        var failOpen = false
        val openEntered = CountDownLatch(1)
        val openFinished = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val queryEntered = CountDownLatch(1)
        val queryFinished = CountDownLatch(1)
        val queryCancelled = CountDownLatch(1)
        val deleteEntered = CountDownLatch(1)
        val deleteFinished = CountDownLatch(1)
        val sources = CopyOnWriteArrayList<AssetFileDescriptor>()
        private val proceed = CountDownLatch(1)
        @Volatile var openThread: Thread? = null
        @Volatile var queryThread: Thread? = null
        @Volatile var deleteThread: Thread? = null
        @Volatile var deleteCount = 0

        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/wav"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            .apply { addRow(arrayOf("Test OneDrive")) }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            queryThread = Thread.currentThread()
            cancellationSignal?.setOnCancelListener { queryCancelled.countDown(); unblock() }
            queryEntered.countDown()
            try {
                if (blockQuery) assertTrue(proceed.await(5, TimeUnit.SECONDS))
                cancellationSignal?.throwIfCanceled()
                return query(uri, projection, selection, selectionArgs, sortOrder)
            } finally {
                queryFinished.countDown()
            }
        }

        override fun openAssetFile(uri: Uri, mode: String, cancellationSignal: CancellationSignal?): AssetFileDescriptor {
            openThread = Thread.currentThread()
            cancellationSignal?.setOnCancelListener { cancelled.countDown(); unblock() }
            openEntered.countDown()
            try {
                if (blockOpen) assertTrue(proceed.await(5, TimeUnit.SECONDS))
                cancellationSignal?.throwIfCanceled()
                if (failOpen) throw IOException("Unavailable document")
                val file = File(context!!.cacheDir, "ui-playback-source.wav").apply { writeBytes(ByteArray(128)) }
                return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0L, file.length())
                    .also { sources += it }
            } finally {
                openFinished.countDown()
            }
        }

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
            openAssetFile(uri, mode, null)

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?,
            signal: CancellationSignal?,
        ): AssetFileDescriptor = openAssetFile(uri, "r", signal)

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method != "android:deleteDocument") return super.call(method, arg, extras)
            deleteThread = Thread.currentThread()
            deleteCount++
            deleteEntered.countDown()
            try {
                if (blockDelete) {
                    try {
                        assertTrue(proceed.await(5, TimeUnit.SECONDS))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                return Bundle()
            } finally {
                deleteFinished.countDown()
            }
        }

        fun unblock() = proceed.countDown()
    }

    companion object {
        private const val AUTHORITY = "speech-recorder-ui-tests"
    }
}
