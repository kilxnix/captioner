package com.sheltron.captioner.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Local-only settings. Cole's Log is fully offline — no API keys here anymore.
 *
 * Uses EncryptedSharedPreferences for consistency with previous builds; falls back
 * to plaintext if the keystore becomes unusable so we never crash on a bad keyring.
 */
class SettingsStore(context: Context) {

    enum class Engine(val id: String, val display: String, val note: String) {
        VOSK("vosk", "Offline Vosk (saves audio)",
            "Weaker live captions, but audio is saved for playback."),
        ON_DEVICE("on_device", "On-device Google (Android 12+)",
            "Better live captions. Audio file not saved this session.");

        companion object {
            fun fromId(id: String?): Engine = entries.firstOrNull { it.id == id } ?: VOSK
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

    var engine: Engine
        get() = Engine.fromId(prefs.getString(KEY_ENGINE, null))
        set(value) { prefs.edit().putString(KEY_ENGINE, value.id).apply() }

    /** Start/stop recording beeps. Default on. */
    var recordingSoundsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUNDS, true)
        set(value) { prefs.edit().putBoolean(KEY_SOUNDS, value).apply() }

    /** Hugging Face access token, used only to download the license-gated Gemma model. */
    var hfToken: String?
        get() = prefs.getString(KEY_HF, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_HF, value?.trim()).apply() }

    companion object {
        private const val KEY_ENGINE = "transcription_engine"
        private const val KEY_SOUNDS = "recording_sounds_enabled"
        private const val KEY_HF = "hf_token"
    }
}
