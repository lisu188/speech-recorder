package pl.lisu188.speechrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return

        prefs.edit()
            .putBoolean("enabled", false)
            .putBoolean("speech_active", false)
            .putString(
                "capture_error",
                "Po restarcie telefonu Android wymaga ręcznego uruchomienia mikrofonu. Otwórz Dyktafon i wybierz ROZPOCZNIJ.",
            )
            .apply()
    }
}
