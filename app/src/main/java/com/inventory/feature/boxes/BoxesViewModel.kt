package com.inventory.feature.boxes

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.database.BoxWithPhotoCount
import com.inventory.database.LocationEntity
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presents the boxes of one location and exposes create/rename/delete/move
 * actions plus the cover-photo flow: gallery picks wait in [pendingCover]
 * for a lightweight confirm, camera saves arrive on the [UserMessageBus].
 * The parent location id arrives as the navigation argument `locationId`.
 *
 * Data lives in [uiState]; one-shot user feedback (snackbars) lives in
 * [events] so it survives configuration changes without being re-shown.
 */
@HiltViewModel
class BoxesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BoxRepository,
    @ApplicationContext private val context: Context,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** Id of the location whose boxes are shown; supplied by the nav route. */
    val locationId: Long = checkNotNull(savedStateHandle["locationId"])

    /** A cover image read from the gallery, waiting for the box it belongs to. */
    data class PendingCoverUpload(val boxId: Long, val bytes: ByteArray)

    data class BoxesUiState(
        val boxes: List<BoxWithPhotoCount> = emptyList(),
        val locationName: String = "",
        val isLoading: Boolean = true,
    )

    val uiState: StateFlow<BoxesUiState> = combine(
        repository.observeBoxesForLocation(locationId),
        repository.observeLocation(locationId),
    ) { boxes, location ->
        BoxesUiState(
            boxes = boxes,
            locationName = location?.name.orEmpty(),
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BoxesUiState(),
    )

    /**
     * Every location except the parent of the current list — the candidates
     * for the move-to-location sheet. Filtering here keeps the picker honest
     * even while [uiState] is still loading.
     */
    val otherLocations: StateFlow<List<LocationEntity>> = repository.observeLocations()
        .map { locations -> locations.filterNot { it.id == locationId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _pendingCover = MutableStateFlow<PendingCoverUpload?>(null)

    /** The picked cover awaiting confirmation in the preview dialog, or null. */
    val pendingCover: StateFlow<PendingCoverUpload?> = _pendingCover.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface BoxesEvent {
        data class ShowMessage(@StringRes val resId: Int) : BoxesEvent
    }

    private val _events = Channel<BoxesEvent>(Channel.BUFFERED)
    val events: Flow<BoxesEvent> = _events.receiveAsFlow()

    init {
        // Cross-screen announcements (e.g. the cover-camera save) arrive on
        // the app-level bus; this ViewModel outlives the cover-capture
        // screen in the back stack, and the buffered channel delivers them
        // to the snackbar once the list is back on top.
        viewModelScope.launch {
            messageBus.messages.collect { sendEvent(it) }
        }
    }

    fun createBox(name: String) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.createBox(locationId, name) }
            when (outcome) {
                BoxRepository.MutationOutcome.Success ->
                    sendEvent(R.string.box_created)
                BoxRepository.MutationOutcome.BlankName ->
                    sendEvent(R.string.error_blank_name)
                BoxRepository.MutationOutcome.DuplicateName ->
                    sendEvent(R.string.error_duplicate_name)
            }
        }
    }

    fun renameBox(id: Long, name: String) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.renameBox(id, name) }
            when (outcome) {
                BoxRepository.MutationOutcome.Success ->
                    sendEvent(R.string.box_saved)
                BoxRepository.MutationOutcome.BlankName ->
                    sendEvent(R.string.error_blank_name)
                BoxRepository.MutationOutcome.DuplicateName ->
                    sendEvent(R.string.error_duplicate_name)
            }
        }
    }

    fun deleteBox(id: Long) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.deleteBox(id) }
            when (outcome) {
                BoxRepository.DeleteOutcome.Success ->
                    sendEvent(R.string.box_deleted)
            }
        }
    }

    fun moveBox(boxId: Long, newLocationId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.moveBox(boxId, newLocationId) }
            sendEvent(R.string.box_moved)
        }
    }

    /**
     * Reads a gallery-picked cover to bytes off the main thread and parks it
     * in [pendingCover] for the confirm dialog; a cancelled pick is a no-op
     * and an unreadable or empty one reports [R.string.error_invalid_image].
     */
    fun onCoverImagePicked(boxId: Long, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                sendEvent(R.string.error_invalid_image)
            } else {
                _pendingCover.value = PendingCoverUpload(boxId = boxId, bytes = bytes)
            }
        }
    }

    /**
     * Stores the pending cover; the row refreshes through the live query.
     * An undecodable pick surfaces as [R.string.error_invalid_image]
     * instead of crashing; the pending image is cleared either way.
     */
    fun confirmCoverUpload() {
        val pending = _pendingCover.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.setCoverImage(pending.boxId, pending.bytes) }
                _pendingCover.value = null
                sendEvent(R.string.cover_updated)
            } catch (exception: IllegalArgumentException) {
                _pendingCover.value = null
                sendEvent(R.string.error_invalid_image)
            }
        }
    }

    /** Throws away the pending cover without storing anything. */
    fun discardCoverUpload() {
        _pendingCover.value = null
    }

    /** Clears the box's cover photo. */
    fun removeCoverImage(boxId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setCoverImage(boxId, null) }
            sendEvent(R.string.cover_updated)
        }
    }

    private suspend fun sendEvent(@StringRes resId: Int) {
        _events.send(BoxesEvent.ShowMessage(resId))
    }
}
