package pl.lisu188.speechrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object CloudFolderAccess {
    private const val PREFS = "storage_settings"
    private const val KEY_TREE_URI = "recording_tree_uri"
    private const val INTERNAL_FOLDER_NAME = "__sr_live"
    private const val INTERNAL_PREFIX = "__sr_"
    private class TreeCache {
        val lock = Any()
        var liveFolder: Uri? = null
        val liveChildren = mutableMapOf<String, Uri>()
    }

    private val treeCaches = ConcurrentHashMap<String, TreeCache>()
    private val selectionLock = Any()
    private val permissionMutationLock = Any()

    private fun cacheFor(tree: Uri): TreeCache = treeCaches.computeIfAbsent(tree.toString()) { TreeCache() }

    data class DocumentInfo(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val archived: Boolean,
        val reportedSizeBytes: Long? = sizeBytes,
        val partial: Boolean = false,
    )

    fun load(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = synchronized(selectionLock) { prefs.getString(KEY_TREE_URI, null) } ?: return null
        val uri = Uri.parse(raw)
        val granted = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
        if (!granted) {
            synchronized(selectionLock) {
                if (prefs.getString(KEY_TREE_URI, null) == raw) prefs.edit().remove(KEY_TREE_URI).apply()
            }
            return null
        }
        return uri
    }

    fun hasAccess(context: Context): Boolean = load(context) != null

    fun save(context: Context, uri: Uri, flags: Int): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        val requested = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (requested and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            requested and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0
        ) return false
        return synchronized(permissionMutationLock) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, requested)
                val old = synchronized(selectionLock) {
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val previous = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
                    prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
                    previous
                }
                if (old != null && old != uri) release(context, old)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    fun clear(context: Context) {
        synchronized(permissionMutationLock) {
            val old = synchronized(selectionLock) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val previous = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
                prefs.edit().remove(KEY_TREE_URI).apply()
                previous
            }
            old?.let { release(context, it) }
        }
    }

    fun displayPath(context: Context): String {
        val tree = load(context) ?: return "Nie wybrano"
        val root = rootDocumentUri(tree)
        return try {
            context.contentResolver.query(
                root,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() } ?: "Wybrany folder OneDrive"
        } catch (_: Exception) {
            "Wybrany folder OneDrive"
        }
    }

    fun listAudio(context: Context): List<DocumentInfo> {
        val tree = load(context) ?: return emptyList()
        val children = childrenUri(tree, rootDocumentUri(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val flagsColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (name.startsWith(INTERNAL_PREFIX)) continue
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    val wav = name.endsWith(".wav", ignoreCase = true) || mime == "audio/wav" || mime == "audio/x-wav"
                    if (!wav) continue
                    add(
                        DocumentInfo(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn)),
                            name = name,
                            sizeBytes = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                            lastModifiedMs = if (cursor.isNull(modifiedColumn)) 0L else cursor.getLong(modifiedColumn),
                            archived = false,
                            reportedSizeBytes = if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn),
                            partial = cursor.getInt(flagsColumn) and DocumentsContract.Document.FLAG_PARTIAL != 0,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    fun openOrCreateAudio(context: Context, name: String): Uri =
        openOrCreateFile(context, name, "audio/wav")

    fun openOrCreateFile(
        context: Context,
        name: String,
        mimeType: String,
        tree: Uri = load(context) ?: throw IOException("OneDrive folder is not configured"),
    ): Uri {
        val root = rootDocumentUri(tree)
        findChild(context, tree, root, name)?.let { return it }
        return DocumentsContract.createDocument(
            context.contentResolver,
            root,
            mimeType,
            name,
        ) ?: throw IOException("OneDrive provider refused to create $name")
    }

    fun openOrCreateLiveFile(
        context: Context,
        name: String,
        mimeType: String,
        tree: Uri = load(context) ?: throw IOException("OneDrive folder is not configured"),
    ): Uri {
        val cache = cacheFor(tree)
        return synchronized(cache.lock) {
            val parent = liveFolder(context, tree, cache)
            cachedLiveChild(context, name, cache)?.let { return@synchronized it }
            findChild(context, tree, parent, name)?.let {
                cache.liveChildren[name.lowercase()] = it
                return@synchronized it
            }
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mimeType,
                name,
            ) ?: throw IOException("OneDrive provider refused to create live file $name")
            cache.liveChildren[name.lowercase()] = created
            created
        }
    }

    fun findFile(context: Context, name: String, tree: Uri? = load(context)): Uri? {
        tree ?: return null
        return findChild(context, tree, rootDocumentUri(tree), name)
    }

    fun findLiveFile(context: Context, name: String, tree: Uri? = load(context)): Uri? {
        tree ?: return null
        val cache = cacheFor(tree)
        return synchronized(cache.lock) {
            val root = rootDocumentUri(tree)
            val parent = findChild(context, tree, root, INTERNAL_FOLDER_NAME) ?: return@synchronized null
            cachedLiveChild(context, name, cache)?.let { return@synchronized it }
            findChild(context, tree, parent, name)?.also { cache.liveChildren[name.lowercase()] = it }
        }
    }

    data class DocumentState(val sizeBytes: Long?, val lastModifiedMs: Long?, val partial: Boolean)

    fun documentState(context: Context, uri: Uri): DocumentState? =
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_FLAGS,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else DocumentState(
                    if (cursor.isNull(0)) null else cursor.getLong(0),
                    if (cursor.isNull(1)) null else cursor.getLong(1),
                    cursor.isNull(2) || cursor.getInt(2) and DocumentsContract.Document.FLAG_PARTIAL != 0,
                )
            }
        } catch (_: Exception) {
            null
        }

    fun liveExists(context: Context, name: String): Boolean = findLiveFile(context, name) != null

    fun fileSize(context: Context, uri: Uri): Long? =
        try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }

    fun delete(context: Context, uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            false
        }

    fun deleteLiveByPrefix(context: Context, prefix: String, tree: Uri? = load(context)): Int {
        tree ?: return 0
        val cache = cacheFor(tree)
        return synchronized(cache.lock) {
            val parent = try {
                liveFolder(context, tree, cache)
            } catch (_: Exception) {
                return@synchronized 0
            }
            var deleted = 0
            queryChildren(context, tree, parent) { name, uri ->
                if (name.startsWith(prefix) && delete(context, uri)) {
                    cache.liveChildren.remove(name.lowercase())
                    deleted++
                }
            }
            deleted
        }
    }

    fun deleteLegacyRootByPrefix(context: Context, prefix: String, tree: Uri? = load(context)): Int {
        tree ?: return 0
        val root = rootDocumentUri(tree)
        var deleted = 0
        queryChildren(context, tree, root) { name, uri ->
            if (name.startsWith(prefix) && delete(context, uri)) deleted++
        }
        return deleted
    }

    fun deleteLiveExact(context: Context, name: String, tree: Uri? = load(context)): Boolean {
        tree ?: return false
        val cache = cacheFor(tree)
        return synchronized(cache.lock) {
            val parent = try {
                liveFolder(context, tree, cache)
            } catch (_: Exception) {
                return@synchronized false
            }
            val uri = cachedLiveChild(context, name, cache) ?: findChild(context, tree, parent, name) ?: return@synchronized true
            val deleted = delete(context, uri)
            if (deleted) cache.liveChildren.remove(name.lowercase())
            deleted
        }
    }

    private fun liveFolder(context: Context, tree: Uri, cache: TreeCache): Uri {
        cache.liveFolder?.let { cached ->
            if (documentExists(context, cached)) return cached
            cache.liveFolder = null
            cache.liveChildren.clear()
        }
        val root = rootDocumentUri(tree)
        val existing = findChild(context, tree, root, INTERNAL_FOLDER_NAME)
        val folder = existing ?: DocumentsContract.createDocument(
            context.contentResolver,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            INTERNAL_FOLDER_NAME,
        ) ?: throw IOException("OneDrive provider refused to create internal live folder")
        cache.liveFolder = folder
        cache.liveChildren.clear()
        return folder
    }

    private fun cachedLiveChild(context: Context, name: String, cache: TreeCache): Uri? {
        val key = name.lowercase()
        val uri = cache.liveChildren[key] ?: return null
        if (documentExists(context, uri)) return uri
        cache.liveChildren.remove(key)
        return null
    }

    private fun documentExists(context: Context, uri: Uri): Boolean =
        try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        } catch (_: Exception) {
            false
        }

    private fun findChild(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        var found: Uri? = null
        queryChildren(context, tree, parent) { childName, uri ->
            if (found == null && childName.equals(name, ignoreCase = true)) found = uri
        }
        return found
    }

    private inline fun queryChildren(
        context: Context,
        tree: Uri,
        parent: Uri,
        block: (String, Uri) -> Unit,
    ) {
        context.contentResolver.query(
            childrenUri(tree, parent),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                block(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn)))
            }
        }
    }

    private fun childrenUri(tree: Uri, parent: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree,
        DocumentsContract.getDocumentId(parent),
    )

    private fun rootDocumentUri(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        tree,
        DocumentsContract.getTreeDocumentId(tree),
    )

    private fun release(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }
}
