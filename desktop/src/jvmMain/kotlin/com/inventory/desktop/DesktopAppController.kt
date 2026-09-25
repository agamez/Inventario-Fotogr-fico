package com.inventory.desktop

import com.inventory.ai.VisionClient
import com.inventory.ai.VisionConfig
import com.inventory.ai.VisionOutcome
import com.inventory.desktop.exif.DesktopExifEditor
import com.inventory.desktop.index.DesktopIndex
import com.inventory.desktop.index.FolderWatcher
import com.inventory.desktop.ui.ANCESTOR_TARGET_PREFIX
import com.inventory.desktop.ui.DragKind
import com.inventory.desktop.ui.DragPayload
import com.inventory.storage.BackendEntry
import com.inventory.storage.COVER_FILE_NAME
import com.inventory.storage.DuplicateGuard
import com.inventory.storage.ExifEditor
import com.inventory.storage.Filenames
import com.inventory.storage.FileBackend
import com.inventory.storage.ImageHasher
import com.inventory.storage.ImageThumbnailer
import com.inventory.storage.ImageTranscoder
import com.inventory.storage.InventoryReportGenerator
import com.inventory.storage.StorageBackend
import com.inventory.storage.TrashManager
import com.inventory.storage.joinRel
import com.inventory.storage.nameOf
import com.inventory.storage.parentOf
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop app's controller: one plain class (no DI framework) wiring
 * the shared [FileBackend] to the [DesktopIndex] and exposing every
 * operation the Compose UI needs. It is the desktop twin of the Android
 * app's `InventoryStore` + `ContainerViewModel`: every action mutates
 * DISK first through the backend, then rebuilds the index so the live
 * flows converge.
 *
 * Relative-path semantics are IDENTICAL to Android: `""` is the storage
 * root, segments join with `/`, sibling name collisions resolve to the
 * Filenames " (2)" variants, photo files are named after their
 * (sanitized) description, and images are stored byte-identical except
 * for the EXIF triple [com.inventory.storage.ExifEditor] stamps.
 */
