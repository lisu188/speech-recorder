package pl.lisu188.speechrecorder;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class AppNavigation {
    public static final int RECORDER = 0;
    public static final int RECORDINGS = 1;
    public static final int SETTINGS = 2;

    private AppNavigation() {
    }

    public static View create(Activity activity, int selected) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 8));
        bar.setBackgroundColor(Color.rgb(24, 24, 24));
        bar.addView(item(activity, "Dyktafon", RECORDER, selected, MainActivity.class), itemParams());
        bar.addView(item(activity, "Nagrania", RECORDINGS, selected, RecordingsActivity.class), itemParams());
        bar.addView(item(activity, "Ustawienia", SETTINGS, selected, SettingsActivity.class), itemParams());
        return bar;
    }

    private static TextView item(Activity activity, String label, int index, int selected, Class<?> target) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 4), dp(activity, 10));
        boolean active = index == selected;
        view.setTextColor(active ? Color.rgb(111, 207, 135) : Color.LTGRAY);
        if (active) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            view.setOnClickListener(v -> {
                Intent intent = new Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
            });
        }
        return view;
    }

    private static LinearLayout.LayoutParams itemParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
