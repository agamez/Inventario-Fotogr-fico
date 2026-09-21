package com.inventory.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inventory.desktop.DesktopAppController
import com.inventory.desktop.index.DesktopIndex
import java.io.File
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
    val scope = rememberCoroutineScope()

    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var showGlobalSearch by remember { mutableStateOf(false) }
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

    // The folder on screen vanished (deleted/renamed/moved elsewhere): ascend.
    LaunchedEffect(snapshot, folderRel) {
        if (folderRel.isNotEmpty() && folderRel !in snapshot.folders) {
            selectedFolder = parentRelOf(folderRel)
            message = DesktopStrings.MSG_CONTAINER_DELETED
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            folderRel = folderRel,
            scanning = scanning,
            showGlobalSearch = showGlobalSearch,
            onRescan = {
                controller.refresh()
                message = DesktopStrings.MSG_RESCANNED
            },
            onToggleSearch = {
                showGlobalSearch = !showGlobalSearch
                browseQuery = ""
            },
            onOpenSettings = { showSettings = true },
        )
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

        if (showGlobalSearch) {
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
            message = DesktopStrings.DELETE_CONTAINER_MESSAGE.format(target.name) +
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
}

/** The app bar: path title + breadcrumb, rescan, global-search toggle and settings. */
@Composable
private fun TopBar(
    folderRel: String,
    scanning: Boolean,
    showGlobalSearch: Boolean,
    onRescan: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSettings: () -> Unit,
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (children.isNotEmpty()) {
            item(key = "containers-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(DesktopStrings.CONTAINERS_SECTION)
            }
            items(children, key = { it.relPath }) { container ->
                ContainerRow(
                    controller = controller,
                    container = container,
                    // Row line shows DIRECT child counts like the Android rows;
                    // the delete dialog is the one that wants subtree totals.
                    counts = (snapshot.containersByParent[container.relPath]?.size ?: 0) to
                        (snapshot.photosByParent[container.relPath]?.size ?: 0),
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
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onSetCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val coverFile = rememberCoverFile(controller, container)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
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
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
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
