package com.inventory.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inventory.desktop.DesktopAppController
import com.inventory.desktop.index.DesktopIndex
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole desktop browsing surface — the twin of Android's single
 * [com.inventory.feature.containers.ContainerScreen]: one screen that
 * shows either the storage root or any container addressed by its
 * relative path, with children rows (cover, name, "N containers · M
 * photos"), a two-column photo grid, in-container search with a
 * here/subtree scope, a global search view, the conflict banner and the
 * container/photo dialogs. Navigation is plain state: [selectedFolder]
 * (null = root) replaces the Android navigation graph.
 */
@Composable
fun App(controller: DesktopAppController) {
    val snapshot by controller.snapshot.collectAsState()
    val scanning by controller.scanning.collectAsState()
    val conflictCount by controller.conflictCount.collectAsState()
    val pendingQueue by controller.pendingQueue.collectAsState()
    val aiSettings by controller.aiSettings.collectAsState()
    val aiSuggestions by controller.aiSuggestions.collectAsState()
    val trashEnabled by controller.trashEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    val dragController = remember { DragController() }
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val gridState = rememberLazyGridState()
    var gridBounds by remember { mutableStateOf<Rect?>(null) }
    var upChipBounds by remember { mutableStateOf<Rect?>(null) }

    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var showGlobalSearch by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var globalQuery by remember { mutableStateOf("") }
    var browseQuery by remember { mutableStateOf("") }
    var includeSubtree by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Dialog state.
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DesktopIndex.IndexedContainer?>(null) }
    var moveSourceRel by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<DesktopIndex.IndexedContainer?>(null) }
    var detailPhotoRel by remember { mutableStateOf<String?>(null) }
    var photoMoveRel by remember { mutableStateOf<String?>(null) }
    var coverSourceRel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MILLIS)
            message = null
        }
    }

    val folderRel = selectedFolder ?: ""
    val dragOwner = folderRel
    val isRoot = folderRel.isEmpty()

    // The folder on screen vanished (deleted/renamed/moved elsewhere): ascend.
    LaunchedEffect(snapshot, folderRel) {
        if (folderRel.isNotEmpty() && folderRel !in snapshot.folders) {
            selectedFolder = parentRelOf(folderRel)
            message = DesktopStrings.MSG_CONTAINER_DELETED
        }
    }

    // While a drag is in flight, keep the drop target under the ghost pointer
    // current; moving off every row clears the highlight. Blocked targets (a
    // dragged container and its subtree) and the payload's own paths never
    // hover: they may not receive a drop.
    LaunchedEffect(
        dragController.pointer,
        dragController.targets,
        dragController.blockedTargets,
        dragController.payload,
    ) {
        val point = dragController.pointer
        if (dragController.payload == null || point == null) {
            dragController.hoveredTarget = null
            return@LaunchedEffect
        }
        val prefix = "$dragOwner|"
        val blocked = dragController.blockedTargets
        val ownPaths = dragController.payload?.relPaths.orEmpty()
        dragController.hoveredTarget = dragController.targets.entries
            .firstOrNull { (key, rect) ->
                if (!key.startsWith(prefix) || !rect.contains(point)) return@firstOrNull false
                val id = key.substringAfter('|')
                id !in blocked && id !in ownPaths
            }
            ?.key
            ?.substringAfter('|')
    }

    // Spring-loaded navigation: dwelling on a hovered container row opens it
    // so the drag can continue into that subtree. The session (payload,
    // pointer, targets) lives at this root, so the drag survives the folder
    // change; the dwell restarts whenever the hovered target changes.
    LaunchedEffect(dragController.hoveredTarget) {
        val hovered = dragController.hoveredTarget ?: return@LaunchedEffect
        if (hovered == dragOwner) return@LaunchedEffect
        // Ancestor chips are drop-only: hovering one must never navigate.
        if (hovered.startsWith(ANCESTOR_TARGET_PREFIX)) return@LaunchedEffect
        // A blocked row (the dragged container or a descendant) never
        // receives a drop, so dwelling on it must not spring it open either.
        if (hovered in dragController.blockedTargets) return@LaunchedEffect
        delay(SPRING_LOAD_DWELL_MS)
        if (dragController.hoveredTarget == hovered) selectedFolder = hovered
    }

    // Drag-time "↑" affordance: dwelling on it ascends to the parent folder
    // (navigation only — it is NOT a drop target). Hidden at the root.
    val upChipVisible = dragController.payload != null && !isRoot
    val pointerOverUpChip = upChipVisible &&
        upChipBounds?.let { bounds ->
            dragController.pointer?.let { bounds.contains(it) } ?: false
        } == true
    LaunchedEffect(pointerOverUpChip) {
        if (!pointerOverUpChip) return@LaunchedEffect
        delay(SPRING_LOAD_DWELL_MS)
        if (pointerOverUpChip) selectedFolder = parentRelOf(folderRel)
    }

    // Drop resolution: the row (or ancestor chip) under the pointer wins,
    // releasing anywhere else is a no-op. The controller refuses null,
    // blocked and own-subtree destinations.
    SideEffect {
        dragController.onDrop = { payload, target ->
            scope.launch {
                val moved = controller.dropDrag(payload, target)
                if (moved) {
                    message = when (payload.kind) {
                        DragKind.CONTAINER -> DesktopStrings.MSG_CONTAINER_MOVED
                        DragKind.PHOTO -> DesktopStrings.MSG_PHOTOS_MOVED
                    }
                }
            }
        }
    }

    // Drag-time auto-scroll: while the pointer hugs the grid's top or bottom
    // edge, keep scrolling the single browse grid so a drop target further
    // down/up can be reached. The speed ramps from 8 to 30 dp/frame.
    LaunchedEffect(gridState, gridBounds, dragController.payload != null) {
        val bounds = gridBounds ?: return@LaunchedEffect
        val edge = with(density) { AUTO_SCROLL_EDGE_DP.dp.toPx() }
        val minSpeed = with(density) { AUTO_SCROLL_MIN_DP.dp.toPx() }
        val maxSpeed = with(density) { AUTO_SCROLL_MAX_DP.dp.toPx() }
        while (dragController.payload != null) {
            val point = dragController.pointer
            if (point != null && point.x in bounds.left..bounds.right) {
                val fromTop = point.y - bounds.top
                val fromBottom = bounds.bottom - point.y
                val speed = when {
                    fromTop < edge -> -autoScrollSpeed(minSpeed, maxSpeed, edge, fromTop)
                    fromBottom < edge -> autoScrollSpeed(minSpeed, maxSpeed, edge, fromBottom)
                    else -> 0f
                }
                if (speed != 0f) gridState.scrollBy(speed)
            }
            withFrameNanos { }
        }
    }

    // Rows of the folder on screen unregister their drop bounds when it leaves.
    DisposableEffect(dragOwner) {
        onDispose { dragController.clearTargets(dragOwner) }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // Esc cancels an in-flight drag; preview so it also wins while a
            // text field (search / dialog) holds focus.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    dragController.payload != null
                ) {
                    dragController.end()
                    true
                } else {
                    false
                }
            }
            // The gesture source (a tile or row) can be disposed by a
            // spring-loaded folder change mid-drag. This root node stays
            // composed and observes the pointer in the Initial pass (never
            // consuming), so the in-flight session keeps following the mouse
            // and ends with the drop on release.
            .pointerInput(dragController) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (dragController.payload == null) continue
                            event.changes.firstOrNull()?.let { change ->
                                dragController.pointer = change.position
                            }
                            if (event.changes.none { it.pressed }) dragController.drop()
                        }
                    }
                } finally {
                    if (dragController.payload != null) dragController.end()
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                folderRel = folderRel,
                scanning = scanning,
                showGlobalSearch = showGlobalSearch,
                showTrash = showTrash,
                onRescan = {
                    controller.refresh()
                    message = DesktopStrings.MSG_RESCANNED
                },
                onToggleSearch = {
                    showGlobalSearch = !showGlobalSearch
                    if (showGlobalSearch) showTrash = false
                    browseQuery = ""
                },
                onToggleTrash = {
                    showTrash = !showTrash
                    if (showTrash) showGlobalSearch = false
                },
                onOpenSettings = { showSettings = true },
                onExportReport = {
                    scope.launch {
                        message = try {
                            val files = controller.exportReport()
                            DesktopStrings.REPORT_WRITTEN.format(files.htmlRelPath)
                        } catch (exception: java.io.IOException) {
                            DesktopStrings.REPORT_EXPORT_FAILED
                        }
                    }
                },
            )
            if (!showTrash) {
                ActionBar(
                    folderRel = folderRel,
                    rootPath = controller.rootDir.absolutePath,
                    onUp = { selectedFolder = parentRelOf(folderRel) },
                    onImport = {
                        scope.launch {
                            val files = pickJpegFiles(DesktopStrings.IMAGE_PICKER_TITLE, controller.rootDir.absolutePath)
                            if (files.isEmpty()) return@launch
                            val staged = withFilesRead(files)
                            if (staged.isNotEmpty()) controller.queueForImport(staged)
                        }
                    },
                    onAddContainer = { showCreateDialog = true },
                )
            }
            // Ancestor drop chips: shown for the whole drag when the current
            // folder is not the root. They are drop-only.
            if (dragController.payload != null && !isRoot) {
                AncestorChipsRow(
                    folderRel = folderRel,
                    hoveredTarget = dragController.hoveredTarget,
                    onRegister = { id, rect -> dragController.registerTarget(dragOwner, id, rect) },
                )
                MoveUpChip(
                    contentDescription = DesktopStrings.MOVE_UP_HERE,
                    onClick = { selectedFolder = parentRelOf(folderRel) },
                    modifier = Modifier
                        .padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                        .onGloballyPositioned { upChipBounds = it.boundsInRoot() },
                )
            }
            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (conflictCount > 0) {
                ConflictBanner(conflictCount)
            }

            if (showTrash) {
                TrashView(
                    controller = controller,
                    onMessage = { message = it },
                )
            } else if (showGlobalSearch) {
                GlobalSearchView(
                    controller = controller,
                    query = globalQuery,
                    onQueryChange = { globalQuery = it },
                    onOpenPhoto = { photo ->
                        showGlobalSearch = false
                        globalQuery = ""
                        selectedFolder = photo.parentRel.takeIf { it.isNotEmpty() }
                        detailPhotoRel = photo.relPath
                    },
                )
            } else {
                BrowseView(
                    controller = controller,
                    folderRel = folderRel,
                    query = browseQuery,
                    includeSubtree = includeSubtree,
                    onQueryChange = { browseQuery = it },
                    onScopeChange = { includeSubtree = it },
                    onOpenContainer = { selectedFolder = it },
                    onOpenPhoto = { detailPhotoRel = it },
                    onRename = { renameTarget = it },
                    onMove = { moveSourceRel = it },
                    onSetCover = { coverSourceRel = it },
                    onDelete = { deleteTarget = it },
                    onRemoveCover = { rel ->
                        scope.launch {
                            controller.removeCover(rel)
                            message = DesktopStrings.MSG_COVER_UPDATED
                        }
                    },
                    dragController = dragController,
                    dragOwner = dragOwner,
                    gridState = gridState,
                    onGridBounds = { gridBounds = it },
                )
            }
        }

        // region dialogs

        if (showCreateDialog) {
            NameDialog(
                title = DesktopStrings.NEW_CONTAINER_TITLE,
                initial = "",
                onDismiss = { showCreateDialog = false },
                onConfirm = { raw ->
                    showCreateDialog = false
                    scope.launch {
                        val outcome = controller.createFolder(folderRel, raw).second
                        message = when (outcome) {
                            DesktopAppController.Outcome.Created -> DesktopStrings.MSG_CONTAINER_CREATED
                            DesktopAppController.Outcome.BlankName -> DesktopStrings.MSG_ERROR_BLANK_NAME
                            else -> DesktopStrings.MSG_ERROR_OPERATION
                        }
                    }
                },
            )
        }

        renameTarget?.let { target ->
            NameDialog(
                title = DesktopStrings.RENAME_CONTAINER_TITLE,
                initial = target.name,
                onDismiss = { renameTarget = null },
                onConfirm = { raw ->
                    renameTarget = null
                    scope.launch {
                        val (actual, outcome) = controller.renameFolder(target.relPath, raw)
                        message = when (outcome) {
                            DesktopAppController.Outcome.Saved -> DesktopStrings.MSG_CONTAINER_SAVED
                            DesktopAppController.Outcome.BlankName -> DesktopStrings.MSG_ERROR_BLANK_NAME
                            else -> DesktopStrings.MSG_ERROR_OPERATION
                        }
                        if (actual == folderRel || target.relPath == folderRel) {
                            selectedFolder = parentRelOf(folderRel)
                        }
                    }
                },
            )
        }

        moveSourceRel?.let { source ->
            FolderPickerDialog(
                title = DesktopStrings.MOVE_CONTAINER_TITLE,
                choices = controller.folderChoices(excludingSubtreeOf = source),
                onDismiss = { moveSourceRel = null },
                onPick = { choice ->
                    moveSourceRel = null
                    scope.launch {
                        when (controller.moveFolder(source, choice.relPath)) {
                            DesktopAppController.Outcome.Moved -> {
                                message = DesktopStrings.MSG_CONTAINER_MOVED
                                if (source == folderRel) selectedFolder = parentRelOf(folderRel)
                            }
                            else -> message = DesktopStrings.MSG_ERROR_INVALID_MOVE
                        }
                    }
                },
            )
        }

        deleteTarget?.let { target ->
            val (containers, photos) = snapshot.subtreeCounts[target.relPath] ?: (0 to 0)
            ConfirmDialog(
                title = DesktopStrings.DELETE_CONTAINER_TITLE,
                message = (if (trashEnabled) {
                    DesktopStrings.DELETE_CONTAINER_MESSAGE
                } else {
                    DesktopStrings.DELETE_CONTAINER_MESSAGE_PERMANENT
                }).format(target.name) +
                    "\n" + DesktopStrings.DELETE_CONTAINS.format(
                        counted(containers, DesktopStrings.CONTAINER_COUNTED_ONE, DesktopStrings.CONTAINERS_COUNTED),
                        counted(photos, DesktopStrings.PHOTO_COUNTED_ONE, DesktopStrings.PHOTOS_COUNTED),
                    ),
                confirmLabel = DesktopStrings.DELETE,
                onDismiss = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    scope.launch {
                        controller.deleteFolder(target.relPath)
                        message = DesktopStrings.MSG_CONTAINER_DELETED
                        if (target.relPath == folderRel || folderRel.startsWith("${target.relPath}/")) {
                            selectedFolder = parentRelOf(target.relPath)
                        }
                    }
                },
            )
        }

        detailPhotoRel?.let { rel ->
            val photo = snapshot.allPhotos.firstOrNull { it.relPath == rel }
            if (photo == null) {
                LaunchedEffect(rel) { detailPhotoRel = null }
            } else {
                PhotoDetailsDialog(
                    controller = controller,
                    photo = photo,
                    onDismiss = { detailPhotoRel = null },
                    onDeleted = {
                        detailPhotoRel = null
                        message = DesktopStrings.MSG_PHOTO_DELETED
                    },
                    onOutcome = { outcome ->
                        message = when (outcome) {
                            DesktopAppController.Outcome.Saved -> DesktopStrings.MSG_PHOTO_SAVED
                            DesktopAppController.Outcome.BlankName -> DesktopStrings.MSG_ERROR_BLANK_DESCRIPTION
                            else -> DesktopStrings.MSG_ERROR_INVALID_IMAGE
                        }
                    },
                    onMoveRequested = { photoMoveRel = photo.relPath },
                )
            }
        }

        photoMoveRel?.let { rel ->
            val currentParent = parentRelOf(rel)
            FolderPickerDialog(
                title = DesktopStrings.PHOTO_MOVE_TITLE,
                choices = controller.folderChoices().filterNot { it.relPath == currentParent },
                onDismiss = { photoMoveRel = null },
                onPick = { choice ->
                    photoMoveRel = null
                    scope.launch {
                        controller.movePhotosTo(listOf(rel), choice.relPath)
                        detailPhotoRel = null
                        message = DesktopStrings.MSG_PHOTOS_MOVED
                    }
                },
            )
        }

        coverSourceRel?.let { sourceRel ->
            LaunchedEffect(sourceRel) {
                val files = pickJpegFiles(DesktopStrings.SET_COVER, controller.rootDir.absolutePath)
                val head = files.firstOrNull()
                coverSourceRel = null
                if (head != null) {
                    val bytes = withContext(Dispatchers.IO) { runCatching { head.readBytes() }.getOrNull() }
                    if (bytes != null) {
                        val outcome = controller.setCover(sourceRel, bytes)
                        message = when (outcome) {
                            DesktopAppController.Outcome.Saved -> DesktopStrings.MSG_COVER_UPDATED
                            else -> DesktopStrings.MSG_ERROR_INVALID_IMAGE
                        }
                    }
                }
            }
        }

        pendingQueue.firstOrNull()?.let { head ->
            ImportQueueDialog(
                photo = head,
                remaining = pendingQueue.size - 1,
                aiEnabled = aiSettings.enabled && aiSettings.model.isNotBlank(),
                suggestion = aiSuggestions[head.id],
                onSave = { description, note ->
                    scope.launch {
                        val outcome = controller.importPendingPhoto(folderRel, description, note)
                        message = when (outcome) {
                            DesktopAppController.Outcome.Saved ->
                                if (controller.pendingQueue.value.isEmpty()) {
                                    DesktopStrings.MSG_IMPORT_DONE
                                } else {
                                    DesktopStrings.MSG_PHOTO_ADDED
                                }
                            DesktopAppController.Outcome.BlankName -> DesktopStrings.MSG_ERROR_BLANK_DESCRIPTION
                            else -> DesktopStrings.MSG_ERROR_INVALID_IMAGE
                        }
                    }
                },
                onCancel = {
                    controller.discardPendingImport()
                },
            )
        }

        if (showSettings) {
            SettingsDialog(
                controller = controller,
                onDismiss = { showSettings = false },
            )
        }

        // endregion

        // Ghost card following the pointer, above every other surface.
        dragController.payload?.let { payload ->
            dragController.pointer?.let { point ->
                DragGhost(controller = controller, payload = payload, point = point)
            }
        }
    }
}

