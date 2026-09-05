package pl.lisu188.speechrecorder

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.IOException

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    @Volatile private var client: OpenAiClient? = null

    override fun onStopped() {
        client?.cancel()
        super.onStopped()
    }

    override fun doWork(): Result {
        if (!TranscriptionSettings.autoTranscribe(applicationContext)) return Result.success()
        if (!TranscriptFolderAccess.hasAccess(applicationContext)) return Result.success()

        val audioUri = inputData.getString(INPUT_AUDIO_URI)?.let(Uri::parse) ?: return Result.failure()
        val apiKey = OpenAiKeyStore.load(applicationContext) ?: return Result.success()
        val store = TranscriptStore(applicationContext)
        val checkpoint = TranscriptionCheckpoint(File(applicationContext.noBackupFilesDir, "transcriptions"), audioUri.toString())
        val startedAt = SystemClock.elapsedRealtime()
        val beforeRequest = {
            if (isStopped || !TranscriptionScheduler.canTranscribe(applicationContext)) throw WorkPaused()
            if (SystemClock.elapsedRealtime() - startedAt >= WORK_SLICE_MS) throw WorkPaused()
        }

        return try {
            val info = store.audioInfo(audioUri)
            val baseName = info.displayName.substringBeforeLast('.')
            if (baseName in store.documentNames()) {
                checkpoint.clear()
                return Result.success()
            }

            val api = OpenAiClient().also { client = it }
            beforeRequest()
            val transcript = checkpoint.remember("transcript") {
                api.transcribe(applicationContext, audioUri, apiKey, checkpoint, beforeRequest)
            }
            val metadata = JSONObject(checkpoint.remember("metadata") {
                beforeRequest()
                val value = api.createMetadata(transcript, apiKey)
                JSONObject().put("title", value.title).put("summary", value.summary).toString()
            })
            beforeRequest()
            val pair = store.finalizePair(
                audioUri = audioUri,
                rawTitle = metadata.getString("title"),
                summary = metadata.getString("summary"),
                transcript = transcript,
            )
            checkpoint.clear()
            Result.success(
                Data.Builder()
                    .putString(OUTPUT_AUDIO_NAME, pair.audioName)
                    .putString(OUTPUT_TRANSCRIPT_NAME, pair.transcriptName)
                    .build(),
            )
        } catch (_: WorkPaused) {
            Result.retry()
        } catch (error: OpenAiClient.OpenAiException) {
            if (error.retryable) retryOrFail(checkpoint, error.message) else failure(error.message)
        } catch (error: IOException) {
            retryOrFail(checkpoint, error.message)
        } catch (error: SecurityException) {
            failure(error.message)
        } catch (error: Exception) {
            failure(error.message)
        } finally {
            client = null
        }
    }

    private class WorkPaused : Exception()

    private fun retryOrFail(checkpoint: TranscriptionCheckpoint, message: String?): Result = try {
        if (checkpoint.retryAfterFailure(id.toString(), MAX_RETRIES)) Result.retry() else failure(message)
    } catch (_: IOException) {
        failure("Nie udało się zapisać postępu transkrypcji. Sprawdź wolne miejsce na urządzeniu.")
    }

    private fun failure(message: String?): Result = Result.failure(
        Data.Builder()
            .putString(OUTPUT_ERROR, message?.take(500) ?: "Transcription failed")
            .build(),
    )

    companion object {
        const val INPUT_AUDIO_URI = "audio_uri"
        const val OUTPUT_AUDIO_NAME = "audio_name"
        const val OUTPUT_TRANSCRIPT_NAME = "transcript_name"
        const val OUTPUT_ERROR = "error"
        private const val MAX_RETRIES = 5
        private const val WORK_SLICE_MS = 4 * 60 * 1000L
    }
}
