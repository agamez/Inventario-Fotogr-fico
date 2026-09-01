package com.inventory.feature.photos

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds a single captured frame and its mandatory-metadata save flow for one
 * box. The parent box id arrives as the navigation argument `boxId`.
 *
 * The captured bytes live in [pending] (kept out of any equality-heavy UI
 * state); success navigates back via [CameraEvent.NavigateBack].
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PhotoRepository,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** Id of the box the photo will be stored in; supplied by the nav route. */
    val boxId: Long = checkNotNull(savedStateHandle["boxId"])

    private val _pending = MutableStateFlow<ByteArray?>(null)

    /** The just-captured image awaiting its description, or null. */
    val pending: StateFlow<ByteArray?> = _pending.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface CameraEvent {
        data class ShowMessage(@StringRes val resId: Int) : CameraEvent
        data object NavigateBack : CameraEvent
    }

    private val _events = Channel<CameraEvent>(Channel.BUFFERED)
    val events: Flow<CameraEvent> = _events.receiveAsFlow()

    fun onCaptured(bytes: ByteArray) {
        _pending.value = bytes
    }

    fun discardPending() {
        _pending.value = null
    }

    /**
     * Stores the pending frame in [boxId]; on success the pending image is
     * cleared, [R.string.photo_added] is posted to the app-level
     * [UserMessageBus] (the photos grid shows the snackbar when it returns)
     * and the screen is asked to navigate back. An undecodable frame is
     * dropped so the user can retake it.
     */
    fun save(rawDescription: String, rawNote: String?) {
        val bytes = _pending.value ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repository.addPhoto(boxId, bytes, rawDescription, rawNote)
            }
            when (outcome) {
                PhotoRepository.AddOutcome.Success -> {
                    _pending.value = null
                    messageBus.post(R.string.photo_added)
                    _events.send(CameraEvent.NavigateBack)
                }
                PhotoRepository.AddOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
                PhotoRepository.AddOutcome.InvalidImage -> {
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
