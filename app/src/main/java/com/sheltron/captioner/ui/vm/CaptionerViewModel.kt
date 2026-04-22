package com.sheltron.captioner.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheltron.captioner.CaptionerApp
import com.sheltron.captioner.api.GemmaModelManager
import com.sheltron.captioner.api.TaskExtractor
import com.sheltron.captioner.audio.AudioDecoder
import com.sheltron.captioner.audio.ModelManager
import com.sheltron.captioner.audio.RecorderService
import com.sheltron.captioner.audio.WhisperCpp
import com.sheltron.captioner.audio.WhisperModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sheltron.captioner.data.db.Line
import com.sheltron.captioner.data.db.Session
import com.sheltron.captioner.data.db.Task
import com.sheltron.captioner.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CaptionerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as CaptionerApp).repository
    val settings = SettingsStore(app)

    val sessions: StateFlow<List<Session>> = repo.allSessions()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tasks: StateFlow<List<Task>> = repo.allTasks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val serviceState = RecorderService.state
    val live = RecorderService.live

    /** Page index the HomePager should animate to on next recomposition. Null = no request. */
    private val _pendingPagerPage = MutableStateFlow<Int?>(null)
    val pendingPagerPage: StateFlow<Int?> = _pendingPagerPage.asStateFlow()
    fun requestPagerPage(page: Int) { _pendingPagerPage.value = page }
    fun clearPendingPagerPage() { _pendingPagerPage.value = null }

    sealed class ModelState {
        object Unknown : ModelState()
        object Ready : ModelState()
        data class Downloading(val percent: Int) : ModelState()
        object Extracting : ModelState()
        data class Failed(val message: String) : ModelState()
    }

    private val _modelState = MutableStateFlow<ModelState>(
        if (ModelManager.isReady(app)) ModelState.Ready else ModelState.Unknown
    )
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    sealed class ExtractionState {
        object Idle : ExtractionState()
        data class Running(val phase: String) : ExtractionState()
        data class Done(val count: Int) : ExtractionState()
        data class Failed(val message: String) : ExtractionState()
    }

    private val _extractionState = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val extractionState: StateFlow<ExtractionState> = _extractionState.asStateFlow()

    // No init-time work: _gemmaState and _whisperState are declared further down the file
    // and are still null when a Kotlin init block runs. We compute the initial state
    // directly in the property initializers below instead.

    fun refreshModelState() {
        _modelState.value = if (ModelManager.isReady(getApplication())) ModelState.Ready
        else ModelState.Unknown
    }

    fun downloadModel() {
        if (_modelState.value is ModelState.Downloading || _modelState.value is ModelState.Extracting) return
        viewModelScope.launch {
            ModelManager.download(getApplication()).collect { event ->
                _modelState.value = when (event) {
                    is ModelManager.DownloadEvent.Progress -> ModelState.Downloading(event.percent)
                    ModelManager.DownloadEvent.Extracting -> ModelState.Extracting
                    ModelManager.DownloadEvent.Done -> ModelState.Ready
                    is ModelManager.DownloadEvent.Failed -> ModelState.Failed(event.message)
                }
            }
        }
    }

    fun startRecording() = RecorderService.start(getApplication())
    fun stopRecording() = RecorderService.stop(getApplication())

    fun linesFor(sessionId: Long): Flow<List<Line>> = repo.linesFor(sessionId)
    fun sessionFlow(sessionId: Long): Flow<Session?> = repo.sessionFlow(sessionId)
    fun tasksForSession(sessionId: Long): Flow<List<Task>> = repo.tasksForSession(sessionId)

    fun deleteSession(id: Long) {
        viewModelScope.launch { repo.deleteSession(id) }
    }

    fun setTaskDone(id: Long, done: Boolean) {
        viewModelScope.launch { repo.setTaskDone(id, done) }
    }

    fun deleteTask(id: Long) {
        viewModelScope.launch { repo.deleteTask(id) }
    }

    fun buildTranscriptText(sessionId: Long, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val lines = repo.linesForOnce(sessionId)
            val text = lines.joinToString("\n") { it.text }
            onReady(text)
        }
    }

    fun audioFileFor(sessionId: Long) = repo.audioFileFor(sessionId)

    fun extractTasks(sessionId: Long) {
        if (_extractionState.value is ExtractionState.Running) return
        _extractionState.value = ExtractionState.Running("Reading transcript")
        viewModelScope.launch {
            val lines = repo.linesForOnce(sessionId)
            if (lines.isEmpty()) {
                _extractionState.value = ExtractionState.Failed("Transcript is empty.")
                return@launch
            }
            val phaseUpdater: (String) -> Unit = { phase ->
                _extractionState.value = ExtractionState.Running(phase)
            }
            when (val r = TaskExtractor.extract(getApplication(), lines, sessionId, onPhase = phaseUpdater)) {
                is TaskExtractor.Result.Ok -> {
                    if (r.tasks.isNotEmpty()) {
                        _extractionState.value = ExtractionState.Running("Saving ${r.tasks.size} tasks")
                        repo.insertTasks(r.tasks)
                    }
                    _extractionState.value = ExtractionState.Done(r.tasks.size)
                }
                is TaskExtractor.Result.Failed -> {
                    _extractionState.value = ExtractionState.Failed(r.message)
                }
            }
        }
    }

    fun clearExtractionState() {
        _extractionState.value = ExtractionState.Idle
    }

    sealed class GemmaState {
        object Unknown : GemmaState()
        object Ready : GemmaState()
        data class Downloading(val percent: Int) : GemmaState()
        data class Failed(val message: String) : GemmaState()
    }

    private val _gemmaState = MutableStateFlow<GemmaState>(
        if (GemmaModelManager.isReady(app)) GemmaState.Ready else GemmaState.Unknown
    )
    val gemmaState: StateFlow<GemmaState> = _gemmaState.asStateFlow()

    fun refreshGemmaState() {
        _gemmaState.value = if (GemmaModelManager.isReady(getApplication())) GemmaState.Ready
        else GemmaState.Unknown
    }

    fun downloadGemma() {
        if (_gemmaState.value is GemmaState.Downloading) return
        viewModelScope.launch {
            GemmaModelManager.download(getApplication()).collect { event ->
                _gemmaState.value = when (event) {
                    is GemmaModelManager.Event.Progress -> GemmaState.Downloading(event.percent)
                    GemmaModelManager.Event.Done -> GemmaState.Ready
                    is GemmaModelManager.Event.Failed -> GemmaState.Failed(event.message)
                }
            }
        }
    }

    // ---- Whisper (offline polish) ----

    sealed class WhisperModelState {
        object Unknown : WhisperModelState()
        object Ready : WhisperModelState()
        data class Downloading(val percent: Int) : WhisperModelState()
        data class Failed(val message: String) : WhisperModelState()
    }

    private val _whisperState = MutableStateFlow<WhisperModelState>(
        if (WhisperModelManager.isReady(app)) WhisperModelState.Ready else WhisperModelState.Unknown
    )
    val whisperState: StateFlow<WhisperModelState> = _whisperState.asStateFlow()

    fun refreshWhisperState() {
        _whisperState.value = if (WhisperModelManager.isReady(getApplication())) WhisperModelState.Ready
        else WhisperModelState.Unknown
    }

    fun downloadWhisper() {
        if (_whisperState.value is WhisperModelState.Downloading) return
        viewModelScope.launch {
            WhisperModelManager.download(getApplication()).collect { event ->
                _whisperState.value = when (event) {
                    is WhisperModelManager.Event.Progress -> WhisperModelState.Downloading(event.percent)
                    WhisperModelManager.Event.Done -> WhisperModelState.Ready
                    is WhisperModelManager.Event.Failed -> WhisperModelState.Failed(event.message)
                }
            }
        }
    }

    sealed class PolishState {
        object Idle : PolishState()
        data class Running(val phase: String) : PolishState()
        data class Done(val segments: Int) : PolishState()
        data class Failed(val message: String) : PolishState()
    }

    private val _polishState = MutableStateFlow<PolishState>(PolishState.Idle)
    val polishState: StateFlow<PolishState> = _polishState.asStateFlow()

    fun polishWithWhisper(sessionId: Long) {
        if (_polishState.value is PolishState.Running) return
        val audio = repo.audioFileFor(sessionId)
        if (!audio.exists() || audio.length() == 0L) {
            _polishState.value = PolishState.Failed("No audio file for this session.")
            return
        }
        if (!WhisperModelManager.isReady(getApplication())) {
            _polishState.value = PolishState.Failed("Whisper model not downloaded. Open Settings.")
            return
        }
        if (!WhisperCpp.isLoadable()) {
            _polishState.value = PolishState.Failed("Native whisper library not available: ${WhisperCpp.loadErrorMessage()}")
            return
        }

        _polishState.value = PolishState.Running("Decoding audio")
        viewModelScope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { AudioDecoder.decodeToFloat16k(audio) }
                if (pcm.isEmpty()) {
                    _polishState.value = PolishState.Failed("Couldn't decode audio.")
                    return@launch
                }

                _polishState.value = PolishState.Running("Loading Whisper")
                val modelPath = WhisperModelManager.modelFile(getApplication()).absolutePath
                val whisper = withContext(Dispatchers.Default) { WhisperCpp.fromFile(modelPath) }
                if (whisper == null) {
                    _polishState.value = PolishState.Failed("Whisper failed to load.")
                    return@launch
                }

                _polishState.value = PolishState.Running("Transcribing (this is the slow part)")
                val segments = withContext(Dispatchers.Default) {
                    try { whisper.transcribe(pcm) } finally { whisper.close() }
                }

                _polishState.value = PolishState.Running("Replacing transcript")
                val newLines = segments
                    .filter { it.text.isNotBlank() }
                    .map { it.startMs to it.text.trim() }
                repo.replaceLines(sessionId, newLines)
                _polishState.value = PolishState.Done(newLines.size)
            } catch (t: Throwable) {
                _polishState.value = PolishState.Failed("${t.javaClass.simpleName}: ${t.message ?: ""}")
            }
        }
    }

    fun clearPolishState() { _polishState.value = PolishState.Idle }
}
