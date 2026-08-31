package pl.lisu188.speechrecorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

object TranscriptFolderAccess {
    private const val PREFS = "transcription_settings"
    private const val KEY_TREE_URI = "transcript_tree_uri"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val REQUIRED_RELATIVE_PATH = "Music/SpeechRecorder"

    fun load(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?: return null
        val uri = Uri.parse(raw)
        val stillGranted = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
        if (!stillGranted) {
            clear(context)
            return null
        }
        return uri
    }

    fun hasAccess(context: Context): Boolean = load(context) != null

    fun save(context: Context, uri: Uri, flags: Int): Boolean {
        if (!isSpeechRecorderFolder(uri)) return false
        val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return try {
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TREE_URI, uri.toString())
                .apply()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse)
        if (current != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    current,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    fun isSpeechRecorderFolder(uri: Uri): Boolean {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return false
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val separator = documentId.indexOf(':')
        if (separator <= 0) return false
        val volume = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1).trim('/').replace('\\', '/')
        return volume.equals("primary", ignoreCase = true) &&
            relativePath.equals(REQUIRED_RELATIVE_PATH, ignoreCase = true)
    }

    fun displayPath(context: Context): String =
        if (hasAccess(context)) REQUIRED_RELATIVE_PATH else "Nie wybrano"
}
