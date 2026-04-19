package com.sheltron.captioner.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Claude Messages API client. Returns the first text block of the response.
 * No streaming, no tool use — pure text-in/text-out for task extraction.
 */
object ClaudeClient {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val VERSION = "2023-06-01"

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class HttpError(val code: Int, val body: String) : Result()
        data class NetworkError(val message: String) : Result()
    }

    suspend fun complete(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int = 2048
    ): Result = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", user)
            ))
            .toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", VERSION)
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) return@withContext Result.HttpError(code, text.take(500))

            val json = JSONObject(text)
            val content = json.optJSONArray("content") ?: return@withContext Result.NetworkError("missing content array")
            val first = (0 until content.length())
                .map { content.getJSONObject(it) }
                .firstOrNull { it.optString("type") == "text" }
                ?: return@withContext Result.NetworkError("no text block in response")
            Result.Ok(first.optString("text", ""))
        } catch (t: Throwable) {
            Result.NetworkError("${t.javaClass.simpleName}: ${t.message ?: ""}".trim())
        } finally {
            conn.disconnect()
        }
    }
}
