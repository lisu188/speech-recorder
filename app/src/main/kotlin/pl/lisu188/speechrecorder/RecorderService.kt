package pl.lisu188.speechrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class RecorderService : Service() {
    private val running = AtomicBoolean(false)
    private val serviceToken = Any()
    private var worker: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var lastLevelBroadcastMs = 0L
    private var lastSpeechTimestamp = 0L
    @Volatile private var destroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lastSpeechTimestamp = prefs().getLong("last_speech", 0L)
        RecordingStorage.recover(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            activeService.compareAndSet(serviceToken, null)
            prefs().edit()
                .putBoolean("enabled", false)
                .putBoolean("speech_active", false)
                .apply()
            sendLevelBroadcast(0, false)
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action != ACTION_START) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopWithError("Brak dostępu do mikrofonu. Nadaj uprawnienie i rozpocznij nagrywanie ponownie.")
            return START_NOT_STICKY
        }
        try {
            startAsForeground(prefs().getBoolean("speech_active", false))
        } catch (_: SecurityException) {
            stopWithError("Android zablokował mikrofon. Otwórz aplikację i rozpocznij nagrywanie ponownie.")
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            stopWithError("Android zablokował start w tle. Otwórz aplikację i rozpocznij nagrywanie ponownie.")
            return START_NOT_STICKY
        }
        activeService.set(serviceToken)
        prefs().edit().putBoolean("enabled", true).remove("capture_error").apply()
        if (!running.get()) startCapture()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        activeService.compareAndSet(serviceToken, null)
        prefs().edit().putBoolean("speech_active", false).apply()
        sendLevelBroadcast(0, false)
        stopCapture()
        super.onDestroy()
    }

    private fun startAsForeground(speechActive: Boolean) {
        val notification = buildNotification(speechActive)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(speechActive: Boolean) {
        val active = running.get() && prefs().getBoolean("enabled", false) && !destroyed
        prefs().edit().putBoolean("speech_active", speechActive && active).apply()
        val manager = getSystemService(NotificationManager::class.java)
        if (active) manager.notify(NOTIFICATION_ID, buildNotification(speechActive))
    }

    private fun stopWithError(message: String) {
        activeService.compareAndSet(serviceToken, null)
        prefs().edit().putBoolean("enabled", false).putBoolean("speech_active", false)
            .putString("capture_error", message).apply()
        stopCapture()
        sendLevelBroadcast(0, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(speechActive: Boolean): Notification {
        val openPending = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            2,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("Dyktafon")
            .setContentText(if (speechActive) "Wykryto mowę — zapisuję" else "Nasłuchuję — czekam na mowę")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .addAction(0, "ZATRZYMAJ", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Nagrywanie mowy",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Informuje o aktywnym nasłuchiwaniu mikrofonu"
            },
        )
    }

    private fun startCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopWithError("Brak dostępu do mikrofonu. Nadaj uprawnienie i spróbuj ponownie.")
            return
        }
        if (worker?.isAlive == true || destroyed) return
        running.set(true)
        worker = Thread({
            try {
                captureSupervisorLoop()
            } finally {
                running.set(false)
                val finished = Thread.currentThread()
                mainHandler.post {
                    if (worker === finished) {
                        worker = null
                        if (!destroyed && prefs().getBoolean("enabled", false)) startCapture()
                    }
                }
            }
        }, "speech-recorder-capture").also { it.start() }
    }

    private fun stopCapture() {
        running.set(false)
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
        }
    }

    private fun captureSupervisorLoop() {
        while (running.get() && prefs().getBoolean("enabled", false)) {
            captureLoop()
            if (!running.get() || !prefs().getBoolean("enabled", false)) break
            updateNotification(false)
            sendLevelBroadcast(0, false)
            try {
                Thread.sleep(RESTART_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun captureLoop() {
        val prebuffer = ArrayDeque<ShortArray>(PREBUFFER_FRAMES + 1)
        var sink: WavSink? = null
        var silenceFrames = 0
        var consecutiveSpeechFrames = 0
        var clipFrames = 0
        var noiseFloorDb = -55.0

        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                stopWithError("Brak dostępu do mikrofonu. Nadaj uprawnienie i spróbuj ponownie.")
                return
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = max(minBuffer, FRAME_SAMPLES * 2 * 8)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            audioRecord = record

            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }

            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            prefs().edit().remove("capture_error").apply()
            val frame = ShortArray(FRAME_SAMPLES)

            while (running.get()) {
                val read = readFrame(record, frame)
                if (read < 0) throw IOException("AudioRecord read failed: $read")
                if (read == 0) continue
                if (read < frame.size) frame.fill(0, read, frame.size)

                val stats = analyze(frame, read)
                val adaptiveThreshold = max(-46.0, min(-30.0, noiseFloorDb + 11.0))
                val speech = stats.dbFs > adaptiveThreshold &&
                    stats.zeroCrossingRate > 0.008 &&
                    stats.zeroCrossingRate < 0.48
                val now = System.currentTimeMillis()

                if (now - lastLevelBroadcastMs >= LEVEL_BROADCAST_INTERVAL_MS) {
                    sendLevelBroadcast(mapLevel(stats.dbFs), sink != null)
                    lastLevelBroadcastMs = now
                }

                if (!speech && sink == null && stats.dbFs < noiseFloorDb + 6.0) {
                    noiseFloorDb = (noiseFloorDb * 0.995 + stats.dbFs * 0.005).coerceIn(-72.0, -38.0)
                }

                if (prebuffer.size >= PREBUFFER_FRAMES) prebuffer.removeFirst()
                prebuffer.addLast(frame.clone())

                if (speech) {
                    consecutiveSpeechFrames++
                    silenceFrames = 0
                    if (sink != null && now - lastSpeechTimestamp >= 1000L) rememberSpeech(now)
                } else {
                    consecutiveSpeechFrames = 0
                    if (sink != null) silenceFrames++
                }

                if (sink == null && consecutiveSpeechFrames >= SPEECH_FRAMES_TO_START) {
                    sink = WavSink(RecordingStorage.newFile(this, now - prebuffer.size * FRAME_MS), SAMPLE_RATE)
                    prebuffer.forEach { sink.write(it, it.size) }
                    clipFrames = prebuffer.size
                    rememberSpeech(now)
                    updateNotification(true)
                    sendLevelBroadcast(mapLevel(stats.dbFs), true)
                    continue
                }

                val activeSink = sink ?: continue
                activeSink.write(frame, read)
                clipFrames++
                if (silenceFrames >= SILENCE_FRAMES_TO_STOP || clipFrames >= MAX_CLIP_FRAMES) {
                    val completed = activeSink.closeAndGetFile()
                    sink = null
                    RecordingStorage.enqueue(this, completed)
                    silenceFrames = 0
                    consecutiveSpeechFrames = 0
                    clipFrames = 0
                    prebuffer.clear()
                    updateNotification(false)
                    sendLevelBroadcast(mapLevel(stats.dbFs), false)
                }
            }
        } catch (_: SecurityException) {
            stopWithError("Utracono dostęp do mikrofonu. Nadaj uprawnienie i rozpocznij nagrywanie ponownie.")
        } catch (_: Exception) {
            if (running.get()) {
                Log.w("SpeechRecorder", "Audio capture interrupted; retrying microphone initialization")
                prefs().edit().putString("capture_error", "Przerwano odczyt mikrofonu. Ponawiam połączenie.").apply()
            }
        } finally {
            sink?.let {
                try {
                    RecordingStorage.enqueue(this, it.closeAndGetFile())
                } catch (_: Exception) {
                }
            }
            updateNotification(false)
            sendLevelBroadcast(0, false)
            try {
                noiseSuppressor?.release()
            } catch (_: RuntimeException) {
            }
            noiseSuppressor = null
            audioRecord?.let {
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            audioRecord = null
        }
    }

    private fun rememberSpeech(timestamp: Long) {
        lastSpeechTimestamp = timestamp
        prefs().edit().putLong("last_speech", timestamp).apply()
    }

    private fun sendLevelBroadcast(level: Int, speechActive: Boolean) {
        sendBroadcast(
            Intent(ACTION_LEVEL)
                .setPackage(packageName)
                .putExtra(EXTRA_LEVEL, level)
                .putExtra(EXTRA_SPEECH, speechActive)
                .putExtra(EXTRA_LAST_SPEECH, lastSpeechTimestamp),
        )
    }

    private fun mapLevel(dbFs: Double): Int = (((dbFs + 60.0) / 60.0)
        .coerceIn(0.0, 1.0) * 100.0).roundToInt()

    private fun readFrame(record: AudioRecord, frame: ShortArray): Int {
        var offset = 0
        while (offset < frame.size && running.get()) {
            val read = record.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
            if (read < 0) return read
            if (read == 0) throw IOException("AudioRecord returned no samples")
            offset += read
        }
        return offset
    }

    private fun analyze(samples: ShortArray, length: Int): FrameStats {
        if (length <= 0) return FrameStats(-96.0, 0.0)
        var sumSquares = 0.0
        var zeroCrossings = 0
        var previous = samples[0]

        repeat(length) { index ->
            val sample = samples[index]
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            if (index > 0 && (sample >= 0) != (previous >= 0)) zeroCrossings++
            previous = sample
        }

        val rms = sqrt(sumSquares / length)
        val dbFs = if (rms <= 0.000001) -96.0 else 20.0 * log10(rms)
        return FrameStats(dbFs, zeroCrossings / length.toDouble())
    }

    internal fun publish(wavFile: File, schedule: (Uri) -> Unit = { TranscriptionScheduler.enqueue(this, it); Unit }) {
        RecordingStorage.publish(this, wavFile, schedule)
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private data class FrameStats(val dbFs: Double, val zeroCrossingRate: Double)

    internal class WavSink(
        private val file: File,
        private val sampleRate: Int,
    ) {
        private val output = RandomAccessFile(file, "rw")
        private val lock = output.channel.lock()
        private var pcmBytes = 0L
        private var closed = false

        init {
            try {
                writeHeader(0)
            } catch (error: Exception) {
                output.close()
                throw error
            }
        }

        fun write(samples: ShortArray, length: Int) {
            if (closed) return
            val bytes = ByteArray(length * 2)
            repeat(length) { index ->
                val value = samples[index].toInt()
                bytes[index * 2] = (value and 0xff).toByte()
                bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
            }
            output.write(bytes)
            pcmBytes += bytes.size
        }

        fun closeAndGetFile(): File {
            if (!closed) {
                try {
                    output.seek(0)
                    writeHeader(pcmBytes)
                    output.fd.sync()
                } finally {
                    closed = true
                    output.close()
                }
            }
            return file
        }

        private fun writeHeader(dataSize: Long) {
            output.writeBytes("RIFF")
            writeLeInt(36 + dataSize)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            writeLeInt(16)
            writeLeShort(1)
            writeLeShort(1)
            writeLeInt(sampleRate.toLong())
            writeLeInt(sampleRate * 2L)
            writeLeShort(2)
            writeLeShort(16)
            output.writeBytes("data")
            writeLeInt(dataSize)
        }

        private fun writeLeShort(value: Long) {
            output.write((value and 0xff).toInt())
            output.write((value ushr 8 and 0xff).toInt())
        }

        private fun writeLeInt(value: Long) {
            output.write((value and 0xff).toInt())
            output.write((value ushr 8 and 0xff).toInt())
            output.write((value ushr 16 and 0xff).toInt())
            output.write((value ushr 24 and 0xff).toInt())
        }
    }

    companion object {
        private val activeService = AtomicReference<Any?>()
        val isRunning: Boolean get() = activeService.get() != null
        const val ACTION_START = "pl.lisu188.speechrecorder.START"
        const val ACTION_STOP = "pl.lisu188.speechrecorder.STOP"
        const val ACTION_LEVEL = "pl.lisu188.speechrecorder.LEVEL"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_SPEECH = "speech"
        const val EXTRA_LAST_SPEECH = "last_speech"

        private const val PREFS = "recorder"
        private const val CHANNEL_ID = "speech_recorder"
        private const val NOTIFICATION_ID = 41
        private const val SAMPLE_RATE = 16000
        private const val FRAME_MS = 20
        private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        private const val PREBUFFER_FRAMES = 5000 / FRAME_MS
        private const val SILENCE_FRAMES_TO_STOP = 8000 / FRAME_MS
        private const val SPEECH_FRAMES_TO_START = 4
        private const val MAX_CLIP_FRAMES = 30 * 60 * 1000 / FRAME_MS
        private const val RESTART_DELAY_MS = 2000L
        private const val LEVEL_BROADCAST_INTERVAL_MS = 200L
    }
}
