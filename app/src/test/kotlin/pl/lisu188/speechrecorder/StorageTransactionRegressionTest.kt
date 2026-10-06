package pl.lisu188.speechrecorder

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageTransactionRegressionTest {
    private lateinit var context: Context
    private lateinit var provider: StorageDocumentsProvider
    private lateinit var tree: Uri
    private val permissions = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val name = "speech_20200101_120000_000_storage.wav"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        File(context.noBackupFilesDir, "recordings").deleteRecursively()
        File(context.noBackupFilesDir, "live-parts").deleteRecursively()
        context.getSharedPreferences("storage_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("recorder", Context.MODE_PRIVATE).edit().clear().commit()
        provider = StorageDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        assertTrue(CloudFolderAccess.save(context, tree, permissions))
    }

    @Test fun emptyReceiptAfterFailedWriteCannotDiscardStagingWhenFinalDisappears() {
        val original = wav(ByteArray(3200) { (it % 251).toByte() })
        val local = localRecording(original)
        val safety = RecordingStorage.newLivePartFile(context, name, 1).apply { writeBytes(original) }
        provider.failReceiptWrites = true
        assertFalse(RecordingStorage.publish(context, local))
        assertTrue(local.exists())
        val marker = provider.find("__sr_live", RecordingStorage.commitMarkerName(name))
        assertNotNull(marker)
        assertEquals(0L, marker!!.file!!.length())
        provider.deleteDocument(provider.find("root", name)!!.id)
        provider.failRecordingWrites = true
        assertFalse(RecordingStorage.publish(context, local))
        assertArrayEquals(original, local.readBytes())
        assertTrue(safety.exists())
        provider.failReceiptWrites = false
        provider.failRecordingWrites = false
        assertTrue(RecordingStorage.publish(context, local))
        assertFalse(local.exists())
        assertArrayEquals(original, provider.find("root", name)!!.file!!.readBytes())
        assertTrue(provider.find("__sr_live", RecordingStorage.commitMarkerName(name))!!.file!!.readText().startsWith("SR2\n"))
    }

    @Test fun corruptReceiptReadbackNeverCommitsLocalDeletion() {
        val original = wav(ByteArray(1600) { 7 })
        val local = localRecording(original)
        provider.corruptReceiptReads = true
        assertFalse(RecordingStorage.publish(context, local))
        assertArrayEquals(original, local.readBytes())
        assertNotNull(provider.find("root", name))
    }

    @Test fun staleReceiptDoesNotAcceptPartialFinalDocument() {
        val original = wav(ByteArray(1600) { 7 })
        val local = localRecording(original)
        provider.add("root", name, original.copyOf(60))
        provider.add("__sr_live", RecordingStorage.commitMarkerName(name), original.size.toString().toByteArray())
        provider.failRecordingWrites = true
        assertFalse(RecordingStorage.publish(context, local))
        assertArrayEquals(original, local.readBytes())
    }

    @Test fun validLegacyReceiptRequiresMatchingCompleteFinalDocument() {
        val original = wav(ByteArray(1600) { 7 })
        val local = localRecording(original)
        provider.add("root", name, original)
        provider.add("__sr_live", RecordingStorage.commitMarkerName(name), original.size.toString().toByteArray())
        provider.failRecordingWrites = true
        assertTrue(RecordingStorage.publish(context, local))
        assertFalse(local.exists())
        assertArrayEquals(original, provider.find("root", name)!!.file!!.readBytes())
    }

    @Test fun liveWorkerNeverUploadsOrUnlinksAnActiveLockedSink() {
        val file = RecordingStorage.newLivePartFile(context, name, 1)
        val sink = RecorderService.WavSink(file, 16000)
        sink.write(shortArrayOf(1, -2, 300), 3)
        val worker = liveWorker(file.name)
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertTrue(file.exists())
        assertNull(provider.find("__sr_live", file.name))
        val completed = sink.closeAndGetFile().readBytes()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertFalse(file.exists())
        assertArrayEquals(completed, provider.find("__sr_live", file.name)!!.file!!.readBytes())
    }

    @Test fun orphanedLivePartHeaderIsRepairedBeforeUpload() {
        val expected = wav(ByteArray(640) { 5 })
        val unfinished = expected.copyOf().apply {
            ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0).putInt(40, 0)
        }
        val file = RecordingStorage.newLivePartFile(context, name, 1).apply { writeBytes(unfinished) }
        assertEquals(ListenableWorker.Result.success(), liveWorker(file.name).doWork())
        assertFalse(file.exists())
        assertArrayEquals(expected, provider.find("__sr_live", file.name)!!.file!!.readBytes())
    }

    @Test fun unknownSourceSizeAndPartialFlagPreventDestructiveArchive() {
        val source = provider.add("root", name, wav(ByteArray(640) { 5 }))
        source.unknownSize = true
        val unknown = RecordingStorage.listPublished(context).single()
        assertNull(unknown.reportedSizeBytes)
        assertFalse(RecordingStorage.archiveRecording(context, unknown))
        source.unknownSize = false
        source.partial = true
        val partial = RecordingStorage.listPublished(context).single()
        assertTrue(partial.partial)
        assertFalse(RecordingStorage.archiveRecording(context, partial))
        assertNotNull(provider.find("root", name))
        assertNull(provider.find("root", "$name.zip"))
    }

    @Test fun shortSourceStreamCannotReplaceTheOriginalWithAPartialZip() {
        val source = provider.add("root", name, wav(ByteArray(6400) { 5 }))
        provider.shortReadDocument = source.id
        assertFalse(RecordingStorage.archiveRecording(context, RecordingStorage.listPublished(context).single()))
        assertNotNull(provider.find("root", name))
    }

    @Test fun sameLengthCorruptArchiveReadbackPreservesSource() {
        provider.add("root", name, wav(ByteArray(6400) { (it % 251).toByte() }))
        provider.corruptArchiveReads = true
        assertFalse(RecordingStorage.archiveRecording(context, RecordingStorage.listPublished(context).single()))
        assertNotNull(provider.find("root", name))
        assertNotNull(provider.find("root", "$name.zip"))
    }

    @Test fun sourceMutationDuringArchiveReadbackPreventsSourceDeletion() {
        val source = provider.add("root", name, wav(ByteArray(6400) { 7 }))
        provider.onOpen = { node, mode ->
            if (node.name.endsWith(".zip") && !mode.contains('w')) source.file!!.appendBytes(byteArrayOf(1, 2))
        }
        assertFalse(RecordingStorage.archiveRecording(context, RecordingStorage.listPublished(context).single()))
        assertNotNull(provider.find("root", name))
    }

    @Test fun verifiedArchiveContainsEverySourceByteBeforeSourceDeletion() {
        val original = wav(ByteArray(6400) { (it % 251).toByte() })
        provider.add("root", name, original)
        assertTrue(RecordingStorage.archiveRecording(context, RecordingStorage.listPublished(context).single()))
        assertNull(provider.find("root", name))
        ZipFile(provider.find("root", "$name.zip")!!.file!!).use { zip ->
            assertArrayEquals(original, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
        }
    }

    @Test fun publicationAndArchiveShareTheSameRecordingTransaction() {
        val original = wav(ByteArray(6400) { (it % 251).toByte() })
        provider.add("root", name, original)
        val candidate = RecordingStorage.listPublished(context).single()
        val local = localRecording(original)
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val archiveStarted = CountDownLatch(1)
        val archived = CountDownLatch(1)
        val publishedResult = AtomicReference<Boolean>()
        val archiveResult = AtomicReference<Boolean>()
        provider.onOpen = { node, mode ->
            if (node.name == name && mode.contains('w')) {
                publishing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val uploader = thread {
            publishedResult.set(RecordingStorage.publish(context, local))
        }
        assertTrue(publishing.await(5, TimeUnit.SECONDS))
        val archiver = thread {
            try {
                archiveStarted.countDown()
                archiveResult.set(RecordingStorage.archiveRecording(context, candidate))
            } finally {
                archived.countDown()
            }
        }
        try {
            assertTrue(archiveStarted.await(5, TimeUnit.SECONDS))
            assertFalse(archived.await(150, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        uploader.join(5000)
        archiver.join(5000)
        assertFalse(uploader.isAlive)
        assertFalse(archiver.isAlive)
        assertEquals(true, publishedResult.get())
        assertEquals(true, archiveResult.get())
        assertFalse(local.exists())
        ZipFile(provider.find("root", "$name.zip")!!.file!!).use { zip ->
            assertArrayEquals(original, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
        }
    }

    @Test fun selectedTreeChangeRetainsStagingAndNeverWritesReceiptIntoTheNewTree() {
        val original = wav(ByteArray(6400) { 7 })
        val local = localRecording(original)
        val secondTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "second")
        provider.addRoot("second")
        provider.onOpen = { node, mode ->
            if (node.name == name && mode.contains('w')) {
                assertTrue(CloudFolderAccess.save(context, secondTree, permissions))
            }
        }
        assertFalse(RecordingStorage.publish(context, local))
        assertArrayEquals(original, local.readBytes())
        assertNull(provider.find("second", RecordingStorage.commitMarkerName(name)))
        assertNull(provider.find("second", name))
    }

    @Test fun oldTreeCleanupCannotDeleteTheNewTreeCachedReceipt() {
        val marker = RecordingStorage.commitMarkerName(name)
        val old = CloudFolderAccess.openOrCreateLiveFile(context, marker, "application/octet-stream", tree)
        provider.addRoot("second")
        val secondTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "second")
        assertTrue(CloudFolderAccess.save(context, secondTree, permissions))
        val replacement = CloudFolderAccess.openOrCreateLiveFile(context, marker, "application/octet-stream", secondTree)
        assertTrue(CloudFolderAccess.deleteLiveExact(context, marker, tree))
        assertFalse(provider.contains(DocumentsContract.getDocumentId(old)))
        assertTrue(provider.contains(DocumentsContract.getDocumentId(replacement)))
    }

    @Test fun reselectingTreeDoesNotReplaceItsInFlightFolderCreationLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        provider.onCreate = { displayName ->
            if (displayName == "__sr_live") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val first = thread { CloudFolderAccess.openOrCreateLiveFile(context, "first", "application/octet-stream") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(CloudFolderAccess.save(context, tree, permissions))
        val second = thread {
            try {
                secondStarted.countDown()
                CloudFolderAccess.openOrCreateLiveFile(context, "second", "application/octet-stream")
            } finally {
                secondFinished.countDown()
            }
        }
        try {
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondFinished.await(150, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            first.join(5000)
            second.join(5000)
        }
        assertEquals(1, provider.count("root", "__sr_live"))
    }

    @Test fun accessChecksNeverWaitForBlockedProviderCacheIo() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.onCreate = { displayName ->
            if (displayName == "__sr_live") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val io = thread { CloudFolderAccess.openOrCreateLiveFile(context, "probe", "application/octet-stream") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val checked = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val reader = thread {
            result.set(CloudFolderAccess.hasAccess(context))
            checked.countDown()
        }
        try {
            assertTrue(checked.await(500, TimeUnit.MILLISECONDS))
            assertEquals(true, result.get())
        } finally {
            release.countDown()
            io.join(5000)
            reader.join(5000)
        }
    }

    private fun localRecording(bytes: ByteArray): File =
        File(RecordingStorage.directory(context), name).apply { writeBytes(bytes) }

    private fun liveWorker(fileName: String): LivePartPublishWorker = TestWorkerBuilder.from(
        context,
        LivePartPublishWorker::class.java,
        Executor { it.run() },
    ).setInputData(Data.Builder().putString(RecordingStorage.INPUT_FILE, fileName).build()).build()

    private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        put("data".toByteArray()).putInt(pcm.size).put(pcm)
    }.array()

    class StorageDocumentsProvider : DocumentsProvider() {
        data class Node(
            val id: String,
            val parent: String?,
            val name: String,
            val mime: String,
            val file: File?,
            var unknownSize: Boolean = false,
            var partial: Boolean = false,
        )

        private val nodes = ConcurrentHashMap<String, Node>()
        private val ids = AtomicInteger()
        private lateinit var files: File
        var failReceiptWrites = false
        var failRecordingWrites = false
        var corruptArchiveReads = false
        var corruptReceiptReads = false
        var shortReadDocument: String? = null
        var onOpen: ((Node, String) -> Unit)? = null
        var onCreate: ((String) -> Unit)? = null

        override fun onCreate(): Boolean {
            files = File(context!!.cacheDir, "fake-saf-${System.nanoTime()}").apply { mkdirs() }
            addRoot("root")
            return true
        }

        fun addRoot(id: String) {
            nodes[id] = Node(id, null, id, DocumentsContract.Document.MIME_TYPE_DIR, null)
        }

        fun contains(id: String): Boolean = nodes.containsKey(id)

        fun count(parent: String, name: String): Int = nodes.values.count { it.parent == parent && it.name == name }

        fun find(parent: String, name: String): Node? {
            val parentId = if (nodes.containsKey(parent)) parent else nodes.values.firstOrNull { it.name == parent }?.id
            return nodes.values.firstOrNull { it.parent == parentId && it.name == name }
        }

        fun add(parent: String, name: String, bytes: ByteArray): Node {
            val parentId = if (parent == "__sr_live") {
                find("root", parent)?.id ?: createDocument("root", DocumentsContract.Document.MIME_TYPE_DIR, parent)
            } else parent
            val id = createDocument(parentId, if (name.endsWith(".wav")) "audio/wav" else "application/octet-stream", name)
            return nodes[id]!!.also { it.file!!.writeBytes(bytes) }
        }

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            cursor(projection, listOfNotNull(nodes[documentId]))

        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            cursor(projection, nodes.values.filter { it.parent == parentDocumentId })

        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
            var node = nodes[documentId]
            while (node != null) {
                if (node.id == parentDocumentId) return true
                node = node.parent?.let(nodes::get)
            }
            return false
        }

        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            onCreate?.invoke(displayName)
            val id = "doc-${files.name}-${ids.incrementAndGet()}"
            val file = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) null else File(files, id).apply { createNewFile() }
            nodes[id] = Node(id, parentDocumentId, displayName, mimeType, file)
            return id
        }

        override fun deleteDocument(documentId: String) {
            nodes.remove(documentId)?.file?.delete()
        }

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
            onOpen?.invoke(node, mode)
            if (mode.contains('w') && (failReceiptWrites && node.name.startsWith("commit_") || failRecordingWrites && node.name.endsWith(".wav"))) {
                throw FileNotFoundException("Injected write failure")
            }
            val original = node.file ?: throw FileNotFoundException(documentId)
            val file = if (!mode.contains('w') && (shortReadDocument == documentId || corruptArchiveReads && node.name.endsWith(".zip") ||
                    corruptReceiptReads && node.name.startsWith("commit_"))) {
                File.createTempFile("readback-", ".bin", files).apply {
                    val bytes = original.readBytes()
                    writeBytes(if (shortReadDocument == documentId) bytes.copyOf(bytes.size / 2) else bytes.apply {
                        val index = if (node.name.startsWith("commit_")) 0 else 40
                        if (size > index) this[index] = (this[index].toInt() xor 1).toByte()
                    })
                }
            } else original
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        private fun cursor(projection: Array<out String>?, documents: List<Node>): Cursor {
            val columns = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
            )
            return MatrixCursor(columns).apply {
                documents.forEach { node ->
                    addRow(columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                            DocumentsContract.Document.COLUMN_SIZE -> if (node.unknownSize) null else node.file?.length() ?: 0L
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_577_880_000_000L
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mime
                            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                                (if (node.partial) DocumentsContract.Document.FLAG_PARTIAL else 0)
                            else -> null
                        }
                    }.toTypedArray())
                }
            }
        }
    }

    companion object {
        const val AUTHORITY = "pl.lisu188.speechrecorder.storage-tests"
    }
}
