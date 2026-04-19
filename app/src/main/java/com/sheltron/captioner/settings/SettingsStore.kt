package com.sheltron.captioner.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value store for BYOK API key and model selection.
 * Falls back to a plaintext SharedPreferences if the encrypted store fails to initialize
 * (e.g., keystore corruption after a device restore). Better to run than to crash.
 */
class SettingsStore(context: Context) {

    enum class Model(val id: String, val display: String) {
        HAIKU("claude-haiku-4-5-20251001", "Claude Haiku 4.5 (fast, cheap)"),
        SONNET("claude-sonnet-4-6", "Claude Sonnet 4.6 (smarter, pricier)");

        companion object {
            fun fromId(id: String?): Model = entries.firstOrNull { it.id == id } ?: HAIKU
        }
    }

    enum class Engine(val id: String, val display: String, val note: String) {
        ON_DEVICE("on_device", "On-device (Google, Android 12+)",
            "Best caption quality. Audio file not saved this session."),
        VOSK("vosk", "Offline Vosk (with audio recording)",
            "Weaker captions, but saves audio for playback.");

        companion object {
            fun fromId(id: String?): Engine = entries.firstOrNull { it.id == id } ?: ON_DEVICE
        }
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "cl_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        context.getSharedPreferences("cl_settings_plain", Context.MODE_PRIVATE)
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_API, value?.trim()).apply() }

    var model: Model
        get() = Model.fromId(prefs.getString(KEY_MODEL, null))
        set(value) { prefs.edit().putString(KEY_MODEL, value.id).apply() }

    var engine: Engine
        get() = Engine.fromId(prefs.getString(KEY_ENGINE, null))
        set(value) { prefs.edit().putString(KEY_ENGINE, value.id).apply() }

    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    companion object {
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_MODEL = "anthropic_model"
        private const val KEY_ENGINE = "transcription_engine"
    }
}
