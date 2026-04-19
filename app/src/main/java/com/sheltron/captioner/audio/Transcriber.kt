package com.sheltron.captioner.audio

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class Transcriber(model: Model, sampleRate: Float = 16000f) {

    private val recognizer = Recognizer(model, sampleRate)

    sealed class Result {
        data class Partial(val text: String) : Result()
        data class Final(val text: String) : Result()
    }

    fun accept(buffer: ShortArray, len: Int): Result {
        return if (recognizer.acceptWaveForm(buffer, len)) {
            Result.Final(extract(recognizer.result, "text"))
        } else {
            Result.Partial(extract(recognizer.partialResult, "partial"))
        }
    }

    fun finish(): String = extract(recognizer.finalResult, "text")

    fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {}
    }

    private fun extract(json: String, key: String): String {
        return try {
            JSONObject(json).optString(key, "").trim()
        } catch (_: Exception) {
            ""
        }
    }
}
