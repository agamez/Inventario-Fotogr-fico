package com.inventory.feature.containers

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
import com.inventory.cache.InventoryScanner
import com.inventory.settings.TrashPrefs
import com.inventory.storage.DuplicateGuard
import com.inventory.storage.InventoryStore
import com.inventory.storage.TrashManager
import com.inventory.ui.UserMessageBus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presents ONE multi-photo element (a folder carrying `_item.json`, see
 * `docs/ideas-multi-photo-items.md`) addressed by the navigation argument
 * `path` (RELATIVE path, contractual with
 * [com.inventory.feature.navigation.Screens.ARG_PATH]).
 *
 * The element's photos are ordinary cache rows whose `parentRel` is this
 * item, so the grid uses [CacheDao.observeItemPhotos] and every mutation
 * goes to disk through [InventoryStore] first (rename/move/delete/flatten/
 * ungroup, note rewrite in the marker, cover writes, photo adds and EXIF
 * edits) and then asks [InventoryScanner] to converge the cache.
 *
 * Adding photos uses exactly the same gallery-pick queue as the container
 * screen (staged bytes, mandatory description, duplicate guard); camera
 * captures arrive through the existing camera route pointed at the item
 * path and are announced on [UserMessageBus], which triggers a rescan here.
 */
@HiltViewModel
class ItemViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: InventoryStore,
    private val dao: CacheDao,
    private val scanner: InventoryScanner,
    private val messageBus: UserMessageBus,
    private val visionPrefs: VisionPrefs,
    private val trashPrefs: TrashPrefs,
    private val trashManager: TrashManager,
    private val duplicateGuard: DuplicateGuard,
) : ViewModel() {

    /** Relative path of the element shown; from the nav route. */
    val itemRel: String = savedStateHandle["path"] ?: ""

    /** Display name fallback ("tornillo" for "Caja A/tornillo"). */
    val itemName: String = itemRel.substringAfterLast('/')

    /** One gallery-picked cover awaiting confirmation. */
    data class PendingCoverUpload(val bytes: ByteArray)

    /** One staged gallery image; mirrors the container screen's queue. */
    data class PendingPhoto(
        val id: Long,
        val bytes: ByteArray,
        val duplicateOf: String? = null,
    )

    /** Sizes gathered before the delete confirmation opens. */
    data class DeleteCandidate(val name: String, val photoCount: Int)

    data class ItemUiState(
        val item: CacheEntry? = null,
        val photos: List<CacheEntry> = emptyList(),
        val trashEnabled: Boolean = false,
        val isLoading: Boolean = true,
    )

    private val itemFlow: Flow<CacheEntry?> = dao.observeItem(itemRel)
    private val photosFlow: Flow<List<CacheEntry>> = dao.observeItemPhotos(itemRel, "")

    val uiState: StateFlow<ItemUiState> = combine(
        itemFlow,
        photosFlow,
        trashPrefs.enabled,
    ) { item, photos, trashEnabled ->
        ItemUiState(
            item = item,
            photos = photos,
            trashEnabled = trashEnabled,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ItemUiState(),
    )

    private val _pendingQueue = MutableStateFlow<List<PendingPhoto>>(emptyList())

    /** Gallery images waiting for their mandatory descriptions. */
    val pendingQueue: StateFlow<List<PendingPhoto>> = _pendingQueue.asStateFlow()

    private val pendingIdCounter = AtomicLong(0)

    private val _aiSuggestions = MutableStateFlow<Map<Long, VisionOutcome>>(emptyMap())

    /** AI replies for [pendingQueue], keyed by [PendingPhoto.id]. */
    val aiSuggestions: StateFlow<Map<Long, VisionOutcome>> = _aiSuggestions.asStateFlow()

    /** True while AI auto-labeling is on AND a model is configured. */
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

    /** The picked cover awaiting confirmation, or null. */
    val pendingCover: StateFlow<PendingCoverUpload?> = _pendingCover.asStateFlow()

    private val _deleteCandidate = MutableStateFlow<DeleteCandidate?>(null)

    /** The element pending its delete confirmation, or null. */
    val deleteCandidate: StateFlow<DeleteCandidate?> = _deleteCandidate.asStateFlow()

    private val _moveExcludedPaths = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Containers a move may target: every indexed folder except this
     * element's own subtree (refreshed by [requestMove] when the picker
     * opens), so a cycle can never even be tapped.
     */
    val movableTargets: StateFlow<List<ContainerViewModel.FolderChoice>> = combine(
        dao.observeAllFolders(),
        _moveExcludedPaths,
    ) { folders, excluded ->
        folders.filterNot { it.relPath in excluded }.map { entry ->
            ContainerViewModel.FolderChoice(
                relPath = entry.relPath,
                label = displayLabel(entry.relPath),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** One-shot UI events, consumed exactly once by the screen. */
    sealed interface ItemEvent {
        data class ShowMessage(
            @StringRes val resId: Int,
            val formatArgs: List<Any> = emptyList(),
        ) : ItemEvent
    }

    private val _events = Channel<ItemEvent>(Channel.BUFFERED)
    val events: Flow<ItemEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (store.hasAccess()) scanner.rescan()
        }
        viewModelScope.launch {
            messageBus.messages.collect { resId ->
                sendEvent(resId)
                if (resId in RESCAN_MESSAGES) scanner.rescan()
            }
        }
    }

    /** Resolves the element's `_cover.jpg` as a Coil model. */
    suspend fun coverModel(relPath: String): Any? = store.coverModel(relPath)

    /** Resolves a photo file as a Coil model for the active backend. */
    suspend fun photoModel(relPath: String): Any? = store.photoModel(relPath)

    /** Refreshes [movableTargets] for the element's move sheet. */
    fun requestMove() {
        viewModelScope.launch {
            _moveExcludedPaths.value = store.descendantFolderPaths(itemRel).toSet()
        }
    }

    /** Renames the element; a changed path invalidates this route, so it pops. */
    fun rename(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_name) }
            return
        }
        viewModelScope.launch {
            if (!store.isItem(itemRel)) {
                sendEvent(R.string.item_action_failed)
                return@launch
            }
            val newRel = store.renameItem(itemRel, name)
            scanner.rescan()
            if (newRel != itemRel) {
                messageBus.post(R.string.item_saved)
            } else {
                sendEvent(R.string.item_saved)
            }
        }
    }

    /** Moves the element into [targetFolderRel]; the old route pops on success. */
    fun moveTo(targetFolderRel: String) {
        viewModelScope.launch {
            if (store.moveItem(itemRel, targetFolderRel)) {
                scanner.rescan()
                messageBus.post(R.string.item_moved)
            } else {
                sendEvent(R.string.error_invalid_move_target)
            }
        }
    }

    /** Loads the subtree photo count into [deleteCandidate]. */
    fun requestDelete() {
        viewModelScope.launch {
            val (_, photos) = store.subtreeCounts(itemRel)
            _deleteCandidate.value = DeleteCandidate(
                name = uiState.value.item?.name ?: itemName,
                photoCount = photos,
            )
        }
    }

    /** Deletes the confirmed element as a whole folder (trash when enabled). */
    fun confirmDelete() {
        if (_deleteCandidate.value == null) return
        _deleteCandidate.value = null
        viewModelScope.launch {
            if (trashPrefs.enabled.value) {
                trashManager.trashContainer(itemRel)
            } else {
                store.deleteFolder(itemRel)
            }
            scanner.rescan()
            messageBus.post(R.string.item_deleted)
        }
    }

    fun dismissDelete() {
        _deleteCandidate.value = null
    }

    /** Flattens a one-photo element into a loose photo (rule 3). */
    fun flatten() {
        viewModelScope.launch {
            if (uiState.value.photos.size != 1) {
                sendEvent(R.string.item_flatten_needs_one_photo)
                return@launch
            }
            if (store.flattenItem(itemRel)) {
                scanner.rescan()
                messageBus.post(R.string.item_flattened)
            } else {
                sendEvent(R.string.item_action_failed)
            }
        }
    }

    /** Removes the marker, turning the element into a container (or loose photos). */
    fun ungroup() {
        viewModelScope.launch {
            if (store.ungroup(itemRel)) {
                scanner.rescan()
                messageBus.post(R.string.item_ungrouped)
            } else {
                sendEvent(R.string.item_action_failed)
            }
        }
    }

    /** Rewrites the element note in `_item.json` (atomic marker replace). */
    fun updateNote(rawNote: String?) {
        viewModelScope.launch {
            val updated = store.updateItemNote(itemRel, rawNote?.trim()?.ifEmpty { null })
            scanner.rescan()
            sendEvent(if (updated) R.string.item_saved else R.string.item_action_failed)
        }
    }

    /** Reads a gallery-picked cover and parks it for the confirm dialog. */
    fun onCoverImagePicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val bytes = store.readBytesFromUri(uri)
            if (bytes == null) {
                sendEvent(R.string.error_invalid_image)
            } else {
                _pendingCover.value = PendingCoverUpload(bytes)
            }
        }
    }

    /** Stores the pending cover bytes as the element's `_cover.jpg`. */
    fun confirmCoverUpload() {
        val pending = _pendingCover.value ?: return
        viewModelScope.launch {
            _pendingCover.value = null
            try {
                store.setCover(itemRel, pending.bytes)
                scanner.rescan()
                sendEvent(R.string.cover_updated)
            } catch (exception: IllegalArgumentException) {
                sendEvent(R.string.error_invalid_image)
            } catch (exception: java.io.IOException) {
                sendEvent(R.string.error_invalid_image)
            }
        }
    }

    fun discardCoverUpload() {
        _pendingCover.value = null
    }

    /** Promotes an existing element photo to `_cover.jpg` (bytes copied, photo kept). */
    fun setCoverFromPhoto(photoRel: String) {
        viewModelScope.launch {
            val bytes = store.readPhotoBytes(photoRel)
            if (bytes == null) {
                sendEvent(R.string.error_invalid_image)
                return@launch
            }
            try {
                store.setCover(itemRel, bytes)
                scanner.rescan()
                sendEvent(R.string.cover_updated)
            } catch (exception: IllegalArgumentException) {
                sendEvent(R.string.error_invalid_image)
            } catch (exception: java.io.IOException) {
                sendEvent(R.string.error_invalid_image)
            }
        }
    }

    /** Deletes the element's cover file; missing is a no-op. */
    fun removeCover() {
        viewModelScope.launch {
            store.removeCover(itemRel)
            scanner.rescan()
            sendEvent(R.string.cover_updated)
        }
    }

    /** Copies gallery [uris] into memory and feeds the save queue. */
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
                if (_pendingQueue.value.any { it.id == photo.id }) {
                    _aiSuggestions.update { it + (photo.id to outcome) }
                }
            }
        }
    }

    /**
     * Saves the queue head into the element with its required description,
     * first checking for an identical photo already inside it (same
     * duplicate flow as the container screen).
     */
    fun savePendingPhoto(rawDescription: String, rawNote: String?) {
        val head = _pendingQueue.value.firstOrNull() ?: return
        val description = rawDescription.trim()
        if (description.isEmpty()) {
            viewModelScope.launch { sendEvent(R.string.error_blank_description) }
            return
        }
        viewModelScope.launch {
            if (head.duplicateOf == null) {
                val hit = duplicateGuard.findDuplicate(itemRel, head.bytes)
                if (hit != null) {
                    _pendingQueue.update { queue ->
                        queue.map { pending ->
                            if (pending.id == head.id) {
                                pending.copy(duplicateOf = hit.description)
                            } else {
                                pending
                            }
                        }
                    }
                    return@launch
                }
            }
            when (
                store.addPhoto(
                    folderRel = itemRel,
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

    fun skipPendingDuplicate() {
        val head = _pendingQueue.value.firstOrNull() ?: return
        _pendingQueue.update { queue -> queue.drop(1) }
        _aiSuggestions.update { it - head.id }
    }

    fun discardPendingPhotos() {
        _pendingQueue.value = emptyList()
        _aiSuggestions.value = emptyMap()
    }

    /** Deletes one of the element's photos (trash when enabled). */
    fun deletePhoto(relPath: String) {
        viewModelScope.launch {
            if (trashPrefs.enabled.value) {
                trashManager.trashPhotos(listOf(relPath))
            } else {
                store.deletePhoto(relPath)
            }
            scanner.rescan()
            sendEvent(R.string.photo_deleted)
        }
    }

    /** Rewrites a photo's EXIF description/note (and renames the file if needed). */
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

    private fun displayLabel(relPath: String): String =
        relPath.split('/').filter(String::isNotBlank).joinToString(" / ").ifEmpty { relPath }

    private suspend fun sendEvent(@StringRes resId: Int, formatArgs: List<Any> = emptyList()) {
        _events.send(ItemEvent.ShowMessage(resId, formatArgs))
    }

    private companion object {
        /** Messages posted by other screens that mean "the disk changed; re-index". */
        val RESCAN_MESSAGES = setOf(
            R.string.photo_added,
            R.string.cover_updated,
            R.string.item_saved,
            R.string.item_moved,
            R.string.item_deleted,
        )
    }
}
