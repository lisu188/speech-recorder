package pl.lisu188.speechrecorder

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (!TranscriptionSettings.autoTranscribe(applicationContext)) return Result.success()
        if (!TranscriptFolderAccess.hasAccess(applicationContext)) return Result.success()

        val audioUri = inputData.getString(INPUT_AUDIO_URI)?.let(Uri::parse) ?: return Result.failure()
        val apiKey = OpenAiKeyStore.load(applicationContext) ?: return Result.success()
        val store = TranscriptStore(applicationContext)

        return try {
            val info = store.audioInfo(audioUri)
            val baseName = info.displayName.substringBeforeLast('.')
            if (baseName in store.loadDocuments()) return Result.success()

            val client = OpenAiClient()
            val transcript = client.transcribe(applicationContext, audioUri, apiKey)
            val metadata = client.createMetadata(transcript, apiKey)
            val pair = store.finalizePair(
                audioUri = audioUri,
                rawTitle = metadata.title,
                summary = metadata.summary,
                transcript = transcript,
            )
            Result.success(
                Data.Builder()
                    .putString(OUTPUT_AUDIO_NAME, pair.audioName)
                    .putString(OUTPUT_TRANSCRIPT_NAME, pair.transcriptName)
                    .build(),
            )
        } catch (error: OpenAiClient.OpenAiException) {
            if (error.retryable) retryOrFail(error.message) else failure(error.message)
        } catch (error: IOException) {
            retryOrFail(error.message)
        } catch (error: SecurityException) {
            failure(error.message)
        } catch (error: Exception) {
            failure(error.message)
        }
    }

    private fun retryOrFail(message: String?): Result =
        if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(message)

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
    }
}
