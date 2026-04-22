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
import kotlinx.coroutines.flow.map
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

    // Auto-polish is now driven by RecorderService itself after the Vosk capture
    // loop stops — the service stays in the foreground and owns its own scope,
    // so Whisper survives the user backgrounding the app mid-transcription.
    // PolishState in this VM just mirrors the service's shared PolishUiState.

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
        /** HF said 401/403 — user needs to accept the Gemma license and/or provide a token. */
        object NeedsAuth : GemmaState()
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
            GemmaModelManager.download(getApplication(), settings.hfToken).collect { event ->
                _gemmaState.value = when (event) {
                    is GemmaModelManager.Event.Progress -> GemmaState.Downloading(event.percent)
                    GemmaModelManager.Event.Done -> GemmaState.Ready
                    is GemmaModelManager.Event.Failed -> GemmaState.Failed(event.message)
                    GemmaModelManager.Event.NeedsLicenseAcceptance -> GemmaState.NeedsAuth
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

    /** Mirrors the service-owned polish flow so UI doesn't need to know about two sources. */
    val polishState: StateFlow<PolishState> = RecorderService.polish
        .map { s ->
            when (s) {
                RecorderService.PolishUiState.Idle -> PolishState.Idle
                is RecorderService.PolishUiState.Running -> PolishState.Running(s.phase)
                is RecorderService.PolishUiState.Done -> PolishState.Done(s.segments)
                is RecorderService.PolishUiState.Failed -> PolishState.Failed(s.message)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PolishState.Idle)

    fun polishWithWhisper(sessionId: Long) {
        if (polishState.value is PolishState.Running) return
        val audio = repo.audioFileFor(sessionId)
        if (!audio.exists() || audio.length() == 0L) return
        if (!WhisperModelManager.isReady(getApplication())) return
        RecorderService.polish(getApplication(), sessionId)
    }

    fun clearPolishState() { RecorderService.clearPolishResult() }
}
