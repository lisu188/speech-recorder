package pl.lisu188.speechrecorder

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

class OpenAiClient {
    fun transcribe(context: Context, audioUri: Uri, apiKey: String): String {
        val chunks = WavChunker.createChunks(context, audioUri)
        return try {
            chunks.mapIndexed { index, chunk ->
                transcribeChunk(chunk, apiKey, index)
            }.filter { it.isNotBlank() }
                .joinToString("\n\n")
                .trim()
        } finally {
            chunks.forEach { it.delete() }
        }
    }

    fun createMetadata(transcript: String, apiKey: String): ConversationMetadata {
        if (transcript.isBlank()) {
            return ConversationMetadata(
                title = "Brak rozpoznanej mowy",
                summary = "Nie udało się rozpoznać treści mowy w nagraniu.",
            )
        }

        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "title",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Short factual conversation title, 3-8 words, at most 60 characters"),
                    )
                    .put(
                        "summary",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Concise factual summary of the conversation in 1-3 sentences"),
                    ),
            )
            .put("required", JSONArray().put("title").put("summary"))
            .put("additionalProperties", false)

        val body = JSONObject()
            .put("model", METADATA_MODEL)
            .put("store", false)
            .put("reasoning", JSONObject().put("effort", "none"))
            .put("max_output_tokens", 350)
            .put(
                "instructions",
                "Create a short filename-quality title and a concise summary from the transcript. " +
                    "Use the dominant language of the conversation; use Polish when Polish dominates. " +
                    "The title must contain 3-8 words, be at most 60 characters, have no quotes, emoji, trailing punctuation, " +
                    "or generic labels such as Nagranie or Rozmowa. Do not invent facts. The summary must be factual and concise.",
            )
            .put("input", transcript)
            .put(
                "text",
                JSONObject().put(
                    "format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put("name", "conversation_metadata")
                        .put("strict", true)
                        .put("schema", schema),
                ),
            )

        val response = executeJson(RESPONSES_URL, body, apiKey, READ_TIMEOUT_MS)
        val outputText = extractOutputText(response)
            ?: throw OpenAiException(500, "OpenAI response did not contain output text", true)
        val metadata = JSONObject(outputText)
        return ConversationMetadata(
            title = metadata.optString("title").trim(),
            summary = metadata.optString("summary").trim(),
        )
    }

    private fun transcribeChunk(file: File, apiKey: String, index: Int): String {
        val boundary = "----SpeechRecorder${UUID.randomUUID()}"
        val connection = (URL(TRANSCRIPTIONS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = TRANSCRIPTION_READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            BufferedOutputStream(connection.outputStream).use { output ->
                writeFormField(output, boundary, "model", TRANSCRIPTION_MODEL)
                output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
                output.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"speech_${index + 1}.wav\"\r\n".toByteArray(
                        StandardCharsets.UTF_8,
                    ),
                )
                output.write("Content-Type: audio/wav\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                BufferedInputStream(FileInputStream(file)).use { input -> input.copyTo(output, 64 * 1024) }
                output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
            }

            val status = connection.responseCode
            val responseBody = readResponse(connection, status)
            if (status !in 200..299) throw apiError(status, responseBody)
            return JSONObject(responseBody).optString("text").trim()
        } catch (error: OpenAiException) {
            throw error
        } catch (error: IOException) {
            throw OpenAiException(0, error.message ?: "Network error", true, error)
        } finally {
            connection.disconnect()
        }
    }

    private fun writeFormField(output: BufferedOutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun executeJson(url: String, body: JSONObject, apiKey: String, readTimeoutMs: Int): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            BufferedOutputStream(connection.outputStream).use { output ->
                output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            val responseBody = readResponse(connection, status)
            if (status !in 200..299) throw apiError(status, responseBody)
            return JSONObject(responseBody)
        } catch (error: OpenAiException) {
            throw error
        } catch (error: IOException) {
            throw OpenAiException(0, error.message ?: "Network error", true, error)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun apiError(status: Int, responseBody: String): OpenAiException {
        val message = try {
            JSONObject(responseBody).optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: "OpenAI HTTP $status"
        } catch (_: Exception) {
            "OpenAI HTTP $status"
        }
        val retryable = status == 408 || status == 409 || status == 429 || status >= 500
        return OpenAiException(status, message, retryable)
    }

    private fun extractOutputText(response: JSONObject): String? {
        val output = response.optJSONArray("output") ?: return null
        for (itemIndex in 0 until output.length()) {
            val item = output.optJSONObject(itemIndex) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val part = content.optJSONObject(contentIndex) ?: continue
                if (part.optString("type") == "output_text") {
                    return part.optString("text").takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    data class ConversationMetadata(
        val title: String,
        val summary: String,
    )

    class OpenAiException(
        val statusCode: Int,
        message: String,
        val retryable: Boolean,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private companion object {
        const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        const val RESPONSES_URL = "https://api.openai.com/v1/responses"
        const val TRANSCRIPTION_MODEL = "gpt-transcribe"
        const val METADATA_MODEL = "gpt-5.6-luna"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 90_000
        const val TRANSCRIPTION_READ_TIMEOUT_MS = 300_000
    }
}
