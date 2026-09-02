package com.inventory.feature.photos

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.storage.InventoryStore
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Holds a single captured frame and its mandatory-metadata save flow for
 * one container folder. The target folder's absolute path arrives as the
 * (URL-decoded) navigation argument `path` — contractual with
 * [com.inventory.feature.navigation.Screens.ARG_PATH].
 *
 * Captured bytes are staged verbatim (no resize, no recompression) as a
 * file under `cacheDir/pending` until the description confirms; the
 * staged file lives in [pending] (kept out of any equality-heavy UI
 * state); success navigates back via [CameraEvent.NavigateBack].
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: InventoryStore,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** Path of the folder the photo will be stored in; supplied by the nav route. */
    val folderPath: String = checkNotNull(savedStateHandle["path"])

    private val _pending = MutableStateFlow<File?>(null)

    /** The staged capture awaiting its description, or null. */
    val pending: StateFlow<File?> = _pending.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface CameraEvent {
        data class ShowMessage(@StringRes val resId: Int) : CameraEvent
        data object NavigateBack : CameraEvent
    }

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events: Flow<CameraEvent> = _events.receiveAsFlow()

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
     * Writes the pending frame into the target folder via
     * [InventoryStore.addPhoto] (byte-identical copy + EXIF stamp); on
     * success the staged file is gone, [R.string.photo_added] is posted to
     * the app-level [UserMessageBus] (the container screen rescans and
     * shows the snackbar when it returns) and the screen is asked to
     * navigate back. A rejected frame is unstaged and reported so the user
     * can retake it.
     */
    fun save(rawDescription: String, rawNote: String?) {
        val staged = _pending.value ?: return
        viewModelScope.launch {
            when (
                store.addPhoto(
                    folder = File(folderPath),
                    staged = staged,
                    description = rawDescription.trim(),
                    note = rawNote?.trim()?.ifEmpty { null },
                )
            ) {
                InventoryStore.AddOutcome.Success -> {
                    _pending.value = null
                    messageBus.post(R.string.photo_added)
                    _events.send(CameraEvent.NavigateBack)
                }
                InventoryStore.AddOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
                InventoryStore.AddOutcome.InvalidImage -> {
                    store.unstage(staged)
                    _pending.value = null
                    sendEvent(R.string.error_invalid_image)
                }
            }
        }
    }

    private suspend fun sendEvent(@StringRes resId: Int) {
        _events.send(CameraEvent.ShowMessage(resId))
    }
}
