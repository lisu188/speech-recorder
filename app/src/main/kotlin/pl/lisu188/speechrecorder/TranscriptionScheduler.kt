package pl.lisu188.speechrecorder

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

object TranscriptionScheduler {
    private const val TAG = "speech-recorder-transcription"

    fun enqueue(context: Context, audioUri: Uri): Boolean {
        if (!TranscriptionSettings.autoTranscribe(context) || !OpenAiKeyStore.hasKey(context)) return false

        val work = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(Data.Builder().putString(TranscriptionWorker.INPUT_AUDIO_URI, audioUri.toString()).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(audioUri),
            ExistingWorkPolicy.KEEP,
            work,
        )
        return true
    }

    fun enqueueMissing(context: Context) {
        if (!TranscriptionSettings.autoTranscribe(context) || !OpenAiKeyStore.hasKey(context)) return

        val transcriptNames = TranscriptStore(context).loadDocuments().keys
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )

        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH}=?",
            arrayOf(TranscriptStore.RELATIVE_PATH_QUERY),
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                if (!name.endsWith(".wav", ignoreCase = true)) continue
                val baseName = name.substringBeforeLast('.')
                if (baseName in transcriptNames) continue
                enqueue(context, ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
            }
        }
    }

    fun cancelPending(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    private fun uniqueWorkName(uri: Uri): String {
        val id = UUID.nameUUIDFromBytes(uri.toString().toByteArray(StandardCharsets.UTF_8))
        return "transcription_$id"
    }
}
