package pl.lisu188.speechrecorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val screenState = mutableStateOf(RecorderScreenState())
    private var receiverRegistered = false
    private var speechActive = false
    private var currentLevel = 0

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRecorder()
        } else {
            Toast.makeText(this, "Bez dostępu do mikrofonu aplikacja nie może działać.", Toast.LENGTH_LONG).show()
        }
    }

    private val levelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingStorage.ACTION_LIBRARY_CHANGED) {
                renderState()
                return
            }
            if (intent?.action != RecorderService.ACTION_LEVEL) return
            currentLevel = intent.getIntExtra(RecorderService.EXTRA_LEVEL, 0)
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
        enableEdgeToEdge()
        setContent {
            SpeechRecorderTheme {
                AppScaffold(this, AppDestination.RECORDER) { padding ->
                    RecorderScreen(
                        state = screenState.value,
                        modifier = Modifier.padding(padding),
                        onToggle = { toggleRecorder() },
                        onOpenSettings = {
                            startActivity(
                                Intent(this, SettingsActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                            )
                        },
                    )
                }
            }
        }

        RecordingStorage.recover(this)
        val resumeAfterBoot = prefs().getBoolean(BootReceiver.KEY_RESUME_AFTER_BOOT, false)
        if (intent.action == ACTION_RESUME_AFTER_BOOT || resumeAfterBoot) {
            prefs().edit().remove(BootReceiver.KEY_RESUME_AFTER_BOOT).remove("capture_error").apply()
            requestAndStart()
        }
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
        val filter = IntentFilter(RecorderService.ACTION_LEVEL).apply {
            addAction(RecordingStorage.ACTION_LIBRARY_CHANGED)
        }
        ContextCompat.registerReceiver(this, levelReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun toggleRecorder() {
        if (prefs().getBoolean("enabled", false) && RecorderService.isRunning) stopRecorder() else requestAndStart()
    }

    private fun renderState() {
        val running = prefs().getBoolean("enabled", false) && RecorderService.isRunning
        val last = prefs().getLong("last_speech", 0L)
        speechActive = running && prefs().getBoolean("speech_active", false)
        if (!running) currentLevel = 0

        screenState.value = RecorderScreenState(
            mode = when {
                !running -> RecorderMode.STOPPED
                speechActive -> RecorderMode.RECORDING
                else -> RecorderMode.LISTENING
            },
            level = currentLevel.coerceIn(0, 100),
            lastSpeech = if (last > 0L) {
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(last))
            } else {
                null
            },
            error = listOfNotNull(
                prefs().getString("capture_error", null),
                prefs().getString("storage_error", null),
            ).joinToString("\n").ifBlank { null },
            cloudReady = CloudFolderAccess.hasAccess(this),
        )
    }

    private fun requestAndStart() {
        if (!CloudFolderAccess.hasAccess(this)) {
            Toast.makeText(
                this,
                "Najpierw wybierz folder OneDrive w Ustawieniach.",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecorder()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecorder() {
        try {
            startForegroundService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
            prefs().edit().putBoolean("enabled", true).remove("capture_error").apply()
        } catch (_: RuntimeException) {
            prefs().edit().putBoolean("enabled", false)
                .putString("capture_error", "Nie można uruchomić mikrofonu. Sprawdź uprawnienia i spróbuj ponownie.")
                .apply()
        }
        renderState()
    }

    private fun stopRecorder() {
        prefs().edit().putBoolean("enabled", false).apply()
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        speechActive = false
        currentLevel = 0
        renderState()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val ACTION_RESUME_AFTER_BOOT = "pl.lisu188.speechrecorder.RESUME_AFTER_BOOT"
        private const val PREFS = "recorder"
    }
}

private enum class RecorderMode {
    STOPPED,
    LISTENING,
    RECORDING,
}

private data class RecorderScreenState(
    val mode: RecorderMode = RecorderMode.STOPPED,
    val level: Int = 0,
    val lastSpeech: String? = null,
    val error: String? = null,
    val cloudReady: Boolean = false,
)

@Composable
private fun RecorderScreen(
    state: RecorderScreenState,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeader(
                title = "Dyktafon",
                subtitle = "Automatycznie zapisuje tylko fragmenty z wykrytą mową.",
            )
        }

        item {
            RecorderStatusCard(state)
        }

        state.error?.let { message ->
            item {
                MessageCard(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Wymaga uwagi",
                    body = message,
                    error = true,
                )
            }
        }

        item {
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Icon(
                    imageVector = if (state.mode == RecorderMode.STOPPED) {
                        Icons.Rounded.Mic
                    } else {
                        Icons.Outlined.MicNone
                    },
                    contentDescription = null,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    if (state.mode == RecorderMode.STOPPED) {
                        "Rozpocznij nasłuch"
                    } else {
                        "Zatrzymaj nasłuch"
                    },
                )
            }
        }

        item {
            MessageCard(
                icon = if (state.cloudReady) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                title = if (state.cloudReady) "OneDrive gotowy" else "Skonfiguruj OneDrive",
                body = if (state.cloudReady) {
                    "Nagrania są zabezpieczane na bieżąco i finalizowane w wybranym folderze."
                } else {
                    "Wybierz folder zapisu, zanim uruchomisz stały nasłuch."
                },
                actionLabel = if (state.cloudReady) null else "Otwórz ustawienia",
                onAction = onOpenSettings,
            )
        }

        item {
            MessageCard(
                icon = Icons.Outlined.Info,
                title = "Tryb always-on",
                body = "Po uruchomieniu nasłuch działa w tle także przy wygaszonym ekranie. Dla najwyższej niezawodności wyłącz optymalizację baterii w Ustawieniach.",
            )
        }
    }
}

