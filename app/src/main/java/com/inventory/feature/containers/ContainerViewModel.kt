package com.inventory.feature.containers

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.database.ContainerEntity
import com.inventory.database.ContainerPath
import com.inventory.database.ContainerWithCounts
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presents one node of the container tree — the virtual root ("Inventory",
 * nodeId 0) or any container — and exposes the full CRUD surface of the old
 * Locations, Boxes and Photos ViewModels merged: child containers
 * create/rename/delete/move with cycle-safe pickers, per-container cover
 * photos, and inside a container the photo search, multi-select
 * move/delete, the mandatory-metadata save queue and single-photo edits.
 *
 * The node id arrives as the navigation argument `nodeId`; the home
 * destination has no arguments and defaults to the root.
 *
 * Data lives in [uiState]; captured/picked bytes waiting for confirmation
 * live in [pendingQueue] and [pendingCover] (kept out of [uiState] so large
 * arrays never participate in state equality); one-shot feedback lives in
 * [events] so it survives configuration changes without being re-shown.
 */
@HiltViewModel
class ContainerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ContainerRepository,
    private val messageBus: UserMessageBus,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Node being shown; 0 = the virtual root (home). */
    val nodeId: Long = savedStateHandle["nodeId"] ?: 0L

    /** Sizes of a subtree, gathered before the delete confirmation opens. */
    data class DeleteCandidate(
        val id: Long,
        val name: String,
        val childContainers: Int,
        val childPhotos: Int,
    )

    data class ContainerUiState(
        val container: ContainerEntity? = null,
        val path: List<String> = emptyList(),
        val children: List<ContainerWithCounts> = emptyList(),
        val photos: List<PhotoEntity> = emptyList(),
        val searchQuery: String = "",
        val selectionMode: Boolean = false,
        val selectedPhotoIds: Set<Long> = emptySet(),
        val deleteCandidate: DeleteCandidate? = null,
        val isLoading: Boolean = true,
    )

    /** A cover image read from the gallery, waiting for the container it belongs to. */
    data class PendingCoverUpload(val containerId: Long, val bytes: ByteArray)

    private val _searchQuery = MutableStateFlow("")
    private val _selectedPhotoIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _deleteCandidate = MutableStateFlow<DeleteCandidate?>(null)

    /** The root has no row and no ancestors: constant, empty flows. */
    private val containerFlow: Flow<ContainerEntity?> =
        if (nodeId == 0L) flowOf(null) else repository.observeContainer(nodeId)

    private val pathFlow: Flow<List<String>> =
        if (nodeId == 0L) flowOf(emptyList()) else repository.observePath(nodeId)

    /** Transient combine slice: photo list plus the UI flows driving it. */
    private data class PhotoSlice(
        val photos: List<PhotoEntity>,
        val query: String,
        val selected: Set<Long>,
        val deleteCandidate: DeleteCandidate?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val photoSlice: Flow<PhotoSlice> = combine(
        _searchQuery.flatMapLatest { query ->
            repository.observePhotosForContainer(nodeId, query)
        },
        _searchQuery,
        _selectedPhotoIds,
        _deleteCandidate,
    ) { photos, query, selected, deleteCandidate ->
        PhotoSlice(photos, query, selected, deleteCandidate)
    }

    val uiState: StateFlow<ContainerUiState> = combine(
        containerFlow,
        pathFlow,
        repository.observeChildren(if (nodeId == 0L) null else nodeId),
        photoSlice,
    ) { container, path, children, slice ->
        ContainerUiState(
            container = container,
            path = path,
            children = children,
            photos = slice.photos,
            searchQuery = slice.query,
            selectionMode = slice.selected.isNotEmpty(),
            selectedPhotoIds = slice.selected,
            deleteCandidate = slice.deleteCandidate,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ContainerUiState(),
    )

    /**
     * Containers a move may target: every path except the subtree of the
     * container being moved; [requestMove] refreshes the excluded set when
     * a move sheet opens (for the screen's own container or a child row).
     */
    private val _moveExcludedIds = MutableStateFlow<Set<Long>>(emptySet())

    val movableTargets: StateFlow<List<ContainerPath>> = combine(
        repository.observeAllPaths(),
        _moveExcludedIds,
    ) { paths, excluded -> paths.filterNot { it.id in excluded } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Destinations for the photo-move picker: every path except the
     * container currently open. Photos may move into descendants — the
     * cycle rule only applies to containers.
     */
    val photoMoveTargets: StateFlow<List<ContainerPath>> = repository.observeAllPaths()
        .map { paths -> paths.filterNot { it.id == nodeId } }
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

    private val _pendingCover = MutableStateFlow<PendingCoverUpload?>(null)

    /** The picked cover awaiting confirmation in the preview dialog, or null. */
    val pendingCover: StateFlow<PendingCoverUpload?> = _pendingCover.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface ContainerEvent {
        data class ShowMessage(
            @StringRes val resId: Int,
            val formatArgs: List<Any> = emptyList(),
        ) : ContainerEvent
    }

    private val _events = Channel<ContainerEvent>(Channel.BUFFERED)
    val events: Flow<ContainerEvent> = _events.receiveAsFlow()

    init {
        // Cross-screen announcements (e.g. the camera save) arrive on the
        // app-level bus; this ViewModel outlives the camera screen in the
        // back stack, and the buffered channel delivers them to the snackbar
        // once the grid recomposes after the pop.
        viewModelScope.launch {
            messageBus.messages.collect { sendEvent(it) }
        }
    }

    // region containers

    /** Creates a container under the node on screen (top level at root). */
    fun createContainer(name: String) {
        val parentId = if (nodeId == 0L) null else nodeId
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repository.createContainer(parentId, name)
            }
            when (outcome) {
                ContainerRepository.MutationOutcome.Success ->
                    sendEvent(R.string.container_created)
                ContainerRepository.MutationOutcome.BlankName ->
                    sendEvent(R.string.error_blank_name)
                ContainerRepository.MutationOutcome.DuplicateName ->
                    sendEvent(R.string.error_duplicate_name)
            }
        }
    }

    fun renameContainer(id: Long, name: String) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.renameContainer(id, name) }
            when (outcome) {
                ContainerRepository.MutationOutcome.Success ->
                    sendEvent(R.string.container_saved)
                ContainerRepository.MutationOutcome.BlankName ->
                    sendEvent(R.string.error_blank_name)
                ContainerRepository.MutationOutcome.DuplicateName ->
                    sendEvent(R.string.error_duplicate_name)
            }
        }
    }

    /** Loads [containerId]'s subtree sizes into the state's delete candidate. */
    fun requestDelete(containerId: Long, name: String) {
        viewModelScope.launch {
            val (containers, photos) = withContext(Dispatchers.IO) {
                repository.subtreeCounts(containerId)
            }
            _deleteCandidate.value = DeleteCandidate(
                id = containerId,
                name = name,
                childContainers = containers,
                childPhotos = photos,
            )
        }
    }

    /** Deletes the confirmed container; the FK cascades its whole subtree. */
    fun confirmDelete() {
        val candidate = _deleteCandidate.value ?: return
        _deleteCandidate.value = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.deleteContainer(candidate.id) }
            sendEvent(R.string.container_deleted)
        }
    }

    fun dismissDelete() {
        _deleteCandidate.value = null
    }

    /**
     * Refreshes [movableTargets] for the container whose move sheet is about
     * to open: the container itself and its whole subtree vanish from the
     * picker, so a cycle can never even be tapped.
     */
    fun requestMove(containerId: Long) {
        viewModelScope.launch {
            _moveExcludedIds.value = withContext(Dispatchers.IO) {
                repository.excludedIdsForMoves(containerId)
            }
        }
    }

    /** Moves container [containerId] (the open node or a child row) into [targetId]. */
    fun moveContainerTo(containerId: Long, targetId: Long) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repository.moveContainer(containerId, targetId)
            }
            when (outcome) {
                ContainerRepository.MoveOutcome.Success -> sendEvent(R.string.container_moved)
                ContainerRepository.MoveOutcome.InvalidTarget ->
                    sendEvent(R.string.error_invalid_move_target)
            }
        }
    }

    /**
     * Reads a gallery-picked cover to bytes off the main thread and parks it
     * in [pendingCover] for the confirm dialog; a cancelled pick is a no-op
     * and an unreadable or empty one reports [R.string.error_invalid_image].
     */
    fun onCoverImagePicked(containerId: Long, uri: Uri?) {
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
                _pendingCover.value = PendingCoverUpload(containerId = containerId, bytes = bytes)
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
                withContext(Dispatchers.IO) {
                    repository.setCoverImage(pending.containerId, pending.bytes)
                }
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

    /** Clears [containerId]'s cover photo. */
    fun removeCoverImage(containerId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setCoverImage(containerId, null) }
            sendEvent(R.string.cover_updated)
        }
    }

    // endregion

    // region photos

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
                repository.addPhoto(nodeId, head, rawDescription, rawNote)
            }
            when (outcome) {
                ContainerRepository.AddOutcome.Success -> {
                    _pendingQueue.update { queue -> queue.drop(1) }
                    sendEvent(R.string.photo_added)
                }
                ContainerRepository.AddOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
                ContainerRepository.AddOutcome.InvalidImage -> {
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

    /** Moves every selected photo to [targetContainerId]; confirmation is in the UI. */
    fun moveSelected(targetContainerId: Long) {
        val ids = _selectedPhotoIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.movePhotos(ids, targetContainerId) }
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
                ContainerRepository.UpdateOutcome.Success ->
                    sendEvent(R.string.photo_saved)
                ContainerRepository.UpdateOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
            }
        }
    }

    // endregion

    private suspend fun sendEvent(@StringRes resId: Int, formatArgs: List<Any> = emptyList()) {
        _events.send(ContainerEvent.ShowMessage(resId, formatArgs))
    }
}
