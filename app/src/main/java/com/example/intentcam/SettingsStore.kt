package com.example.intentcam

import android.content.Context

/** Persists the (editable) model configuration in SharedPreferences. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("intentcam_settings", Context.MODE_PRIVATE)

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: LlmConfig.DEFAULT_BASE_URL,
        authToken = prefs.getString(KEY_TOKEN, null) ?: LlmConfig.DEFAULT_TOKEN,
        model = prefs.getString(KEY_MODEL, null) ?: LlmConfig.DEFAULT_MODEL,
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

    /**
     * Whether the on-screen debug overlay is shown.  Default ON so a
     * fresh install immediately surfaces the per-step pipeline output;
     * the developer can flip it off via the bug icon in the top bar.
     */
    fun loadDebugEnabled(): Boolean =
        prefs.getBoolean(KEY_DEBUG_ENABLED, true)

    fun saveDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "auth_token"
        const val KEY_MODEL = "model"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
    }
}