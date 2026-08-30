package pl.lisu188.speechrecorder

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var apiKeyInput: EditText
    private lateinit var autoTranscribe: Switch
    private lateinit var keyStatus: TextView
    private lateinit var deleteKeyButton: Button

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
            textView("Działanie w tle, nagrywanie i transkrypcja", 15, Color.LTGRAY).apply {
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

        content.addView(
            textView("Transkrypcja OpenAI", 18, Color.WHITE, true),
            matchWrap().apply { topMargin = dp(12) },
        )
        keyStatus = textView("", 14, Color.LTGRAY).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(keyStatus, matchWrap())

        apiKeyInput = EditText(this).apply {
            hint = "Klucz OpenAI API (sk-...)"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        content.addView(apiKeyInput, matchWrap())

        autoTranscribe = Switch(this).apply {
            text = "Automatycznie transkrybuj nowe nagrania"
            setTextColor(Color.WHITE)
            isChecked = TranscriptionSettings.autoTranscribe(this@SettingsActivity)
        }
        content.addView(autoTranscribe, matchWrap().apply { topMargin = dp(8) })

        content.addView(
            Button(this).apply {
                text = "ZAPISZ USTAWIENIA TRANSKRYPCJI"
                setOnClickListener { saveTranscriptionSettings() }
            },
            matchWrap().apply { topMargin = dp(8) },
        )

        content.addView(
            Button(this).apply {
                text = "TRANSKRYBUJ BRAKUJĄCE NAGRANIA"
                setOnClickListener {
                    if (!OpenAiKeyStore.hasKey(this@SettingsActivity)) {
                        Toast.makeText(this@SettingsActivity, "Najpierw zapisz klucz OpenAI API", Toast.LENGTH_LONG).show()
                    } else {
                        TranscriptionScheduler.enqueueMissing(this@SettingsActivity)
                        Toast.makeText(this@SettingsActivity, "Dodano brakujące transkrypcje do kolejki", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            matchWrap().apply { topMargin = dp(8) },
        )

        deleteKeyButton = Button(this).apply {
            text = "USUŃ KLUCZ OPENAI"
            setOnClickListener {
                OpenAiKeyStore.delete(this@SettingsActivity)
                TranscriptionScheduler.cancelPending(this@SettingsActivity)
                refreshKeyState()
                Toast.makeText(this@SettingsActivity, "Klucz OpenAI usunięty", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(deleteKeyButton, matchWrap().apply { topMargin = dp(8) })

        addSection(
            content,
            "Pliki po transkrypcji",
            "Po zakończeniu transkrypcji aplikacja nadaje WAV krótką nazwę opisującą rozmowę i zapisuje obok plik TXT o identycznej nazwie bazowej. TXT zawiera podsumowanie oraz pełną transkrypcję.",
        )
        addSection(
            content,
            "Prywatność",
            "Nagrywanie i wykrywanie mowy działają lokalnie. Gdy automatyczna transkrypcja jest włączona i zapisano klucz API, zakończone fragmenty audio są wysyłane do OpenAI. Klucz API jest szyfrowany przy użyciu Android Keystore i nie jest zapisany w repozytorium.",
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

        refreshKeyState()
        page.addView(AppNavigation.create(this, AppNavigation.SETTINGS), matchWrap())
        return page
    }

    private fun saveTranscriptionSettings() {
        val newKey = apiKeyInput.text.toString().trim()
        if (newKey.isNotEmpty()) {
            if (!newKey.startsWith("sk-") || newKey.length < 20) {
                Toast.makeText(this, "Nieprawidłowy format klucza OpenAI API", Toast.LENGTH_LONG).show()
                return
            }
            try {
                OpenAiKeyStore.save(this, newKey)
                apiKeyInput.text.clear()
            } catch (_: Exception) {
                Toast.makeText(this, "Nie udało się bezpiecznie zapisać klucza", Toast.LENGTH_LONG).show()
                return
            }
        }

        TranscriptionSettings.setAutoTranscribe(this, autoTranscribe.isChecked)
        if (autoTranscribe.isChecked && OpenAiKeyStore.hasKey(this)) {
            TranscriptionScheduler.enqueueMissing(this)
        } else if (!autoTranscribe.isChecked) {
            TranscriptionScheduler.cancelPending(this)
        }
        refreshKeyState()
        Toast.makeText(this, "Ustawienia zapisane", Toast.LENGTH_SHORT).show()
    }

    private fun refreshKeyState() {
        val hasKey = OpenAiKeyStore.hasKey(this)
        keyStatus.text = if (hasKey) {
            "Klucz OpenAI jest zapisany bezpiecznie na tym urządzeniu."
        } else {
            "Brak klucza OpenAI — transkrypcja nie będzie wysyłana."
        }
        apiKeyInput.hint = if (hasKey) {
            "Wpisz nowy klucz, aby zastąpić zapisany"
        } else {
            "Klucz OpenAI API (sk-...)"
        }
        deleteKeyButton.isEnabled = hasKey
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
