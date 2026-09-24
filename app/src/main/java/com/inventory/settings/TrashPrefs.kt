package com.inventory.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted configuration of the optional trash (soft-delete) feature, the
 * twin of the desktop `DesktopConfig.trashEnabled`.
 *
 * Backed by [Context.getSharedPreferences] ("trash_prefs"), the source of
 * truth for the settings screen and for the delete-time decision to move an
 * entry into `.trash` or destroy it permanently. Whitelisted into Hilt via
 * constructor injection; the value is mirrored in a [StateFlow] so
 * ViewModels can observe edits without polling the file.
 *
 * The feature is OFF by default: until the user opts in, deletions are
 * permanent (the pre-trash behaviour).
 */
@Singleton
class TrashPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))

    /** Master switch; off until the user turns it on. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    companion object {
        private const val PREFS_NAME = "trash_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val DEFAULT_ENABLED = false
    }
}
