package com.inventory.feature.containers

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.cache.CacheDao
import com.inventory.cache.CacheEntry
import com.inventory.cache.ChildCounts
import com.inventory.cache.InventoryScanner
import com.inventory.storage.InventoryStore
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presents one folder of the filesystem inventory — the storage root
 * ("Inventory", no navigation argument) or any container — and owns the
 * full CRUD surface: folders create/rename/move/delete, `_cover.jpg`
 * covers, and the photo queue (staged files → EXIF-stamped JPEGs), search
 * (here / whole subtree) and multi-select move/delete.
 *
 * Every action mutates DISK first through [InventoryStore], then asks
 * [InventoryScanner] to converge the cache; the live DAO flows do the
 * rest. The folder shown arrives as the (URL-decoded) navigation argument
 * `path`; the root destination has none.
 *
 * Captured/picked files waiting for confirmation live in [pendingQueue]
 * and [pendingCover] (kept out of [uiState] so `File`s never participate
 * in state equality); one-shot feedback lives in [events], and
 * mutations announced by other screens (camera, cover capture) arrive on
 * the app-level [UserMessageBus] and also trigger a rescan here.
 */
@HiltViewModel
class ContainerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: InventoryStore,
    private val dao: CacheDao,
    private val scanner: InventoryScanner,
    private val messageBus: UserMessageBus,
) : ViewModel() {

    /**
     * Absolute path of the folder on screen; the storage root when no
     * argument. The key is contractual with
     * [com.inventory.feature.navigation.Screens.ARG_PATH].
     */
    val folderPath: String = savedStateHandle["path"] ?: store.rootPath

    /** The folder on screen as a [File] (may not exist — e.g. just deleted). */
    val folder: File = File(folderPath)

    /** True when the screen shows the storage root itself. */
    val isRoot: Boolean = folderPath == store.rootPath

    /** Sizes of a subtree, gathered before the delete confirmation opens. */
    data class DeleteCandidate(
        val path: String,
        val name: String,
        val childContainers: Int,
        val childPhotos: Int,
    )

    data class ContainerUiState(
        val isRoot: Boolean = false,
        val containerName: String = "",
        val children: List<CacheEntry> = emptyList(),
        val childCounts: Map<String, ChildCounts> = emptyMap(),
        val photos: List<CacheEntry> = emptyList(),
        val searchQuery: String = "",
        val searchScopeHere: Boolean = true,
        val selectionMode: Boolean = false,
        val selectedPaths: Set<String> = emptySet(),
        val deleteCandidate: DeleteCandidate? = null,
        val hasCover: Boolean = false,
        val folderMissing: Boolean = false,
        val scanning: Boolean = false,
        val conflictCount: Int = 0,
        val isLoading: Boolean = true,
    )

    /** A gallery-picked cover waiting in the confirm dialog for its folder. */
    data class PendingCoverUpload(val folderPath: String, val file: File)

    /** One entry of the folder pickers: absolute path + "Building / Shelf" label. */
    data class FolderChoice(val path: String, val label: String)

    private val parentArg: String? = if (isRoot) null else folderPath

    private val _searchQuery = MutableStateFlow("")
    private val _searchScopeHere = MutableStateFlow(true)
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _deleteCandidate = MutableStateFlow<DeleteCandidate?>(null)
    private val _moveExcludedPaths = MutableStateFlow<Set<String>>(emptySet())

    private val childrenFlow: Flow<List<CacheEntry>> = dao.observeChildren(parentArg)

    private val countsFlow: Flow<Map<String, ChildCounts>> =
        dao.observeAllChildCounts().map { rows -> rows.associateBy(ChildCounts::path) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val photosFlow: Flow<List<CacheEntry>> = combine(
        _searchQuery,
        _searchScopeHere,
    ) { query, here -> query.trim() to here }
        .flatMapLatest { (query, here) ->
            if (here) {
                dao.observePhotosIn(parentArg, query)
            } else {
                dao.observePhotosUnder(folderPath, query)
            }
        }

    /** Transient combine slice: photo list plus the UI flows driving it. */
    private data class PhotoSlice(
        val photos: List<CacheEntry>,
        val query: String,
        val scopeHere: Boolean,
        val selected: Set<String>,
        val deleteCandidate: DeleteCandidate?,
    )

    private val photoSlice: Flow<PhotoSlice> = combine(
        photosFlow,
        _searchQuery,
        _searchScopeHere,
        _selectedPaths,
        _deleteCandidate,
    ) { photos, query, scope, selected, deleteCandidate ->
        PhotoSlice(photos, query, scope, selected, deleteCandidate)
    }

    /** Cover presence + folder survival, re-checked on every cache refresh. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val folderFactsFlow: Flow<FolderFacts> = childrenFlow
        .flatMapLatest {
            flow {
                emit(
                    FolderFacts(
                        hasCover = store.coverFile(folder) != null,
                        exists = folder.isDirectory,
                    ),
                )
            }
        }
        .flowOn(Dispatchers.IO)

    private data class FolderFacts(val hasCover: Boolean, val exists: Boolean)

    private val scanSlice = combine(
        scanner.scanState.map { it.scanning },
        dao.countConflicts(),
    ) { scanning, conflicts -> scanning to conflicts }

    val uiState: StateFlow<ContainerUiState> = combine(
        childrenFlow,
        countsFlow,
        photoSlice,
        folderFactsFlow,
        scanSlice,
    ) { children, counts, slice, facts, (scanning, conflicts) ->
        ContainerUiState(
            isRoot = isRoot,
            containerName = if (isRoot) "" else folder.name,
            children = children,
            childCounts = counts,
            photos = slice.photos,
            searchQuery = slice.query,
            searchScopeHere = slice.scopeHere,
            selectionMode = slice.selected.isNotEmpty(),
            selectedPaths = slice.selected,
            deleteCandidate = slice.deleteCandidate,
            hasCover = facts.hasCover,
            folderMissing = !isRoot && !facts.exists,
            scanning = scanning,
            conflictCount = conflicts,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ContainerUiState(isRoot = isRoot),
    )

    /**
     * Folders a container move may target: every indexed folder except the
     * subtree of the folder being moved ([requestMove] refreshes the
     * excluded set when a move sheet opens), so a cycle can never even be
     * tapped.
     */
    val movableTargets: StateFlow<List<FolderChoice>> = combine(
        dao.observeAllFolders(),
        _moveExcludedPaths,
    ) { folders, excluded ->
        folders.filterNot { it.path in excluded }.map { it.toFolderChoice() }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * Destinations for the photo-move picker: every folder except the one
     * currently open. Photos may move into descendants — the cycle rule
     * only applies to containers.
     */
    val photoMoveTargets: StateFlow<List<FolderChoice>> = dao.observeAllFolders()
        .map { folders -> folders.filterNot { it.path == folderPath }.map { it.toFolderChoice() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Staged images captured or picked but still lacking their mandatory
     * description; the save sheet edits [List.first] and pops it on
     * success. Cancelling the sheet deletes every staged file.
     */
    private val _pendingQueue = MutableStateFlow<List<File>>(emptyList())
    val pendingQueue: StateFlow<List<File>> = _pendingQueue.asStateFlow()

    private val _pendingCover = MutableStateFlow<PendingCoverUpload?>(null)

    /** The picked cover awaiting confirmation in the preview dialog, or null. */
    val pendingCover: StateFlow<PendingCoverUpload?> = _pendingCover.asStateFlow()

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface ContainerEvent {
        data class ShowMessage(
            @StringRes val resId: Int,
            val formatArgs: List<Any> = emptyList(),
        ) : ContainerEvent

        /** The folder on screen was renamed/moved/deleted: ascend to its parent. */
        data object PopBackStack : ContainerEvent
    }

    private val _events = Channel<ContainerEvent>(Channel.BUFFERED)
    val events: Flow<ContainerEvent> = _events.receiveAsFlow()

    init {
        // Non-root screens can scan on entry straight away; the root screen
        // waits for the storage-permission gate to call [onStorageReady].
        if (!isRoot) refresh()
        // Cross-screen announcements (camera save, cover capture, the
        // pop-after-rename of a child screen) arrive on the app-level bus;
        // this ViewModel outlives those screens in the back stack, and every
        // mutation message also drives a rescan so the shared cache — and
        // every live query in it — converges.
        viewModelScope.launch {
            messageBus.messages.collect { resId ->
                sendEvent(resId)
                if (resId in RESCAN_MESSAGES) scanner.rescan()
            }
        }
    }

    /** The screen's permission gate opened: create the root and index it. */
    fun onStorageReady() = refresh()

    /** Ensure the root exists, then index the filesystem (Mutex-coalesced). */
    fun refresh() {
        viewModelScope.launch {
            if (store.ensureRoot()) scanner.rescan()
        }
    }

    /** Overflow "Rescan storage": manual sync with whatever a file manager did. */
    fun rescanNow() {
        viewModelScope.launch {
            store.ensureRoot()
            scanner.rescan()
            sendEvent(R.string.rescanned)
        }
    }

    // region containers

    /** Creates a folder under the one on screen (top level at the root). */
    fun createContainer(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_name) }
            return
        }
        viewModelScope.launch {
            store.createFolder(folder, name)
            scanner.rescan()
            sendEvent(R.string.container_created)
        }
    }

    /**
     * Renames the folder at [path] (the screen's own or a child row). A
     * rename of the folder on screen invalidates this route: the message
     * goes out on the bus (the parent screen shows it) and this screen
     * pops itself.
     */
    fun renameFolder(path: String, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_name) }
            return
        }
        val isSelf = path == folderPath
        viewModelScope.launch {
            store.renameFolder(File(path), name)
            scanner.rescan()
            if (isSelf) {
                messageBus.post(R.string.container_saved)
                _events.send(ContainerEvent.PopBackStack)
            } else {
                sendEvent(R.string.container_saved)
            }
        }
    }

    /** Refreshes [movableTargets] for the folder whose move sheet opens. */
    fun requestMove(path: String) {
        viewModelScope.launch {
            _moveExcludedPaths.value = store.descendantFolderPaths(File(path)).toSet()
        }
    }

    /** Moves the folder at [sourcePath] into the folder at [targetFolderPath]. */
    fun moveFolderTo(sourcePath: String, targetFolderPath: String) {
        val isSelf = sourcePath == folderPath
        viewModelScope.launch {
            when (val result = store.moveFolder(File(sourcePath), File(targetFolderPath))) {
                is InventoryStore.MoveResult.Success -> {
                    scanner.rescan()
                    if (isSelf) {
                        messageBus.post(R.string.container_moved)
                        _events.send(ContainerEvent.PopBackStack)
                    } else {
                        sendEvent(R.string.container_moved)
                    }
                }
                InventoryStore.MoveResult.Failed ->
                    sendEvent(R.string.error_invalid_move_target)
            }
        }
    }

    /** Loads the subtree sizes of [path] into the state's delete candidate. */
    fun requestDelete(path: String, name: String) {
        viewModelScope.launch {
            val (containers, photos) = store.subtreeCounts(File(path))
            _deleteCandidate.value = DeleteCandidate(
                path = path,
                name = name,
                childContainers = containers,
                childPhotos = photos,
            )
        }
    }

    /** Deletes the confirmed folder with its whole subtree. */
    fun confirmDelete() {
        val candidate = _deleteCandidate.value ?: return
        _deleteCandidate.value = null
        val isSelf = candidate.path == folderPath
        viewModelScope.launch {
            store.deleteFolder(File(candidate.path))
            scanner.rescan()
            if (isSelf) {
                messageBus.post(R.string.container_deleted)
                _events.send(ContainerEvent.PopBackStack)
            } else {
                sendEvent(R.string.container_deleted)
            }
        }
    }

    fun dismissDelete() {
        _deleteCandidate.value = null
    }

    /**
     * Reads a gallery-picked cover into a staged file off the main thread
     * and parks it in [pendingCover] for the confirm dialog; a cancelled
     * pick is a no-op and an unreadable one reports [R.string.error_invalid_image].
     */
    fun onCoverImagePicked(coverFolderPath: String, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val staged = store.stageUri(uri)
            if (staged == null) {
                sendEvent(R.string.error_invalid_image)
            } else {
                _pendingCover.value = PendingCoverUpload(coverFolderPath, staged)
            }
        }
    }

    /**
     * Stores the pending cover as `_cover.jpg` in its folder; the row
     * refreshes through the cache. A non-JPEG pick or a failed rename
     * surfaces as [R.string.error_invalid_image]; the staged file is
     * cleared either way.
     */
    fun confirmCoverUpload() {
        val pending = _pendingCover.value ?: return
        viewModelScope.launch {
            try {
                store.setCover(File(pending.folderPath), pending.file)
                _pendingCover.value = null
                scanner.rescan()
                sendEvent(R.string.cover_updated)
            } catch (exception: IllegalArgumentException) {
                discardStaged(pending.file)
                _pendingCover.value = null
                sendEvent(R.string.error_invalid_image)
            } catch (exception: IOException) {
                discardStaged(pending.file)
                _pendingCover.value = null
                sendEvent(R.string.error_invalid_image)
            }
        }
    }

    /** Throws away the pending cover without storing anything. */
    fun discardCoverUpload() {
        val pending = _pendingCover.value ?: return
        _pendingCover.value = null
        discardStaged(pending.file)
    }

    /** Deletes the cover file of the folder at [path]. */
    fun removeCoverImage(path: String) {
        viewModelScope.launch {
            store.removeCover(File(path))
            scanner.rescan()
            sendEvent(R.string.cover_updated)
        }
    }

    // endregion

    // region photos

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        clearSelection()
    }

    /** Switches the in-container search scope: this folder only / whole subtree. */
    fun onScopeChange(here: Boolean) {
        _searchScopeHere.value = here
    }

    fun enterSelectionMode(path: String) {
        _selectedPaths.value = setOf(path)
    }

    fun toggleSelection(path: String) {
        _selectedPaths.update { current ->
            if (path in current) current - path else current + path
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    /**
     * Copies gallery [uris] into staged files (byte-identical — the
     * originals stay in the gallery) and feeds them to the save queue;
     * unreadable or empty results are dropped.
     */
    fun onGalleryUrisPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val staged = uris.mapNotNull { uri -> store.stageUri(uri) }
            onImagesPicked(staged)
        }
    }

    fun onImagesPicked(images: List<File>) {
        if (images.isEmpty()) return
        _pendingQueue.value = images
    }

    /**
     * Saves the queue head into the folder on screen with its (required)
     * description. Success pops the head (the staged file was promoted to
     * a permanent JPEG) and rescans; an undecodable head is dropped with
     * an error so a broken image cannot stall the queue.
     */
    fun savePendingPhoto(rawDescription: String, rawNote: String?) {
        val head = _pendingQueue.value.firstOrNull() ?: return
        val description = rawDescription.trim()
        if (description.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_description) }
            return
        }
        viewModelScope.launch {
            when (
                val outcome = store.addPhoto(
                    folder = folder,
                    staged = head,
                    description = description,
                    note = rawNote?.trim()?.ifEmpty { null },
                )
            ) {
                InventoryStore.AddOutcome.Success -> {
                    _pendingQueue.update { queue -> queue.drop(1) }
                    scanner.rescan()
                    sendEvent(R.string.photo_added)
                }
                InventoryStore.AddOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
                InventoryStore.AddOutcome.InvalidImage -> {
                    discardStaged(head)
                    _pendingQueue.update { queue -> queue.drop(1) }
                    sendEvent(R.string.error_invalid_image)
                }
            }
        }
    }

    fun discardPendingPhotos() {
        val queue = _pendingQueue.value
        _pendingQueue.value = emptyList()
        viewModelScope.launch {
            queue.forEach { file -> store.unstage(file) }
        }
    }

    fun deletePhoto(path: String) {
        viewModelScope.launch {
            store.deletePhoto(File(path))
            scanner.rescan()
            _selectedPaths.update { current -> current - path }
            sendEvent(R.string.photo_deleted)
        }
    }

    /** Deletes every selected photo file; the confirmation itself lives in the UI. */
    fun deleteSelected() {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            paths.forEach { path -> store.deletePhoto(File(path)) }
            scanner.rescan()
            clearSelection()
            sendEvent(R.string.photo_deleted)
        }
    }

    /** Moves every selected photo into [targetFolderPath]; confirmation is in the UI. */
    fun moveSelected(targetFolderPath: String) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val moved = store.movePhotos(paths.map(::File), File(targetFolderPath))
            scanner.rescan()
            clearSelection()
            sendEvent(R.string.photos_moved, listOf(moved))
        }
    }

    /**
     * Rewrites a photo's EXIF description/note — and renames the file when
     * the sanitized description differs from the current name. The tile
     * updates through the rescan.
     */
    fun updatePhotoDetails(entry: CacheEntry, rawDescription: String, rawNote: String?) {
        val description = rawDescription.trim()
        if (description.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_description) }
            return
        }
        viewModelScope.launch {
            when (
                store.updatePhotoMetadata(File(entry.path), description, rawNote?.trim()?.ifEmpty { null })
            ) {
                InventoryStore.UpdateOutcome.Success -> {
                    scanner.rescan()
                    sendEvent(R.string.photo_saved)
                }
                InventoryStore.UpdateOutcome.BlankDescription ->
                    sendEvent(R.string.error_blank_description)
                InventoryStore.UpdateOutcome.InvalidImage ->
                    sendEvent(R.string.error_invalid_image)
            }
        }
    }

    // endregion

    private fun CacheEntry.toFolderChoice(): FolderChoice = FolderChoice(
        path = path,
        label = displayLabel(path),
    )

    /** "/root/Building/Shelf A" → "Building / Shelf A" (relative to the storage root). */
    private fun displayLabel(path: String): String =
        path.removePrefix(store.rootPath).trim('/').split('/').filter(String::isNotBlank)
            .joinToString(" / ").ifEmpty { path }

    private fun discardStaged(file: File) {
        viewModelScope.launch { store.unstage(file) }
    }

    private suspend fun sendEvent(@StringRes resId: Int, formatArgs: List<Any> = emptyList()) {
        _events.send(ContainerEvent.ShowMessage(resId, formatArgs))
    }

    /** Mutation announcements that mean "the disk changed somewhere; re-index". */
    private companion object {
        val RESCAN_MESSAGES = setOf(
            R.string.photo_added,
            R.string.cover_updated,
            R.string.container_saved,
            R.string.container_moved,
            R.string.container_deleted,
        )
    }
}
