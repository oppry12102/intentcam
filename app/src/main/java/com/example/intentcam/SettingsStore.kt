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

    /**
     * Set of `ActionDef.id`s the user has left enabled.  `null` =
     * user has never touched this preference (treat as "all enabled"
     * upstream; AppViewModel feeds the resolver an
     * `ActionRegistry.allIds()`-derived set when this comes back null).
     *
     * Stored as a `Set<String>` via SharedPreferences'
     * `putStringSet`.  Persisted across launches so the user's
     * "disable share" toggle survives an app restart.
     */
    fun loadEnabledActions(): Set<String>? =
        prefs.getStringSet(KEY_ENABLED_ACTIONS, null)

    fun saveEnabledActions(ids: Set<String>) {
        // Defensive copy + sort — makeSharedPreferences occasionally
        // returns a live mutable set the caller might mutate; copying
        // here keeps our persisted value stable.
        prefs.edit().putStringSet(KEY_ENABLED_ACTIONS, ids.toSet()).apply()
    }

    /**
     * Per-action opt-in toggle keyed by [ActionDef.userPrefKey].
     * Returns false when the key is absent — PII actions
     * (`dial_number`, future `read_id_card`, etc.) ship OFF by
     * default and require an explicit user consent.  Stored as a
     * boolean to keep the user mental model simple: "I gave this
     * specific action permission once" vs "I didn't".
     *
     * Mirrors the call-site semantics in AppViewModel's
     * `enabledIds()`: an action with `userPrefKey=null` (universal
     * actions like `view_details` and `open_in_maps`) is always
     * considered enabled, regardless of this map.  An action with
     * `userPrefKey=...` is enabled iff this returns true.
     */
    fun loadActionPermission(key: String): Boolean =
        prefs.getBoolean(key, false)

    fun saveActionPermission(key: String, enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "auth_token"
        const val KEY_MODEL = "model"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_ENABLED_ACTIONS = "enabled_actions"
    }
}