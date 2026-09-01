package com.inventory.feature.locations

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.database.LocationWithBoxes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presents the location list and exposes create/rename/delete actions.
 *
 * Data lives in [uiState]; one-shot user feedback (snackbars) lives in
 * [events] so it survives configuration changes without being re-shown.
 */
@HiltViewModel
class LocationsViewModel @Inject constructor(
    private val repository: LocationRepository,
) : ViewModel() {

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

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface LocationsEvent {
        data class ShowMessage(@StringRes val resId: Int) : LocationsEvent
    }

    private val _events = Channel<LocationsEvent>(Channel.BUFFERED)
    val events: Flow<LocationsEvent> = _events.receiveAsFlow()

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

    private suspend fun sendEvent(@StringRes resId: Int) {
        _events.send(LocationsEvent.ShowMessage(resId))
    }
}
