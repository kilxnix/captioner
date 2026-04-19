package com.sheltron.captioner.api

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device LLM via MediaPipe LlmInference + Gemma 3 1B IT int4.
 * Single-use per call: creates the inference engine, runs one prompt, releases.
 * Not super efficient for rapid-fire prompts, but fine for task extraction
 * which happens at most once per session.
 */
object GemmaClient {

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun generate(context: Context, prompt: String, maxTokens: Int = 2000): Result =
        withContext(Dispatchers.Default) {
            if (!GemmaModelManager.isReady(context)) {
                return@withContext Result.Failed("Gemma model not downloaded yet. Open Settings → Task extraction model.")
            }

            val modelPath = GemmaModelManager.modelFile(context).absolutePath
            var llm: LlmInference? = null
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(maxTokens)
                    .build()
                llm = LlmInference.createFromOptions(context, options)
                val response = llm.generateResponse(prompt)?.trim().orEmpty()
                if (response.isEmpty()) Result.Failed("Model returned empty response")
                else Result.Ok(response)
            } catch (t: Throwable) {
                Result.Failed("Gemma error: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                try { llm?.close() } catch (_: Throwable) {}
            }
        }
}
