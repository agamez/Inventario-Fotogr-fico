package com.inventory.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted configuration of the optional AI auto-labeling feature.
 *
 * Backed by [Context.getSharedPreferences] ("vision_prefs"), the source of
 * truth for the settings screen and for the queue-time decision to ask a
 * model. Whitelisted into Hilt via constructor injection; each value is
 * mirrored in a [StateFlow] so ViewModels can observe edits (and the
 * settings screen can render them) without polling the file.
 *
 * The feature is OFF by default.
 */
@Singleton
class VisionPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))

    /** Master switch; off until the user turns it on. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL)

    /** OpenAI-compatible API root (no trailing `/chat/completions`). */
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, null) ?: "")

    /** Bearer token; blank for local servers that need none. */
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, null) ?: "")

    /** Vision model name, e.g. `gpt-4o-mini`. */
    val model: StateFlow<String> = _model.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setBaseUrl(value: String) {
        prefs.edit().putString(KEY_BASE_URL, value).apply()
        _baseUrl.value = value
    }

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API_KEY, value).apply()
        _apiKey.value = value
    }

    fun setModel(value: String) {
        prefs.edit().putString(KEY_MODEL, value).apply()
        _model.value = value
    }

    /**
     * Immutable snapshot for one request. A blank API key becomes null so
     * the client omits the Authorization header entirely; the base URL and
     * model are trimmed.
     */
    fun config(): VisionConfig = VisionConfig(
        baseUrl = _baseUrl.value.trim(),
        apiKey = _apiKey.value.trim().ifBlank { null },
        model = _model.value.trim(),
    )

    /** True when the feature is on AND a model is configured (so calls can be made). */
    fun isActive(): Boolean = _enabled.value && _model.value.isNotBlank()

    private companion object {
        const val PREFS_NAME = "vision_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val DEFAULT_ENABLED = false
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
