package pl.lisu188.speechrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingsActivity extends Activity {
    private static final String[] SORT_LABELS = {"Najnowsze", "Najstarsze", "Najdłuższe", "Największe"};
    private static final char[] BARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private final List<Recording> recordings = new ArrayList<>();
    private final List<Recording> visible = new ArrayList<>();
    private RecordingAdapter adapter;
    private MediaPlayer player;
    private Uri playingUri;
    private TextView summary;
    private TextView nowPlaying;
    private EditText search;
    private Spinner sort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadRecordings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecordings();
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(18, 18, 18));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(8));
        page.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = text("Nagrania", 30, Color.WHITE, true);
        content.addView(title, matchWrap());

        summary = text("0 nagrań", 14, Color.LTGRAY, false);
        summary.setPadding(0, dp(4), 0, dp(14));
        content.addView(summary, matchWrap());

        search = new EditText(this);
        search.setHint("Szukaj nagrania");
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.GRAY);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilterAndSort(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        content.addView(search, matchWrap());

        sort = new Spinner(this);
        ArrayAdapter<String> sortAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SORT_LABELS);
        sort.setAdapter(sortAdapter);
        sort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { applyFilterAndSort(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        LinearLayout.LayoutParams sortParams = matchWrap();
        sortParams.topMargin = dp(6);
        content.addView(sort, sortParams);

        nowPlaying = text("Nic nie jest odtwarzane", 13, Color.rgb(111, 207, 135), false);
        nowPlaying.setPadding(0, dp(8), 0, dp(8));
        content.addView(nowPlaying, matchWrap());

        ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new RecordingAdapter();
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        page.addView(AppNavigation.create(this, AppNavigation.RECORDINGS), matchWrap());
        return page;
    }

    private void loadRecordings() {
        recordings.clear();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        String[] args = {"Music/SpeechRecorder/"};

        try (Cursor cursor = getContentResolver().query(collection, projection, selection, args, null)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                while (cursor.moveToNext()) {
                    Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn));
                    long duration = cursor.getLong(durationColumn);
                    if (duration <= 0) {
                        duration = readDuration(uri);
                    }
                    recordings.add(new Recording(
                            uri,
                            cursor.getString(nameColumn),
                            cursor.getLong(dateColumn) * 1000L,
                            cursor.getLong(sizeColumn),
                            duration,
                            buildWaveform(uri)
                    ));
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Nie udało się odczytać nagrań", Toast.LENGTH_LONG).show();
        }
        applyFilterAndSort();
    }

    private void applyFilterAndSort() {
        if (adapter == null || search == null || sort == null) {
            return;
        }
        String query = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        visible.clear();
        for (Recording recording : recordings) {
            if (query.isEmpty() || recording.name.toLowerCase(Locale.getDefault()).contains(query) || formatDate(recording.dateAdded).toLowerCase(Locale.getDefault()).contains(query)) {
                visible.add(recording);
            }
        }

        int selected = sort.getSelectedItemPosition();
        Comparator<Recording> comparator;
        if (selected == 1) {
            comparator = Comparator.comparingLong(r -> r.dateAdded);
        } else if (selected == 2) {
            comparator = (a, b) -> Long.compare(b.durationMs, a.durationMs);
        } else if (selected == 3) {
            comparator = (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes);
        } else {
            comparator = (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
        }
        Collections.sort(visible, comparator);

        long bytes = 0;
        for (Recording recording : visible) {
            bytes += recording.sizeBytes;
        }
        summary.setText(visible.size() + (visible.size() == 1 ? " nagranie" : " nagrań") + "  •  " + formatSize(bytes));
        adapter.notifyDataSetChanged();
    }

    private long readDuration(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private String buildWaveform(Uri uri) {
        StringBuilder result = new StringBuilder();
        try (AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
             FileInputStream in = afd == null ? null : new FileInputStream(afd.getFileDescriptor())) {
            if (afd == null || in == null || afd.getLength() <= 80) {
                return "";
            }
            FileChannel channel = in.getChannel();
            long dataLength = Math.max(1, afd.getLength() - 44);
            byte[] buffer = new byte[768];
            int bars = 28;
            for (int i = 0; i < bars; i++) {
                long position = afd.getStartOffset() + 44 + dataLength * i / bars;
                channel.position(position);
                int read = in.read(buffer);
                int max = 0;
                for (int p = 0; p + 1 < read; p += 2) {
                    int sample = (short) ((buffer[p] & 0xff) | (buffer[p + 1] << 8));
                    max = Math.max(max, Math.abs(sample));
                }
                int level = Math.max(0, Math.min(7, (int) Math.floor(Math.sqrt(max / 32767.0) * 8)));
                result.append(BARS[level]);
            }
        } catch (Exception ignored) {
            return "";
        }
        return result.toString();
    }

    private void togglePlayback(Recording recording) {
        if (playingUri != null && playingUri.equals(recording.uri) && player != null && player.isPlaying()) {
            stopPlayback();
            adapter.notifyDataSetChanged();
            return;
        }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, recording.uri);
            player.setOnCompletionListener(mp -> {
                stopPlayback();
                adapter.notifyDataSetChanged();
            });
            player.prepare();
            player.start();
            playingUri = recording.uri;
            nowPlaying.setText("Odtwarzanie: " + formatDate(recording.dateAdded));
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            stopPlayback();
            Toast.makeText(this, "Nie udało się odtworzyć nagrania", Toast.LENGTH_LONG).show();
        }
    }

    private void shareRecording(Recording recording) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("audio/wav");
        share.putExtra(Intent.EXTRA_STREAM, recording.uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Udostępnij nagranie"));
    }

    private void confirmDelete(Recording recording) {
        new AlertDialog.Builder(this)
                .setTitle("Usunąć nagranie?")
                .setMessage(formatDate(recording.dateAdded))
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Usuń", (dialog, which) -> deleteRecording(recording))
                .show();
    }

    private void deleteRecording(Recording recording) {
        if (recording.uri.equals(playingUri)) {
            stopPlayback();
        }
        try {
            int deleted = getContentResolver().delete(recording.uri, null, null);
            if (deleted > 0) {
                Toast.makeText(this, "Nagranie usunięte", Toast.LENGTH_SHORT).show();
                loadRecordings();
            } else {
                Toast.makeText(this, "Nie udało się usunąć nagrania", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Android nie pozwolił usunąć tego nagrania", Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        playingUri = null;
        if (nowPlaying != null) {
            nowPlaying.setText("Nic nie jest odtwarzane");
        }
    }

    private String formatDate(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(millis));
    }

    private String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000L);
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class RecordingAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Recording getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Recording recording = getItem(position);
            LinearLayout row = new LinearLayout(RecordingsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(6), dp(10), dp(6), dp(10));

            LinearLayout top = new LinearLayout(RecordingsActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            Button play = new Button(RecordingsActivity.this);
            boolean playing = recording.uri.equals(playingUri) && player != null && player.isPlaying();
            play.setText(playing ? "Ⅱ" : "▶");
            play.setMinWidth(dp(54));
            play.setOnClickListener(v -> togglePlayback(recording));
            top.addView(play, new LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout info = new LinearLayout(RecordingsActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(10), 0, 0, 0);

            TextView date = text(formatDate(recording.dateAdded), 16, Color.WHITE, true);
            info.addView(date, matchWrap());

            TextView meta = text(formatDuration(recording.durationMs) + "  •  " + formatSize(recording.sizeBytes) + "  •  " + recording.name, 12, Color.LTGRAY, false);
            meta.setMaxLines(1);
            info.addView(meta, matchWrap());

            if (!recording.waveform.isEmpty()) {
                TextView waveform = text(recording.waveform, 18, Color.rgb(111, 207, 135), false);
                waveform.setLetterSpacing(0.02f);
                info.addView(waveform, matchWrap());
            }
            top.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(top, matchWrap());

            LinearLayout actions = new LinearLayout(RecordingsActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END);
            actions.setPadding(dp(58), dp(6), 0, 0);

            Button share = new Button(RecordingsActivity.this);
            share.setText("UDOSTĘPNIJ");
            share.setTextSize(11);
            share.setOnClickListener(v -> shareRecording(recording));
            actions.addView(share, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button delete = new Button(RecordingsActivity.this);
            delete.setText("USUŃ");
            delete.setTextSize(11);
            delete.setOnClickListener(v -> confirmDelete(recording));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            deleteParams.leftMargin = dp(6);
            actions.addView(delete, deleteParams);
            row.addView(actions, matchWrap());

            return row;
        }
    }

    private static final class Recording {
        final Uri uri;
        final String name;
        final long dateAdded;
        final long sizeBytes;
        final long durationMs;
        final String waveform;

        Recording(Uri uri, String name, long dateAdded, long sizeBytes, long durationMs, String waveform) {
            this.uri = uri;
            this.name = name;
            this.dateAdded = dateAdded;
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
            this.waveform = waveform;
        }
    }
}
