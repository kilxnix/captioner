package com.sheltron.captioner.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Transcribes the microphone using Android's on-device SpeechRecognizer.
 *
 * SpeechRecognizer sessions auto-end on silence; we loop them to provide continuous
 * recognition. Runs on the main thread (required by SpeechRecognizer). The caller is
 * responsible for mic permission.
 *
 * Note: SpeechRecognizer captures audio internally; this engine is mutually exclusive
 * with a concurrent AudioRecord / AudioEncoder on most devices.
 */
@RequiresApi(Build.VERSION_CODES.S)
class SpeechRecognizerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFatal: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    private var consecutiveErrors = 0

    fun start() {
        if (running) return
        running = true
        main.post { startSession() }
    }

    fun stop() {
        running = false
        main.post {
            try { recognizer?.cancel() } catch (_: Throwable) {}
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
        }
    }

    private fun startSession() {
        if (!running) return
        try {
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            sr.setRecognitionListener(makeListener())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Keep listening through natural pauses so dictated notes aren't chopped.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000)
            }
            recognizer = sr
            sr.startListening(intent)
        } catch (t: Throwable) {
            onFatal("SpeechRecognizer init failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun restartSoon() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        if (!running) return
        // Small delay before restart — starting too fast sometimes triggers ERROR_CLIENT.
        main.postDelayed({ if (running) startSession() }, 120)
    }

    private fun makeListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { /* listening */ }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!text.isNullOrEmpty()) scope.launch(Dispatchers.Main) { onPartial(text) }
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!text.isNullOrEmpty()) scope.launch(Dispatchers.Main) { onFinal(text) }
            restartSoon()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal — user was quiet. Loop without counting against the budget.
                    restartSoon()
                }
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Transient engine glitch — retry a few times then bail.
                    consecutiveErrors++
                    if (consecutiveErrors < 5) restartSoon()
                    else { running = false; onFatal("Recognizer stuck (error $error)") }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    running = false
                    onFatal("Microphone permission missing")
                }
                else -> {
                    // ERROR_AUDIO / ERROR_NETWORK (shouldn't happen on-device) / ERROR_SERVER / ERROR_LANGUAGE_NOT_SUPPORTED / …
                    consecutiveErrors++
                    if (consecutiveErrors < 3) restartSoon()
                    else { running = false; onFatal("Recognizer error $error") }
                }
            }
        }
    }

    companion object {
        /** Cheap pre-flight — true if the API is present. Real availability surfaces at runtime. */
        fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
