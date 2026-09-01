package com.inventory.feature.photos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.feature.boxes.BoxRepository
import com.inventory.feature.locations.LocationRepository
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
 * Holds one captured frame destined for the cover of a box or a location.
 * The target kind and id arrive as the navigation arguments `target`
 * ("box" | "location") and `id`.
 *
 * The captured bytes live in [pending] (kept out of any equality-heavy UI
 * state); success posts [R.string.cover_updated] on the app-level
 * [UserMessageBus] — the list screen underneath owns the snackbar — and
 * asks the screen to navigate back via [CoverCaptureEvent.NavigateBack].
 */
@HiltViewModel
class CoverCaptureViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val boxRepository: BoxRepository,
    private val locationRepository: LocationRepository,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** Which entity's cover this capture targets; supplied by the nav route. */
    val target: String = checkNotNull(savedStateHandle["target"])

    /** Id of the targeted box or location; supplied by the nav route. */
    val targetId: Long = checkNotNull(savedStateHandle["id"])

    private val _pending = MutableStateFlow<ByteArray?>(null)

    /** The just-captured image awaiting confirmation, or null. */
    val pending: StateFlow<ByteArray?> = _pending.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface CoverCaptureEvent {
        data object NavigateBack : CoverCaptureEvent
    }

    private val _events = Channel<CoverCaptureEvent>(Channel.BUFFERED)
    val events: Flow<CoverCaptureEvent> = _events.receiveAsFlow()

    fun onCaptured(bytes: ByteArray) {
        _pending.value = bytes
    }

    fun discardPending() {
        _pending.value = null
    }

    /**
     * Stores the pending frame as the target's cover. Success clears the
     * pending image, announces [R.string.cover_updated] on the bus and
     * navigates back. An undecodable frame clears the pending image and
     * announces [R.string.error_invalid_image] instead, staying on the
     * screen so the user can retry.
     */
    fun save() {
        val bytes = _pending.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (target) {
                        "box" -> boxRepository.setCoverImage(targetId, bytes)
                        "location" -> locationRepository.setCoverImage(targetId, bytes)
                        else -> throw IllegalArgumentException("Unknown cover target '$target'.")
                    }
                }
                _pending.value = null
                messageBus.post(R.string.cover_updated)
                _events.send(CoverCaptureEvent.NavigateBack)
            } catch (exception: IllegalArgumentException) {
                _pending.value = null
                messageBus.post(R.string.error_invalid_image)
            }
        }
    }
}
