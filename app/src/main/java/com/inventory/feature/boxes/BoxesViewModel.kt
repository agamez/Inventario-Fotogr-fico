package com.inventory.feature.boxes

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.database.BoxWithPhotoCount
import com.inventory.database.LocationEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presents the boxes of one location and exposes create/rename/delete/move
 * actions. The parent location id arrives as the navigation argument
 * `locationId`.
 *
 * Data lives in [uiState]; one-shot user feedback (snackbars) lives in
 * [events] so it survives configuration changes without being re-shown.
 */
@HiltViewModel
class BoxesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BoxRepository,
) : ViewModel() {

    /** Id of the location whose boxes are shown; supplied by the nav route. */
    val locationId: Long = checkNotNull(savedStateHandle["locationId"])

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

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface BoxesEvent {
        data class ShowMessage(@StringRes val resId: Int) : BoxesEvent
    }

    private val _events = Channel<BoxesEvent>(Channel.BUFFERED)
    val events: Flow<BoxesEvent> = _events.receiveAsFlow()

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

    private suspend fun sendEvent(@StringRes resId: Int) {
        _events.send(BoxesEvent.ShowMessage(resId))
    }
}
