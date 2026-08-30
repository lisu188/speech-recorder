package pl.lisu188.speechrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val enabled = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (!enabled) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Wznowienie nagrywania",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Pozwala wznowić nasłuchiwanie po restarcie telefonu"
            },
        )

        val openIntent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RESUME_AFTER_BOOT)
        val pendingIntent = PendingIntent.getActivity(
            context,
            3,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("Dyktafon zatrzymany po restarcie")
            .setContentText("Dotknij, aby wznowić stałe nasłuchiwanie mikrofonu")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "speech_recorder_resume"
        const val NOTIFICATION_ID = 42
    }
}
