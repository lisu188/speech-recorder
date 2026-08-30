package pl.lisu188.speechrecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecorderService extends Service {
    public static final String ACTION_START = "pl.lisu188.speechrecorder.START";
    public static final String ACTION_STOP = "pl.lisu188.speechrecorder.STOP";
    public static final String ACTION_LEVEL = "pl.lisu188.speechrecorder.LEVEL";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_SPEECH = "speech";
    public static final String EXTRA_LAST_SPEECH = "last_speech";

    private static final String CHANNEL_ID = "speech_recorder";
    private static final int NOTIFICATION_ID = 41;
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_MS = 20;
    private static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;
    private static final int PREBUFFER_FRAMES = 5000 / FRAME_MS;
    private static final int SILENCE_FRAMES_TO_STOP = 8000 / FRAME_MS;
    private static final int SPEECH_FRAMES_TO_START = 4;
    private static final int MAX_CLIP_FRAMES = 30 * 60 * 1000 / FRAME_MS;
    private static final long RESTART_DELAY_MS = 2000L;
    private static final long LEVEL_BROADCAST_INTERVAL_MS = 200L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private AudioRecord audioRecord;
    private NoiseSuppressor noiseSuppressor;
    private long lastLevelBroadcastMs;
    private long lastSpeechTimestamp;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        lastSpeechTimestamp = getSharedPreferences("recorder", MODE_PRIVATE).getLong("last_speech", 0L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            getSharedPreferences("recorder", MODE_PRIVATE).edit()
                    .putBoolean("enabled", false)
                    .putBoolean("speech_active", false)
                    .apply();
            sendLevelBroadcast(0, false);
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        startAsForeground(false);
        if (!running.get()) {
            startCapture();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("speech_active", false).apply();
        sendLevelBroadcast(0, false);
        stopCapture();
        super.onDestroy();
    }

    private void startAsForeground(boolean speechActive) {
        Notification notification = buildNotification(speechActive);
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(boolean speechActive) {
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("speech_active", speechActive).apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(speechActive));
    }

    private Notification buildNotification(boolean speechActive) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_notification)
                .setContentTitle("Dyktafon")
                .setContentText(speechActive ? "Wykryto mowę — zapisuję" : "Nasłuchuję — czekam na mowę")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(new Notification.Action.Builder(null, "ZATRZYMAJ", stopPending).build())
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Nagrywanie mowy", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Informuje o aktywnym nasłuchiwaniu mikrofonu");
        manager.createNotificationChannel(channel);
    }

    private void startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            getSharedPreferences("recorder", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
            return;
        }
        running.set(true);
        worker = new Thread(this::captureSupervisorLoop, "speech-recorder-capture");
        worker.start();
    }

    private void stopCapture() {
        running.set(false);
        AudioRecord local = audioRecord;
        if (local != null) {
            try {
                local.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        Thread localWorker = worker;
        if (localWorker != null && localWorker != Thread.currentThread()) {
            try {
                localWorker.join(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    private void captureSupervisorLoop() {
        while (running.get() && getSharedPreferences("recorder", MODE_PRIVATE).getBoolean("enabled", false)) {
            captureLoop();
            if (!running.get() || !getSharedPreferences("recorder", MODE_PRIVATE).getBoolean("enabled", false)) {
                break;
            }
            updateNotification(false);
            sendLevelBroadcast(0, false);
            try {
                Thread.sleep(RESTART_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void captureLoop() {
        Deque<short[]> prebuffer = new ArrayDeque<>(PREBUFFER_FRAMES + 1);
        WavSink sink = null;
        int silenceFrames = 0;
        int consecutiveSpeechFrames = 0;
        int clipFrames = 0;
        double noiseFloorDb = -55.0;

        try {
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferBytes = Math.max(minBuffer, FRAME_SAMPLES * 2 * 8);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord initialization failed");
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }

            audioRecord.startRecording();
            short[] frame = new short[FRAME_SAMPLES];

            while (running.get()) {
                int read = readFrame(audioRecord, frame);
                if (read <= 0) {
                    continue;
                }
                if (read < frame.length) {
                    for (int i = read; i < frame.length; i++) {
                        frame[i] = 0;
                    }
                }

                FrameStats stats = analyze(frame, read);
                double adaptiveThreshold = Math.max(-46.0, Math.min(-30.0, noiseFloorDb + 11.0));
                boolean speech = stats.dbFs > adaptiveThreshold && stats.zeroCrossingRate > 0.008 && stats.zeroCrossingRate < 0.48;
                long now = System.currentTimeMillis();

                if (now - lastLevelBroadcastMs >= LEVEL_BROADCAST_INTERVAL_MS) {
                    sendLevelBroadcast(mapLevel(stats.dbFs), sink != null);
                    lastLevelBroadcastMs = now;
                }

                if (!speech && sink == null && stats.dbFs < noiseFloorDb + 6.0) {
                    noiseFloorDb = noiseFloorDb * 0.995 + stats.dbFs * 0.005;
                    noiseFloorDb = Math.max(-72.0, Math.min(-38.0, noiseFloorDb));
                }

                short[] snapshot = frame.clone();
                if (prebuffer.size() >= PREBUFFER_FRAMES) {
                    prebuffer.removeFirst();
                }
                prebuffer.addLast(snapshot);

                if (speech) {
                    consecutiveSpeechFrames++;
                    silenceFrames = 0;
                    if (sink != null && now - lastSpeechTimestamp >= 1000L) {
                        rememberSpeech(now);
                    }
                } else {
                    consecutiveSpeechFrames = 0;
                    if (sink != null) {
                        silenceFrames++;
                    }
                }

                if (sink == null && consecutiveSpeechFrames >= SPEECH_FRAMES_TO_START) {
                    sink = new WavSink(new File(getCacheDir(), "speech_" + System.nanoTime() + ".wav"), SAMPLE_RATE);
                    for (short[] bufferedFrame : prebuffer) {
                        sink.write(bufferedFrame, bufferedFrame.length);
                    }
                    clipFrames = prebuffer.size();
                    rememberSpeech(now);
                    updateNotification(true);
                    sendLevelBroadcast(mapLevel(stats.dbFs), true);
                    continue;
                }

                if (sink != null) {
                    sink.write(frame, read);
                    clipFrames++;
                    if (silenceFrames >= SILENCE_FRAMES_TO_STOP || clipFrames >= MAX_CLIP_FRAMES) {
                        File completed = sink.closeAndGetFile();
                        sink = null;
                        publish(completed);
                        silenceFrames = 0;
                        consecutiveSpeechFrames = 0;
                        clipFrames = 0;
                        prebuffer.clear();
                        updateNotification(false);
                        sendLevelBroadcast(mapLevel(stats.dbFs), false);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sink != null) {
                try {
                    File completed = sink.closeAndGetFile();
                    publish(completed);
                } catch (Exception ignored) {
                }
            }
            updateNotification(false);
            sendLevelBroadcast(0, false);
            if (noiseSuppressor != null) {
                noiseSuppressor.release();
                noiseSuppressor = null;
            }
            if (audioRecord != null) {
                try {
                    audioRecord.release();
                } catch (Exception ignored) {
                }
                audioRecord = null;
            }
        }
    }

    private void rememberSpeech(long timestamp) {
        lastSpeechTimestamp = timestamp;
        getSharedPreferences("recorder", MODE_PRIVATE).edit().putLong("last_speech", timestamp).apply();
    }

    private void sendLevelBroadcast(int level, boolean speechActive) {
        Intent intent = new Intent(ACTION_LEVEL).setPackage(getPackageName());
        intent.putExtra(EXTRA_LEVEL, level);
        intent.putExtra(EXTRA_SPEECH, speechActive);
        intent.putExtra(EXTRA_LAST_SPEECH, lastSpeechTimestamp);
        sendBroadcast(intent);
    }

    private int mapLevel(double dbFs) {
        double normalized = (dbFs + 60.0) / 60.0;
        return (int) Math.round(Math.max(0.0, Math.min(1.0, normalized)) * 100.0);
    }

    private int readFrame(AudioRecord record, short[] frame) {
        int offset = 0;
        while (offset < frame.length && running.get()) {
            int read = record.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
            if (read < 0) {
                return read;
            }
            offset += read;
        }
        return offset;
    }

    private FrameStats analyze(short[] samples, int length) {
        if (length <= 0) {
            return new FrameStats(-96.0, 0.0);
        }
        double sumSquares = 0.0;
        int zeroCrossings = 0;
        short previous = samples[0];
        for (int i = 0; i < length; i++) {
            double normalized = samples[i] / 32768.0;
            sumSquares += normalized * normalized;
            if (i > 0 && ((samples[i] >= 0) != (previous >= 0))) {
                zeroCrossings++;
            }
            previous = samples[i];
        }
        double rms = Math.sqrt(sumSquares / length);
        double dbFs = rms <= 0.000001 ? -96.0 : 20.0 * Math.log10(rms);
        double zcr = zeroCrossings / (double) length;
        return new FrameStats(dbFs, zcr);
    }

    private void publish(File wavFile) {
        if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44) {
            if (wavFile != null) {
                wavFile.delete();
            }
            return;
        }

        String fileName = "speech_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SpeechRecorder");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert failed");
            }
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(wavFile));
                 OutputStream rawOut = resolver.openOutputStream(uri);
                 BufferedOutputStream out = rawOut == null ? null : new BufferedOutputStream(rawOut)) {
                if (out == null) {
                    throw new IOException("MediaStore output stream unavailable");
                }
                byte[] buffer = new byte[32768];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            wavFile.delete();
        } catch (Exception e) {
            if (uri != null) {
                resolver.delete(uri, null, null);
            }
            fallbackSave(wavFile, fileName);
        }
    }

    private void fallbackSave(File wavFile, String fileName) {
        File base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) {
            return;
        }
        File dir = new File(base, "SpeechRecorder");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File target = new File(dir, fileName);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(wavFile));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            wavFile.delete();
        } catch (IOException ignored) {
        }
    }

    private static final class FrameStats {
        final double dbFs;
        final double zeroCrossingRate;

        FrameStats(double dbFs, double zeroCrossingRate) {
            this.dbFs = dbFs;
            this.zeroCrossingRate = zeroCrossingRate;
        }
    }

    private static final class WavSink {
        private final File file;
        private final RandomAccessFile output;
        private long pcmBytes;
        private final int sampleRate;
        private boolean closed;

        WavSink(File file, int sampleRate) throws IOException {
            this.file = file;
            this.sampleRate = sampleRate;
            this.output = new RandomAccessFile(file, "rw");
            writeHeader(0);
        }

        void write(short[] samples, int length) throws IOException {
            if (closed) {
                return;
            }
            byte[] bytes = new byte[length * 2];
            for (int i = 0; i < length; i++) {
                short value = samples[i];
                bytes[i * 2] = (byte) (value & 0xff);
                bytes[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
            }
            output.write(bytes);
            pcmBytes += bytes.length;
        }

        File closeAndGetFile() throws IOException {
            if (!closed) {
                output.seek(0);
                writeHeader(pcmBytes);
                output.close();
                closed = true;
            }
            return file;
        }

        private void writeHeader(long dataSize) throws IOException {
            long byteRate = sampleRate * 2L;
            output.writeBytes("RIFF");
            writeLeInt(36 + dataSize);
            output.writeBytes("WAVE");
            output.writeBytes("fmt ");
            writeLeInt(16);
            writeLeShort(1);
            writeLeShort(1);
            writeLeInt(sampleRate);
            writeLeInt(byteRate);
            writeLeShort(2);
            writeLeShort(16);
            output.writeBytes("data");
            writeLeInt(dataSize);
        }

        private void writeLeShort(long value) throws IOException {
            output.write((int) (value & 0xff));
            output.write((int) ((value >>> 8) & 0xff));
        }

        private void writeLeInt(long value) throws IOException {
            output.write((int) (value & 0xff));
            output.write((int) ((value >>> 8) & 0xff));
            output.write((int) ((value >>> 16) & 0xff));
            output.write((int) ((value >>> 24) & 0xff));
        }
    }
}
