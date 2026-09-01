package com.inventory.feature.locations

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.database.LocationWithBoxes
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presents the location list and exposes create/rename/delete actions plus
 * the cover-photo flow: gallery picks wait in [pendingCover] for a
 * lightweight confirm, camera saves arrive on the [UserMessageBus].
 *
 * Data lives in [uiState]; one-shot user feedback (snackbars) lives in
 * [events] so it survives configuration changes without being re-shown.
 */
@HiltViewModel
class LocationsViewModel @Inject constructor(
    private val repository: LocationRepository,
    @ApplicationContext private val context: Context,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** A cover image read from the gallery, waiting for the row it belongs to. */
    data class PendingCoverUpload(val locationId: Long, val bytes: ByteArray)

    data class LocationsUiState(
        val locations: List<LocationWithBoxes> = emptyList(),
        val isLoading: Boolean = true,
    )

    val uiState: StateFlow<LocationsUiState> = repository.observeLocations()
        .map { locations -> LocationsUiState(locations = locations, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LocationsUiState(),
        )

    private val _pendingCover = MutableStateFlow<PendingCoverUpload?>(null)

    /** The picked cover awaiting confirmation in the preview dialog, or null. */
    val pendingCover: StateFlow<PendingCoverUpload?> = _pendingCover.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface LocationsEvent {
        data class ShowMessage(@StringRes val resId: Int) : LocationsEvent
    }

    private val _events = Channel<LocationsEvent>(Channel.BUFFERED)
    val events: Flow<LocationsEvent> = _events.receiveAsFlow()

    init {
        // Cross-screen announcements (e.g. the cover-camera save) arrive on
        // the app-level bus; this ViewModel outlives the cover-capture
        // screen in the back stack, and the buffered channel delivers them
        // to the snackbar once the list is back on top.
        viewModelScope.launch {
            messageBus.messages.collect { sendEvent(it) }
        }
    }

    fun createLocation(name: String) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.createLocation(name) }
            when (outcome) {
                LocationRepository.MutationOutcome.Success ->
                    sendEvent(R.string.location_created)
                LocationRepository.MutationOutcome.BlankName ->
                    sendEvent(R.string.error_blank_name)
                LocationRepository.MutationOutcome.DuplicateName ->
                    sendEvent(R.string.error_duplicate_name)
            }
        }
    }

    fun renameLocation(id: Long, name: String) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.renameLocation(id, name) }
            when (outcome) {
                LocationRepository.MutationOutcome.Success ->
                    sendEvent(R.string.location_saved)
                LocationRepository.MutationOutcome.BlankName ->
                    sendEvent(R.string.error_blank_name)
                LocationRepository.MutationOutcome.DuplicateName ->
                    sendEvent(R.string.error_duplicate_name)
            }
        }
    }

    fun deleteLocation(id: Long) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.deleteLocation(id) }
            when (outcome) {
                LocationRepository.DeleteOutcome.Success ->
                    sendEvent(R.string.location_deleted)
                LocationRepository.DeleteOutcome.HasBoxes ->
                    sendEvent(R.string.error_location_has_boxes)
            }
        }
    }

    /**
     * Reads a gallery-picked cover to bytes off the main thread and parks it
     * in [pendingCover] for the confirm dialog; a cancelled pick is a no-op
     * and an unreadable or empty one reports [R.string.error_invalid_image].
     */
    fun onCoverImagePicked(locationId: Long, uri: Uri?) {
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
                _pendingCover.value = PendingCoverUpload(locationId = locationId, bytes = bytes)
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
                withContext(Dispatchers.IO) { repository.setCoverImage(pending.locationId, pending.bytes) }
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

    /** Clears the location's cover photo. */
    fun removeCoverImage(locationId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setCoverImage(locationId, null) }
            sendEvent(R.string.cover_updated)
        }
    }

    private suspend fun sendEvent(@StringRes resId: Int) {
        _events.send(LocationsEvent.ShowMessage(resId))
    }
}
