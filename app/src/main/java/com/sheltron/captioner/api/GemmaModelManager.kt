package com.sheltron.captioner.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and tracks the Gemma on-device LLM for task extraction.
 * Model ships as a MediaPipe .task bundle. ~550 MB one-time download.
 */
object GemmaModelManager {

    const val MODEL_FILE = "gemma3-1b-it-int4.task"
    // Hugging Face mirror for the MediaPipe-packaged Gemma 3 1B IT int4 model.
    private val URLS = listOf(
        "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
        "https://huggingface.co/litert-community/Gemma-3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
    )
    private const val USER_AGENT = "ColesLog/1.0 (Android)"
    private const val MAX_REDIRECTS = 5

    fun modelFile(context: Context): File =
        File(context.filesDir, "models/$MODEL_FILE").also { it.parentFile?.mkdirs() }

    fun isReady(context: Context): Boolean {
        val f = modelFile(context)
        // Guard against truncated files from an interrupted download by requiring >100MB.
        return f.exists() && f.length() > 100L * 1024 * 1024
    }

    sealed class Event {
        data class Progress(val percent: Int, val bytesDone: Long, val bytesTotal: Long) : Event()
        object Done : Event()
        data class Failed(val message: String) : Event()
    }

    fun download(context: Context): Flow<Event> = flow {
        if (isReady(context)) {
            emit(Event.Done)
            return@flow
        }

        val dest = modelFile(context)
        if (dest.exists()) dest.delete()

        var lastError: String? = null
        for (url in URLS) {
            try {
                downloadFromUrl(url, dest) { pct, done, total ->
                    emit(Event.Progress(pct, done, total))
                }
                if (dest.exists() && dest.length() > 100L * 1024 * 1024) {
                    emit(Event.Done)
                    return@flow
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
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct, done, total)
                                }
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