@Composable
private fun RecorderStatusCard(state: RecorderScreenState) {
    val container by animateColorAsState(
        targetValue = when (state.mode) {
            RecorderMode.RECORDING -> MaterialTheme.colorScheme.primaryContainer
            RecorderMode.LISTENING -> MaterialTheme.colorScheme.secondaryContainer
            RecorderMode.STOPPED -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "statusContainer",
    )
    val content = when (state.mode) {
        RecorderMode.RECORDING -> MaterialTheme.colorScheme.onPrimaryContainer
        RecorderMode.LISTENING -> MaterialTheme.colorScheme.onSecondaryContainer
        RecorderMode.STOPPED -> MaterialTheme.colorScheme.onSurface
    }
    val animatedLevel = animateFloatAsState(
        targetValue = state.level / 100f,
        label = "microphoneLevel",
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = content.copy(alpha = 0.10f),
                ) {
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when (state.mode) {
                                RecorderMode.RECORDING -> Icons.Rounded.GraphicEq
                                RecorderMode.LISTENING -> Icons.Rounded.Mic
                                RecorderMode.STOPPED -> Icons.Outlined.MicNone
                            },
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = content,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = when (state.mode) {
                            RecorderMode.RECORDING -> "Nagrywanie mowy"
                            RecorderMode.LISTENING -> "Nasłuchiwanie"
                            RecorderMode.STOPPED -> "Zatrzymane"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = content,
                    )
                    Text(
                        text = when (state.mode) {
                            RecorderMode.RECORDING -> "Wykryto głos — zapisuję bieżący fragment"
                            RecorderMode.LISTENING -> "Mikrofon aktywny, czekam na mowę"
                            RecorderMode.STOPPED -> "Mikrofon nie jest aktywny"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.78f),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Poziom wejścia",
                        style = MaterialTheme.typography.labelLarge,
                        color = content.copy(alpha = 0.78f),
                    )
                    Text(
                        "${state.level}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                    )
                }
                LinearProgressIndicator(
                    progress = { animatedLevel.value },
                    modifier = Modifier.fillMaxWidth(),
                    color = content,
                    trackColor = content.copy(alpha = 0.16f),
                )
            }

            Text(
                text = state.lastSpeech?.let { "Ostatnia wykryta mowa: $it" }
                    ?: "Nie wykryto jeszcze mowy w tej sesji.",
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun MessageCard(
    icon: ImageVector,
    title: String,
    body: String,
    error: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val container = if (error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = content)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.78f),
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
