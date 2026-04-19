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

    const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_NAME)

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        // Vosk models ship with a conf/ or am/ subfolder; existence of any contents is a cheap check.
        return dir.exists() && (dir.list()?.isNotEmpty() == true)
    }

    sealed class DownloadEvent {
        data class Progress(val percent: Int, val bytesDone: Long, val bytesTotal: Long) : DownloadEvent()
        object Extracting : DownloadEvent()
        object Done : DownloadEvent()
        data class Failed(val message: String) : DownloadEvent()
    }

    /**
     * Emits download/extract progress. Consumed from a coroutine; cancelling the collection cancels
     * the download. Safe to call even when model already exists — will emit [Done] immediately.
     */
    fun download(context: Context): Flow<DownloadEvent> = flow {
        if (isReady(context)) {
            emit(DownloadEvent.Done)
            return@flow
        }

        val cacheZip = File(context.cacheDir, "$MODEL_NAME.zip")
        if (cacheZip.exists()) cacheZip.delete()

        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()

            if (conn.responseCode !in 200..299) {
                emit(DownloadEvent.Failed("HTTP ${conn.responseCode}"))
                return@flow
            }

            val totalBytes = conn.contentLengthLong.coerceAtLeast(-1L)
            var doneBytes = 0L
            var lastPercent = -1

            conn.inputStream.use { input ->
                FileOutputStream(cacheZip).use { out ->
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
                                emit(DownloadEvent.Progress(pct, doneBytes, totalBytes))
                            }
                        }
                    }
                }
            }

            emit(DownloadEvent.Extracting)

            val targetDir = modelDir(context)
            if (targetDir.exists()) targetDir.deleteRecursively()
            context.filesDir.mkdirs()

            ZipInputStream(FileInputStream(cacheZip)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val out = File(context.filesDir, entry.name)
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

            cacheZip.delete()

            if (!isReady(context)) {
                emit(DownloadEvent.Failed("Model extracted but directory missing"))
                return@flow
            }

            emit(DownloadEvent.Done)
        } catch (t: Throwable) {
            emit(DownloadEvent.Failed(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)
}
