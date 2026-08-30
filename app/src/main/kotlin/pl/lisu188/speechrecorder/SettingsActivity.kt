package pl.lisu188.speechrecorder

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        val scroll = ScrollView(this).apply { addView(content, matchWrap()) }
        page.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(textView("Ustawienia", 30, Color.WHITE, true), matchWrap())
        content.addView(
            textView("Działanie w tle i szczegóły nagrywania", 15, Color.LTGRAY).apply {
                setPadding(0, dp(6), 0, dp(22))
            },
            matchWrap(),
        )

        addSection(
            content,
            "Nagrywanie",
            "5 s bufora przed wykrytą mową\n8 s ciszy kończy klip\nWAV 16 kHz mono\nNagrania: Music/SpeechRecorder",
        )
        addSection(
            content,
            "Działanie w tle",
            "Foreground service pozostaje aktywny po zamknięciu ekranu aplikacji. Android może nadal zatrzymać usługę po Force stop, odebraniu uprawnień lub przez ograniczenia systemowe.",
        )
        addSection(
            content,
            "Prywatność",
            "Audio pozostaje lokalnie na telefonie. Aplikacja nie ma uprawnienia INTERNET i nie wysyła nagrań do chmury.",
        )

        content.addView(
            Button(this).apply {
                text = "USTAWIENIA SYSTEMOWE APLIKACJI"
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        },
                    )
                }
            },
            matchWrap().apply { topMargin = dp(14) },
        )

        content.addView(
            Button(this).apply {
                text = "OPTYMALIZACJA BATERII"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            matchWrap().apply { topMargin = dp(10) },
        )

        page.addView(AppNavigation.create(this, AppNavigation.SETTINGS), matchWrap())
        return page
    }

    private fun addSection(parent: LinearLayout, heading: String, body: String) {
        parent.addView(
            textView(heading, 18, Color.WHITE, true),
            matchWrap().apply { topMargin = dp(12) },
        )
        parent.addView(
            textView(body, 14, Color.LTGRAY).apply {
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, dp(12))
            },
            matchWrap(),
        )
    }

    private fun textView(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
