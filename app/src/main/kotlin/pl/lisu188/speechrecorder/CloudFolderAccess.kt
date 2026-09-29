package pl.lisu188.speechrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

object CloudFolderAccess {
    private const val PREFS = "storage_settings"
    private const val KEY_TREE_URI = "recording_tree_uri"

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
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)?.let { release(context, it) }
        prefs.edit().remove(KEY_TREE_URI).apply()
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
        val children = childrenUri(tree)
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
                    val archived = name.endsWith(".wav.zip", ignoreCase = true)
                    val wav = name.endsWith(".wav", ignoreCase = true) || mime == "audio/wav" || mime == "audio/x-wav"
                    if (!wav && !archived) continue
                    add(
                        DocumentInfo(
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn)),
                            name = name,
                            sizeBytes = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn),
                            lastModifiedMs = if (cursor.isNull(modifiedColumn)) 0L else cursor.getLong(modifiedColumn),
                            archived = archived,
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
        findChild(context, tree, name)?.let { return it }
        return DocumentsContract.createDocument(
            context.contentResolver,
            rootDocumentUri(tree),
            mimeType,
            name,
        ) ?: throw IOException("OneDrive provider refused to create $name")
    }

    fun exists(context: Context, name: String): Boolean {
        val tree = load(context) ?: return false
        return findChild(context, tree, name) != null
    }

    fun delete(context: Context, uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            false
        }

    fun deleteByPrefix(context: Context, prefix: String): Int {
        val tree = load(context) ?: return 0
        var deleted = 0
        val children = childrenUri(tree)
        context.contentResolver.query(
            children,
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
                if (!name.startsWith(prefix)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                if (delete(context, uri)) deleted++
            }
        }
        return deleted
    }

    private fun findChild(context: Context, tree: Uri, name: String): Uri? {
        val children = childrenUri(tree)
        context.contentResolver.query(
            children,
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
                if (cursor.getString(nameColumn).equals(name, ignoreCase = true)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                }
            }
        }
        return null
    }

    private fun childrenUri(tree: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        tree,
        DocumentsContract.getTreeDocumentId(tree),
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

    private const val INTERNAL_PREFIX = "__sr_"
}
