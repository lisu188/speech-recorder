package pl.lisu188.speechrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "speech_recorder_resume";
    private static final int NOTIFICATION_ID = 42;

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean enabled = context.getSharedPreferences("recorder", Context.MODE_PRIVATE).getBoolean("enabled", false);
        if (!enabled) {
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Wznowienie nagrywania", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Pozwala wznowić nasłuchiwanie po restarcie telefonu");
        manager.createNotificationChannel(channel);

        Intent open = new Intent(context, MainActivity.class).setAction(MainActivity.ACTION_RESUME_AFTER_BOOT);
        PendingIntent pending = PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_notification)
                .setContentTitle("Speech Recorder zatrzymany po restarcie")
                .setContentText("Dotknij, aby wznowić stałe nasłuchiwanie mikrofonu")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
