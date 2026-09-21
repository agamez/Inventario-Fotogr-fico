package com.inventory.feature.containers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.R
import com.inventory.ai.VisionClient
import com.inventory.ai.VisionOutcome
import com.inventory.ai.VisionPrefs
import com.inventory.cache.CacheDao
import com.inventory.cache.CacheEntry
import com.inventory.cache.ChildCounts
import com.inventory.cache.InventoryScanner
import com.inventory.storage.InventoryStore
import com.inventory.storage.StorageMode
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
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
 * (relative path "", shown at the "inventory" destination) or any
 * container (navigation argument `path` = the RELATIVE folder path) — and
 * owns the full CRUD surface: folders create/rename/move/delete,
 * `_cover.jpg` covers, and the photo queue (staged BYTES → EXIF-stamped
 * JPEGs), search (here / whole subtree) and multi-select move/delete.
 *
 * Every action mutates DISK first through [InventoryStore] (which routes
 * to the active backend — SAF tree or direct File access), then asks
 * [InventoryScanner] to converge the cache; the live DAO flows do the
 * rest.
 *
 * [storageStatus] drives the root gate: a null mode shows the chooser
 * ("Pick a folder" or "All files access"), an unready mode shows the
 * re-grant/re-pick gate. Staged images awaiting their mandatory
 * description live in [pendingQueue] and [pendingCover] (kept out of
 * [uiState] so heavy byte arrays never participate in state equality);
 * one-shot feedback lives in [events], and mutations announced by other
 * screens (camera, cover capture) arrive on the app-level
 * [UserMessageBus] and also trigger a rescan here.
 */
