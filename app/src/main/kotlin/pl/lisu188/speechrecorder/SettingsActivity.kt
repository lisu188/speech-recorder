package pl.lisu188.speechrecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.PowerManager
import android.provider.Settings
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SettingsActivity : ComponentActivity() {
    private val screenState = mutableStateOf(SettingsScreenState())
    private val folderExecutor = Executors.newSingleThreadExecutor()
    private var folderTask: Future<*>? = null
    private var folderCancellation: CancellationSignal? = null
    private var folderGeneration = 0
    private var uiDestroyed = false

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { saveFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpeechRecorderTheme {
                AppScaffold(this, AppDestination.SETTINGS) { padding ->
                    SettingsScreen(
                        state = screenState.value,
                        modifier = Modifier.padding(padding),
                        onChooseFolder = { folderPicker.launch(null) },
                        onRevokeFolder = { revokeFolder() },
                        onBatterySettings = { requestBatteryExemption() },
                        onAppSettings = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:$packageName")
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onPause() {
        cancelFolderRefresh()
        super.onPause()
    }

    override fun onDestroy() {
        uiDestroyed = true
        cancelFolderRefresh()
        folderExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun saveFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (!CloudFolderAccess.save(this, uri, flags)) {
            Toast.makeText(
                this,
                "Nie udało się zachować dostępu do folderu. Wybierz folder OneDrive z prawem zapisu.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        RecordingStorage.migrateMediaStore(this)
        RecordingStorage.recover(this)
        refreshState()

        val authority = uri.authority.orEmpty().lowercase()
        val looksLikeOneDrive = authority.contains("microsoft") ||
            authority.contains("skydrive") ||
            authority.contains("onedrive")
        Toast.makeText(
            this,
            if (looksLikeOneDrive) {
                "Folder OneDrive zapisany."
            } else {
                "Folder zapisany. Upewnij się, że pochodzi z sekcji OneDrive."
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun revokeFolder() {
        CloudFolderAccess.clear(this)
        refreshState()
        Toast.makeText(
            this,
            "Dostęp usunięty. Niezapisane nagrania pozostają na telefonie.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refreshState() {
        cancelFolderRefresh()
        val tree = CloudFolderAccess.load(this)
        val hasFolder = tree != null
        val powerManager = getSystemService(PowerManager::class.java)
        screenState.value = SettingsScreenState(
            folderReady = hasFolder,
            folderPath = if (hasFolder) "Odczytywanie nazwy folderu…" else null,
            batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(packageName),
        )
        if (tree == null) return
        val generation = folderGeneration
        val cancellation = CancellationSignal()
        folderCancellation = cancellation
        val app = applicationContext
        folderTask = folderExecutor.submit {
            val path = try {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                app.contentResolver.query(
                    root,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                    cancellation,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.takeIf { it.isNotBlank() } ?: "Wybrany folder OneDrive"
            } catch (_: Exception) {
                "Wybrany folder OneDrive"
            }
            if (!cancellation.isCanceled && !Thread.currentThread().isInterrupted) runOnUiThread {
                if (!uiDestroyed && generation == folderGeneration) {
                    folderTask = null
                    folderCancellation = null
                    screenState.value = screenState.value.copy(folderPath = path)
                }
            }
        }
    }

    private fun cancelFolderRefresh() {
        folderGeneration++
        folderCancellation?.cancel()
        folderCancellation = null
        folderTask?.cancel(true)
        folderTask = null
    }
}

private data class SettingsScreenState(
    val folderReady: Boolean = false,
    val folderPath: String? = null,
    val batteryUnrestricted: Boolean = false,
)

@Composable
private fun SettingsScreen(
    state: SettingsScreenState,
    modifier: Modifier = Modifier,
    onChooseFolder: () -> Unit,
    onRevokeFolder: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            PageHeader(
                title = "Ustawienia",
                subtitle = "Przechowywanie, niezawodność i prywatność.",
            )
        }

        item {
            SettingsStatusCard(state)
        }

        item {
            SectionHeader("Przechowywanie")
        }

        item {
            SettingsCard {
                SettingsRow(
                    icon = if (state.folderReady) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                    title = "Folder OneDrive",
                    body = state.folderPath ?: "Nie wybrano folderu zapisu",
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onChooseFolder,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null)
                        Text(
                            if (state.folderReady) "Zmień folder" else "Wybierz folder",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (state.folderReady) {
                        OutlinedButton(
                            onClick = onRevokeFolder,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Usuń dostęp")
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Nagrywanie")
        }

        item {
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Mic,
                    title = "Wykrywanie mowy",
                    body = "5 s bufora przed mową · 8 s ciszy kończy klip · WAV 16 kHz mono",
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.CloudDone,
                    title = "Kopia bezpieczeństwa",
                    body = "Podczas dłuższej rozmowy aplikacja regularnie zabezpiecza fragmenty w OneDrive, a po zakończeniu zapisuje pełny plik.",
                )
            }
        }

        item {
            SectionHeader("Praca w tle")
        }

        item {
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.BatteryChargingFull,
                    title = if (state.batteryUnrestricted) "Bateria: bez ograniczeń" else "Bateria: optymalizacja aktywna",
                    body = if (state.batteryUnrestricted) {
                        "Zalecane ustawienie dla stałego nasłuchu."
                    } else {
                        "Android może ograniczać pracę mikrofonu w tle. Wyłącz optymalizację dla większej niezawodności."
                    },
                )
                if (!state.batteryUnrestricted) {
                    FilledTonalButton(
                        onClick = onBatterySettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Wyłącz optymalizację baterii")
                    }
                }
            }
        }

        item {
            SectionHeader("Archiwum")
        }

        item {
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Archive,
                    title = "Automatyczna kompresja",
                    body = "Nagrania starsze niż 30 dni są bezstratnie pakowane do ZIP podczas ładowania telefonu. Oryginał jest usuwany dopiero po zweryfikowaniu archiwum.",
                )
            }
        }

        item {
            SectionHeader("Prywatność")
        }

        item {
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Security,
                    title = "Audio bez usług AI",
                    body = "Aplikacja nie zawiera OpenAI, transkrypcji ani własnego dostępu do internetu. Synchronizację obsługuje systemowy provider OneDrive.",
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "Stały nasłuch",
                    body = "Android wymaga foreground service dla mikrofonu działającego w tle. Force stop lub odebranie uprawnienia zatrzyma usługę.",
                )
            }
        }

        item {
            OutlinedButton(
                onClick = onAppSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                Text("Ustawienia systemowe aplikacji", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SettingsStatusCard(state: SettingsScreenState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.folderReady && state.batteryUnrestricted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (state.folderReady && state.batteryUnrestricted) {
                    "Gotowe do pracy ciągłej"
                } else {
                    "Dokończ konfigurację"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    !state.folderReady -> "Wybierz folder OneDrive, aby aplikacja mogła bezpiecznie zapisywać nagrania."
                    !state.batteryUnrestricted -> "OneDrive jest gotowy. Wyłączenie optymalizacji baterii poprawi działanie always-on."
                    else -> "OneDrive i ustawienia baterii są skonfigurowane."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    body: String,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
    )
}
