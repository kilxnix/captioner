package com.sheltron.captioner.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and tracks the quantized Whisper model used for post-recording polish. */
object WhisperModelManager {

    // ggml-small.en-q5_1 — ~190 MB. Noticeably better than base.en on connected /
    // mumbled / accented speech, still small enough to live in filesDir.
    const val MODEL_FILE = "ggml-small.en-q5_1.bin"
    /** Old base.en file from prior builds — deleted when the new model lands. */
    private const val LEGACY_MODEL_FILE = "ggml-base.en-q5_1.bin"
    private val URLS = listOf(
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin"
    )
    private const val USER_AGENT = "ColesLog/1.0 (Android)"
    private const val MAX_REDIRECTS = 5
    private const val MIN_BYTES = 150L * 1024 * 1024

    fun modelFile(context: Context): File =
        File(context.filesDir, "models/$MODEL_FILE").also { it.parentFile?.mkdirs() }

    fun isReady(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > MIN_BYTES
    }

    sealed class Event {
        data class Progress(val percent: Int, val bytesDone: Long, val bytesTotal: Long) : Event()
        object Done : Event()
        data class Failed(val message: String) : Event()
    }

    fun download(context: Context): Flow<Event> = flow {
        if (isReady(context)) { emit(Event.Done); return@flow }

        val dest = modelFile(context)
        if (dest.exists()) dest.delete()
        // Clean up the smaller base.en model if it's still sitting in files from a prior build.
        runCatching { File(context.filesDir, "models/$LEGACY_MODEL_FILE").delete() }

        var lastError: String? = null
        for (url in URLS) {
            try {
                downloadFromUrl(url, dest) { pct, done, total ->
                    emit(Event.Progress(pct, done, total))
                }
                if (dest.exists() && dest.length() > MIN_BYTES) {
                    emit(Event.Done); return@flow
                }
                lastError = "download: incomplete file from $url"
            } catch (t: Throwable) {
                lastError = "${phaseOf(t)}: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
                if (dest.exists()) dest.delete()
            }
        }
        emit(Event.Failed(lastError ?: "unknown failure"))
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadFromUrl(
        initialUrl: String,
        dest: File,
        onProgress: suspend (Int, Long, Long) -> Unit
    ) {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw java.io.IOException("redirect $code without Location header")
                    if (++redirects > MAX_REDIRECTS) throw java.io.IOException("too many redirects")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    continue
                }
                if (code !in 200..299) throw java.io.IOException("HTTP $code")

                val total = conn.contentLengthLong.coerceAtLeast(-1L)
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct, done, total) }
                            }
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun phaseOf(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "network"
        is java.net.SocketTimeoutException -> "timeout"
        is javax.net.ssl.SSLException -> "tls"
        is java.io.FileNotFoundException -> "fs"
        else -> "download"
    }
}
