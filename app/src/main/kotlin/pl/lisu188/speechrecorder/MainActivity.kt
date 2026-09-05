package pl.lisu188.speechrecorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var lastSpeech: TextView
    private lateinit var levelMeter: ProgressBar
    private lateinit var primaryAction: Button
    private var receiverRegistered = false
    private var speechActive = false

    private val levelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecorderService.ACTION_LEVEL) return
            levelMeter.progress = intent.getIntExtra(RecorderService.EXTRA_LEVEL, 0)
            speechActive = intent.getBooleanExtra(RecorderService.EXTRA_SPEECH, false)
            intent.getLongExtra(RecorderService.EXTRA_LAST_SPEECH, 0L)
                .takeIf { it > 0L }
                ?.let {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putLong("last_speech", it)
                        .apply()
                }
            renderState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (intent.action == ACTION_RESUME_AFTER_BOOT) requestAndStart()
    }

    override fun onResume() {
        super.onResume()
        registerLevelReceiver()
        renderState()
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(levelReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun registerLevelReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(RecorderService.ACTION_LEVEL)
        ContextCompat.registerReceiver(this, levelReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun buildUi(): View {
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

        content.addView(textView("Dyktafon", 30, Color.WHITE, true), matchWrap())
        content.addView(
            textView("Zapisuje tylko fragmenty, w których wykryje mowę.", 15, Color.LTGRAY).apply {
                setPadding(0, dp(6), 0, dp(20))
            },
            matchWrap(),
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = cardBackground()
        }
        content.addView(card, matchWrap())

        statusTitle = textView("Zatrzymane", 24, Color.WHITE, true)
        card.addView(statusTitle, matchWrap())

        statusSubtitle = textView("Mikrofon nie jest aktywny", 15, Color.LTGRAY).apply {
            setPadding(0, dp(4), 0, dp(16))
        }
        card.addView(statusSubtitle, matchWrap())

        card.addView(textView("Poziom wejścia", 12, Color.GRAY), matchWrap())

        levelMeter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        card.addView(levelMeter, matchWrap().apply { topMargin = dp(5) })

        lastSpeech = textView("Ostatnia mowa: —", 13, Color.LTGRAY).apply {
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(lastSpeech, matchWrap())

        primaryAction = Button(this).apply {
            text = "ROZPOCZNIJ"
            textSize = 17f
            minHeight = dp(58)
            setOnClickListener { toggleRecorder() }
        }
        content.addView(primaryAction, matchWrap().apply { topMargin = dp(18) })

        content.addView(
            textView(
                "Nagrania są zapisywane na telefonie. Opcjonalna transkrypcja w ustawieniach wysyła zakończone klipy do OpenAI.",
                13,
                Color.GRAY,
            ).apply { setPadding(0, dp(18), 0, dp(8)) },
            matchWrap(),
        )

        page.addView(AppNavigation.create(this, AppNavigation.RECORDER), matchWrap())
        return page
    }

    private fun toggleRecorder() {
        if (prefs().getBoolean("enabled", false)) stopRecorder() else requestAndStart()
    }

    private fun renderState() {
        val enabled = prefs().getBoolean("enabled", false)
        val last = prefs().getLong("last_speech", 0L)

        when {
            !enabled -> {
                speechActive = false
                statusTitle.text = "Zatrzymane"
                statusSubtitle.text = "Mikrofon nie jest aktywny"
                primaryAction.text = "ROZPOCZNIJ"
                levelMeter.progress = 0
            }
            speechActive -> {
                statusTitle.text = "Nagrywanie"
                statusSubtitle.text = "Wykryto mowę — zapisuję ten fragment"
                primaryAction.text = "ZATRZYMAJ"
            }
            else -> {
                statusTitle.text = "Nasłuchiwanie"
                statusSubtitle.text = "Czekam na mowę"
                primaryAction.text = "ZATRZYMAJ"
            }
        }

        lastSpeech.text = if (last > 0L) {
            "Ostatnia mowa: ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(last))}"
        } else {
            "Ostatnia mowa: —"
        }
    }

    private fun requestAndStart() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) startRecorder() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecorder()
        } else {
            Toast.makeText(this, "Bez dostępu do mikrofonu aplikacja nie może działać.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRecorder() {
        prefs().edit().putBoolean("enabled", true).apply()
        startForegroundService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
        renderState()
    }

    private fun stopRecorder() {
        prefs().edit().putBoolean("enabled", false).apply()
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        speechActive = false
        renderState()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun textView(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun cardBackground() = GradientDrawable().apply {
        setColor(Color.rgb(32, 32, 32))
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), Color.rgb(55, 55, 55))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_RESUME_AFTER_BOOT = "pl.lisu188.speechrecorder.RESUME_AFTER_BOOT"
        private const val REQUEST_PERMISSIONS = 1001
        private const val PREFS = "recorder"
    }
}
