package com.inventory.feature.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.storage.InventoryStore
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Holds one captured frame destined for the cover of a container folder.
 * The target folder's RELATIVE path arrives as the (URL-decoded)
 * navigation argument `path` — contractual with
 * [com.inventory.feature.navigation.Screens.ARG_PATH].
 *
 * The frame is kept verbatim in memory (no staging file) in [pending];
 * success promotes it to the folder's `_cover.jpg` through the active
 * storage backend, posts [R.string.cover_updated] on the app-level
 * [UserMessageBus] (the container screen underneath rescans and owns the
 * snackbar) and asks the screen to navigate back via
 * [CoverCaptureEvent.NavigateBack].
 */
@HiltViewModel
class CoverCaptureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: InventoryStore,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** Relative path of the folder whose cover this capture sets; from the nav route. */
    val folderRel: String = checkNotNull(savedStateHandle["path"])

    private val _pending = MutableStateFlow<ByteArray?>(null)

    /** The captured bytes awaiting confirmation, or null. */
    val pending: StateFlow<ByteArray?> = _pending.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface CoverCaptureEvent {
        data object NavigateBack : CoverCaptureEvent
    }

    private val _events = Channel<CoverCaptureEvent>(Channel.BUFFERED)
    val events: Flow<CoverCaptureEvent> = _events.receiveAsFlow()

    /** Keeps the fresh capture bytes verbatim, replacing any earlier pending frame. */
    fun onCaptured(bytes: ByteArray) {
        _pending.value = bytes
    }

    /** Throws away the pending frame. */
    fun discardPending() {
        _pending.value = null
    }

    /**
     * Stores the pending bytes as the folder's cover. Success clears the
     * pending state, announces [R.string.cover_updated] on the bus and
     * navigates back. A rejected frame announces
     * [R.string.error_invalid_image] instead, staying on the screen so
     * the user can retry.
     */
    fun save() {
        val staged = _pending.value ?: return
        viewModelScope.launch {
            try {
                store.setCover(folderRel, staged)
                _pending.value = null
                messageBus.post(R.string.cover_updated)
                _events.send(CoverCaptureEvent.NavigateBack)
            } catch (exception: IllegalArgumentException) {
                _pending.value = null
                messageBus.post(R.string.error_invalid_image)
            } catch (exception: IOException) {
                _pending.value = null
                messageBus.post(R.string.error_invalid_image)
            }
        }
    }
}
