package pl.lisu188.speechrecorder

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object GoogleDriveBackup {
    private const val PREFS = "storage_settings"
    private const val MODE_KEY = "recording_cloud_provider"
    private const val MODE_GOOGLE = "google_drive"
    private const val MODE_ONEDRIVE = "onedrive"
    private const val ACCOUNT_KEY = "google_drive_account"
    private const val FOLDER_KEY = "google_drive_folder"
    private const val LIVE_FOLDER_KEY = "google_drive_live_folder"
    private const val FOLDER_NAME = "SpeechRecorder"
    private const val LIVE_FOLDER_NAME = "__sr_live"
    private const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
    private val selectionLock = Any()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSelected(context: Context): Boolean =
        preferences(context).getString(MODE_KEY, MODE_ONEDRIVE) == MODE_GOOGLE

    fun selectedProvider(context: Context): String = if (isSelected(context)) "Google Drive" else "OneDrive"

    fun account(context: Context): String? = preferences(context).getString(ACCOUNT_KEY, null)

    fun isConfigured(context: Context): Boolean = isSelected(context) && !account(context).isNullOrBlank()

    fun hasAccess(context: Context): Boolean =
        if (isSelected(context)) isConfigured(context) else CloudFolderAccess.hasAccess(context)

    fun folderLink(context: Context): String {
        val id = preferences(context).getString(FOLDER_KEY, null)
        return if (id.isNullOrBlank()) "https://drive.google.com/drive/my-drive"
        else "https://drive.google.com/drive/folders/" + id
    }

    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(Scopes.DRIVE_FILE), Scope(Scopes.EMAIL)))
        .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        .build()

    fun selectAccount(context: Context, result: AuthorizationResult): Boolean {
        if (result.hasResolution() || result.accessToken.isNullOrBlank() ||
            Scopes.DRIVE_FILE !in result.grantedScopes
        ) return false
        val email = result.toGoogleSignInAccount()?.email?.trim()?.takeIf { it.isNotBlank() } ?: return false
        synchronized(selectionLock) {
            val prefs = preferences(context)
            val old = prefs.getString(ACCOUNT_KEY, null)
            prefs.edit().apply {
                putString(ACCOUNT_KEY, email)
                putString(MODE_KEY, MODE_GOOGLE)
                if (old != email) {
                    remove(FOLDER_KEY)
                    remove(LIVE_FOLDER_KEY)
                }
            }.apply()
        }
        return true
    }

    fun selectOneDrive(context: Context) {
        synchronized(selectionLock) {
            preferences(context).edit().putString(MODE_KEY, MODE_ONEDRIVE).apply()
        }
    }

    fun disconnect(context: Context) {
        synchronized(selectionLock) {
            preferences(context).edit()
                .putString(MODE_KEY, MODE_ONEDRIVE)
                .remove(ACCOUNT_KEY)
                .remove(FOLDER_KEY)
                .remove(LIVE_FOLDER_KEY)
                .apply()
        }
    }

    private fun token(context: Context, email: String): String? {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(Scopes.DRIVE_FILE)))
            .setAccount(Account(email, "com.google"))
            .build()
        val result = Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request),
            30,
            TimeUnit.SECONDS,
        )
        return if (result.hasResolution()) null else result.accessToken?.takeIf { it.isNotBlank() }
    }

    private data class DriveItem(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val md5: String?,
    )

    private data class Response(val status: Int, val body: String, val location: String?)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(token: String, url: String, method: String, json: JSONObject? = null): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.setRequestProperty("Authorization", "Bearer " + token)
        connection.setRequestProperty("Accept", "application/json")
        if (json != null) {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.doOutput = true
            connection.outputStream.use { it.write(bytes) }
        }
        return try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Google Drive HTTP " + status)
            Response(status, body, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    private fun item(json: JSONObject): DriveItem = DriveItem(
        id = json.getString("id"),
        name = json.optString("name"),
        mimeType = json.optString("mimeType"),
        size = json.optLong("size", -1),
        md5 = json.optString("md5Checksum").takeIf { it.isNotBlank() },
    )

    private fun list(token: String, parent: String, name: String? = null): List<DriveItem> {
        val query = buildString {
            append("'").append(parent.replace("'", "\\'")).append("' in parents and trashed = false")
            if (name != null) append(" and name = '").append(name.replace("'", "\\'")).append("'")
        }
        val found = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val url = API + "?q=" + encode(query) +
                "&fields=" + encode("nextPageToken,files(id,name,mimeType,size,md5Checksum)") +
                "&pageSize=1000" +
                (pageToken?.let { "&pageToken=" + encode(it) } ?: "")
            val response = JSONObject(request(token, url, "GET").body)
            val items = response.optJSONArray("files") ?: JSONArray()
            for (index in 0 until items.length()) found.add(item(items.getJSONObject(index)))
            pageToken = response.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return found
    }

    private fun metadata(token: String, id: String): DriveItem =
        item(JSONObject(request(token, API + "/" + encode(id) +
            "?fields=" + encode("id,name,mimeType,size,md5Checksum"), "GET").body))

    private fun folder(
        context: Context,
        token: String,
        parent: String,
        name: String,
        key: String,
        email: String,
    ): String {
        val prefs = preferences(context)
        prefs.getString(key, null)?.let { cached ->
            try {
                if (metadata(token, cached).mimeType == DRIVE_FOLDER_MIME) return cached
            } catch (_: IOException) {
                synchronized(selectionLock) {
                    if (prefs.getString(key, null) == cached) prefs.edit().remove(key).apply()
                }
            }
        }
        val existing = list(token, parent, name).firstOrNull { it.mimeType == DRIVE_FOLDER_MIME }
        val id = existing?.id ?: JSONObject(request(token, API + "?fields=id", "POST", JSONObject()
            .put("name", name)
            .put("mimeType", DRIVE_FOLDER_MIME)
            .put("parents", JSONArray().put(parent))
        ).body).getString("id")
        synchronized(selectionLock) {
            if (isSelected(context) && account(context) == email) {
                prefs.edit().putString(key, id).apply()
            }
        }
        return id
    }

    private fun destination(context: Context, token: String, live: Boolean, email: String): String {
        val root = folder(context, token, "root", FOLDER_NAME, FOLDER_KEY, email)
        return if (live) folder(context, token, root, LIVE_FOLDER_NAME, LIVE_FOLDER_KEY, email) else root
    }

    private fun fileMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun upload(token: String, file: File, parent: String): String {
        val metadata = JSONObject()
            .put("name", file.name)
            .put("mimeType", "audio/wav")
            .put("parents", JSONArray().put(parent))
        val connection = URL(UPLOAD_API + "?uploadType=resumable&fields=id").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.setRequestProperty("Authorization", "Bearer " + token)
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("X-Upload-Content-Type", "audio/wav")
        connection.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        val body = metadata.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(body.size)
        connection.doOutput = true
        val session = try {
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw IOException("Drive session HTTP " + connection.responseCode)
            connection.getHeaderField("Location") ?: throw IOException("Missing resumable upload location")
        } finally {
            connection.disconnect()
        }
        val sessionUrl = URL(session)
        if (sessionUrl.protocol != "https" || !(sessionUrl.host == "www.googleapis.com" ||
                sessionUrl.host.endsWith(".googleapis.com"))) {
            throw IOException("Unexpected Google Drive upload endpoint")
        }
        val transfer = sessionUrl.openConnection() as HttpURLConnection
        transfer.requestMethod = "PUT"
        transfer.connectTimeout = 20_000
        transfer.readTimeout = 90_000
        transfer.setRequestProperty("Content-Type", "audio/wav")
        transfer.setFixedLengthStreamingMode(file.length())
        transfer.doOutput = true
        return try {
            file.inputStream().use { input -> transfer.outputStream.use { output -> input.copyTo(output) } }
            if (transfer.responseCode !in 200..299) throw IOException("Drive upload HTTP " + transfer.responseCode)
            JSONObject(transfer.inputStream.bufferedReader().use { it.readText() }).getString("id")
        } finally {
            transfer.disconnect()
        }
    }

    fun publish(context: Context, file: File, live: Boolean): Boolean {
        val email = account(context) ?: return false
        if (!isConfigured(context) || !file.isFile || file.length() <= 44L) return false
        return try {
            val access = token(context, email) ?: return false
            val parent = destination(context, access, live, email)
            val size = file.length()
            val hash = fileMd5(file)
            val previous = list(access, parent, file.name).firstOrNull {
                it.size == size && it.md5.equals(hash, ignoreCase = true)
            }
            val remoteId = previous?.id ?: upload(access, file, parent)
            val remote = metadata(access, remoteId)
            val valid = remote.name == file.name && remote.size == size &&
                remote.md5.equals(hash, ignoreCase = true)
            valid && isConfigured(context) && account(context) == email && file.length() == size
        } catch (_: Exception) {
            false
        }
    }

    fun cleanLiveParts(context: Context, finalName: String) {
        val email = account(context) ?: return
        if (!isConfigured(context)) return
        try {
            val access = token(context, email) ?: return
            val parent = preferences(context).getString(LIVE_FOLDER_KEY, null) ?: return
            val prefix = RecordingStorage.livePrefix(finalName)
            list(access, parent).filter { it.name.startsWith(prefix) }.forEach { entry ->
                request(access, API + "/" + encode(entry.id), "DELETE")
            }
        } catch (_: Exception) {
        }
    }
}
