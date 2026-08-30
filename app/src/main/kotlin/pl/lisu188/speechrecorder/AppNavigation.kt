package pl.lisu188.speechrecorder

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object AppNavigation {
    const val RECORDER = 0
    const val RECORDINGS = 1
    const val SETTINGS = 2

    fun create(activity: Activity, selected: Int): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(8))
        setBackgroundColor(Color.rgb(24, 24, 24))
        addView(item(activity, "Dyktafon", RECORDER, selected, MainActivity::class.java), itemParams())
        addView(item(activity, "Nagrania", RECORDINGS, selected, RecordingsActivity::class.java), itemParams())
        addView(item(activity, "Ustawienia", SETTINGS, selected, SettingsActivity::class.java), itemParams())
    }

    private fun item(
        activity: Activity,
        label: String,
        index: Int,
        selected: Int,
        target: Class<out Activity>,
    ): TextView = TextView(activity).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(activity.dp(4), activity.dp(10), activity.dp(4), activity.dp(10))
        val active = index == selected
        setTextColor(if (active) Color.rgb(111, 207, 135) else Color.LTGRAY)
        if (active) {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        } else {
            setOnClickListener {
                activity.startActivity(
                    Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
    }

    private fun itemParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
