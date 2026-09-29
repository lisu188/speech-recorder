package pl.lisu188.speechrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

class RecordingsActivity : ComponentActivity() {
    private val screenState = mutableStateOf(RecordingsScreenState())
    private var recordings = emptyList<Recording>()
    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private val loadExecutor = Executors.newSingleThreadExecutor()
    private var loadTask: Future<*>? = null
    private var loadGeneration = 0
    private var libraryReceiverRegistered = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = Runnable { if (!isDestroyed && libraryReceiverRegistered) loadRecordings() }
    private val libraryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshHandler.removeCallbacks(refreshTask)
            refreshHandler.postDelayed(refreshTask, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpeechRecorderTheme {
                AppScaffold(this, AppDestination.RECORDINGS) { padding ->
                    RecordingsScreen(
                        state = screenState.value,
                        modifier = Modifier.padding(padding),
                        onRefresh = { loadRecordings() },
                        onQueryChange = { updateFilter(query = it) },
                        onSortChange = { updateFilter(sortIndex = it) },
                        onPlay = { togglePlayback(it) },
                        onShare = { shareRecording(it) },
                        onDeleteRequest = {
                            screenState.value = screenState.value.copy(pendingDelete = it)
                        },
                        onDeleteDismiss = {
                            screenState.value = screenState.value.copy(pendingDelete = null)
                        },
                        onDeleteConfirm = {
                            screenState.value.pendingDelete?.let { recording ->
                                screenState.value = screenState.value.copy(pendingDelete = null)
                                deleteRecording(recording)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!libraryReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                libraryReceiver,
                IntentFilter(RecordingStorage.ACTION_LIBRARY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            libraryReceiverRegistered = true
        }
        loadRecordings()
    }

    override fun onPause() {
        if (libraryReceiverRegistered) {
            unregisterReceiver(libraryReceiver)
            libraryReceiverRegistered = false
        }
        refreshHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onDestroy() {
        refreshHandler.removeCallbacks(refreshTask)
        loadGeneration++
        loadTask?.cancel(true)
        loadExecutor.shutdownNow()
        stopPlayback(updateUi = false)
        super.onDestroy()
    }

    private fun loadRecordings() {
        val generation = ++loadGeneration
        loadTask?.cancel(true)
        if (!CloudFolderAccess.hasAccess(this)) {
            recordings = emptyList()
            screenState.value = screenState.value.copy(
                visible = emptyList(),
                loading = false,
                cloudReady = false,
                error = null,
            )
            return
        }

        screenState.value = screenState.value.copy(loading = true, cloudReady = true, error = null)
        loadTask = loadExecutor.submit {
            try {
                val loaded = RecordingStorage.listPublished(this).map { stored ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    Recording(
                        uri = stored.uri,
                        name = stored.name,
                        dateAdded = RecordingStorage.recordingTimestamp(stored.name, stored.lastModifiedMs),
                        sizeBytes = stored.sizeBytes,
                        durationMs = readDuration(stored.uri),
                        waveform = buildWaveform(stored.uri, stored.sizeBytes),
                    )
                }
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed) {
                        recordings = loaded
                        updateFilter(loading = false, error = null)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed) {
                        updateFilter(
                            loading = false,
                            error = "Nie udało się odczytać OneDrive. Sprawdź połączenie i dostęp do folderu.",
                        )
                    }
                }
            }
        }
    }

    private fun updateFilter(
        query: String? = null,
        sortIndex: Int? = null,
        loading: Boolean? = null,
        error: String? = screenState.value.error,
    ) {
        val previous = screenState.value
        val resolvedQuery = query ?: previous.query
        val resolvedSort = sortIndex ?: previous.sortIndex
        val normalized = resolvedQuery.trim().lowercase(Locale.getDefault())

        val visible = recordings.filter { recording ->
            normalized.isEmpty() ||
                recording.name.lowercase(Locale.getDefault()).contains(normalized) ||
                displayTitle(recording).lowercase(Locale.getDefault()).contains(normalized) ||
                formatDate(recording.dateAdded).lowercase(Locale.getDefault()).contains(normalized)
        }.toMutableList()

        when (resolvedSort) {
            1 -> visible.sortBy { it.dateAdded }
            2 -> visible.sortByDescending { it.durationMs }
            3 -> visible.sortByDescending { it.sizeBytes }
            else -> visible.sortByDescending { it.dateAdded }
        }

        screenState.value = previous.copy(
            visible = visible,
            query = resolvedQuery,
            sortIndex = resolvedSort,
            loading = loading ?: previous.loading,
            cloudReady = true,
            playingUri = playingUri,
            error = error,
        )
    }

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun buildWaveform(uri: Uri, knownSize: Long): String {
        val result = StringBuilder()
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val totalLength = afd.length.takeIf { it > 44L } ?: knownSize
                if (totalLength <= 80L) return ""
                FileInputStream(afd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val dataLength = (totalLength - 44L).coerceAtLeast(1L)
                    val buffer = ByteArray(768)
                    repeat(WAVEFORM_BARS) { index ->
                        channel.position(afd.startOffset + 44L + dataLength * index / WAVEFORM_BARS)
                        val read = input.read(buffer)
                        if (read <= 0) return@repeat
                        var maxSample = 0
                        var position = 0
                        while (position + 1 < read) {
                            val sample = (
                                (buffer[position].toInt() and 0xff) or
                                    (buffer[position + 1].toInt() shl 8)
                                ).toShort().toInt()
                            maxSample = maxOf(maxSample, abs(sample))
                            position += 2
                        }
                        val level = floor(sqrt(maxSample / 32767.0) * BARS.size)
                            .toInt()
                            .coerceIn(0, BARS.lastIndex)
                        result.append(BARS[level])
                    }
                }
            } ?: return ""
            result.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun togglePlayback(recording: Recording) {
        if (playingUri == recording.uri && player?.isPlaying == true) {
            stopPlayback()
            return
        }

        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(this@RecordingsActivity, recording.uri)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playingUri = recording.uri
            screenState.value = screenState.value.copy(playingUri = playingUri)
        } catch (_: Exception) {
            stopPlayback()
            Toast.makeText(this, "Nie udało się odtworzyć nagrania z OneDrive", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareRecording(recording: Recording) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_STREAM, recording.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Udostępnij nagranie",
            ),
        )
    }

    private fun deleteRecording(recording: Recording) {
        if (playingUri == recording.uri) stopPlayback()
        if (RecordingStorage.deletePublished(this, recording.uri, recording.name)) {
            Toast.makeText(this, "Nagranie usunięte z OneDrive", Toast.LENGTH_SHORT).show()
            loadRecordings()
        } else {
            Toast.makeText(this, "Nie udało się usunąć nagrania z OneDrive", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayback(updateUi: Boolean = true) {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        playingUri = null
        if (updateUi && !isDestroyed) {
            screenState.value = screenState.value.copy(playingUri = null)
        }
    }

    companion object {
        val SORT_LABELS = listOf("Najnowsze", "Najstarsze", "Najdłuższe", "Największe")
        val BARS = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
        const val WAVEFORM_BARS = 28
    }
}

private data class Recording(
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val waveform: String,
)

private data class RecordingsScreenState(
    val visible: List<Recording> = emptyList(),
    val query: String = "",
    val sortIndex: Int = 0,
    val loading: Boolean = false,
    val cloudReady: Boolean = true,
    val playingUri: Uri? = null,
    val pendingDelete: Recording? = null,
    val error: String? = null,
)

@Composable
private fun RecordingsScreen(
    state: RecordingsScreenState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (Int) -> Unit,
    onPlay: (Recording) -> Unit,
    onShare: (Recording) -> Unit,
    onDeleteRequest: (Recording) -> Unit,
    onDeleteDismiss: () -> Unit,
    onDeleteConfirm: () -> Unit,
) {
    state.pendingDelete?.let { recording ->
        AlertDialog(
            onDismissRequest = onDeleteDismiss,
            title = { Text("Usunąć nagranie?") },
            text = { Text(displayTitle(recording)) },
            confirmButton = {
                TextButton(onClick = onDeleteConfirm) {
                    Text("Usuń", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteDismiss) {
                    Text("Anuluj")
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageHeader(
                    title = "Nagrania",
                    subtitle = if (state.cloudReady) {
                        recordingSummary(state.visible)
                    } else {
                        "Wybierz folder OneDrive w Ustawieniach."
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !state.loading,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Odśwież OneDrive")
                    }
                }
            }
        }

        if (!state.cloudReady) {
            item {
                EmptyRecordingsCard(
                    icon = Icons.Outlined.CloudOff,
                    title = "Brak folderu OneDrive",
                    body = "Skonfiguruj folder zapisu w Ustawieniach, aby zobaczyć nagrania.",
                )
            }
        } else {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Szukaj nagrań") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "Wyczyść wyszukiwanie")
                            }
                        }
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecordingsActivity.SORT_LABELS.forEachIndexed { index, label ->
                        FilterChip(
                            selected = state.sortIndex == index,
                            onClick = { onSortChange(index) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            state.error?.let { message ->
                item {
                    EmptyRecordingsCard(
                        icon = Icons.Outlined.CloudOff,
                        title = "Nie udało się odświeżyć",
                        body = message,
                    )
                }
            }

            if (state.visible.isEmpty() && !state.loading && state.error == null) {
                item {
                    EmptyRecordingsCard(
                        icon = Icons.Outlined.LibraryMusic,
                        title = if (state.query.isBlank()) "Brak nagrań" else "Brak wyników",
                        body = if (state.query.isBlank()) {
                            "Gdy aplikacja wykryje mowę, zapisane klipy pojawią się tutaj."
                        } else {
                            "Zmień wyszukiwaną frazę lub wyczyść filtr."
                        },
                    )
                }
            }

            items(
                items = state.visible,
                key = { it.uri.toString() },
            ) { recording ->
                RecordingCard(
                    recording = recording,
                    playing = state.playingUri == recording.uri,
                    onPlay = { onPlay(recording) },
                    onShare = { onShare(recording) },
                    onDelete = { onDeleteRequest(recording) },
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: Recording,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (playing) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "Zatrzymaj odtwarzanie" else "Odtwórz nagranie",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = displayTitle(recording),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatDate(recording.dateAdded)} · ${formatDuration(recording.durationMs)} · ${formatSize(recording.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = "Udostępnij")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Usuń",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (recording.waveform.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = recording.waveform,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRecordingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun recordingSummary(recordings: List<Recording>): String {
    val count = recordings.size
    val totalBytes = recordings.sumOf { it.sizeBytes }
    val noun = if (count == 1) "nagranie" else "nagrań"
    return "$count $noun · ${formatSize(totalBytes)} · OneDrive"
}

private fun displayTitle(recording: Recording): String {
    val timestamp = timestampFromName(recording.name)
    if (timestamp > 0L) {
        return "Nagranie ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))}"
    }
    return recording.name.substringBeforeLast('.').replace('_', ' ')
}

private fun timestampFromName(name: String): Long {
    val match = Regex("^speech_(\\d{8})_(\\d{6})").find(name) ?: return 0L
    return try {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { isLenient = false }
            .parse("${match.groupValues[1]}_${match.groupValues[2]}")
            ?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

private fun formatDate(millis: Long): String =
    if (millis > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    } else {
        "Brak daty"
    }

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return if (totalSeconds >= 3600L) {
        String.format(
            Locale.getDefault(),
            "%d:%02d:%02d",
            totalSeconds / 3600L,
            (totalSeconds % 3600L) / 60L,
            totalSeconds % 60L,
        )
    } else {
        String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }
}

private fun formatSize(bytes: Long) = if (bytes < 1024L * 1024L) {
    String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
} else {
    String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}
