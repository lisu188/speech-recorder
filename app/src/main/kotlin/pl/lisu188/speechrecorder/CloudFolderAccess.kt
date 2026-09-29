package pl.lisu188.speechrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

object CloudFolderAccess {
    private const val PREFS = "storage_settings"
    private const val KEY_TREE_URI = "recording_tree_uri"
    private const val INTERNAL_FOLDER_NAME = "__sr_live"
    private const val INTERNAL_PREFIX = "__sr_"
    private val cacheLock = Any()
    private var cachedTree: String? = null
    private var cachedLiveFolder: Uri? = null
    private val cachedLiveChildren = mutableMapOf<String, Uri>()

    data class DocumentInfo(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val archived: Boolean,
    )

    fun load(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?: return null
        val uri = Uri.parse(raw)
        val granted = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
        if (!granted) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE_URI).apply()
            invalidateCache()
            return null
        }
        ensureCacheTree(uri)
        return uri
    }

    fun hasAccess(context: Context): Boolean = load(context) != null

    fun save(context: Context, uri: Uri, flags: Int): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        val requested = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (requested and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) return false

        val old = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?.let(Uri::parse)
        return try {
            context.contentResolver.takePersistableUriPermission(uri, requested)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, uri.toString())
                .apply()
            if (old != null && old != uri) release(context, old)
            invalidateCache()
            ensureCacheTree(uri)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)?.let { release(context, it) }
        prefs.edit().remove(KEY_TREE_URI).apply()
        invalidateCache()
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
        )
        return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
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
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    fun openOrCreateAudio(context: Context, name: String): Uri =
        openOrCreateFile(context, name, "audio/wav")

    fun openOrCreateFile(context: Context, name: String, mimeType: String): Uri {
        val tree = load(context) ?: throw IOException("OneDrive folder is not configured")
        val root = rootDocumentUri(tree)
        findChild(context, tree, root, name)?.let { return it }
        return DocumentsContract.createDocument(
            context.contentResolver,
            root,
            mimeType,
            name,
        ) ?: throw IOException("OneDrive provider refused to create $name")
    }

    fun openOrCreateLiveFile(context: Context, name: String, mimeType: String): Uri {
        val tree = load(context) ?: throw IOException("OneDrive folder is not configured")
        val parent = liveFolder(context, tree)
        cachedLiveChild(context, name)?.let { return it }
        findChild(context, tree, parent, name)?.let {
            cacheLiveChild(name, it)
            return it
        }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            mimeType,
            name,
        ) ?: throw IOException("OneDrive provider refused to create live file $name")
        cacheLiveChild(name, created)
        return created
    }

    fun liveExists(context: Context, name: String): Boolean {
        val tree = load(context) ?: return false
        val parent = try {
            liveFolder(context, tree)
        } catch (_: Exception) {
            return false
        }
        cachedLiveChild(context, name)?.let { return true }
        val found = findChild(context, tree, parent, name) ?: return false
        cacheLiveChild(name, found)
        return true
    }

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

    fun deleteLiveByPrefix(context: Context, prefix: String): Int {
        val tree = load(context) ?: return 0
        val parent = try {
            liveFolder(context, tree)
        } catch (_: Exception) {
            return 0
        }
        var deleted = 0
        queryChildren(context, tree, parent) { name, uri ->
            if (name.startsWith(prefix) && delete(context, uri)) {
                synchronized(cacheLock) { cachedLiveChildren.remove(name.lowercase()) }
                deleted++
            }
        }
        return deleted
    }

    fun deleteLegacyRootByPrefix(context: Context, prefix: String): Int {
        val tree = load(context) ?: return 0
        val root = rootDocumentUri(tree)
        var deleted = 0
        queryChildren(context, tree, root) { name, uri ->
            if (name.startsWith(prefix) && delete(context, uri)) deleted++
        }
        return deleted
    }

    fun deleteLiveExact(context: Context, name: String): Boolean {
        val tree = load(context) ?: return false
        val parent = try {
            liveFolder(context, tree)
        } catch (_: Exception) {
            return false
        }
        val uri = cachedLiveChild(context, name) ?: findChild(context, tree, parent, name) ?: return true
        val deleted = delete(context, uri)
        if (deleted) synchronized(cacheLock) { cachedLiveChildren.remove(name.lowercase()) }
        return deleted
    }

    private fun liveFolder(context: Context, tree: Uri): Uri {
        synchronized(cacheLock) {
            if (cachedTree == tree.toString()) cachedLiveFolder?.let { return it }
        }
        val root = rootDocumentUri(tree)
        val existing = findChild(context, tree, root, INTERNAL_FOLDER_NAME)
        val folder = existing ?: DocumentsContract.createDocument(
            context.contentResolver,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            INTERNAL_FOLDER_NAME,
        ) ?: throw IOException("OneDrive provider refused to create internal live folder")
        synchronized(cacheLock) {
            cachedTree = tree.toString()
            cachedLiveFolder = folder
            cachedLiveChildren.clear()
        }
        return folder
    }

    private fun cachedLiveChild(context: Context, name: String): Uri? {
        val key = name.lowercase()
        val uri = synchronized(cacheLock) { cachedLiveChildren[key] } ?: return null
        if (documentExists(context, uri)) return uri
        synchronized(cacheLock) { cachedLiveChildren.remove(key) }
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

    private fun cacheLiveChild(name: String, uri: Uri) {
        synchronized(cacheLock) { cachedLiveChildren[name.lowercase()] = uri }
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

    private fun ensureCacheTree(tree: Uri) {
        synchronized(cacheLock) {
            if (cachedTree == tree.toString()) return
            cachedTree = tree.toString()
            cachedLiveFolder = null
            cachedLiveChildren.clear()
        }
    }

    private fun invalidateCache() {
        synchronized(cacheLock) {
            cachedTree = null
            cachedLiveFolder = null
            cachedLiveChildren.clear()
        }
    }

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
