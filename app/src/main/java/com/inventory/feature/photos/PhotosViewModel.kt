package com.inventory.feature.photos

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.database.BoxEntity
import com.inventory.database.PhotoEntity
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presents the photos of one box: search, multi-select move/delete, the
 * mandatory-metadata save queue fed by the gallery picker, and single-photo
 * detail edits. The parent box id arrives as the navigation argument `boxId`.
 *
 * Data lives in [uiState]; the captured/picked images waiting for a
 * description live in [pendingQueue] (kept out of [uiState] so large byte
 * arrays never participate in state equality); one-shot feedback lives in
 * [events] so it survives configuration changes without being re-shown.
 */
@HiltViewModel
class PhotosViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PhotoRepository,
    @ApplicationContext private val context: Context,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /** Id of the box whose photos are shown; supplied by the nav route. */
    val boxId: Long = checkNotNull(savedStateHandle["boxId"])

    data class PhotosUiState(
        val box: BoxEntity? = null,
        val photos: List<PhotoEntity> = emptyList(),
        val searchQuery: String = "",
        val selectionMode: Boolean = false,
        val selectedPhotoIds: Set<Long> = emptySet(),
        val isLoading: Boolean = true,
    )

    private val _searchQuery = MutableStateFlow("")
    private val _selectedPhotoIds = MutableStateFlow<Set<Long>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<PhotosUiState> = combine(
        repository.observeBox(boxId),
        _searchQuery.flatMapLatest { query -> repository.observePhotos(boxId, query) },
        _searchQuery,
        _selectedPhotoIds,
    ) { box, photos, query, selectedIds ->
        PhotosUiState(
            box = box,
            photos = photos,
            searchQuery = query,
            selectionMode = selectedIds.isNotEmpty(),
            selectedPhotoIds = selectedIds,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PhotosUiState(),
    )

    /**
     * Every box except the one currently open — the candidates for the
     * move-to-box picker. Filtering here keeps the picker honest even while
     * [uiState] is still loading.
     */
    val otherBoxes: StateFlow<List<BoxEntity>> = repository.observeAllBoxes()
        .map { boxes -> boxes.filterNot { it.id == boxId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Images captured or picked but still lacking their mandatory
     * description; the save sheet edits [List.first] and pops it on success.
     * Cancelling the sheet discards the whole queue.
     */
    private val _pendingQueue = MutableStateFlow<List<ByteArray>>(emptyList())
    val pendingQueue: StateFlow<List<ByteArray>> = _pendingQueue

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface PhotosEvent {
        data class ShowMessage(
            @StringRes val resId: Int,
            val formatArgs: List<Any> = emptyList(),
        ) : PhotosEvent
    }

    private val _events = Channel<PhotosEvent>(Channel.BUFFERED)
    val events: Flow<PhotosEvent> = _events.receiveAsFlow()

    init {
        // Cross-screen announcements (e.g. the camera save) arrive on the
        // app-level bus; this ViewModel outlives the camera screen in the
        // back stack, and the buffered channel delivers them to the snackbar
        // once the grid recomposes after the pop.
        viewModelScope.launch {
            messageBus.messages.collect { sendEvent(it) }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        clearSelection()
    }

    fun enterSelectionMode(id: Long) {
        _selectedPhotoIds.value = setOf(id)
    }

    fun toggleSelection(id: Long) {
        _selectedPhotoIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun clearSelection() {
        _selectedPhotoIds.value = emptySet()
    }

    /**
     * Reads gallery [uris] to bytes off the main thread and feeds them to
     * [onImagesPicked]; unreadable or empty results are dropped.
     */
    fun onGalleryUrisPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }.filter { it.isNotEmpty() }
            }
            onImagesPicked(images)
        }
    }

    fun onImagesPicked(images: List<ByteArray>) {
        if (images.isEmpty()) return
        _pendingQueue.value = images
    }

    /**
     * Saves the queue head with its (required) description. Success pops the
     * head so the sheet advances to the next image; an undecodable head is
     * dropped with an error so a broken image cannot stall the queue.
     */
    fun savePendingPhoto(rawDescription: String, rawNote: String?) {
        val head = _pendingQueue.value.firstOrNull() ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repository.addPhoto(boxId, head, rawDescription, rawNote)
            }
            when (outcome) {
                PhotoRepository.AddOutcome.Success -> {
                    _pendingQueue.update { queue -> queue.drop(1) }
                    sendEvent(R.string.photo_added)
                }
                PhotoRepository.AddOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
                PhotoRepository.AddOutcome.InvalidImage -> {
                    _pendingQueue.update { queue -> queue.drop(1) }
                    sendEvent(R.string.error_invalid_image)
                }
            }
        }
    }

    fun discardPendingPhotos() {
        _pendingQueue.value = emptyList()
    }

    fun deletePhoto(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deletePhoto(id) }
            _selectedPhotoIds.update { current -> current - id }
            sendEvent(R.string.photo_deleted)
        }
    }

    /** Deletes every selected photo; the confirmation itself lives in the UI. */
    fun deleteSelected() {
        val ids = _selectedPhotoIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ids.forEach { repository.deletePhoto(it) }
            }
            clearSelection()
            sendEvent(R.string.photo_deleted)
        }
    }

    /** Moves every selected photo to [targetBoxId]; confirmation is in the UI. */
    fun moveSelected(targetBoxId: Long) {
        val ids = _selectedPhotoIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.movePhotos(ids, targetBoxId) }
            clearSelection()
            sendEvent(R.string.photos_moved, listOf(ids.size))
        }
    }

    fun updatePhotoDetails(id: Long, rawDescription: String, rawNote: String?) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repository.updatePhotoDetails(id, rawDescription, rawNote)
            }
            when (outcome) {
                PhotoRepository.UpdateOutcome.Success ->
                    sendEvent(R.string.photo_saved)
                PhotoRepository.UpdateOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
            }
        }
    }

    private suspend fun sendEvent(@StringRes resId: Int, formatArgs: List<Any> = emptyList()) {
        _events.send(PhotosEvent.ShowMessage(resId, formatArgs))
    }
}