class DesktopAppController(
    /** The inventory root folder; created by [start] when missing. */
    val rootDir: File,
    /** The EXIF seam — injectable for tests, real on desktop. */
    private val exifEditor: ExifEditor = DesktopExifEditor(),
    /** The persisted local config (root + AI settings); injectable for tests. */
    private val config: DesktopConfig = DesktopConfig.default,
    /** Non-JPEG images (PNG/WebP) are transcoded to JPEG before they are stored. */
    private val transcoder: ImageTranscoder = ImageIoImageTranscoder(),
    /** Decodes image bytes into the dHash grid used by duplicate detection. */
    private val hasher: ImageHasher = ImageIoImageHasher(),
    /** Downscales + orientation-corrects each photo into a report thumbnail. */
    private val thumbnailer: ImageThumbnailer = ImageIoImageThumbnailer(),
    /**
     * Builds the process argv that [openReport] runs to hand a generated
     * report to the desktop's file handler. `xdg-open` by default; injectable
     * so tests can assert the command shape without spawning a real browser.
     */
    private val openCommand: (String) -> List<String> = { path -> listOf("xdg-open", path) },
) {

    val backend: StorageBackend = FileBackend(root = rootDir, accessCheck = { true }, exif = exifEditor)
    val index = DesktopIndex(backend)

    /**
     * Read-only duplicate detection shared by the UI import queue and the
     * CLI `--import` path (see [DuplicateGuard] for the cost model).
     */
    private val duplicateGuard = DuplicateGuard(backend, hasher)

    /** Soft-delete/undo over the same file backend the controller uses. */
    val trashManager = TrashManager(backend)

    /** Shared report builder over the same file backend (HTML + CSV). */
    private val reportGenerator = InventoryReportGenerator(
        backend = backend,
        thumbnailer = thumbnailer,
        rootName = rootDir.name,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: FolderWatcher? = null

    /** The last walk result; the UI renders nothing but this snapshot. */
    val snapshot: StateFlow<DesktopIndex.Snapshot> = index.snapshot

    val scanning: StateFlow<Boolean> = index.scanning

    /** How many entries carry a sync-conflict marker, per the last walk. */
    val conflictCount: StateFlow<Int> = index.snapshot
        .map { it.conflictCount }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _trashEntries = MutableStateFlow<List<TrashManager.TrashEntry>>(emptyList())

    /** The current trash listing, newest deletion first (mirrors Android). */
    val trashEntries: StateFlow<List<TrashManager.TrashEntry>> = _trashEntries.asStateFlow()

    private val _reportGenerating = MutableStateFlow(false)

    /** True while the report generator walks the tree, thumbnails and writes. */
    val reportGenerating: StateFlow<Boolean> = _reportGenerating.asStateFlow()

    init {
        // Opportunistic purge at startup (fire-and-forget), but ONLY while
        // the optional trash is enabled (off by default: deletions are
        // permanent, so there is nothing to purge). Runs the 30-day and the
        // 500 MB size-cap sweep together.
        if (config.trashEnabled.value) {
            scope.launch {
                trashManager.purgeOlderThan(PURGE_DAYS)
                trashManager.purgeOverSizeCap()
            }
        }
    }

    private val _pendingQueue = MutableStateFlow<List<PendingPhoto>>(emptyList())

    /**
     * One staged import with a stable [id]: the import dialog edits the
     * head and pops it on success, and the optional AI suggestion is keyed
     * by the id so a reply that lands after the queue moved on is ignored.
     * Mirrors the Android ContainerViewModel's queue item.
     *
     * [duplicateOf] is the description of an identical-looking photo already
     * in the target container, set by the first import attempt so the dialog
     * can show the duplicate warning with its Import anyway / Skip actions.
     */
    data class PendingPhoto(
        val id: Long,
        val bytes: ByteArray,
        val duplicateOf: String? = null,
    )

    private val pendingIdCounter = AtomicLong(0)

    /** Staged import bytes awaiting their mandatory description (UI queue). */
    val pendingQueue: StateFlow<List<PendingPhoto>> = _pendingQueue.asStateFlow()

    private val _aiSuggestions = MutableStateFlow<Map<Long, VisionOutcome>>(emptyMap())

    /**
     * AI replies for queued photos, keyed by [PendingPhoto.id]. Cleared
     * with the queue; an in-flight reply for a photo that is no longer
     * queued is dropped before it is stored.
     */
    val aiSuggestions: StateFlow<Map<Long, VisionOutcome>> = _aiSuggestions.asStateFlow()

    /**
     * One photo whose name still looks camera-generated, awaiting an AI
     * tidy-up suggestion and the user's review.
     */
    data class TidyItem(val relPath: String, val name: String)

    /**
     * A running "AI tidy-up" session: the [queue] of vague photos, the
     * cursor [index], the current AI [suggestion] (null while it is being
     * fetched) and whether a metadata write is in flight. [scanning] is
     * kept for parity with Android (the desktop index scan is instant).
     */
    data class TidyState(
        val queue: List<TidyItem>,
        val index: Int,
        val suggestion: VisionOutcome?,
        val applying: Boolean,
        val scanning: Boolean = false,
    )

    /** Counts shown when a tidy session ends (applied vs skipped). */
    data class TidySummary(val applied: Int, val skipped: Int)

    /** How [startTidyUp] finished: a session opened, or why it did not. */
    enum class TidyStart { STARTED, NOTHING, NEED_AI }

    private val _tidyState = MutableStateFlow<TidyState?>(null)

    /** The active tidy session, or null when none is open. */
    val tidyState: StateFlow<TidyState?> = _tidyState.asStateFlow()

    private val _tidySummary = MutableStateFlow<TidySummary?>(null)

    /** The last session's counts, consumed by the UI with [acknowledgeTidySummary]. */
    val tidySummary: StateFlow<TidySummary?> = _tidySummary.asStateFlow()

    private var tidyApplied = 0
    private var tidySkipped = 0

    /** The persisted AI auto-label settings, observed by the settings dialog. */
    val aiSettings: StateFlow<AiSettings> = config.aiSettings

    /** Whether deletions move into `.trash`; observed by the settings dialog. */
    val trashEnabled: StateFlow<Boolean> = config.trashEnabled

    /** Result of a mutating operation, mapped to user messages by the UI. */
    sealed interface Outcome {
        data object Saved : Outcome
        data object Created : Outcome
        data object Moved : Outcome
        data object Deleted : Outcome
        data object BlankName : Outcome
        data object InvalidImage : Outcome
        data object InvalidMoveTarget : Outcome

        /** The staged photo looks identical to one already in the target folder. */
        data object Duplicate : Outcome
        data object Failed : Outcome
    }

    /** One entry of a folder picker: relative path + human "Building / Shelf" label. */
    data class FolderChoice(val relPath: String, val label: String)

    /**
     * Creates the root when missing, indexes it and starts watching the
     * folder (external file drops rebuild the index automatically).
     */
    fun start() {
        scope.launch {
            (backend as FileBackend).ensureRoot()
            index.rebuild()
        }
        watcher = FolderWatcher(rootDir.toPath()) {
            scope.launch { index.rebuild() }
        }.also { it.start() }
    }

    /** Stops the watcher and cancels the background scope. */
    fun stop() {
        watcher?.stop()
        watcher = null
        scope.cancel()
    }

    /** Re-indexes now (toolbar "Rescan", watcher events, post-mutation). */
    fun refresh() {
        scope.launch {
            backend.asFileBackend().ensureRoot()
            index.rebuild()
        }
    }

    // region browsing (pure snapshot reads)

    /** Direct child containers of [folderRel], sorted case-insensitively by name. */
    fun children(folderRel: String): List<DesktopIndex.IndexedContainer> =
        index.snapshot.value.containersByParent[folderRel].orEmpty()

    /** (child containers excluding self, subtree photos) for the row/delete dialogs. */
    fun subtreeCounts(folderRel: String): Pair<Int, Int> =
        index.snapshot.value.subtreeCounts[folderRel] ?: (0 to 0)

    /** Photos of [folderRel] filtered by [query] (subtree-wide on demand). */
    fun photosIn(folderRel: String, query: String, includeSubtree: Boolean): List<DesktopIndex.IndexedPhoto> =
        index.photosIn(index.snapshot.value, folderRel, query, includeSubtree)

    /** Global search over every indexed photo. */
    fun searchAll(query: String): List<DesktopIndex.IndexedPhoto> =
        index.searchAll(index.snapshot.value, query)

    /**
     * Move-picker targets: every indexed folder plus the storage root
     * (labelled [rootLabel]); [excludingSubtreeOf] cuts a container's own
     * subtree so a folder can never even be tapped into its own depths.
     */
    fun folderChoices(excludingSubtreeOf: String? = null): List<FolderChoice> {
        val excluded = excludingSubtreeOf?.let { source ->
            index.snapshot.value.folders.filter { it == source || it.startsWith("$source/") }.toSet()
        } ?: emptySet()
        val paths = (index.snapshot.value.folders + "").filterNot { it in excluded }
        return paths.sortedWith(
            compareBy<String> { depthOf(it) }.thenBy { it.lowercase(Locale.getDefault()) },
        ).map { rel ->
            FolderChoice(rel, if (rel.isEmpty()) rootLabel() else labelOf(rel))
        }
    }

    private fun rootLabel(): String = com.inventory.desktop.ui.DesktopStrings.ROOT_FOLDER_LABEL

    private fun depthOf(rel: String): Int = if (rel.isEmpty()) 0 else rel.count { it == '/' } + 1

    /** "Estate/Storage unit" → "Estate / Storage unit" (Android's picker label). */
    fun labelOf(rel: String): String =
        rel.split('/').filter(String::isNotBlank).joinToString(" / ").ifEmpty { rel }

    // endregion

    // region containers

    /** Creates a sanitized folder inside [parentRel]; returns the ACTUAL relPath or null. */
    suspend fun createFolder(parentRel: String, rawName: String): Pair<String?, Outcome> {
        val name = rawName.trim()
        if (name.isEmpty()) return null to Outcome.BlankName
        return withContext(Dispatchers.IO) {
            val created = runCatching { backend.createFolder(parentRel, Filenames.sanitize(name)) }
                .getOrNull() ?: return@withContext null to Outcome.Failed
            index.rebuild()
            created to Outcome.Created
        }
    }

    /** Renames the folder at [rel]; returns the ACTUAL relPath or null when refused. */
    suspend fun renameFolder(rel: String, rawName: String): Pair<String?, Outcome> {
        val name = rawName.trim()
        if (name.isEmpty()) return null to Outcome.BlankName
        return withContext(Dispatchers.IO) {
            val actual = runCatching { backend.renameFolder(rel, Filenames.sanitize(name)) }
                .getOrNull() ?: return@withContext null to Outcome.Failed
            index.rebuild()
            actual to Outcome.Saved
        }
    }

    /**
     * Moves the folder at [rel] into [newParentRel] ("" = the root);
     * refuses a move into itself or its own subtree, mirroring the
     * cycle guard of the Android store's `moveFolder`.
     */
    suspend fun moveFolder(rel: String, newParentRel: String): Outcome {
        val folderRel = rel.trimEnd('/')
        val parentRel = newParentRel.trimEnd('/')
        if (parentRel == folderRel || parentRel.startsWith("$folderRel/")) return Outcome.InvalidMoveTarget
        if (parentOf(folderRel) == parentRel) return Outcome.Moved
        return withContext(Dispatchers.IO) {
            if (!backend.moveFolder(folderRel, parentRel)) return@withContext Outcome.InvalidMoveTarget
            index.rebuild()
            Outcome.Moved
        }
    }

    /**
     * Invalid drop targets for a container drag: [rel] itself plus every
     * descendant, resolved from disk (the drag gesture is not a coroutine,
     * so this is computed once when the drag starts), mirroring the Android
     * store's `descendantFolderPaths`.
     */
    suspend fun descendantFolders(rel: String): Set<String> = withContext(Dispatchers.IO) {
        val source = rel.trimEnd('/')
        if (source.isEmpty()) return@withContext emptySet()
        val result = mutableSetOf(source)
        val pending = ArrayDeque<String>()
        pending += source
        while (pending.isNotEmpty()) {
            for (child in backend.listContainers(pending.removeFirst())) {
                if (result.add(child.relPath)) pending += child.relPath
            }
        }
        result
    }

    /**
     * Resolves a released drag: maps [hoveredTarget] (an `"ancestor:<rel>"`
     * chip or a container relative path) to its destination folder and
     * performs the move through the existing ops. Returns whether a move
     * happened — null targets, empty payloads, unchanged parents and drops
     * into the dragged container's own subtree are refused.
     */
    suspend fun dropDrag(payload: DragPayload, hoveredTarget: String?): Boolean {
        val destination = hoveredTarget?.removePrefix(ANCESTOR_TARGET_PREFIX) ?: return false
        return when (payload.kind) {
            DragKind.CONTAINER -> {
                val source = payload.relPaths.firstOrNull()?.trimEnd('/') ?: return false
                if (source.isEmpty()) return false
                if (destination == source || destination.startsWith("$source/")) return false
                moveFolder(source, destination) == Outcome.Moved
            }

            DragKind.PHOTO -> {
                if (payload.relPaths.isEmpty()) return false
                movePhotosTo(payload.relPaths, destination) == Outcome.Moved
            }
        }
    }

    /** Deletes the folder at [rel]: trashed when enabled, else permanent. */
    suspend fun deleteFolder(rel: String): Outcome = withContext(Dispatchers.IO) {
        if (config.trashEnabled.value) {
            trashManager.trashContainer(rel)
        } else {
            backend.deleteFolderRecursive(rel)
        }
        index.rebuild()
        _trashEntries.value = trashManager.listEntries()
        Outcome.Deleted
    }

    // endregion

    // region covers

    /** Promotes [bytes] to [folderRel]'s `_cover.jpg` (atomic replace). */
    suspend fun setCover(folderRel: String, bytes: ByteArray): Outcome = withContext(Dispatchers.IO) {
        val result = runCatching { backend.setCover(folderRel, bytes) }
        if (result.isFailure) return@withContext Outcome.InvalidImage
        index.rebuild()
        Outcome.Saved
    }

    suspend fun removeCover(folderRel: String): Outcome = withContext(Dispatchers.IO) {
        backend.removeCover(folderRel)
        index.rebuild()
        Outcome.Saved
    }

    /** The cover file for the row thumbnail, or null when the folder has none. */
    suspend fun coverFile(folderRel: String): File? = backend.coverModel(folderRel) as? File

    /** The photo file behind a tile/dialog preview, or null when gone. */
    suspend fun photoFile(photoRel: String): File? = backend.photoModel(photoRel) as? File

    // endregion

    // region photos

    /**
     * Queues picked import bytes (the UI prompts a description per photo)
     * and, when AI auto-labeling is on with a model configured, asks the
     * endpoint for a suggestion per photo in the background.
     */
    fun queueForImport(bytes: List<ByteArray>) {
        if (bytes.isEmpty()) return
        val photos = bytes.map { PendingPhoto(pendingIdCounter.incrementAndGet(), it) }
        _pendingQueue.value = photos
        _aiSuggestions.value = emptyMap()
        val visionConfig = aiConfig() ?: return
        val client = VisionClient(visionConfig)
        photos.forEach { photo ->
            scope.launch {
                val outcome = client.describe(photo.bytes)
                // A reply that lands after its photo left the queue is ignored.
                if (_pendingQueue.value.any { it.id == photo.id }) {
                    _aiSuggestions.update { it + (photo.id to outcome) }
                }
            }
        }
    }

    /** Drops the whole pending import queue (Cancel) and its suggestions. */
    fun discardPendingImport() {
        _pendingQueue.value = emptyList()
        _aiSuggestions.value = emptyMap()
    }

    /**
     * Saves the queue head into [folderRel] with its description/note; the
     * head is popped on success AND on invalid image (a broken file must
     * not stall the queue), mirroring the Android VM.
     *
     * The first attempt checks the head against the folder with
     * [DuplicateGuard]: a hit marks the head with the existing description
     * and returns [Outcome.Duplicate] instead of importing, so the dialog
     * can show the warning. "Import anyway" re-invokes this with
     * [force] = true, which bypasses the check (and matches the CLI
     * `--force` flag).
     */
    suspend fun importPendingPhoto(
        folderRel: String,
        rawDescription: String,
        rawNote: String?,
        force: Boolean = false,
    ): Outcome {
        val head = _pendingQueue.value.firstOrNull() ?: return Outcome.InvalidImage
        if (rawDescription.trim().isEmpty()) return Outcome.BlankName
        if (!force && head.duplicateOf == null) {
            val hit = duplicateGuard.findDuplicate(folderRel, head.bytes)
            if (hit != null) {
                _pendingQueue.update { queue ->
                    queue.map { item ->
                        if (item.id == head.id) item.copy(duplicateOf = hit.description) else item
                    }
                }
                return Outcome.Duplicate
            }
        }
        val outcome = importPhoto(folderRel, head.bytes, rawDescription, rawNote)
        if (outcome == Outcome.Saved || outcome == Outcome.InvalidImage) {
            _pendingQueue.value = _pendingQueue.value.drop(1)
        }
        return outcome
    }

    /**
     * Drops the queue head the user chose to Skip at the duplicate warning
     * and lets the queue continue with the next staged photo.
     */
    fun skipPendingImport() {
        val head = _pendingQueue.value.firstOrNull() ?: return
        _pendingQueue.value = _pendingQueue.value.drop(1)
        _aiSuggestions.update { it - head.id }
    }

    /**
     * Adds [bytes] to [folderRel] as a permanent photo named after the
     * sanitized [rawDescription], EXIF-stamped in memory first (taken date
     * stamped only when the payload has none — Android semantics).
     */
    suspend fun importPhoto(
        folderRel: String,
        bytes: ByteArray,
        rawDescription: String,
        rawNote: String?,
    ): Outcome = importPhotoReturningPath(folderRel, bytes, rawDescription, rawNote).second

    /**
     * [importPhoto]'s body, also returning the ACTUAL stored relative path
     * (the CLI `--import` mode prints it; the queue ignores it).
     */
    private suspend fun importPhotoReturningPath(
        folderRel: String,
        bytes: ByteArray,
        rawDescription: String,
        rawNote: String?,
    ): Pair<String?, Outcome> = withContext(Dispatchers.IO) {
        val description = rawDescription.trim()
        if (description.isEmpty()) return@withContext null to Outcome.BlankName
        if (bytes.isEmpty() || !exifEditor.looksLikeJpeg(bytes)) {
            return@withContext null to Outcome.InvalidImage
        }
        val stamped = try {
            exifEditor.withExif(
                bytes,
                description = description,
                note = rawNote?.trim()?.ifEmpty { null },
                setDateTakenIfMissing = System.currentTimeMillis(),
            )
        } catch (exception: IOException) {
            return@withContext null to Outcome.InvalidImage
        } catch (exception: RuntimeException) {
            return@withContext null to Outcome.InvalidImage
        }
        val name = uniquePhotoName(folderRel, Filenames.sanitize(description))
        val target = joinRel(folderRel, name)
        runCatching { backend.writePhotoBytes(target, stamped) }
            .onFailure { return@withContext null to Outcome.InvalidImage }
        index.rebuild()
        target to Outcome.Saved
    }

    /** One file's CLI import result: the stored relative path, a skip, or a reason. */
    sealed interface FileImportResult {
        data class Imported(val relPath: String) : FileImportResult

        /** An identical photo is already in the target; [description] names it. */
        data class SkippedDuplicate(val description: String) : FileImportResult
        data class Failed(val reason: String) : FileImportResult
    }

    /**
     * Imports [file] into [folderRel] for the CLI `--import` mode: reads the
     * file, transcodes non-JPEG images to JPEG ([transcoder]) and stores it
     * with description = the file name minus its extension. Unless [force]
     * is set, a file that looks identical to one already in the container is
     * reported as [FileImportResult.SkippedDuplicate] without importing
     * (matching the UI import queue's duplicate warning). Returns the stored
     * relative path, a skip, or a reason the file was refused.
     */
    suspend fun importFile(
        folderRel: String,
        file: File,
        force: Boolean = false,
    ): FileImportResult = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext FileImportResult.Failed("no such file")
        val raw = runCatching { file.readBytes() }.getOrNull()
            ?: return@withContext FileImportResult.Failed("cannot read file")
        val jpeg = runCatching { transcoder.toJpeg(raw) }.getOrElse {
            return@withContext FileImportResult.Failed("not a decodable image")
        }
        val description = file.name.substringBeforeLast('.', file.name).trim()
        if (description.isEmpty()) return@withContext FileImportResult.Failed("blank file name")
        if (!force) {
            val hit = duplicateGuard.findDuplicate(folderRel, jpeg)
            if (hit != null) return@withContext FileImportResult.SkippedDuplicate(hit.description)
        }
        val (relPath, outcome) = importPhotoReturningPath(folderRel, jpeg, description, null)
        if (outcome == Outcome.Saved && relPath != null) {
            FileImportResult.Imported(relPath)
        } else {
            FileImportResult.Failed(
                when (outcome) {
                    Outcome.BlankName -> "blank description"
                    else -> "not a valid image"
                },
            )
        }
    }

    /**
     * Rewrites a photo's EXIF description/note and — when the sanitized
     * description differs from the current base name — moves the
     * restamped bytes to the new collision-safe name, deleting the old
     * file (the rename-with-description rule, implemented over the shared
     * backend's primitives exactly like the Android store).
     */
    suspend fun updatePhotoMetadata(
        rel: String,
        rawDescription: String,
        rawNote: String?,
    ): Outcome = withContext(Dispatchers.IO) {
        val description = rawDescription.trim()
        if (description.isEmpty()) return@withContext Outcome.BlankName
        val bytes = backend.readPhotoBytes(rel) ?: return@withContext Outcome.InvalidImage
        val stamped = try {
            exifEditor.withExif(
                bytes,
                description = description,
                note = rawNote?.trim()?.ifEmpty { null },
                setDateTakenIfMissing = null,
            )
        } catch (exception: Exception) {
            return@withContext Outcome.InvalidImage
        }
        val desired = Filenames.sanitize(description)
        val currentName = nameOf(rel)
        val currentBase = currentName.substringBeforeLast('.', currentName)
        val parentRel = parentOf(rel)
        val targetRel = if (desired == currentBase) {
            rel
        } else {
            joinRel(parentRel, uniquePhotoName(parentRel, desired, ignoring = currentName))
        }
        try {
            backend.writePhotoBytes(targetRel, stamped)
            if (targetRel != rel && !backend.deleteFile(rel)) return@withContext Outcome.InvalidImage
        } catch (exception: IOException) {
            return@withContext Outcome.InvalidImage
        }
        index.rebuild()
        Outcome.Saved
    }

    /** Deletes the photo at [rel]: trashed when enabled, else permanent. */
    suspend fun deletePhoto(rel: String): Outcome = withContext(Dispatchers.IO) {
        if (config.trashEnabled.value) {
            trashManager.trashPhotos(listOf(rel))
        } else {
            backend.deleteFile(rel)
        }
        _selectedPhotoRels.update { it - rel }
        index.rebuild()
        _trashEntries.value = trashManager.listEntries()
        Outcome.Deleted
    }

    // region multi-select (desktop mouse/keyboard)

    private val _selectedPhotoRels = MutableStateFlow<Set<String>>(emptySet())

    /** Relative paths of the multi-selected photos (empty = no selection). */
    val selectedPhotoRels: StateFlow<Set<String>> = _selectedPhotoRels.asStateFlow()

    /**
     * Anchor of a Shift+click range: the last photo whose selection was
     * toggled. Kept private because the grid order itself lives in the UI.
     */
    private var selectionAnchor: String? = null

    /** Ctrl+click: toggles [rel] and makes it the new range anchor. */
    fun toggleSelect(rel: String) {
        _selectedPhotoRels.update { current ->
            if (rel in current) current - rel else current + rel
        }
        selectionAnchor = rel
    }

    /**
     * Shift+click: adds the inclusive range between the anchor and
     * [targetRel] to the selection. The caller supplies [orderedPhotoRels]
     * because the grid order is a UI concern; an anchor that is no longer
     * on screen degrades to "select just the target".
     */
    fun rangeSelectTo(targetRel: String, orderedPhotoRels: List<String>) {
        val targetIndex = orderedPhotoRels.indexOf(targetRel)
        if (targetIndex < 0) return
        val anchorIndex = selectionAnchor?.let { orderedPhotoRels.indexOf(it) } ?: -1
        if (anchorIndex < 0) {
            selectionAnchor = targetRel
            _selectedPhotoRels.update { it + targetRel }
            return
        }
        val from = minOf(anchorIndex, targetIndex)
        val to = maxOf(anchorIndex, targetIndex)
        _selectedPhotoRels.update { it + orderedPhotoRels.subList(from, to + 1) }
    }

    /** Ctrl+A: selects every photo currently listed in the browse grid. */
    fun selectAllInCurrentFolder(orderedPhotoRels: List<String>) {
        _selectedPhotoRels.value = orderedPhotoRels.toSet()
        selectionAnchor = orderedPhotoRels.firstOrNull()
    }

    /** Esc: drops the whole multi-selection. */
    fun clearSelection() {
        _selectedPhotoRels.value = emptySet()
        selectionAnchor = null
    }

    /**
     * Drops the multi-selection when the browse folder changes. The UI calls
     * this from a folder-keyed effect — the controller deliberately does not
     * track navigation itself.
     */
    fun onFolderChanged() = clearSelection()

    /**
     * Deletes every selected photo through the existing per-photo delete
     * (trash semantics follow the setting), rebuilds the index and clears
     * the selection. The single user confirmation is shown by the UI before
     * this is called; mirrors Android's `deleteSelected`.
     */
    suspend fun deleteSelectedPhotos(): Outcome = withContext(Dispatchers.IO) {
        val paths = _selectedPhotoRels.value.toList()
        if (paths.isEmpty()) return@withContext Outcome.Deleted
        if (config.trashEnabled.value) {
            trashManager.trashPhotos(paths)
        } else {
            paths.forEach { backend.deleteFile(it) }
        }
        index.rebuild()
        _trashEntries.value = trashManager.listEntries()
        clearSelection()
        Outcome.Deleted
    }

    // endregion

    /**
     * Moves every photo in [rels] into [targetFolderRel] byte-identically,
     * collision-suffixing names; returns how many ended up there.
     */
    suspend fun movePhotosTo(rels: List<String>, targetFolderRel: String): Outcome = withContext(Dispatchers.IO) {
        var moved = 0
        for (rel in rels) {
            if (parentOf(rel) == targetFolderRel) {
                moved++
                continue
            }
            val bytes = backend.readPhotoBytes(rel) ?: continue
            val name = nameOf(rel)
            val ext = name.substringAfterLast('.', "jpg").ifEmpty { "jpg" }
            val base = name.substringBeforeLast('.', name)
            val targetName = uniqueNameIn(targetFolderRel, base, ext)
            try {
                backend.writePhotoBytes(joinRel(targetFolderRel, targetName), bytes)
                if (backend.deleteFile(rel)) moved++
            } catch (exception: IOException) {
                continue
            }
        }
        index.rebuild()
        // Moving drops any multi-selection (Android's movePhotosTo does the
        // same); a drag that carried the selection must not leave hidden
        // paths selected after the files changed parent.
        clearSelection()
        if (moved == rels.size) Outcome.Moved else Outcome.InvalidImage
    }

    /** The EXIF triple of one photo straight from disk (the edit dialog). */
    suspend fun readPhotoMeta(rel: String): com.inventory.storage.PhotoMeta? = backend.readPhotoMeta(rel)

    // region trash (soft delete / undo)

    /** Re-lists the trash from disk; the UI calls this when it opens the view. */
    fun refreshTrash() {
        scope.launch { _trashEntries.value = trashManager.listEntries() }
    }

    /** Puts the trashed entry [id] back and re-indexes the restored subtree. */
    suspend fun restore(id: String): Boolean = withContext(Dispatchers.IO) {
        val restored = trashManager.restore(id)
        if (restored) {
            index.rebuild()
            _trashEntries.value = trashManager.listEntries()
        }
        restored
    }

    /** Permanently deletes the trashed entry [id]; returns the disk verdict. */
    suspend fun deleteForever(id: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = trashManager.deleteForever(id)
        _trashEntries.value = trashManager.listEntries()
        deleted
    }

    /** Permanently deletes every trashed entry. */
    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        trashManager.listEntries().forEach { trashManager.deleteForever(it.id) }
        _trashEntries.value = trashManager.listEntries()
    }

    // endregion

    // region report

    /**
     * Writes `inventory-report.html` and `inventory-report.csv` into the
     * inventory root through the shared [InventoryReportGenerator] and
     * returns their relative paths (the UI confirms with the HTML path).
     * [reportGenerating] drives an indeterminate progress indicator while
     * the walk + thumbnailing runs; the report files are not JPEGs, so the
     * index ignores them.
     */
    suspend fun exportReport(): InventoryReportGenerator.ReportFiles {
        _reportGenerating.value = true
        return try {
            reportGenerator.generate()
        } finally {
            _reportGenerating.value = false
        }
    }

    /**
     * Hands the generated report at [absolutePath] to the platform opener
     * ([openCommand], `xdg-open` by default). Returns false when the process
     * cannot be started or exits non-zero, so the UI can show
     * [com.inventory.desktop.ui.DesktopStrings.REPORT_OPEN_FAILED].
     */
    fun openReport(absolutePath: String): Boolean = runCatching {
        ProcessBuilder(openCommand(absolutePath)).start().waitFor() == 0
    }.getOrDefault(false)

    /** The argv [openReport] runs for [absolutePath] (the test seam). */
    fun reportOpenCommand(absolutePath: String): List<String> = openCommand(absolutePath)

    // endregion

    // region ai auto-label

    /**
     * The effective vision config, or null when the feature is off or no
     * model is configured (so callers never make a request in that state).
     * A blank API key becomes null so the client omits the Authorization
     * header; the base URL and model are trimmed, mirroring Android.
     */
    fun aiConfig(): VisionConfig? {
        val settings = aiSettings.value
        if (!settings.enabled || settings.model.isBlank()) return null
        return VisionConfig(
            baseUrl = settings.baseUrl.trim(),
            apiKey = settings.apiKey.trim().ifBlank { null },
            model = settings.model.trim(),
            outputLanguage = resolvedOutputLanguage(settings.outputLanguage),
        )
    }

    /** Persists the AI master switch (settings dialog + import gate). */
    fun setAiEnabled(value: Boolean) = config.setAiEnabled(value)

    fun setAiBaseUrl(value: String) = config.setAiBaseUrl(value)

    fun setAiApiKey(value: String) = config.setAiApiKey(value)

    fun setAiModel(value: String) = config.setAiModel(value)

    fun setAiOutputLanguage(value: String) = config.setAiOutputLanguage(value)

    /** Persists the trash (soft-delete) master switch. */
    fun setTrashEnabled(value: Boolean) = config.setTrashEnabled(value)

    /**
     * Probes the configured endpoint with a text-only ping, building the
     * client from the CURRENT field values. Returns a [VisionOutcome], so
     * the dialog can render "Connection OK" or the key-free failure reason.
     */
    suspend fun testConnection(): VisionOutcome {
        val visionConfig = aiConfig() ?: return VisionOutcome.Failed(MODEL_NOT_CONFIGURED)
        return VisionClient(visionConfig).ping()
    }

    // endregion

    // region ai tidy-up

    /**
     * Starts an "AI tidy-up" session over every indexed photo whose name
     * still looks camera-generated. The index scan is local and instant;
     * [TidyStart.NOTHING] means there is nothing to do and
     * [TidyStart.NEED_AI] means the feature is off or has no model (no
     * request is made in either case).
     */
    fun startTidyUp(): TidyStart {
        if (_tidyState.value != null) return TidyStart.STARTED
        val vague = index.snapshot.value.allPhotos
            .filter { Filenames.isCameraGenerated(it.name) }
            .map { TidyItem(it.relPath, it.name) }
        if (vague.isEmpty()) return TidyStart.NOTHING
        if (aiConfig() == null) return TidyStart.NEED_AI
        tidyApplied = 0
        tidySkipped = 0
        _tidyState.value = TidyState(vague, 0, suggestion = null, applying = false)
        describeCurrentTidyItem()
        return TidyStart.STARTED
    }

    /**
     * Asks the model to label the current tidy item and parks the (possibly
     * failed) outcome in [tidyState] for the review dialog; a failure is
     * rendered inline so the user can Skip or Retry. A reply that lands
     * after the cursor moved on is dropped.
     */
    fun describeCurrentTidyItem() {
        val state = _tidyState.value ?: return
        val item = state.queue.getOrNull(state.index) ?: return
        val visionConfig = aiConfig()
        if (visionConfig == null) {
            _tidyState.update { it?.copy(suggestion = VisionOutcome.Failed(MODEL_NOT_CONFIGURED)) }
            return
        }
        scope.launch {
            _tidyState.update { current ->
                if (current != null && current.queue === state.queue && current.index == state.index) {
                    current.copy(suggestion = null)
                } else {
                    current
                }
            }
            val bytes = backend.readPhotoBytes(item.relPath)
            val outcome = if (bytes == null) {
                VisionOutcome.Failed(TIDY_UNREADABLE_REASON)
            } else {
                VisionClient(visionConfig).describe(bytes)
            }
            _tidyState.update { current ->
                if (current != null && current.queue === state.queue && current.index == state.index) {
                    current.copy(suggestion = outcome)
                } else {
                    current
                }
            }
        }
    }

    /**
     * Applies the reviewed description/note to the current tidy item
     * through the existing [updatePhotoMetadata] path (the file is RENAMED
     * when the sanitized description changes) and advances the queue; the
     * last item closes the session and fills [tidySummary].
     */
    suspend fun applyTidy(rawDescription: String, rawNote: String?): Outcome {
        val state = _tidyState.value ?: return Outcome.InvalidImage
        val item = state.queue.getOrNull(state.index) ?: return Outcome.InvalidImage
        val description = rawDescription.trim()
        if (description.isEmpty()) return Outcome.BlankName
        _tidyState.update { it?.copy(applying = true) }
        val outcome = updatePhotoMetadata(item.relPath, description, rawNote)
        if (outcome == Outcome.Saved) {
            tidyApplied++
            advanceTidy()
        } else {
            _tidyState.update { it?.copy(applying = false) }
        }
        return outcome
    }

    /** Leaves the current tidy item untouched and moves to the next. */
    fun skipTidy() {
        val state = _tidyState.value ?: return
        if (state.applying) return
        tidySkipped++
        advanceTidy()
    }

    /** Abandons the tidy session; the summary arrives on [tidySummary]. */
    fun cancelTidy() = finishTidy()

    /** Consumes the summary once the UI has shown it. */
    fun acknowledgeTidySummary() {
        _tidySummary.value = null
    }

    private fun advanceTidy() {
        val state = _tidyState.value ?: return
        val next = state.index + 1
        if (next >= state.queue.size) {
            finishTidy()
            return
        }
        _tidyState.value = state.copy(index = next, suggestion = null, applying = false)
        describeCurrentTidyItem()
    }

    private fun finishTidy() {
        if (_tidyState.value == null) return
        _tidyState.value = null
        _tidySummary.value = TidySummary(tidyApplied, tidySkipped)
    }

    // endregion

    /** The first free "<base>.jpg" name inside [folderRel] (case-insensitive). */
    private suspend fun uniquePhotoName(
        folderRel: String,
        base: String,
        ignoring: String? = null,
    ): String = uniqueNameIn(folderRel, base, "jpg", ignoring)

    private suspend fun uniqueNameIn(
        folderRel: String,
        base: String,
        ext: String,
        ignoring: String? = null,
    ): String {
        val taken = backend.listContainers(folderRel).map(BackendEntry::name) +
            backend.listPhotos(folderRel).map(BackendEntry::name) +
            listOfNotNull(COVER_FILE_NAME.takeIf { backend.hasCover(folderRel) })
        return Filenames.uniqueNameIn(taken.toSet(), base, ext, ignoring)
    }

    private fun StorageBackend.asFileBackend(): FileBackend = this as FileBackend

    /** Ensures the root and rebuilds from disk — the initial index fill. */
    suspend fun refreshNow() {
        backend.asFileBackend().ensureRoot()
        index.rebuild()
    }

    private companion object {
        /** Key-free reason for a "Test connection" with no model configured. */
        const val MODEL_NOT_CONFIGURED = "model not configured"

        /** Key-free inline reason when a tidy photo cannot be read from disk. */
        const val TIDY_UNREADABLE_REASON = "photo could not be read"

        /** Trash entries older than this many days are purged at startup. */
        const val PURGE_DAYS = 30

        /**
         * The language to instruct the model with: the trimmed user value,
         * or the system locale's display language (named in English) when
         * the value is blank.
         */
        fun resolvedOutputLanguage(raw: String): String? =
            raw.trim().ifEmpty { Locale.getDefault().getDisplayLanguage(Locale.ENGLISH) }
    }
}