@HiltViewModel
class ContainerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: InventoryStore,
    private val dao: CacheDao,
    private val scanner: InventoryScanner,
    private val messageBus: UserMessageBus,
    private val visionPrefs: VisionPrefs,
) : ViewModel() {

    /**
     * Relative path of the folder on screen ("" = storage root); the key
     * is contractual with [com.inventory.feature.navigation.Screens.ARG_PATH].
     */
    val folderRel: String = savedStateHandle["path"] ?: ""

    /** True when the screen shows the storage root itself. */
    val isRoot: Boolean = folderRel.isEmpty()

    /** Sizes of a subtree, gathered before the delete confirmation opens. */
    data class DeleteCandidate(
        val relPath: String,
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

    /** A gallery-picked cover (staged bytes) waiting in the confirm dialog. */
    data class PendingCoverUpload(val folderRel: String, val bytes: ByteArray)

    /** One entry of the folder pickers: relative path + "Building / Shelf" label. */
    data class FolderChoice(val relPath: String, val label: String)

    /** The active backend and whether it can currently reach the tree. */
    data class StorageStatus(val mode: StorageMode?, val ready: Boolean)

    private val _storageStatus = MutableStateFlow(StorageStatus(store.activeMode, store.hasAccess()))

    /** Root-gate state, re-checked on resume and after every mode change. */
    val storageStatus: StateFlow<StorageStatus> = _storageStatus.asStateFlow()

    private val parentArg: String? = if (isRoot) null else folderRel

    private val _searchQuery = MutableStateFlow("")
    private val _searchScopeHere = MutableStateFlow(true)
    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _deleteCandidate = MutableStateFlow<DeleteCandidate?>(null)
    private val _moveExcludedPaths = MutableStateFlow<Set<String>>(emptySet())

    private val childrenFlow: Flow<List<CacheEntry>> = dao.observeChildren(parentArg)

    private val countsFlow: Flow<Map<String, ChildCounts>> =
        dao.observeAllChildCounts().map { rows -> rows.associateBy(ChildCounts::relPath) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val photosFlow: Flow<List<CacheEntry>> = combine(
        _searchQuery,
        _searchScopeHere,
    ) { query, here -> query.trim() to here }
        .flatMapLatest { (query, here) ->
            if (here) {
                dao.observePhotosIn(parentArg, query)
            } else {
                dao.observePhotosUnder(folderRel, query)
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
                        hasCover = store.hasCover(folderRel),
                        exists = store.exists(folderRel),
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
            containerName = folderRel.substringAfterLast('/'),
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
        folders.filterNot { it.relPath in excluded }.map { it.toFolderChoice() }
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
        .map { folders ->
            folders.filterNot { it.relPath == folderRel }.map { it.toFolderChoice() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * One staged gallery image with a stable [id]: the save sheet edits the
     * head and pops it on success, and the optional AI suggestion is keyed
     * by the id so a reply that lands after the queue moved on is simply
     * ignored. Cancelling drops the whole queue (bytes live in memory only
     * — nothing to clean up on disk).
     */
    data class PendingPhoto(val id: Long, val bytes: ByteArray)

    private val pendingIdCounter = AtomicLong(0)

    private val _pendingQueue = MutableStateFlow<List<PendingPhoto>>(emptyList())
    val pendingQueue: StateFlow<List<PendingPhoto>> = _pendingQueue.asStateFlow()

    private val _aiSuggestions = MutableStateFlow<Map<Long, VisionOutcome>>(emptyMap())

    /**
     * AI replies for queued photos, keyed by [PendingPhoto.id]. Cleared
     * with the queue; an in-flight reply for a photo that is no longer
     * queued is dropped.
     */
    val aiSuggestions: StateFlow<Map<Long, VisionOutcome>> = _aiSuggestions.asStateFlow()

    /**
     * True while the feature is on AND a model is configured — the save
     * dialog only shows AI status when calls can actually happen.
     */
    val aiEnabled: StateFlow<Boolean> = combine(
        visionPrefs.enabled,
        visionPrefs.model,
    ) { enabled, model -> enabled && model.isNotBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = visionPrefs.isActive(),
        )

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
        // waits for the storage gate to call [onStorageReady].
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

    /** The screen's storage gate opened: create the root and index it. */
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

    // region storage mode

    /** Re-reads the gate state (resume, after grants/picks); SAF also checks the tree. */
    fun recheckStorage() {
        viewModelScope.launch {
            _storageStatus.value =
                StorageStatus(store.activeMode, store.hasAccess() && store.exists(""))
        }
    }

    /** The `ACTION_OPEN_DOCUMENT_TREE` chooser intent (opens at PhotoInventory). */
    fun safPickIntent(context: Context): Intent = store.safPickIntent(context)

    /** The All-Files-Access settings intent (null below API 30 — runtime pair there). */
    fun fileModeIntent(context: Context): Intent? = store.fileModeIntent(context)

    /**
     * A tree uri came back from the folder picker. Takes the persistable
     * grant, activates SAF mode, wipes the (foreign-mode) cache and lets
     * the gate's ready-flip effect rescan; when a mode had already been
     * chosen this was a switch, confirmed via [R.string.storage_mode_changed].
     */
    fun onTreePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val wasChosen = store.activeMode != null
            if (!store.setTreeUri(uri)) return@launch
            dao.clear()
            recheckStorage()
            // Ready may already have been true (mode switch FROM file): the
            // gate's effect only fires on flips, so index directly too.
            refresh()
            if (wasChosen) sendEvent(R.string.storage_mode_changed)
        }
    }

    /**
     * "All files access" was chosen (first launch or mode switch):
     * activates File mode and wipes the SAF-keyed cache immediately — the
     * index refills through the gate as soon as the system grant lands
     * (the screen re-checks on resume). A switch (a mode was already
     * chosen) additionally confirms via [R.string.storage_mode_changed].
     */
    fun switchToFileMode() {
        viewModelScope.launch {
            val wasChosen = store.activeMode != null
            store.setMode(StorageMode.FILE)
            dao.clear()
            recheckStorage()
            // As in onTreePicked: with the grant already in place the ready
            // bit never flips, so index directly (guarded scanOnce no-ops
            // while access is missing).
            refresh()
            if (wasChosen) sendEvent(R.string.storage_mode_changed)
        }
    }

    // endregion

    /** Resolves a folder's `_cover.jpg` as a Coil model for the active backend. */
    suspend fun coverModel(relPath: String): Any? = store.coverModel(relPath)

    /** Resolves a photo file as a Coil model for the active backend. */
    suspend fun photoModel(relPath: String): Any? = store.photoModel(relPath)

    // region containers

    /** Creates a folder under the one on screen (top level at the root). */
    fun createContainer(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_name) }
            return
        }
        viewModelScope.launch {
            store.createFolder(folderRel, name)
            scanner.rescan()
            sendEvent(R.string.container_created)
        }
    }

    /**
     * Renames the folder at [relPath] (the screen's own or a child row). A
     * rename of the folder on screen invalidates this route: the message
     * goes out on the bus (the parent screen shows it) and this screen
     * pops itself.
     */
    fun renameFolder(relPath: String, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_name) }
            return
        }
        val isSelf = relPath == folderRel
        viewModelScope.launch {
            store.renameFolder(relPath, name)
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
    fun requestMove(relPath: String) {
        viewModelScope.launch {
            _moveExcludedPaths.value = store.descendantFolderPaths(relPath).toSet()
        }
    }

    /** Moves the folder at [sourceRel] into the folder at [targetFolderRel]. */
    fun moveFolderTo(sourceRel: String, targetFolderRel: String) {
        val isSelf = sourceRel == folderRel
        viewModelScope.launch {
            if (store.moveFolder(sourceRel, targetFolderRel)) {
                scanner.rescan()
                if (isSelf) {
                    messageBus.post(R.string.container_moved)
                    _events.send(ContainerEvent.PopBackStack)
                } else {
                    sendEvent(R.string.container_moved)
                }
            } else {
                sendEvent(R.string.error_invalid_move_target)
            }
        }
    }

    /** Loads the subtree sizes of [relPath] into the state's delete candidate. */
    fun requestDelete(relPath: String, name: String) {
        viewModelScope.launch {
            val (containers, photos) = store.subtreeCounts(relPath)
            _deleteCandidate.value = DeleteCandidate(
                relPath = relPath,
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
        val isSelf = candidate.relPath == folderRel
        viewModelScope.launch {
            store.deleteFolder(candidate.relPath)
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
     * Reads a gallery-picked cover into memory (byte-identical copy) and
     * parks it in [pendingCover] for the confirm dialog; a cancelled pick
     * is a no-op and an unreadable one reports [R.string.error_invalid_image].
     */
    fun onCoverImagePicked(coverFolderRel: String, uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val bytes = store.readBytesFromUri(uri)
            if (bytes == null) {
                sendEvent(R.string.error_invalid_image)
            } else {
                _pendingCover.value = PendingCoverUpload(coverFolderRel, bytes)
            }
        }
    }

    /**
     * Stores the pending cover bytes as the folder's `_cover.jpg`; the row
     * refreshes through the cache. A non-JPEG pick or a failed write
     * surfaces as [R.string.error_invalid_image]; the pending entry is
     * cleared either way.
     */
    fun confirmCoverUpload() {
        val pending = _pendingCover.value ?: return
        viewModelScope.launch {
            _pendingCover.value = null
            try {
                store.setCover(pending.folderRel, pending.bytes)
                scanner.rescan()
                sendEvent(R.string.cover_updated)
            } catch (exception: IllegalArgumentException) {
                sendEvent(R.string.error_invalid_image)
            } catch (exception: java.io.IOException) {
                sendEvent(R.string.error_invalid_image)
            }
        }
    }

    /** Throws away the pending cover without storing anything. */
    fun discardCoverUpload() {
        _pendingCover.value = null
    }

    /** Deletes the cover file of the folder at [relPath]. */
    fun removeCoverImage(relPath: String) {
        viewModelScope.launch {
            store.removeCover(relPath)
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

    fun enterSelectionMode(relPath: String) {
        _selectedPaths.value = setOf(relPath)
    }

    fun toggleSelection(relPath: String) {
        _selectedPaths.update { current ->
            if (relPath in current) current - relPath else current + relPath
        }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    /**
     * Copies gallery [uris] into memory byte-identically (the originals
     * stay in the gallery) and feeds them to the save queue; unreadable or
     * empty results are dropped.
     */
    fun onGalleryUrisPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val staged = uris.mapNotNull { uri -> store.readBytesFromUri(uri) }
            onImagesPicked(staged)
        }
    }

    fun onImagesPicked(images: List<ByteArray>) {
        if (images.isEmpty()) return
        val photos = images.map { PendingPhoto(pendingIdCounter.incrementAndGet(), it) }
        _pendingQueue.value = photos
        _aiSuggestions.value = emptyMap()
        if (!visionPrefs.isActive()) return
        val client = VisionClient(visionPrefs.config())
        photos.forEach { photo ->
            viewModelScope.launch {
                val outcome = client.describe(photo.bytes)
                // A reply that lands after its photo left the queue is ignored.
                if (_pendingQueue.value.any { it.id == photo.id }) {
                    _aiSuggestions.update { it + (photo.id to outcome) }
                }
            }
        }
    }

    /**
     * Saves the queue head into the folder on screen with its (required)
     * description. Success pops the head (the bytes were written as a
     * permanent JPEG) and rescans; an undecodable head is dropped with an
     * error so a broken image cannot stall the queue.
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
                store.addPhoto(
                    folderRel = folderRel,
                    bytes = head.bytes,
                    description = description,
                    note = rawNote?.trim()?.ifEmpty { null },
                    takenAt = System.currentTimeMillis(),
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
                    _pendingQueue.update { queue -> queue.drop(1) }
                    sendEvent(R.string.error_invalid_image)
                }
            }
        }
    }

    fun discardPendingPhotos() {
        _pendingQueue.value = emptyList()
        _aiSuggestions.value = emptyMap()
    }

    fun deletePhoto(relPath: String) {
        viewModelScope.launch {
            store.deletePhoto(relPath)
            scanner.rescan()
            _selectedPaths.update { current -> current - relPath }
            sendEvent(R.string.photo_deleted)
        }
    }

    /** Deletes every selected photo file; the confirmation itself lives in the UI. */
    fun deleteSelected() {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            paths.forEach { relPath -> store.deletePhoto(relPath) }
            scanner.rescan()
            clearSelection()
            sendEvent(R.string.photo_deleted)
        }
    }

    /** Moves every selected photo into [targetFolderRel]; confirmation is in the UI. */
    fun moveSelected(targetFolderRel: String) {
        val paths = _selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val moved = store.movePhotos(paths, targetFolderRel)
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
                store.updatePhotoMetadata(entry.relPath, description, rawNote?.trim()?.ifEmpty { null })
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
        relPath = relPath,
        label = displayLabel(relPath),
    )

    /** "Building/Shelf A" → "Building / Shelf A". */
    private fun displayLabel(relPath: String): String =
        relPath.split('/').filter(String::isNotBlank).joinToString(" / ").ifEmpty { relPath }

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
