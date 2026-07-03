package com.example.intentcam

import android.content.Context

/** Persists the (editable) model configuration in SharedPreferences. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("intentcam_settings", Context.MODE_PRIVATE)

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: LlmConfig.DEFAULT_BASE_URL,
        authToken = prefs.getString(KEY_TOKEN, null) ?: LlmConfig.DEFAULT_TOKEN,
        model = prefs.getString(KEY_MODEL, null) ?: LlmConfig.DEFAULT_MODEL
    )

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_TOKEN, config.authToken.trim())
            .putString(KEY_MODEL, config.model.trim())
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "auth_token"
        const val KEY_MODEL = "model"
    }
}