/** The app bar: path title + breadcrumb, rescan, global search, report export, trash and settings. */
@Composable
private fun TopBar(
    folderRel: String,
    scanning: Boolean,
    showGlobalSearch: Boolean,
    showTrash: Boolean,
    onRescan: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportReport: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (folderRel.isEmpty()) {
                    DesktopStrings.TITLE_INVENTORY
                } else {
                    folderRel.substringAfterLast('/')
                },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = breadcrumbOf(folderRel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            if (scanning) {
                Text(
                    DesktopStrings.SCANNING_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            IconButton(onClick = onRescan) {
                Icon(Icons.Filled.Refresh, contentDescription = DesktopStrings.RESCAN)
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    imageVector = if (showGlobalSearch) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = DesktopStrings.SEARCH,
                )
            }
            TextButton(onClick = onExportReport) { Text(DesktopStrings.EXPORT_REPORT) }
            IconButton(onClick = onToggleTrash) {
                Icon(
                    imageVector = if (showTrash) Icons.Filled.Close else Icons.Filled.Delete,
                    contentDescription = DesktopStrings.TRASH,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = DesktopStrings.SETTINGS)
            }
        }
    }
}

/** The action strip: Up / Import / Add container, plus the storage line. */
@Composable
private fun ActionBar(
    folderRel: String,
    rootPath: String,
    onUp: () -> Unit,
    onImport: () -> Unit,
    onAddContainer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folderRel.isNotEmpty()) {
            TextButton(onClick = onUp) { Text(DesktopStrings.UP) }
        }
        if (folderRel.isNotEmpty()) {
            Button(onClick = onImport) { Text(DesktopStrings.IMPORT_PHOTOS) }
            Spacer(Modifier.width(8.dp))
        }
        Button(onClick = onAddContainer) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(DesktopStrings.ADD_CONTAINER)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "${DesktopStrings.STORAGE_LABEL} $rootPath",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Yellow warning strip when sync-conflicted entries await a resolve. */
