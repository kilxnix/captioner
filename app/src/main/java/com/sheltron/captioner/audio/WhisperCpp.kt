package com.sheltron.captioner.audio

import java.io.Closeable

/**
 * Thin wrapper over whisper.cpp native lib. Owns a `whisper_context` handle.
 * Thread-safety: a single instance should be used from one thread at a time.
 */
class WhisperCpp private constructor(private val ctxPtr: Long) : Closeable {

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    fun transcribe(audio: FloatArray, threads: Int = defaultThreads()): List<Segment> {
        if (ctxPtr == 0L || audio.isEmpty()) return emptyList()
        val raw = nativeTranscribe(ctxPtr, audio, threads) ?: return emptyList()
        return raw.mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) null
            else Segment(
                startMs = parts[0].toLongOrNull() ?: return@mapNotNull null,
                endMs = parts[1].toLongOrNull() ?: return@mapNotNull null,
                text = parts[2].trim()
            )
        }
    }

    override fun close() {
        if (ctxPtr != 0L) nativeRelease(ctxPtr)
    }

    companion object {
        /** Use most of the big cores, but leave one free so the UI / service don't starve. */
        private fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores - 1).coerceIn(2, 8)
        }

        @Volatile private var loaded = false
        private val loadError: Throwable? by lazy {
            try {
                System.loadLibrary("whisperjni")
                loaded = true
                null
            } catch (t: Throwable) { t }
        }

        fun isLoadable(): Boolean {
            loadError
            return loaded
        }

        fun loadErrorMessage(): String? = loadError?.let { "${it.javaClass.simpleName}: ${it.message}" }

        fun fromFile(modelPath: String): WhisperCpp? {
            if (!isLoadable()) return null
            val ptr = nativeInit(modelPath)
            if (ptr == 0L) return null
            return WhisperCpp(ptr)
        }

        @JvmStatic private external fun nativeInit(modelPath: String): Long
        @JvmStatic private external fun nativeRelease(ctxPtr: Long)
        @JvmStatic private external fun nativeTranscribe(ctxPtr: Long, audio: FloatArray, threads: Int): Array<String>?
    }
}
