package com.sheltron.captioner.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheltron.captioner.CaptionerApp
import com.sheltron.captioner.api.TaskExtractor
import com.sheltron.captioner.audio.ModelManager
import com.sheltron.captioner.audio.RecorderService
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

    sealed class ModelState {
        object Unknown : ModelState()
        object Ready : ModelState()
        data class Downloading(val percent: Int) : ModelState()
        object Extracting : ModelState()
        data class Failed(val message: String) : ModelState()
    }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unknown)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    sealed class ExtractionState {
        object Idle : ExtractionState()
        object Running : ExtractionState()
        data class Done(val count: Int) : ExtractionState()
        data class Failed(val message: String) : ExtractionState()
    }

    private val _extractionState = MutableStateFlow<ExtractionState>(ExtractionState.Idle)
    val extractionState: StateFlow<ExtractionState> = _extractionState.asStateFlow()

    init {
        refreshModelState()
    }

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
        val key = settings.apiKey
        if (key.isNullOrBlank()) {
            _extractionState.value = ExtractionState.Failed("Set your API key in Settings first.")
            return
        }
        _extractionState.value = ExtractionState.Running
        viewModelScope.launch {
            val lines = repo.linesForOnce(sessionId)
            if (lines.isEmpty()) {
                _extractionState.value = ExtractionState.Failed("Transcript is empty.")
                return@launch
            }
            when (val r = TaskExtractor.extract(lines, sessionId, key, settings.model.id)) {
                is TaskExtractor.Result.Ok -> {
                    if (r.tasks.isNotEmpty()) repo.insertTasks(r.tasks)
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
}
