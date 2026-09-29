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
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var folderStatus: TextView
    private lateinit var revokeFolderButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::folderStatus.isInitialized) refreshState()
    }

    @Deprecated("Deprecated in Android API, retained for minSdk-compatible folder selection")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ONEDRIVE_FOLDER || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        if (!CloudFolderAccess.save(this, uri, data.flags)) {
            Toast.makeText(
                this,
                "Nie udało się zachować dostępu do wybranego folderu. Wybierz folder z prawem zapisu.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        refreshState()
        RecordingStorage.migrateMediaStore(this)
        RecordingStorage.recover(this)

        val authority = uri.authority.orEmpty().lowercase()
        val looksLikeOneDrive = authority.contains("microsoft") ||
            authority.contains("skydrive") ||
            authority.contains("onedrive")
        Toast.makeText(
            this,
            if (looksLikeOneDrive) {
                "Folder OneDrive zapisany. Oczekujące nagrania zostaną wysłane."
            } else {
                "Folder zapisany. Upewnij się, że wybrałeś go z sekcji OneDrive w selektorze Androida."
            },
            Toast.LENGTH_LONG,
        ).show()
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
            textView("Nagrywanie w tle i zapis do OneDrive", 15, Color.LTGRAY).apply {
                setPadding(0, dp(6), 0, dp(22))
            },
            matchWrap(),
        )

        addSection(
            content,
            "Nagrywanie",
            "5 s bufora przed wykrytą mową\n8 s ciszy kończy klip\nWAV 16 kHz mono\nAudio jest wykrywane i zapisywane lokalnie bez usług AI.",
        )

        addSection(
            content,
            "Działanie w tle",
            "Ciągły dostęp do mikrofonu wymaga foreground service. Na Androidzie 13+ aplikacja nie prosi o zgodę na zwykłe powiadomienia, więc komunikat usługi nie jest pokazywany w panelu powiadomień; Android nadal pokazuje aktywną usługę w systemowym widoku aktywnych aplikacji. Na starszych wersjach Androida stałe powiadomienie usługi może być widoczne.",
        )

        content.addView(
            textView("OneDrive", 18, Color.WHITE, true),
            matchWrap().apply { topMargin = dp(12) },
        )
        folderStatus = textView("", 14, Color.LTGRAY).apply {
            setPadding(0, dp(6), 0, dp(8))
        }
        content.addView(folderStatus, matchWrap())

        content.addView(
            Button(this).apply {
                text = "WYBIERZ FOLDER W ONEDRIVE"
                setOnClickListener { requestOneDriveFolder() }
            },
            matchWrap(),
        )

        revokeFolderButton = Button(this).apply {
            text = "USUŃ DOSTĘP DO FOLDERU"
            setOnClickListener {
                CloudFolderAccess.clear(this@SettingsActivity)
                refreshState()
                Toast.makeText(
                    this@SettingsActivity,
                    "Dostęp usunięty. Niezapisane nagrania pozostaną bezpiecznie na telefonie.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        content.addView(revokeFolderButton, matchWrap().apply { topMargin = dp(6) })

        addSection(
            content,
            "Jak działa zapis",
            "Wybierz w systemowym selektorze folder w OneDrive, np. SpeechRecorder. Zakończone nagranie jest najpierw domykane w prywatnym katalogu aplikacji, następnie kopiowane przez systemowy provider OneDrive. Lokalna kopia jest usuwana dopiero po poprawnym zapisie. Po utracie dostępu lub błędzie providera WorkManager ponawia operację.",
        )

        addSection(
            content,
            "Migracja",
            "Po wybraniu folderu aplikacja przeniesie istniejące pliki WAV z wcześniejszego Music/SpeechRecorder do wybranego folderu. Plik źródłowy jest kasowany dopiero po poprawnym skopiowaniu.",
        )

        addSection(
            content,
            "Prywatność",
            "Integracja z OpenAI została usunięta. Aplikacja nie posiada klucza API, nie wykonuje transkrypcji i nie ma własnego uprawnienia INTERNET. Dostęp do OneDrive jest realizowany przez systemowy Storage Access Framework i provider OneDrive zainstalowany na urządzeniu.",
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

        refreshState()
        page.addView(AppNavigation.create(this, AppNavigation.SETTINGS), matchWrap())
        return page
    }

    private fun requestOneDriveFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_ONEDRIVE_FOLDER)
    }

    private fun refreshState() {
        val hasFolder = CloudFolderAccess.hasAccess(this)
        folderStatus.text = if (hasFolder) {
            "Folder zapisu: ${CloudFolderAccess.displayPath(this)}"
        } else {
            "Folder zapisu: nie wybrano. Nagrywanie wymaga jednorazowego wskazania folderu OneDrive."
        }
        revokeFolderButton.isEnabled = hasFolder
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

    private companion object {
        const val REQUEST_ONEDRIVE_FOLDER = 7301
    }
}