@Composable
private fun ConflictBanner(count: Int) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = DesktopStrings.CONFLICT_BADGE, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (count == 1) {
                    DesktopStrings.CONFLICT_BANNER_ONE
                } else {
                    DesktopStrings.CONFLICT_BANNER_MANY.format(count)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Children rows + filtered photo grid for the folder on screen. */
@Composable
private fun BrowseView(
    controller: DesktopAppController,
    folderRel: String,
    query: String,
    includeSubtree: Boolean,
    onQueryChange: (String) -> Unit,
    onScopeChange: (Boolean) -> Unit,
    onOpenContainer: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onRename: (DesktopIndex.IndexedContainer) -> Unit,
    onMove: (String) -> Unit,
    onSetCover: (String) -> Unit,
    onRemoveCover: (String) -> Unit,
    onDelete: (DesktopIndex.IndexedContainer) -> Unit,
    dragController: DragController,
    dragOwner: String,
    gridState: LazyGridState,
    onGridBounds: (Rect) -> Unit,
) {
    val snapshot by controller.snapshot.collectAsState()
    val children = snapshot.containersByParent[folderRel].orEmpty()
    val photos = controller.photosIn(folderRel, query, includeSubtree)
    val isRoot = folderRel.isEmpty()

    if (children.isEmpty() && photos.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when {
                    query.isNotBlank() -> DesktopStrings.NO_RESULTS
                    isRoot -> DesktopStrings.INVENTORY_EMPTY
                    else -> DesktopStrings.EMPTY_FOLDER
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onGridBounds(it.boundsInRoot()) },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (children.isNotEmpty()) {
            item(key = "containers-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(DesktopStrings.CONTAINERS_SECTION)
            }
            items(children, key = { it.relPath }) { container ->
                val payload = dragController.payload
                val containerDrag = payload?.kind == DragKind.CONTAINER
                val draggedRel = if (containerDrag) payload?.relPaths?.firstOrNull() else null
                ContainerRow(
                    controller = controller,
                    container = container,
                    // Row line shows DIRECT child counts like the Android rows;
                    // the delete dialog is the one that wants subtree totals.
                    counts = (snapshot.containersByParent[container.relPath]?.size ?: 0) to
                        (snapshot.photosByParent[container.relPath]?.size ?: 0),
                    highlighted = payload != null && dragController.hoveredTarget == container.relPath,
                    blocked = containerDrag && container.relPath in dragController.blockedTargets,
                    dragging = container.relPath == draggedRel,
                    dragController = dragController,
                    dragOwner = dragOwner,
                    onOpen = { onOpenContainer(container.relPath) },
                    onRename = { onRename(container) },
                    onMove = { onMove(container.relPath) },
                    onSetCover = { onSetCover(container.relPath) },
                    onRemoveCover = { onRemoveCover(container.relPath) },
                    onDelete = { onDelete(container) },
                )
            }
        }
        item(key = "search-row", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(DesktopStrings.SEARCH_HINT) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilterChip(selected = !includeSubtree, onClick = { onScopeChange(false) }, label = { Text(DesktopStrings.SEARCH_SCOPE_HERE) })
                    FilterChip(selected = includeSubtree, onClick = { onScopeChange(true) }, label = { Text(DesktopStrings.SEARCH_SCOPE_SUBTREE) })
                }
            }
        }
        if (photos.isNotEmpty()) {
            item(key = "photos-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(DesktopStrings.PHOTOS_SECTION)
            }
            items(photos, key = { it.relPath }) { photo ->
                PhotoTile(
                    photo = photo,
                    controller = controller,
                    dragController = dragController,
                    onClick = { onOpenPhoto(photo.relPath) },
                )
            }
        } else if (query.isNotBlank()) {
            item(key = "no-results", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    DesktopStrings.NO_RESULTS,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                )
            }
        }
    }
}

