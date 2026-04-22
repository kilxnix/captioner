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
 * Downloads and tracks the Gemma on-device LLM used for task extraction.
 *
 * The litert-community Gemma 3 1B repo on Hugging Face is license-gated:
 * the user must first accept the Gemma license on huggingface.co and provide
 * a Hugging Face access token, which we send as a Bearer header on the
 * download request.
 */
object GemmaModelManager {

    const val MODEL_FILE = "gemma3-1b-it-int4.task"
    // litert-community publishes the MediaPipe-ready .task bundle behind a gate.
    const val HF_GEMMA_LICENSE_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT"
    const val HF_TOKEN_URL = "https://huggingface.co/settings/tokens"
    private val URLS = listOf(
        "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
    )
    private const val USER_AGENT = "ColesLog/1.0 (Android)"
    private const val MAX_REDIRECTS = 5

    fun modelFile(context: Context): File =
        File(context.filesDir, "models/$MODEL_FILE").also { it.parentFile?.mkdirs() }

    // Gemma 3 1B int4 ships around 529 MB; require ≥ 400 MB to reject truncated downloads.
    private const val MIN_VALID_BYTES = 400L * 1024 * 1024

    fun isReady(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > MIN_VALID_BYTES
    }

    sealed class Event {
        data class Progress(val percent: Int, val bytesDone: Long, val bytesTotal: Long) : Event()
        object Done : Event()
        data class Failed(val message: String) : Event()
        /** The HF repo returned 401/403 — user needs to accept the license and provide a token. */
        object NeedsLicenseAcceptance : Event()
    }

    fun download(context: Context, hfToken: String?): Flow<Event> = flow {
        if (isReady(context)) {
            emit(Event.Done)
            return@flow
        }

        val dest = modelFile(context)
        if (dest.exists()) dest.delete()

        var lastError: String? = null
        var sawAuth = false
        for (url in URLS) {
            try {
                downloadFromUrl(url, dest, hfToken) { pct, done, total ->
                    emit(Event.Progress(pct, done, total))
                }
                if (dest.exists() && dest.length() > MIN_VALID_BYTES) {
                    emit(Event.Done)
                    return@flow
                }
                lastError = "download: incomplete file from $url"
            } catch (e: HttpAuthException) {
                sawAuth = true
                lastError = "auth: HTTP ${e.code} — license acceptance or token missing"
                if (dest.exists()) dest.delete()
            } catch (t: Throwable) {
                lastError = "${phaseOf(t)}: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
                if (dest.exists()) dest.delete()
            }
        }
        if (sawAuth) emit(Event.NeedsLicenseAcceptance)
        else emit(Event.Failed(lastError ?: "unknown failure"))
    }.flowOn(Dispatchers.IO)

    private class HttpAuthException(val code: Int) : java.io.IOException("HTTP $code")

    private suspend fun downloadFromUrl(
        initialUrl: String,
        dest: File,
        hfToken: String?,
        onProgress: suspend (Int, Long, Long) -> Unit
    ) {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            val isHuggingFace = currentUrl.contains("huggingface.co")
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                // Only send the bearer on HF-owned hosts; after a 302 to a CDN we drop it.
                if (isHuggingFace && !hfToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
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
                if (code == 401 || code == 403) throw HttpAuthException(code)
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
