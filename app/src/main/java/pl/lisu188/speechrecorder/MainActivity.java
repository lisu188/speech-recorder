package pl.lisu188.speechrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {
    public static final String ACTION_RESUME_AFTER_BOOT = "pl.lisu188.speechrecorder.RESUME_AFTER_BOOT";
    private static final int REQUEST_PERMISSIONS = 1001;

    private TextView statusTitle;
    private TextView statusSubtitle;
    private TextView lastSpeech;
    private ProgressBar levelMeter;
    private Button primaryAction;
    private boolean receiverRegistered;
    private boolean speechActive;

    private final BroadcastReceiver levelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!RecorderService.ACTION_LEVEL.equals(intent.getAction())) {
                return;
            }
            levelMeter.setProgress(intent.getIntExtra(RecorderService.EXTRA_LEVEL, 0));
            speechActive = intent.getBooleanExtra(RecorderService.EXTRA_SPEECH, false);
            long timestamp = intent.getLongExtra(RecorderService.EXTRA_LAST_SPEECH, 0L);
            if (timestamp > 0) {
                getSharedPreferences("recorder", MODE_PRIVATE).edit().putLong("last_speech", timestamp).apply();
            }
            renderState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        if (ACTION_RESUME_AFTER_BOOT.equals(getIntent().getAction())) {
            requestAndStart();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerLevelReceiver();
        renderState();
    }

    @Override
    protected void onPause() {
        if (receiverRegistered) {
            unregisterReceiver(levelReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    private void registerLevelReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(RecorderService.ACTION_LEVEL);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(levelReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(levelReceiver, filter);
        }
        receiverRegistered = true;
    }

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(18, 18, 18));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(22));
        scroll.addView(content, matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = text("Dyktafon", 30, Color.WHITE, true);
        content.addView(title, matchWrap());

        TextView intro = text("Zapisuje tylko fragmenty, w których wykryje mowę.", 15, Color.LTGRAY, false);
        intro.setPadding(0, dp(6), 0, dp(20));
        content.addView(intro, matchWrap());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(cardBackground());
        content.addView(card, matchWrap());

        statusTitle = text("Zatrzymane", 24, Color.WHITE, true);
        card.addView(statusTitle, matchWrap());

        statusSubtitle = text("Mikrofon nie jest aktywny", 15, Color.LTGRAY, false);
        statusSubtitle.setPadding(0, dp(4), 0, dp(16));
        card.addView(statusSubtitle, matchWrap());

        TextView levelLabel = text("Poziom wejścia", 12, Color.GRAY, false);
        card.addView(levelLabel, matchWrap());

        levelMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelMeter.setMax(100);
        levelMeter.setProgress(0);
        LinearLayout.LayoutParams meterParams = matchWrap();
        meterParams.topMargin = dp(5);
        card.addView(levelMeter, meterParams);

        lastSpeech = text("Ostatnia mowa: —", 13, Color.LTGRAY, false);
        lastSpeech.setPadding(0, dp(12), 0, 0);
        card.addView(lastSpeech, matchWrap());

        primaryAction = new Button(this);
        primaryAction.setText("ROZPOCZNIJ");
        primaryAction.setTextSize(17);
        primaryAction.setMinHeight(dp(58));
        primaryAction.setOnClickListener(v -> toggleRecorder());
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(18);
        content.addView(primaryAction, actionParams);

        TextView privacy = text("Audio pozostaje na telefonie. Android pokazuje aktywność mikrofonu, gdy nasłuchiwanie jest włączone.", 13, Color.GRAY, false);
        privacy.setPadding(0, dp(18), 0, dp(8));
        content.addView(privacy, matchWrap());

        page.addView(AppNavigation.create(this, AppNavigation.RECORDER), matchWrap());
        return page;
    }

    private void toggleRecorder() {
        boolean enabled = getSharedPreferences("recorder", MODE_PRIVATE).getBoolean("enabled", false);
        if (enabled) {
            stopRecorder();
        } else {
            requestAndStart();
        }
    }

    private void renderState() {
        if (statusTitle == null) {
            return;
        }
        boolean enabled = getSharedPreferences("recorder", MODE_PRIVATE).getBoolean("enabled", false);
        long last = getSharedPreferences("recorder", MODE_PRIVATE).getLong("last_speech", 0L);
        if (!enabled) {
            speechActive = false;
            statusTitle.setText("Zatrzymane");
            statusSubtitle.setText("Mikrofon nie jest aktywny");
            primaryAction.setText("ROZPOCZNIJ");
            levelMeter.setProgress(0);
        } else if (speechActive) {
            statusTitle.setText("Nagrywanie");
            statusSubtitle.setText("Wykryto mowę — zapisuję ten fragment");
            primaryAction.setText("ZATRZYMAJ");
        } else {
            statusTitle.setText("Nasłuchiwanie");
            statusSubtitle.setText("Czekam na mowę");
            primaryAction.setText("ZATRZYMAJ");
        }
        lastSpeech.setText(last > 0 ? "Ostatnia mowa: " + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(last)) : "Ostatnia mowa: —");
    }

    private void requestAndStart() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startRecorder();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecorder();
        } else if (requestCode == REQUEST_PERMISSIONS) {
            Toast.makeText(this, "Bez dostępu do mikrofonu aplikacja nie może działać.", Toast.LENGTH_LONG).show();
        }
    }

    private void startRecorder() {
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        startForegroundService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START));
        renderState();
    }

    private void stopRecorder() {
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        startService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP));
        speechActive = false;
        renderState();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(32, 32, 32));
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), Color.rgb(55, 55, 55));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
