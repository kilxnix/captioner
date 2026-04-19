package com.sheltron.captioner.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelManager {

    const val MODEL_NAME = "vosk-model-en-us-0.22-lgraph"
    private val MODEL_URLS = listOf(
        "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"
    )
    private const val USER_AGENT = "ColesLog/1.0 (Android)"
    private const val MAX_REDIRECTS = 5

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_NAME)

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.exists() && (dir.list()?.isNotEmpty() == true)
    }

    sealed class DownloadEvent {
        data class Progress(val percent: Int, val bytesDone: Long, val bytesTotal: Long) : DownloadEvent()
        object Extracting : DownloadEvent()
        object Done : DownloadEvent()
        data class Failed(val message: String) : DownloadEvent()
    }

    fun download(context: Context): Flow<DownloadEvent> = flow {
        if (isReady(context)) {
            emit(DownloadEvent.Done)
            return@flow
        }

        val cacheZip = File(context.cacheDir, "$MODEL_NAME.zip")
        if (cacheZip.exists()) cacheZip.delete()
        context.cacheDir.mkdirs()
        context.filesDir.mkdirs()

        var lastError: String? = null
        for (url in MODEL_URLS) {
            try {
                downloadFromUrl(url, cacheZip) { pct, done, total ->
                    emit(DownloadEvent.Progress(pct, done, total))
                }

                if (!cacheZip.exists() || cacheZip.length() == 0L) {
                    lastError = "download: empty file from $url"
                    continue
                }

                emit(DownloadEvent.Extracting)

                val targetDir = modelDir(context)
                if (targetDir.exists()) targetDir.deleteRecursively()

                extractZip(cacheZip, context.filesDir)
                cacheZip.delete()

                if (!isReady(context)) {
                    lastError = "extract: '$MODEL_NAME/' not present after unzip"
                    continue
                }

                emit(DownloadEvent.Done)
                return@flow
            } catch (t: Throwable) {
                lastError = "${phaseOf(t)}: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
                if (cacheZip.exists()) cacheZip.delete()
            }
        }

        emit(DownloadEvent.Failed(lastError ?: "unknown failure"))
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadFromUrl(
        initialUrl: String,
        dest: File,
        onProgress: suspend (pct: Int, done: Long, total: Long) -> Unit
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
                    if (++redirects > MAX_REDIRECTS) {
                        throw java.io.IOException("too many redirects")
                    }
                    currentUrl = URL(URL(currentUrl), location).toString()
                    continue
                }
                if (code !in 200..299) {
                    throw java.io.IOException("HTTP $code")
                }

                val totalBytes = conn.contentLengthLong.coerceAtLeast(-1L)
                var doneBytes = 0L
                var lastPercent = -1

                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            doneBytes += n
                            if (totalBytes > 0) {
                                val pct = ((doneBytes * 100) / totalBytes).toInt()
                                if (pct != lastPercent) {
                                    lastPercent = pct
                                    onProgress(pct, doneBytes, totalBytes)
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

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                // Guard against zip-slip: resolved path must stay under destDir.
                val out = File(destDir, entry.name).canonicalFile
                val destRoot = destDir.canonicalFile
                if (!out.path.startsWith(destRoot.path)) {
                    throw java.io.IOException("zip entry escapes target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zin.copyTo(fos) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    private fun phaseOf(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "network"
        is java.net.SocketTimeoutException -> "timeout"
        is javax.net.ssl.SSLException -> "tls"
        is java.util.zip.ZipException -> "extract"
        is java.io.FileNotFoundException -> "fs"
        else -> "download"
    }
}
