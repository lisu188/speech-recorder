package pl.lisu188.speechrecorder

import android.content.Context

object TranscriptionSettings {
    private const val PREFS = "transcription_settings"
    private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"

    fun autoTranscribe(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_TRANSCRIBE, true)

    fun setAutoTranscribe(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_TRANSCRIBE, enabled)
            .apply()
    }
}
