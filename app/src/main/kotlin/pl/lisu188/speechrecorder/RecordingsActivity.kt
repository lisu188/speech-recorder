package pl.lisu188.speechrecorder

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.FileInputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

class RecordingsActivity : Activity() {
    private val recordings = mutableListOf<Recording>()
    private val visible = mutableListOf<Recording>()
    private lateinit var adapter: RecordingAdapter
    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private lateinit var summary: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var search: EditText
    private lateinit var sort: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadRecordings()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(8))
        }
        page.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(textView("Nagrania", 30, Color.WHITE, true), matchWrap())
        summary = textView("0 nagrań", 14, Color.LTGRAY).apply {
            setPadding(0, dp(4), 0, dp(14))
        }
        content.addView(summary, matchWrap())

        search = EditText(this).apply {
            hint = "Szukaj nagrania"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilterAndSort()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(search, matchWrap())

        sort = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@RecordingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                SORT_LABELS,
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    applyFilterAndSort()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        content.addView(sort, matchWrap().apply { topMargin = dp(6) })

        nowPlaying = textView("Nic nie jest odtwarzane", 13, Color.rgb(111, 207, 135)).apply {
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(nowPlaying, matchWrap())

        adapter = RecordingAdapter()
        content.addView(
            ListView(this).apply {
                dividerHeight = dp(1)
                setCacheColorHint(Color.TRANSPARENT)
                adapter = this@RecordingsActivity.adapter
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        page.addView(AppNavigation.create(this, AppNavigation.RECORDINGS), matchWrap())
        return page
    }

    private fun loadRecordings() {
        recordings.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
        )

        try {
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.RELATIVE_PATH}=?",
                arrayOf("Music/SpeechRecorder/"),
                null,
            )?.use { cursor -> readRecordings(cursor, collection) }
        } catch (_: Exception) {
            Toast.makeText(this, "Nie udało się odczytać nagrań", Toast.LENGTH_LONG).show()
        }
        applyFilterAndSort()
    }

    private fun readRecordings(cursor: Cursor, collection: Uri) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
            val duration = cursor.getLong(durationColumn).takeIf { it > 0L } ?: readDuration(uri)
            recordings += Recording(
                uri = uri,
                name = cursor.getString(nameColumn),
                dateAdded = cursor.getLong(dateColumn) * 1000L,
                sizeBytes = cursor.getLong(sizeColumn),
                durationMs = duration,
                waveform = buildWaveform(uri),
            )
        }
    }

    private fun applyFilterAndSort() {
        if (!::adapter.isInitialized || !::search.isInitialized || !::sort.isInitialized) return
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        visible.clear()
        visible += recordings.filter { recording ->
            query.isEmpty() ||
                recording.name.lowercase(Locale.getDefault()).contains(query) ||
                formatDate(recording.dateAdded).lowercase(Locale.getDefault()).contains(query)
        }

        when (sort.selectedItemPosition) {
            1 -> visible.sortBy { it.dateAdded }
            2 -> visible.sortByDescending { it.durationMs }
            3 -> visible.sortByDescending { it.sizeBytes }
            else -> visible.sortByDescending { it.dateAdded }
        }

        val totalBytes = visible.sumOf { it.sizeBytes }
        summary.text = "${visible.size}${if (visible.size == 1) " nagranie" else " nagrań"}  •  ${formatSize(totalBytes)}"
        adapter.notifyDataSetChanged()
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

    private fun buildWaveform(uri: Uri): String {
        val result = StringBuilder()
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length <= 80L) return ""
                FileInputStream(afd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val dataLength = (afd.length - 44L).coerceAtLeast(1L)
                    val buffer = ByteArray(768)
                    repeat(WAVEFORM_BARS) { index ->
                        channel.position(afd.startOffset + 44L + dataLength * index / WAVEFORM_BARS)
                        val read = input.read(buffer)
                        var maxSample = 0
                        var position = 0
                        while (position + 1 < read) {
                            val sample = ((buffer[position].toInt() and 0xff) or (buffer[position + 1].toInt() shl 8)).toShort().toInt()
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
            adapter.notifyDataSetChanged()
            return
        }

        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(this@RecordingsActivity, recording.uri)
                setOnCompletionListener {
                    stopPlayback()
                    adapter.notifyDataSetChanged()
                }
                prepare()
                start()
            }
            playingUri = recording.uri
            nowPlaying.text = "Odtwarzanie: ${formatDate(recording.dateAdded)}"
            adapter.notifyDataSetChanged()
        } catch (_: Exception) {
            stopPlayback()
            Toast.makeText(this, "Nie udało się odtworzyć nagrania", Toast.LENGTH_LONG).show()
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

    private fun confirmDelete(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle("Usunąć nagranie?")
            .setMessage(formatDate(recording.dateAdded))
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń") { _, _ -> deleteRecording(recording) }
            .show()
    }

    private fun deleteRecording(recording: Recording) {
        if (playingUri == recording.uri) stopPlayback()
        try {
            if (contentResolver.delete(recording.uri, null, null) > 0) {
                Toast.makeText(this, "Nagranie usunięte", Toast.LENGTH_SHORT).show()
                loadRecordings()
            } else {
                Toast.makeText(this, "Nie udało się usunąć nagrania", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Android nie pozwolił usunąć tego nagrania", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayback() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        playingUri = null
        if (::nowPlaying.isInitialized) nowPlaying.text = "Nic nie jest odtwarzane"
    }

    private fun formatDate(millis: Long) = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
    ).format(Date(millis))

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun formatSize(bytes: Long) = if (bytes < 1024L * 1024L) {
        String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
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

    private inner class RecordingAdapter : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val recording = getItem(position)
            return LinearLayout(this@RecordingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(10), dp(6), dp(10))

                addView(
                    LinearLayout(this@RecordingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        addView(
                            Button(this@RecordingsActivity).apply {
                                text = if (recording.uri == playingUri && player?.isPlaying == true) "Ⅱ" else "▶"
                                minWidth = dp(54)
                                setOnClickListener { togglePlayback(recording) }
                            },
                            LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT),
                        )

                        addView(
                            LinearLayout(this@RecordingsActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(10), 0, 0, 0)
                                addView(textView(formatDate(recording.dateAdded), 16, Color.WHITE, true), matchWrap())
                                addView(
                                    textView(
                                        "${formatDuration(recording.durationMs)}  •  ${formatSize(recording.sizeBytes)}  •  ${recording.name}",
                                        12,
                                        Color.LTGRAY,
                                    ).apply { maxLines = 1 },
                                    matchWrap(),
                                )
                                if (recording.waveform.isNotEmpty()) {
                                    addView(
                                        textView(recording.waveform, 18, Color.rgb(111, 207, 135)).apply {
                                            letterSpacing = 0.02f
                                        },
                                        matchWrap(),
                                    )
                                }
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    },
                    matchWrap(),
                )

                addView(
                    LinearLayout(this@RecordingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        setPadding(dp(58), dp(6), 0, 0)
                        addView(
                            Button(this@RecordingsActivity).apply {
                                text = "UDOSTĘPNIJ"
                                textSize = 11f
                                setOnClickListener { shareRecording(recording) }
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(
                            Button(this@RecordingsActivity).apply {
                                text = "USUŃ"
                                textSize = 11f
                                setOnClickListener { confirmDelete(recording) }
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                leftMargin = dp(6)
                            },
                        )
                    },
                    matchWrap(),
                )
            }
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

    private companion object {
        val SORT_LABELS = listOf("Najnowsze", "Najstarsze", "Najdłuższe", "Największe")
        val BARS = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
        const val WAVEFORM_BARS = 28
    }
}
