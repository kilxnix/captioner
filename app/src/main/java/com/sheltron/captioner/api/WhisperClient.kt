package com.sheltron.captioner.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts an audio file to OpenAI's /v1/audio/transcriptions endpoint and returns the
 * verbose-JSON response parsed into timestamped segments.
 *
 * Multipart body is assembled manually (no okhttp dep) — keeps APK small.
 */
object WhisperClient {

    private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
    private const val BOUNDARY = "----coleslog-boundary-8675309"

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    sealed class Result {
        data class Ok(val segments: List<Segment>, val fullText: String) : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class NetworkError(val message: String) : Result()
    }

    suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        model: String = "whisper-1",
        language: String = "en"
    ): Result = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.NetworkError("audio file missing or empty")
        }

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 300_000 // Whisper can take a while on longer files
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            setRequestProperty("Accept", "application/json")
            // Tell the connection we're streaming (unknown length is fine; server reads to boundary).
            setChunkedStreamingMode(16 * 1024)
        }

        try {
            DataOutputStream(conn.outputStream).use { out ->
                writePart(out, "model", model)
                writePart(out, "response_format", "verbose_json")
                writePart(out, "language", language)
                writePart(out, "temperature", "0")
                writeFilePart(out, "file", audioFile)
                out.writeBytes("--$BOUNDARY--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val detail = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Throwable) { null }
                return@withContext Result.HttpError(code, detail ?: text.take(300))
            }

            parseVerbose(text)
        } catch (t: Throwable) {
            Result.NetworkError("${t.javaClass.simpleName}: ${t.message ?: ""}".trim())
        } finally {
            conn.disconnect()
        }
    }

    private fun writePart(out: DataOutputStream, name: String, value: String) {
        out.writeBytes("--$BOUNDARY\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun writeFilePart(out: DataOutputStream, name: String, file: File) {
        out.writeBytes("--$BOUNDARY\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n")
        out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
        file.inputStream().use { it.copyTo(out) }
        out.writeBytes("\r\n")
    }

    private fun parseVerbose(responseText: String): Result {
        val json = try { JSONObject(responseText) } catch (t: Throwable) {
            return Result.NetworkError("bad JSON from whisper: ${t.message}")
        }
        val fullText = json.optString("text", "").trim()
        val segArr: JSONArray? = json.optJSONArray("segments")
        val segs = mutableListOf<Segment>()
        if (segArr != null) {
            for (i in 0 until segArr.length()) {
                val s = segArr.optJSONObject(i) ?: continue
                val start = (s.optDouble("start", 0.0) * 1000.0).toLong()
                val end = (s.optDouble("end", 0.0) * 1000.0).toLong()
                val t = s.optString("text", "").trim()
                if (t.isNotEmpty()) segs.add(Segment(start, end, t))
            }
        }
        // If Whisper returned no segments (very short clip), fall back to a single segment.
        if (segs.isEmpty() && fullText.isNotEmpty()) {
            segs.add(Segment(0L, 0L, fullText))
        }
        return Result.Ok(segs, fullText)
    }
}
