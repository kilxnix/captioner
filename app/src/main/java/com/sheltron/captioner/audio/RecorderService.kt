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
        /** Post-recording Whisper polish running inside the foreground service. */
        data class Polishing(val sessionId: Long, val phase: String) : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    sealed class PolishUiState {
        object Idle : PolishUiState()
        data class Running(val phase: String) : PolishUiState()
        data class Done(val segments: Int) : PolishUiState()
        data class Failed(val message: String) : PolishUiState()
    }

    data class LiveText(val partial: String, val finals: List<String>) {
        companion object { val Empty = LiveText("", emptyList()) }
    }

    companion object {
        const val ACTION_START = "com.sheltron.captioner.START"
        const val ACTION_STOP = "com.sheltron.captioner.STOP"
        const val ACTION_POLISH = "com.sheltron.captioner.POLISH"
        const val EXTRA_SESSION_ID = "session_id"
        const val CHANNEL_ID = "captioner_recording"
        const val RESULTS_CHANNEL_ID = "captioner_results"
        const val NOTIFICATION_ID = 1001
        const val RESULTS_NOTIFICATION_ID_BASE = 2000

        private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        private val _live = MutableStateFlow(LiveText.Empty)
        val live: StateFlow<LiveText> = _live.asStateFlow()

        /** Shared polish status; updated from the service so UI survives VM lifecycle. */
        private val _polish = MutableStateFlow<PolishUiState>(PolishUiState.Idle)
        val polish: StateFlow<PolishUiState> = _polish.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecorderService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /** Kick off a Whisper polish as a foreground service so it survives backgrounding. */
        fun polish(context: Context, sessionId: Long) {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = ACTION_POLISH
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun clearPolishResult() { _polish.value = PolishUiState.Idle }
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
            ACTION_POLISH -> {
                val sid = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                if (sid > 0) startPolish(sid)
            }
        }
        return START_NOT_STICKY
    }

    private var polishJob: Job? = null

    override fun onDestroy() {
        captureJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCapture() {
        if (_state.value is ServiceState.Recording || _state.value is ServiceState.Starting) return

        _state.value = ServiceState.Starting
        startForegroundSafely(buildNotification("Starting..."))

        val settings = com.sheltron.captioner.settings.SettingsStore(applicationContext)
        val engine = settings.engine
        val soundsOn = settings.recordingSoundsEnabled

        val useOnDevice = engine == com.sheltron.captioner.settings.SettingsStore.Engine.ON_DEVICE &&
                          SpeechRecognizerEngine.isAvailable()

        if (soundsOn) SoundEffects.playStart()

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

                if (useOnDevice) {
                    runOnDeviceCapture()
                } else {
                    runVoskCapture()
                }
            } catch (ce: CancellationException) {
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

    private suspend fun runVoskCapture() {
        if (!ModelManager.isReady(this@RecorderService)) {
            fail("Voice model not downloaded")
            return
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
    }

    private suspend fun runOnDeviceCapture() {
        val repo = (applicationContext as CaptionerApp).repository
        val startedAt = System.currentTimeMillis()
        val title = defaultTitle(startedAt)
        val sessionId = repo.startSession(startedAt, title)

        _state.value = ServiceState.Recording(sessionId, startedAt)
        _live.value = LiveText.Empty
        startForegroundSafely(buildNotification("Recording"))

        val finals = mutableListOf<String>()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = SpeechRecognizerEngine(
            context = applicationContext,
            scope = scope,
            onPartial = { partial ->
                _live.value = _live.value.copy(partial = partial)
            },
            onFinal = { finalText ->
                val text = finalText.trim()
                if (text.isNotEmpty()) {
                    scope.launch {
                        val offset = System.currentTimeMillis() - startedAt
                        repo.addLine(sessionId, offset, text)
                    }
                    finals.add(text)
                    _live.value = LiveText(partial = "", finals = finals.toList())
                }
            },
            onFatal = { msg ->
                // Engine gave up — surface to UI and let stopCapture clean up.
                _state.value = ServiceState.Error(msg)
                done.complete(Unit)
            }
        )
        engine.start()

        try {
            // Suspend until cancelled (stopCapture) or engine fatally fails.
            done.await()
        } finally {
            engine.stop()
            repo.endSession(sessionId, System.currentTimeMillis())
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
        val current = _state.value
        val wasRecording = current is ServiceState.Recording
        val recordedSessionId = (current as? ServiceState.Recording)?.sessionId
        captureJob?.cancel()
        captureJob = null
        _live.value = LiveText.Empty

        val settings = com.sheltron.captioner.settings.SettingsStore(applicationContext)
        if (wasRecording && settings.recordingSoundsEnabled) SoundEffects.playStop()

        // Auto-polish with Whisper inline: same process, still in foreground, survives
        // the user backgrounding the app mid-transcription.
        val shouldAutoPolish = wasRecording &&
            recordedSessionId != null &&
            settings.engine == com.sheltron.captioner.settings.SettingsStore.Engine.VOSK &&
            WhisperModelManager.isReady(applicationContext) &&
            WhisperCpp.isLoadable()

        if (shouldAutoPolish && recordedSessionId != null) {
            // Hand off directly to the polish pipeline without ever going Idle.
            launchPolish(recordedSessionId)
        } else {
            _state.value = ServiceState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startPolish(sessionId: Long) {
        // Ignore a fresh polish request while a recording is still active — the stop
        // flow will chain into polish naturally.
        val s = _state.value
        if (s is ServiceState.Recording || s is ServiceState.Starting) return
        if (s is ServiceState.Polishing) return
        launchPolish(sessionId)
    }

    private fun launchPolish(sessionId: Long) {
        _state.value = ServiceState.Polishing(sessionId, "Decoding audio")
        _polish.value = PolishUiState.Running("Decoding audio")
        startForegroundSafely(buildNotification("Polishing transcript…"))
        polishJob = scope.launch {
            try {
                val count = runPolish(sessionId) { phase ->
                    _state.value = ServiceState.Polishing(sessionId, phase)
                    _polish.value = PolishUiState.Running(phase)
                    startForegroundSafely(buildNotification("Polishing · $phase"))
                }
                _polish.value = PolishUiState.Done(count)
                postPolishDoneNotification(sessionId, count)
            } catch (_: kotlinx.coroutines.CancellationException) {
                _polish.value = PolishUiState.Failed("Polish cancelled")
                throw kotlinx.coroutines.CancellationException()
            } catch (t: Throwable) {
                _polish.value = PolishUiState.Failed(
                    "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
                )
            } finally {
                _state.value = ServiceState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Runs whisper.cpp on the session's saved .m4a and replaces the transcript lines. */
    private suspend fun runPolish(
        sessionId: Long,
        onPhase: (String) -> Unit
    ): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!WhisperModelManager.isReady(applicationContext))
            throw IllegalStateException("Whisper model not downloaded")
        if (!WhisperCpp.isLoadable())
            throw IllegalStateException("whisperjni native lib failed to load")

        val audio = AudioPaths.sessionAudio(applicationContext, sessionId)
        if (!audio.exists() || audio.length() == 0L)
            throw IllegalStateException("No audio file for session $sessionId")

        onPhase("Decoding audio")
        val pcm = AudioDecoder.decodeToFloat16k(audio)
        if (pcm.isEmpty()) throw IllegalStateException("Couldn't decode audio")

        onPhase("Loading Whisper")
        val whisper = WhisperCpp.fromFile(WhisperModelManager.modelFile(applicationContext).absolutePath)
            ?: throw IllegalStateException("Whisper failed to load")

        val segments = try {
            onPhase("Transcribing (slow part)")
            whisper.transcribe(pcm)
        } finally {
            whisper.close()
        }

        onPhase("Replacing transcript")
        val repo = (applicationContext as CaptionerApp).repository
        val newLines = segments
            .filter { it.text.isNotBlank() }
            .map { it.startMs to it.text.trim() }
        repo.replaceLines(sessionId, newLines)
        newLines.size
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
                description = "Microphone capture, live captions, transcript polish progress"
                setShowBadge(false)
            }
            manager.createNotificationChannel(ch)
        }
        if (manager.getNotificationChannel(RESULTS_CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                RESULTS_CHANNEL_ID,
                "Transcript ready",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Fires once when a Whisper polish finishes so you know the session is clean."
                setShowBadge(true)
            }
            manager.createNotificationChannel(ch)
        }
    }

    /** One-shot results notification that opens the finished session on tap. */
    private fun postPolishDoneNotification(sessionId: Long, segments: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_OPEN_SESSION_ID, sessionId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            sessionId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (segments <= 0) "Transcript polished"
                    else "Transcript ready · $segments segment${if (segments == 1) "" else "s"}"
        val notif = NotificationCompat.Builder(this, RESULTS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Tap to open the session.")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(RESULTS_NOTIFICATION_ID_BASE + sessionId.toInt(), notif)
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

    /** Default type matches the current service state — mic for recording, dataSync for polish. */
    private fun startForegroundSafely(
        notification: Notification,
        type: Int = when (_state.value) {
            is ServiceState.Polishing -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun defaultTitle(ts: Long): String {
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        return fmt.format(Date(ts))
    }
}
