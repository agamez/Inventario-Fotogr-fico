package com.inventory.storage

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which door [InventoryStore] currently uses to reach the inventory tree.
 * Persisted in [StorageModePrefs]; unset means the user has not chosen yet
 * (first launch shows the mode chooser).
 */
enum class StorageMode {
    /** Direct `File` access to `/sdcard/PhotoInventory` — All Files Access required. */
    FILE,

    /** SAF tree pick through [SafBackend] — zero permissions, the default. */
    SAF,
    ;

    companion object {
        /** Reverse of [StorageModePrefs.setMode]; null for unset/unknown keys. */
        fun fromKey(key: String?): StorageMode? = entries.firstOrNull { it.name == key }
    }
}

/**
 * The two persisted storage settings ("storage_prefs"): the chosen
 * [StorageMode] and the SAF tree uri the user granted. The tree uri is
 * meaningless without its persistable permission grant — taking that
 * grant belongs to [InventoryStore.setTreeUri], this class only stores
 * the strings.
 */
@Singleton
class StorageModePrefs @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The chosen backend, or null when the user has not picked one yet. */
    fun mode(): StorageMode? = StorageMode.fromKey(prefs.getString(KEY_MODE, null))

    /** The granted SAF tree uri as a string, or null. */
    fun treeUri(): String? = prefs.getString(KEY_TREE_URI, null)

    /** Persists the active [mode] (null resets to "not chosen yet"). */
    fun setMode(mode: StorageMode?) {
        prefs.edit { putString(KEY_MODE, mode?.name) }
    }

    /** Persists (or clears, with null) the SAF tree uri. */
    fun setTreeUri(uri: String?) {
        prefs.edit { putString(KEY_TREE_URI, uri) }
    }

    private companion object {
        const val NAME = "storage_prefs"
        const val KEY_MODE = "mode"
        const val KEY_TREE_URI = "tree_uri"
    }
}
