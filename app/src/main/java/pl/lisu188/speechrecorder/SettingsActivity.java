package pl.lisu188.speechrecorder;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private LinearLayout buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(18, 18, 18));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(22));
        scroll.addView(content, matchWrap());
        page.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = text("Ustawienia", 30, Color.WHITE, true);
        content.addView(title, matchWrap());

        TextView subtitle = text("Działanie w tle i szczegóły nagrywania", 15, Color.LTGRAY, false);
        subtitle.setPadding(0, dp(6), 0, dp(22));
        content.addView(subtitle, matchWrap());

        addSection(content, "Nagrywanie", "5 s bufora przed wykrytą mową\n8 s ciszy kończy klip\nWAV 16 kHz mono\nNagrania: Music/SpeechRecorder");
        addSection(content, "Działanie w tle", "Foreground service pozostaje aktywny po zamknięciu ekranu aplikacji. Android może nadal zatrzymać usługę po Force stop, odebraniu uprawnień lub przez ograniczenia systemowe.");
        addSection(content, "Prywatność", "Audio pozostaje lokalnie na telefonie. Aplikacja nie ma uprawnienia INTERNET i nie wysyła nagrań do chmury.");

        Button appSettings = new Button(this);
        appSettings.setText("USTAWIENIA SYSTEMOWE APLIKACJI");
        appSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        LinearLayout.LayoutParams appParams = matchWrap();
        appParams.topMargin = dp(14);
        content.addView(appSettings, appParams);

        Button battery = new Button(this);
        battery.setText("OPTYMALIZACJA BATERII");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        LinearLayout.LayoutParams batteryParams = matchWrap();
        batteryParams.topMargin = dp(10);
        content.addView(battery, batteryParams);

        page.addView(AppNavigation.create(this, AppNavigation.SETTINGS), matchWrap());
        return page;
    }

    private void addSection(LinearLayout parent, String heading, String body) {
        TextView h = text(heading, 18, Color.WHITE, true);
        LinearLayout.LayoutParams hp = matchWrap();
        hp.topMargin = dp(12);
        parent.addView(h, hp);

        TextView b = text(body, 14, Color.LTGRAY, false);
        b.setLineSpacing(0, 1.15f);
        b.setPadding(0, dp(6), 0, dp(12));
        parent.addView(b, matchWrap());
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