/** One container row: cover, name (+conflict badge), direct child counts, menu. */
@Composable
private fun ContainerRow(
    controller: DesktopAppController,
    container: DesktopIndex.IndexedContainer,
    counts: Pair<Int, Int>,
    highlighted: Boolean,
    blocked: Boolean,
    dragging: Boolean,
    dragController: DragController,
    dragOwner: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onSetCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Gesture coordinates are local to the row; the session (ghost + drop
    // targets) speaks root coordinates, so the row origin translates them.
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val coverFile = rememberCoverFile(controller, container)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                rowOrigin = coordinates.boundsInRoot().topLeft
                dragController.registerTarget(dragOwner, container.relPath, coordinates.boundsInRoot())
            }
            .alpha(if (blocked || dragging) 0.4f else 1f)
            .then(
                if (highlighted) {
                    Modifier
                        .background(DropHighlightColor.copy(alpha = 0.12f))
                        .border(2.dp, DropHighlightColor, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onOpen)
            .pointerInput(container.relPath) {
                // No long-press on desktop: a press that moves past touch
                // slop starts the drag; a press+release without movement
                // stays a click (the drag detector consumes the moves, so
                // clickable's tap is cancelled).
                detectDragGestures(
                    onDragStart = { offset ->
                        dragController.start(
                            DragPayload(
                                relPaths = listOf(container.relPath),
                                label = container.name,
                                kind = DragKind.CONTAINER,
                            ),
                        )
                        dragController.pointer = rowOrigin + offset
                        // Cycle guard: the dragged container and its whole
                        // subtree become invalid drop targets for this drag.
                        scope.launch {
                            val resolved = controller.descendantFolders(container.relPath)
                            val current = dragController.payload
                            if (current != null &&
                                current.kind == DragKind.CONTAINER &&
                                current.relPaths.firstOrNull() == container.relPath
                            ) {
                                dragController.blockedTargets = resolved
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        dragController.pointer = rowOrigin + change.position
                    },
                    onDragEnd = { dragController.drop() },
                    // A destination change (spring-loaded navigation) disposes
                    // this row mid-gesture and cancels the detector; the root
                    // tracker owns the session now, so this must NOT end it.
                    onDragCancel = { },
                )
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (container.hasCover) {
            ImageTile(file = coverFile, size = 56.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(container.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (container.isConflict) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = DesktopStrings.CONFLICT_BADGE,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(16.dp),
                    )
                }
            }
            Text(
                text = counted(counts.first, DesktopStrings.CONTAINER_COUNTED_ONE, DesktopStrings.CONTAINERS_COUNTED) +
                    DesktopStrings.COUNT_SEPARATOR +
                    counted(counts.second, DesktopStrings.PHOTO_COUNTED_ONE, DesktopStrings.PHOTOS_COUNTED),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = DesktopStrings.CONTAINER_OPTIONS)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text(DesktopStrings.RENAME) }, onClick = { menuExpanded = false; onRename() })
                DropdownMenuItem(text = { Text(DesktopStrings.MOVE_CONTAINER_TO) }, onClick = { menuExpanded = false; onMove() })
                DropdownMenuItem(text = { Text(DesktopStrings.SET_COVER) }, onClick = { menuExpanded = false; onSetCover() })
                if (container.hasCover) {
                    DropdownMenuItem(text = { Text(DesktopStrings.REMOVE_COVER) }, onClick = { menuExpanded = false; onRemoveCover() })
                }
                DropdownMenuItem(text = { Text(DesktopStrings.DELETE) }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

/** A 1:1 photo tile with a description overlay and the conflict badge. */
@Composable
private fun PhotoTile(
    photo: DesktopIndex.IndexedPhoto,
    controller: DesktopAppController,
    dragController: DragController,
    onClick: () -> Unit,
) {
    // Gesture coordinates are local to this tile; the session (ghost + drop
    // targets) speaks root coordinates, so the tile origin translates them.
    var tileOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { tileOrigin = it.boundsInRoot().topLeft }
            .clickable(onClick = onClick)
            .pointerInput(photo.relPath) {
                // Press + move past slop starts a photo drag; press + release
                // without movement stays a click (tap is cancelled once the
                // drag detector consumes the moves).
                detectDragGestures(
                    onDragStart = { offset ->
                        dragController.start(
                            DragPayload(
                                relPaths = listOf(photo.relPath),
                                label = photo.description,
                                kind = DragKind.PHOTO,
                            ),
                        )
                        dragController.pointer = tileOrigin + offset
                    },
                    onDrag = { change, _ ->
                        dragController.pointer = tileOrigin + change.position
                    },
                    onDragEnd = { dragController.drop() },
                    // A destination change (spring-loaded navigation) disposes
                    // this tile mid-gesture and cancels the detector; the root
                    // tracker owns the session now, so this must NOT end it.
                    onDragCancel = { },
                )
            },
    ) {
        ImageTile(
            file = rememberPhotoFile(controller, photo),
            size = null,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = photo.description,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = androidx.compose.ui.graphics.Color.White,
        )
        if (photo.isConflict) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = DesktopStrings.CONFLICT_BADGE,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
    }
}

/** The global search view: a query field over every indexed photo. */
@Composable
private fun GlobalSearchView(
    controller: DesktopAppController,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenPhoto: (DesktopIndex.IndexedPhoto) -> Unit,
) {
    val snapshot by controller.snapshot.collectAsState()
    val results = remember(query, snapshot) { controller.searchAll(query) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(DesktopStrings.SEARCH_HINT) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )
        if (query.isNotBlank() && results.isEmpty()) {
            Text(
                DesktopStrings.NO_RESULTS,
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.relPath }) { photo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPhoto(photo) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ImageTile(file = rememberPhotoFile(controller, photo), size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(photo.description, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            photo.note ?: photo.parentRel.let { controller.labelOf(it).ifEmpty { DesktopStrings.TITLE_INVENTORY } },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (photo.isConflict) {
                        Icon(Icons.Filled.Warning, contentDescription = DesktopStrings.CONFLICT_BADGE, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// region small shared pieces

/**
 * The drag-time ancestor bar: a horizontally scrollable row with one chip per
 * ancestor of [folderRel] (root first, "Inventory" for `""`), each a drop
 * target registered under `"ancestor:<relative path>"`, plus the current
 * folder's own name as a non-target chip. Only shown while dragging and never
 * at the root.
 */
@Composable
private fun AncestorChipsRow(
    folderRel: String,
    hoveredTarget: String?,
    onRegister: (String, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ancestorPaths(folderRel).forEach { relPath ->
            val targetId = ANCESTOR_TARGET_PREFIX + relPath
            DropChip(
                label = if (relPath.isEmpty()) {
                    DesktopStrings.TITLE_INVENTORY
                } else {
                    relPath.substringAfterLast('/')
                },
                highlighted = hoveredTarget == targetId,
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    onRegister(targetId, coordinates.boundsInRoot())
                },
            )
        }
        // The current folder itself: orientation only, never a drop target.
        DropChip(
            label = folderRel.substringAfterLast('/'),
            highlighted = false,
        )
    }
}

/** One small ancestor-bar chip; the current-folder chip is never registered. */
@Composable
private fun DropChip(
    label: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = {},
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.then(
            if (highlighted) {
                Modifier.border(2.dp, DropHighlightColor, RoundedCornerShape(8.dp))
            } else {
                Modifier
            },
        ),
    )
}

/**
 * The drag-time "↑" chip: a navigation affordance that ascends one level.
 * It is not registered as a drop target — dropping on it does nothing.
 */
@Composable
private fun MoveUpChip(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 2.dp,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
    ) {
        Text(
            text = "↑",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * The floating ghost card under the pointer while a drag is in flight: the
 * dragged photo's thumbnail (decoded async with a placeholder) or a folder
 * icon plus the payload label. Single-item payloads never need a count.
 */
@Composable
private fun DragGhost(
    controller: DesktopAppController,
    payload: DragPayload,
    point: Offset,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 8.dp,
        modifier = Modifier
            .offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
            .widthIn(max = 180.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (payload.kind == DragKind.PHOTO) {
                val file by produceState<File?>(null, payload.relPaths.firstOrNull()) {
                    value = payload.relPaths.firstOrNull()?.let { controller.photoFile(it) }
                }
                ImageTile(file = file, size = 40.dp)
            } else {
                Icon(
                    imageVector = FolderIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = payload.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Ring/tint marking the container row currently under an in-flight drag. */
private val DropHighlightColor = Color(0xFF009688)

/** Dwell on a hovered row / the "↑" chip before the drag springs the screen. */
private const val SPRING_LOAD_DWELL_MS = 800L

/** Pointer distance from a grid edge that starts the drag auto-scroll. */
private const val AUTO_SCROLL_EDGE_DP = 64f

/** Auto-scroll speed at the very edge and at [AUTO_SCROLL_EDGE_DP] away (dp/frame). */
private const val AUTO_SCROLL_MIN_DP = 8f
private const val AUTO_SCROLL_MAX_DP = 30f

/**
 * Ancestor relative paths of [folderRel], root first (`""`), excluding the
 * folder itself. Empty when [folderRel] is already the root.
 */
private fun ancestorPaths(folderRel: String): List<String> {
    if (folderRel.isEmpty()) return emptyList()
    val segments = folderRel.split('/')
    val paths = mutableListOf("")
    var prefix = ""
    for (index in 0 until segments.lastIndex) {
        prefix = if (prefix.isEmpty()) segments[index] else "$prefix/${segments[index]}"
        paths += prefix
    }
    return paths
}

/** Auto-scroll speed for [distance] to an edge: max at 0, min at [edge]. */
private fun autoScrollSpeed(min: Float, max: Float, edge: Float, distance: Float): Float {
    val closeness = (1f - distance / edge).coerceIn(0f, 1f)
    return min + (max - min) * closeness
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun rememberCoverFile(controller: DesktopAppController, container: DesktopIndex.IndexedContainer): File? {
    var file by remember(container.relPath, container.hasCover) { mutableStateOf<File?>(null) }
    LaunchedEffect(container.relPath, container.hasCover) {
        file = if (container.hasCover) controller.coverFile(container.relPath) else null
    }
    return file
}

@Composable
private fun rememberPhotoFile(controller: DesktopAppController, photo: DesktopIndex.IndexedPhoto): File? =
    produceState<File?>(null, photo.relPath, photo.name) {
        value = controller.photoFile(photo.relPath)
    }.value

/** "Estate/Storage unit/Shelf A" → "Estate / Storage unit / Shelf A". */
private fun breadcrumbOf(folderRel: String): String =
    folderRel.split('/').filter(String::isNotBlank).joinToString(" / ")

private fun parentRelOf(rel: String): String? =
    rel.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }

private fun counted(value: Int, one: String, many: String): String =
    (if (value == 1) one else many).format(value)

private suspend fun withFilesRead(files: List<File>): List<ByteArray> =
    withContext(Dispatchers.IO) {
        files.mapNotNull { file -> runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } }
    }

private const val MESSAGE_MILLIS = 3_000L

// endregion
