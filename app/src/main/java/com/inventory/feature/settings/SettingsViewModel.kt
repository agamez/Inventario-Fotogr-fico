package com.inventory.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.ai.VisionClient
import com.inventory.ai.VisionOutcome
import com.inventory.ai.VisionPrefs
import com.inventory.settings.TrashPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The AI settings screen: mirrors [VisionPrefs] into one [SettingsUiState]
 * (edits persist immediately) and owns the "Test connection" probe.
 *
 * The probe builds a throwaway [VisionClient] from the CURRENT field values
 * and calls its text-only [VisionClient.ping]; the result is resolved here
 * so the screen renders a plain string (success = `ai_test_ok`, failure =
 * `ai_test_failed` + the client's key-free reason).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: VisionPrefs,
    private val trashPrefs: TrashPrefs,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class SettingsUiState(
        val enabled: Boolean = false,
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val outputLanguage: String = "",
        val trashEnabled: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null,
    )

    private val _testing = MutableStateFlow(false)
    private val _testResult = MutableStateFlow<String?>(null)

    private val fields = combine(
        prefs.enabled,
        prefs.baseUrl,
        prefs.apiKey,
        prefs.model,
        prefs.outputLanguage,
    ) { enabled, baseUrl, apiKey, model, outputLanguage ->
        Fields(enabled, baseUrl, apiKey, model, outputLanguage)
    }

    private val status = combine(_testing, _testResult, trashPrefs.enabled) { testing, result, trashEnabled ->
        Triple(testing, result, trashEnabled)
    }

    val uiState: StateFlow<SettingsUiState> = combine(fields, status) { current, (testing, result, trashEnabled) ->
        SettingsUiState(
            enabled = current.enabled,
            baseUrl = current.baseUrl,
            apiKey = current.apiKey,
            model = current.model,
            outputLanguage = current.outputLanguage,
            trashEnabled = trashEnabled,
            testing = testing,
            testResult = result,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            enabled = prefs.enabled.value,
            baseUrl = prefs.baseUrl.value,
            apiKey = prefs.apiKey.value,
            model = prefs.model.value,
            outputLanguage = prefs.outputLanguage.value,
            trashEnabled = trashPrefs.enabled.value,
        ),
    )

    fun setEnabled(value: Boolean) = prefs.setEnabled(value)

    fun setBaseUrl(value: String) = prefs.setBaseUrl(value)

    fun setApiKey(value: String) = prefs.setApiKey(value)

    fun setModel(value: String) = prefs.setModel(value)

    fun setOutputLanguage(value: String) = prefs.setOutputLanguage(value)

    fun setTrashEnabled(value: Boolean) = trashPrefs.setEnabled(value)

    /** Probes the configured endpoint; no-op while a probe is already in flight. */
    fun testConnection() {
        if (_testing.value) return
        val config = prefs.config()
        if (config.model.isBlank()) {
            _testResult.value = context.getString(R.string.ai_test_failed)
            return
        }
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch {
            when (val outcome = VisionClient(config).ping()) {
                is VisionOutcome.Suggested ->
                    _testResult.value = context.getString(R.string.ai_test_ok)
                is VisionOutcome.Failed ->
                    _testResult.value =
                        context.getString(R.string.ai_test_failed) + ": " + outcome.reason
            }
            _testing.value = false
        }
    }

    private data class Fields(
        val enabled: Boolean,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val outputLanguage: String,
    )
}
