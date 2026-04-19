package com.sheltron.captioner.audio

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper over MediaPlayer for per-session playback. Exposes a StateFlow that ticks while
 * playing so the UI can highlight the active line and move the scrub bar. Safe to [prepare]
 * multiple times; each call releases the previous player.
 */
class PlaybackController : Closeable {

    sealed class State {
        object Idle : State()
        data class Prepared(val durationMs: Int) : State()
        data class Playing(val positionMs: Int, val durationMs: Int) : State()
        data class Paused(val positionMs: Int, val durationMs: Int) : State()
        data class Error(val message: String) : State()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun prepare(file: File) {
        release()
        if (!file.exists() || file.length() == 0L) {
            _state.value = State.Idle
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopTicker()
                    val p = player
                    if (p != null) _state.value = State.Paused(p.duration, p.duration)
                }
                setOnErrorListener { _, what, extra ->
                    _state.value = State.Error("playback error $what/$extra")
                    true
                }
                prepare()
            }
            _state.value = State.Prepared(player!!.duration)
        } catch (t: Throwable) {
            _state.value = State.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    fun playFrom(offsetMs: Long) {
        val p = player ?: return
        try {
            val clamped = offsetMs.coerceIn(0L, p.duration.toLong()).toInt()
            p.seekTo(clamped)
            if (!p.isPlaying) p.start()
            startTicker()
        } catch (_: Throwable) { }
    }

    fun togglePlayPause() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                stopTicker()
                _state.value = State.Paused(p.currentPosition, p.duration)
            } else {
                p.start()
                startTicker()
            }
        } catch (_: Throwable) { }
    }

    fun seekTo(offsetMs: Long) {
        val p = player ?: return
        try {
            val clamped = offsetMs.coerceIn(0L, p.duration.toLong()).toInt()
            p.seekTo(clamped)
            val s = _state.value
            _state.value = when (s) {
                is State.Playing -> s.copy(positionMs = clamped)
                is State.Paused -> s.copy(positionMs = clamped)
                else -> State.Paused(clamped, p.duration)
            }
        } catch (_: Throwable) { }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                if (p.isPlaying) {
                    _state.value = State.Playing(p.currentPosition, p.duration)
                }
                delay(100)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun release() {
        stopTicker()
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }

    override fun close() {
        release()
        scope.cancel()
        _state.value = State.Idle
    }
}
