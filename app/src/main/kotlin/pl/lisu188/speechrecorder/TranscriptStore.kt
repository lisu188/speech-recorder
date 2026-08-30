package pl.lisu188.speechrecorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TranscriptStore(private val context: Context) {
    private val resolver = context.contentResolver

    fun audioInfo(audioUri: Uri): AudioInfo {
        val projection = arrayOf(
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
        )
        resolver.query(audioUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return AudioInfo(
                    displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)),
                    dateAddedMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)) * 1000L,
                    durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)),
                )
            }
        }
        throw IOException("Recording metadata is unavailable")
    }

    fun finalizePair(
        audioUri: Uri,
        rawTitle: String,
        summary: String,
        transcript: String,
    ): FinalizedPair {
        val info = audioInfo(audioUri)
        val cleanTitle = sanitizeTitle(rawTitle)
        val timestamp = recordingTimestamp(info)
        val basePrefix = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(timestamp))
        val baseName = uniqueBaseName("${basePrefix}_$cleanTitle")
        val audioName = "$baseName.wav"
        val transcriptName = "$baseName.txt"
        val transcriptUri = createPendingTranscript()
        var audioRenamed = false

        try {
            val content = buildTranscriptContent(
                title = cleanTitle.replace('_', ' '),
                timestamp = timestamp,
                durationMs = info.durationMs,
                summary = summary,
                transcript = transcript,
            )
            resolver.openOutputStream(transcriptUri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(content)
            } ?: throw IOException("Unable to write transcript")

            val renamed = resolver.update(
                audioUri,
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, audioName) },
                null,
                null,
            )
            if (renamed <= 0) throw IOException("Unable to rename audio recording")
            audioRenamed = true

            val transcriptFinalized = resolver.update(
                transcriptUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, transcriptName)
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null,
            )
            if (transcriptFinalized <= 0) throw IOException("Unable to finalize transcript")

            return FinalizedPair(audioName, transcriptName, transcriptUri)
        } catch (error: Exception) {
            if (audioRenamed) {
                try {
                    resolver.update(
                        audioUri,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, info.displayName)
                        },
                        null,
                        null,
                    )
                } catch (_: Exception) {
                }
            }
            try {
                resolver.delete(transcriptUri, null, null)
            } catch (_: Exception) {
            }
            throw error
        }
    }

    fun loadDocuments(): Map<String, TranscriptDocument> {
        val collection = MediaStore.Files.getContentUri("external")
        val result = linkedMapOf<String, TranscriptDocument>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )

        resolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf(RELATIVE_PATH_QUERY, "%.txt"),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                if (!name.endsWith(".txt", ignoreCase = true) || name.startsWith("pending_")) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val text = readText(uri)
                result[name.dropLast(4)] = TranscriptDocument(
                    uri = uri,
                    displayName = name,
                    text = text,
                    summary = extractSummary(text),
                )
            }
        }
        return result
    }

    fun deleteTranscript(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    private fun createPendingTranscript(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "pending_${UUID.randomUUID()}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: throw IOException("Unable to create transcript file")
    }

    private fun uniqueBaseName(initial: String): String {
        var candidate = initial
        var suffix = 2
        while (displayNameExists("$candidate.wav") || displayNameExists("$candidate.txt")) {
            candidate = "${initial}_$suffix"
            suffix++
        }
        return candidate
    }

    private fun displayNameExists(name: String): Boolean {
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(RELATIVE_PATH_QUERY, name),
            null,
        )?.use { cursor -> return cursor.moveToFirst() }
        return false
    }

    private fun readText(uri: Uri): String = try {
        resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun recordingTimestamp(info: AudioInfo): Long {
        val match = Regex("^speech_(\\d{8}_\\d{6})").find(info.displayName)
        val fromName = match?.groupValues?.getOrNull(1)?.let { value ->
            try {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { isLenient = false }.parse(value)?.time
            } catch (_: Exception) {
                null
            }
        }
        return fromName ?: info.dateAddedMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    }

    private fun sanitizeTitle(rawTitle: String): String {
        val invalid = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        val normalized = rawTitle
            .map { character ->
                if (character.code < 32 || character in invalid) ' ' else character
            }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
            .take(MAX_TITLE_LENGTH)
            .trim()

        return (normalized.ifBlank { "Nagranie" })
            .replace(Regex("\\s+"), "_")
            .trim('_')
            .ifBlank { "Nagranie" }
    }

    private fun buildTranscriptContent(
        title: String,
        timestamp: Long,
        durationMs: Long,
        summary: String,
        transcript: String,
    ): String = buildString {
        appendLine(title)
        appendLine()
        append("Data: ")
        appendLine(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp)))
        append("Czas nagrania: ")
        appendLine(formatDuration(durationMs))
        appendLine()
        appendLine("PODSUMOWANIE")
        appendLine()
        appendLine(summary.trim())
        appendLine()
        appendLine("TRANSKRYPCJA")
        appendLine()
        appendLine(transcript.trim())
    }

    private fun extractSummary(text: String): String {
        val marker = "PODSUMOWANIE\n\n"
        val transcriptMarker = "\n\nTRANSKRYPCJA"
        val start = text.indexOf(marker)
        if (start < 0) return ""
        val contentStart = start + marker.length
        val end = text.indexOf(transcriptMarker, contentStart).takeIf { it >= 0 } ?: text.length
        return text.substring(contentStart, end).trim()
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    data class AudioInfo(
        val displayName: String,
        val dateAddedMs: Long,
        val durationMs: Long,
    )

    data class TranscriptDocument(
        val uri: Uri,
        val displayName: String,
        val text: String,
        val summary: String,
    )

    data class FinalizedPair(
        val audioName: String,
        val transcriptName: String,
        val transcriptUri: Uri,
    )

    companion object {
        val RELATIVE_PATH: String = "${Environment.DIRECTORY_MUSIC}/SpeechRecorder"
        val RELATIVE_PATH_QUERY: String = "$RELATIVE_PATH/"
        private const val MAX_TITLE_LENGTH = 60
    }
}
