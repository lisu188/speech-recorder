package pl.lisu188.speechrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RecordingStorage.recover(context)

        val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("enabled", false)
        if (!wasEnabled) return

        prefs.edit()
            .putBoolean("enabled", false)
            .putBoolean("speech_active", false)
            .putBoolean(KEY_RESUME_AFTER_BOOT, true)
            .putString(
                "capture_error",
                "Android zatrzymał mikrofon przy restarcie. Otwórz Dyktafon — nasłuch wznowi się automatycznie.",
            )
            .apply()
    }

    companion object {
        const val KEY_RESUME_AFTER_BOOT = "resume_after_boot"
    }
}
