package com.inventory.feature.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.storage.InventoryStore
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
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
 * The target folder's absolute path arrives as the (URL-decoded)
 * navigation argument `path` — contractual with
 * [com.inventory.feature.navigation.Screens.ARG_PATH].
 *
 * The frame is staged verbatim under `cacheDir/pending` and lives in
 * [pending]; success promotes it to the folder's `_cover.jpg`, posts
 * [R.string.cover_updated] on the app-level [UserMessageBus] (the
 * container screen underneath rescans and owns the snackbar) and asks the
 * screen to navigate back via [CoverCaptureEvent.NavigateBack].
 */
@HiltViewModel
class CoverCaptureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: InventoryStore,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** Path of the folder whose cover this capture sets; supplied by the nav route. */
    val folderPath: String = checkNotNull(savedStateHandle["path"])

    private val _pending = MutableStateFlow<File?>(null)

    /** The staged capture awaiting confirmation, or null. */
    val pending: StateFlow<File?> = _pending.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface CoverCaptureEvent {
        data object NavigateBack : CoverCaptureEvent
    }

    private val _events = Channel<CoverCaptureEvent>(Channel.BUFFERED)
    val events: Flow<CoverCaptureEvent> = _events.receiveAsFlow()

    /** Stages the fresh capture bytes verbatim, replacing any earlier pending frame. */
    fun onCaptured(bytes: ByteArray) {
        viewModelScope.launch {
            _pending.value?.let { store.unstage(it) }
            _pending.value = store.stageBytes(bytes)
        }
    }

    /** Throws away the pending staged frame. */
    fun discardPending() {
        val file = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch { store.unstage(file) }
    }

    /**
     * Stores the pending frame as the folder's cover. Success clears the
     * pending state (the staged file was consumed), announces
     * [R.string.cover_updated] on the bus and navigates back. A rejected
     * frame announces [R.string.error_invalid_image] instead, staying on
     * the screen so the user can retry.
     */
    fun save() {
        val staged = _pending.value ?: return
        viewModelScope.launch {
            try {
                store.setCover(File(folderPath), staged)
                _pending.value = null
                messageBus.post(R.string.cover_updated)
                _events.send(CoverCaptureEvent.NavigateBack)
            } catch (exception: IllegalArgumentException) {
                store.unstage(staged)
                _pending.value = null
                messageBus.post(R.string.error_invalid_image)
            } catch (exception: IOException) {
                store.unstage(staged)
                _pending.value = null
                messageBus.post(R.string.error_invalid_image)
            }
        }
    }
}
