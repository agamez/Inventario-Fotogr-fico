package com.inventory.feature.trash

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.cache.InventoryScanner
import com.inventory.storage.TrashManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Trash screen's state holder: lists the restorable entries from
 * [TrashManager] (name, original location, kind, deletion time) and runs
 * restore / delete-forever / empty-trash. Disk is the source of truth, so
 * every mutation re-lists the trash and triggers a cache [rescan] — a
 * restore puts a real folder or JPEG back into the inventory, and the
 * browse screens converge on it.
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashManager: TrashManager,
    private val scanner: InventoryScanner,
) : ViewModel() {

    data class TrashUiState(
        val entries: List<TrashManager.TrashEntry> = emptyList(),
        val isLoading: Boolean = true,
    )

    private val _uiState = MutableStateFlow(TrashUiState())

    /** The current trash listing, newest deletion first. */
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    /** One-shot feedback, consumed exactly once by the screen. */
    sealed interface TrashEvent {
        data class ShowMessage(@StringRes val resId: Int) : TrashEvent
    }

    private val _events = Channel<TrashEvent>(Channel.BUFFERED)
    val events: Flow<TrashEvent> = _events.receiveAsFlow()

    init {
        refresh()
    }

    /** Re-reads the trash listings from disk. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(entries = trashManager.listEntries(), isLoading = false)
            }
        }
    }

    /** Puts the entry [id] back into the inventory. */
    fun restore(id: String) {
        viewModelScope.launch {
            if (trashManager.restore(id)) {
                scanner.rescan()
                refresh()
                _events.send(TrashEvent.ShowMessage(R.string.trash_restored))
            }
        }
    }

    /** Permanently deletes the entry [id]. */
    fun deleteForever(id: String) {
        viewModelScope.launch {
            if (trashManager.deleteForever(id)) {
                refresh()
                _events.send(TrashEvent.ShowMessage(R.string.trash_purged_entry))
            }
        }
    }

    /** Permanently deletes every trashed entry. */
    fun emptyTrash() {
        viewModelScope.launch {
            trashManager.listEntries().forEach { trashManager.deleteForever(it.id) }
            refresh()
            _events.send(TrashEvent.ShowMessage(R.string.trash_empty_done))
        }
    }
}
