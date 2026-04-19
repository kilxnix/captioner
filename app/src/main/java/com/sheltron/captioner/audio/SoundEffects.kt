package com.sheltron.captioner.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Short "recording starting / stopping" beeps, piggybacking on ToneGenerator so we ship no
 * audio assets. Called from the foreground service on the main-looper.
 */
object SoundEffects {
    private val handler = Handler(Looper.getMainLooper())

    private fun play(tone: Int, durationMs: Int = 180) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(tone, durationMs)
            handler.postDelayed({ runCatching { tg.release() } }, (durationMs + 250).toLong())
        } catch (_: Throwable) { /* no sound is fine */ }
    }

    fun playStart() = play(ToneGenerator.TONE_PROP_ACK)
    fun playStop() = play(ToneGenerator.TONE_PROP_BEEP2)
}
