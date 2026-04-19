package com.sheltron.captioner.api

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig

/**
 * Thin wrapper around Gemini Nano via Google's AICore SDK.
 *
 * Availability is device-dependent: generally Pixel 8 Pro+, Galaxy S24+, and similar
 * flagships with AICore installed. On unsupported devices, [generate] returns a Failed
 * result with a readable message rather than crashing.
 */
object NanoClient {

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun generate(context: Context, prompt: String, maxTokens: Int = 2000): Result {
        val model = try {
            val config = generationConfig {
                this.context = context
                temperature = 0.2f
                topK = 16
                maxOutputTokens = maxTokens
            }

            val downloadConfig = DownloadConfig(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {}
                override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                override fun onDownloadCompleted() {}
                override fun onDownloadFailed(failureStatus: String, e: com.google.ai.edge.aicore.GenerativeAIException) {}
            })

            GenerativeModel(generationConfig = config, downloadConfig = downloadConfig)
        } catch (t: Throwable) {
            return Result.Failed(unavailableMessage(t))
        }

        return try {
            model.prepareInferenceEngine()
            val response = model.generateContent(prompt)
            val text = response.text?.trim().orEmpty()
            if (text.isEmpty()) Result.Failed("Nano returned empty response")
            else Result.Ok(text)
        } catch (t: Throwable) {
            Result.Failed(unavailableMessage(t))
        } finally {
            try { model.close() } catch (_: Throwable) {}
        }
    }

    private fun unavailableMessage(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        return when {
            raw.contains("not supported", ignoreCase = true) ||
            raw.contains("unavailable", ignoreCase = true) ->
                "Gemini Nano isn't available on this device. Needs a supported phone (Pixel 8 Pro+, Galaxy S24+) with AICore installed."
            raw.contains("download", ignoreCase = true) ->
                "Gemini Nano is still downloading. Open Settings → Google → Device & Privacy → Private Compute Services to check progress. Try again in a few minutes."
            else -> "Nano error: $raw"
        }
    }
}
