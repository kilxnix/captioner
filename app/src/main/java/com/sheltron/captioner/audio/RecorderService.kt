package com.sheltron.captioner.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sheltron.captioner.CaptionerApp
import com.sheltron.captioner.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var transcriber: Transcriber? = null
    private var voskModel: Model? = null

    sealed class ServiceState {
        object Idle : ServiceState()
        object Starting : ServiceState()
        data class Recording(val sessionId: Long, val startedAt: Long) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    data class LiveText(val partial: String, val finals: List<String>) {
        companion object { val Empty = LiveText("", emptyList()) }
    }

    companion object {
        const val ACTION_START = "com.sheltron.captioner.START"
        const val ACTION_STOP = "com.sheltron.captioner.STOP"
        const val CHANNEL_ID = "captioner_recording"
        const val NOTIFICATION_ID = 1001

        private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        private val _live = MutableStateFlow(LiveText.Empty)
        val live: StateFlow<LiveText> = _live.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCapture() {
        if (_state.value is ServiceState.Recording || _state.value is ServiceState.Starting) return

        _state.value = ServiceState.Starting
        startForegroundSafely(buildNotification("Starting..."))

        captureJob = scope.launch {
            try {
                if (ContextCompat.checkSelfPermission(
                        this@RecorderService,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    fail("Microphone permission missing")
                    return@launch
                }

                if (!ModelManager.isReady(this@RecorderService)) {
                    fail("Voice model not downloaded")
                    return@launch
                }

                val modelPath = ModelManager.modelDir(this@RecorderService).absolutePath
                voskModel = Model(modelPath)
                transcriber = Transcriber(voskModel!!)

                val repo = (applicationContext as CaptionerApp).repository
                val startedAt = System.currentTimeMillis()
                val title = defaultTitle(startedAt)
                val sessionId = repo.startSession(startedAt, title)

                _state.value = ServiceState.Recording(sessionId, startedAt)
                _live.value = LiveText.Empty
                startForegroundSafely(buildNotification("Recording"))

                runCapture(sessionId, startedAt)

                val tail = transcriber?.finish()?.trim().orEmpty()
                if (tail.isNotEmpty()) {
                    val offset = System.currentTimeMillis() - startedAt
                    repo.addLine(sessionId, offset, tail)
                }
                repo.endSession(sessionId, System.currentTimeMillis())
            } catch (ce: CancellationException) {
                // Normal stop path — still close the session cleanly
                val current = _state.value
                if (current is ServiceState.Recording) {
                    try {
                        val repo = (applicationContext as CaptionerApp).repository
                        val tail = transcriber?.finish()?.trim().orEmpty()
                        if (tail.isNotEmpty()) {
                            val offset = System.currentTimeMillis() - current.startedAt
                            repo.addLine(current.sessionId, offset, tail)
                        }
                        repo.endSession(current.sessionId, System.currentTimeMillis())
                    } catch (_: Exception) {}
                }
                throw ce
            } catch (t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            } finally {
                transcriber?.close()
                voskModel?.close()
                transcriber = null
                voskModel = null
            }
        }
    }

    private suspend fun runCapture(sessionId: Long, startedAt: Long) = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        @Suppress("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        val shortBuf = ShortArray(minBuf / 2)
        val finals = mutableListOf<String>()
        val repo = (applicationContext as CaptionerApp).repository

        val encoder = runCatching {
            AudioEncoder(AudioPaths.sessionAudio(applicationContext, sessionId), sampleRate)
                .also { it.start() }
        }.getOrNull()

        record.startRecording()
        try {
            while (isActive) {
                val n = record.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                try { encoder?.encodePcm(shortBuf, n) } catch (_: Throwable) { /* transcript keeps going */ }
                when (val r = transcriber!!.accept(shortBuf, n)) {
                    is Transcriber.Result.Partial -> {
                        _live.value = _live.value.copy(partial = r.text)
                    }
                    is Transcriber.Result.Final -> {
                        val text = r.text.trim()
                        if (text.isNotEmpty()) {
                            val offset = System.currentTimeMillis() - startedAt
                            repo.addLine(sessionId, offset, text)
                            finals.add(text)
                            _live.value = LiveText(partial = "", finals = finals.toList())
                        } else {
                            _live.value = _live.value.copy(partial = "")
                        }
                    }
                }
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            try { encoder?.close() } catch (_: Exception) {}
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        _live.value = LiveText.Empty
        _state.value = ServiceState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        _state.value = ServiceState.Error(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Cole's Log recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Microphone capture and live captions"
                setShowBadge(false)
            }
            manager.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, RecorderService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cole's Log")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun defaultTitle(ts: Long): String {
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return fmt.format(Date(ts))
    }
}
