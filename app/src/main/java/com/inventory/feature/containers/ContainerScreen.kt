package com.inventory.feature.containers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.inventory.R
import com.inventory.database.ContainerEntity
import com.inventory.database.ContainerPath
import com.inventory.database.ContainerWithCounts
import com.inventory.database.PhotoEntity

/** Maximum number of images the gallery picker may return at once. */
private const val MAX_GALLERY_IMAGES = 10

/**
 * The one browse screen of the hierarchical inventory, used for the virtual
 * root ([nodeId] = 0, home: top-level containers, global search icon, FAB
 * adds a container) and for any container (children + photos, in-container
 * search, long-press multi-select, full-screen preview, cover and subtree
 * actions in the overflow menu).
 *
 * Navigation is owned by the caller: [onOpenContainer] descends into a
 * child, [onOpenCamera] and the gallery picker attach photos to this node,
 * [onOpenCoverCapture] photographs a cover for the addressed container,
 * [onOpenSearch] opens the global search and [onBack] ascends (unused at
 * the root).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContainerScreen(
    nodeId: Long,
    onOpenContainer: (Long) -> Unit,
    onOpenCamera: (Long) -> Unit,
    onOpenCoverCapture: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
    viewModel: ContainerViewModel = hiltViewModel(),
) {
    val isRoot = nodeId == 0L
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingQueue by viewModel.pendingQueue.collectAsStateWithLifecycle()
    val pendingCover by viewModel.pendingCover.collectAsStateWithLifecycle()
    val photoMoveTargets by viewModel.photoMoveTargets.collectAsStateWithLifecycle()
    val movableTargets by viewModel.movableTargets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var searchExpanded by remember { mutableStateOf(false) }
    var showFabSheet by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var containerToRename by remember { mutableStateOf<RenameTarget?>(null) }
    var containerToMove by remember { mutableStateOf<Long?>(null) }
    var showPhotoMoveSheet by remember { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<PhotoEntity?>(null) }
    var fullScreenPhotoId by remember { mutableStateOf<Long?>(null) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var coverTargetId by remember { mutableStateOf<Long?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_GALLERY_IMAGES),
    ) { uris -> viewModel.onGalleryUrisPicked(uris) }

    val coverPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val targetId = coverTargetId
        if (uri != null && targetId != null) {
            viewModel.onCoverImagePicked(targetId, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ContainerViewModel.ContainerEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    context.getString(event.resId, *event.formatArgs.toTypedArray()),
                )
            }
        }
    }

    // The container on screen vanished (deleted from this very screen's
    // menu): ascend instead of staring at an empty title.
    LaunchedEffect(uiState.isLoading, uiState.container) {
        if (!isRoot && !uiState.isLoading && uiState.container == null) onBack()
    }

    val selectedCount = uiState.selectedPhotoIds.size

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                if (uiState.selectionMode) {
                    TopAppBar(
                        title = {
                            Text(
                                pluralStringResource(
                                    R.plurals.selected_count,
                                    selectedCount,
                                    selectedCount,
                                ),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.selection_exit),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { showPhotoMoveSheet = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                    contentDescription = stringResource(R.string.selection_move),
                                )
                            }
                            IconButton(onClick = { confirmDeleteSelection = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.selection_delete),
                                )
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = if (isRoot) {
                                    stringResource(R.string.title_inventory)
                                } else {
                                    uiState.container?.name
                                        ?: stringResource(R.string.container_fallback_title)
                                },
                            )
                        },
                        navigationIcon = {
                            if (!isRoot) {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.navigate_back),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (isRoot) {
                                IconButton(onClick = onOpenSearch) {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = stringResource(R.string.open_search),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        if (searchExpanded || uiState.searchQuery.isNotEmpty()) {
                                            searchExpanded = false
                                            viewModel.onSearchQueryChange("")
                                        } else {
                                            searchExpanded = true
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (searchExpanded) {
                                            Icons.Filled.Close
                                        } else {
                                            Icons.Filled.Search
                                        },
                                        contentDescription = stringResource(R.string.search_toggle),
                                    )
                                }
                                uiState.container?.let { container ->
                                    ContainerOptionsAction(
                                        container = container,
                                        onRename = { id, name -> containerToRename = RenameTarget(id, name) },
                                        onMove = { id ->
                                            viewModel.requestMove(id)
                                            containerToMove = id
                                        },
                                        onSetPhoto = { id ->
                                            coverTargetId = id
                                            showCoverSheet = true
                                        },
                                        onRemovePhoto = viewModel::removeCoverImage,
                                        onDelete = { id, name ->
                                            viewModel.requestDelete(id, name)
                                        },
                                    )
                                }
                            }
                        },
                    )
                    if (uiState.searchQuery.isNotEmpty() ||
                        (searchExpanded && uiState.photos.isNotEmpty())
                    ) {
                        SearchField(
                            query = uiState.searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!uiState.selectionMode) {
                FloatingActionButton(
                    onClick = {
                        if (isRoot) showCreateDialog = true else showFabSheet = true
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(
                            if (isRoot) R.string.add_container else R.string.add,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.children.isEmpty() &&
                    (isRoot || (uiState.photos.isEmpty() && uiState.searchQuery.isBlank())) -> {
                    val messageResId = if (isRoot) R.string.inventory_empty else R.string.empty_containers
                    EmptyContainerPlaceholder(
                        messageResId = messageResId,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (uiState.children.isNotEmpty()) {
                            item(
                                key = "containers-header",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                SectionHeader(stringResource(R.string.containers_section))
                            }
                            items(
                                items = uiState.children,
                                key = { entry -> entry.container.id },
                                span = { GridItemSpan(maxLineSpan) },
                            ) { entry ->
                                ContainerRow(
                                    entry = entry,
                                    onOpen = { onOpenContainer(entry.container.id) },
                                    onRename = { id, name -> containerToRename = RenameTarget(id, name) },
                                    onMove = { id ->
                                        viewModel.requestMove(id)
                                        containerToMove = id
                                    },
                                    onSetPhoto = { id ->
                                        coverTargetId = id
                                        showCoverSheet = true
                                    },
                                    onRemovePhoto = viewModel::removeCoverImage,
                                    onDelete = { id, name -> viewModel.requestDelete(id, name) },
                                )
                            }
                        }
                        if (!isRoot) {
                            if (uiState.photos.isNotEmpty()) {
                                item(
                                    key = "photos-header",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    SectionHeader(stringResource(R.string.photos_section))
                                }
                                items(
                                    items = uiState.photos,
                                    key = { photo -> photo.id },
                                ) { photo ->
                                    PhotoTile(
                                        photo = photo,
                                        selectionMode = uiState.selectionMode,
                                        selected = photo.id in uiState.selectedPhotoIds,
                                        onClick = {
                                            if (uiState.selectionMode) {
                                                viewModel.toggleSelection(photo.id)
                                            } else {
                                                fullScreenPhotoId = photo.id
                                            }
                                        },
                                        onLongClick = { viewModel.enterSelectionMode(photo.id) },
                                    )
                                }
                            } else if (uiState.searchQuery.isNotBlank()) {
                                item(
                                    key = "no-results",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    Text(
                                        text = stringResource(R.string.no_search_results),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFabSheet) {
        AddOptionsSheet(
            onDismiss = { showFabSheet = false },
            onAddContainer = {
                showFabSheet = false
                showCreateDialog = true
            },
            onTakePhoto = {
                showFabSheet = false
                onOpenCamera(nodeId)
            },
            onChooseFromGallery = {
                showFabSheet = false
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    if (showCreateDialog) {
        ContainerNameDialog(
            title = stringResource(R.string.add_container),
            initialName = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createContainer(name)
                showCreateDialog = false
            },
        )
    }

    containerToRename?.let { target ->
        key(target.id) {
            ContainerNameDialog(
                title = stringResource(R.string.rename_container),
                initialName = target.name,
                onDismiss = { containerToRename = null },
                onConfirm = { name ->
                    viewModel.renameContainer(target.id, name)
                    containerToRename = null
                },
            )
        }
    }

    containerToMove?.let { sourceId ->
        ContainerPathPickerSheet(
            paths = movableTargets,
            onDismiss = { containerToMove = null },
            onPathSelected = { path ->
                viewModel.moveContainerTo(sourceId, path.id)
                containerToMove = null
            },
        )
    }

    if (showPhotoMoveSheet) {
        ContainerPathPickerSheet(
            paths = photoMoveTargets,
            onDismiss = { showPhotoMoveSheet = false },
            onPathSelected = { path ->
                viewModel.moveSelected(path.id)
                showPhotoMoveSheet = false
            },
        )
    }

    uiState.deleteCandidate?.let { candidate ->
        DeleteContainerDialog(
            candidate = candidate,
            onDismiss = viewModel::dismissDelete,
            onConfirm = viewModel::confirmDelete,
        )
    }

    if (confirmDeleteSelection) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelection = false },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Text(stringResource(R.string.delete_photos_confirm, selectedCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        confirmDeleteSelection = false
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelection = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    photoToDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { photoToDelete = null },
            title = { Text(stringResource(R.string.delete_photo_title)) },
            text = {
                Text(stringResource(R.string.delete_photo_message, photo.description))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePhoto(photo.id)
                        if (fullScreenPhotoId == photo.id) fullScreenPhotoId = null
                        photoToDelete = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { photoToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val fullScreenPhoto = uiState.photos.firstOrNull { it.id == fullScreenPhotoId }
    LaunchedEffect(fullScreenPhotoId, fullScreenPhoto) {
        if (fullScreenPhotoId != null && fullScreenPhoto == null) fullScreenPhotoId = null
    }
    fullScreenPhoto?.let { photo ->
        key(photo.id) {
            FullScreenPhotoDialog(
                photo = photo,
                onClose = { fullScreenPhotoId = null },
                onSave = { description, note ->
                    viewModel.updatePhotoDetails(photo.id, description, note)
                    fullScreenPhotoId = null
                },
                onDelete = { photoToDelete = photo },
            )
        }
    }

    pendingQueue.firstOrNull()?.let { head ->
        key(head) {
            PendingPhotoSaveSheet(
                imageBytes = head,
                remainingCount = pendingQueue.size - 1,
                onSave = { description, note ->
                    viewModel.savePendingPhoto(description, note)
                },
                onCancel = viewModel::discardPendingPhotos,
            )
        }
    }

    if (showCoverSheet) {
        CoverSourceSheet(
            onDismiss = { showCoverSheet = false },
            onTakePhoto = {
                showCoverSheet = false
                coverTargetId?.let { containerId -> onOpenCoverCapture(containerId) }
            },
            onChooseFromGallery = {
                showCoverSheet = false
                coverPickLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    pendingCover?.let { pending ->
        key(pending) {
            CoverConfirmDialog(
                imageBytes = pending.bytes,
                onSave = viewModel::confirmCoverUpload,
                onDiscard = viewModel::discardCoverUpload,
            )
        }
    }
}

/** Rename dialog target: the row/menu entry point only knows id and name. */
private data class RenameTarget(val id: Long, val name: String)

/** App-bar overflow button opening the container actions menu. */
@Composable
private fun ContainerOptionsAction(
    container: ContainerEntity,
    onRename: (Long, String) -> Unit,
    onMove: (Long) -> Unit,
    onSetPhoto: (Long) -> Unit,
    onRemovePhoto: (Long) -> Unit,
    onDelete: (Long, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.container_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ContainerMenuItems(
                container = container,
                onRequestClose = { expanded = false },
                onRename = onRename,
                onMove = onMove,
                onSetPhoto = onSetPhoto,
                onRemovePhoto = onRemovePhoto,
                onDelete = onDelete,
            )
        }
    }
}

/** The shared action list: rename, move, cover set/remove, delete. */
@Composable
private fun ContainerMenuItems(
    container: ContainerEntity,
    onRequestClose: () -> Unit,
    onRename: (Long, String) -> Unit,
    onMove: (Long) -> Unit,
    onSetPhoto: (Long) -> Unit,
    onRemovePhoto: (Long) -> Unit,
    onDelete: (Long, String) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.rename)) },
        onClick = {
            onRequestClose()
            onRename(container.id, container.name)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.move_container_to)) },
        onClick = {
            onRequestClose()
            onMove(container.id)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.set_photo)) },
        onClick = {
            onRequestClose()
            onSetPhoto(container.id)
        },
    )
    if (container.coverImage != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remove_photo)) },
            onClick = {
                onRequestClose()
                onRemovePhoto(container.id)
            },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.delete)) },
        onClick = {
            onRequestClose()
            onDelete(container.id, container.name)
        },
    )
}

/** Full-span child row: cover, name, "n containers · m photos", options menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContainerRow(
    entry: ContainerWithCounts,
    onOpen: () -> Unit,
    onRename: (Long, String) -> Unit,
    onMove: (Long) -> Unit,
    onSetPhoto: (Long) -> Unit,
    onRemovePhoto: (Long) -> Unit,
    onDelete: (Long, String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuExpanded = true },
                ),
            leadingContent = { CoverThumbnail(entry.container.coverImage) },
            headlineContent = {
                Text(
                    text = entry.container.name,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Text(
                    text = pluralStringResource(
                        R.plurals.container_count,
                        entry.childCount,
                        entry.childCount,
                    ) +
                        " · " +
                        pluralStringResource(
                            R.plurals.photo_count,
                            entry.photoCount,
                            entry.photoCount,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.container_options),
                    )
                }
            },
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            ContainerMenuItems(
                container = entry.container,
                onRequestClose = { menuExpanded = false },
                onRename = onRename,
                onMove = onMove,
                onSetPhoto = onSetPhoto,
                onRemovePhoto = onRemovePhoto,
                onDelete = onDelete,
            )
        }
        HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Row thumbnail: the saved cover, or a neutral folder placeholder. */
@Composable
private fun CoverThumbnail(coverImage: ByteArray?) {
    if (coverImage != null) {
        AsyncImage(
            model = coverImage,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Small full-width label separating the containers and photos sections. */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** FAB sheet: the three ways to put something into this container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddOptionsSheet(
    onDismiss: () -> Unit,
    onAddContainer: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFromGallery: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddContainer),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.add_container),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTakePhoto),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.take_photo),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onChooseFromGallery),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.choose_from_gallery),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Container picker shared by the container-move and photo-move sheets:
 * full paths, path-sorted; an explanatory message when nothing can be
 * listed (e.g. this is the only container).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerPathPickerSheet(
    paths: List<ContainerPath>,
    onDismiss: () -> Unit,
    onPathSelected: (ContainerPath) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.move_container_to),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (paths.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_other_containers),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(
                    items = paths,
                    key = { path -> path.id },
                ) { path ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPathSelected(path) },
                        headlineContent = {
                            Text(
                                text = path.path,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** Delete confirmation sized by the subtree counts in [candidate]. */
@Composable
private fun DeleteContainerDialog(
    candidate: ContainerViewModel.DeleteCandidate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_container_title)) },
        text = {
            Text(
                stringResource(
                    R.string.delete_container_message,
                    candidate.name,
                    candidate.childContainers,
                    candidate.childPhotos,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Rounded search input with leading icon and a trailing clear button. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldShape = RoundedCornerShape(28.dp)
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.search_photos)) },
        singleLine = true,
        shape = fieldShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.clear_search),
                    )
                }
            }
        },
    )
}

/** 1:1 grid tile: image, bottom scrim with the description, selection cues. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    photo: PhotoEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 4.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        AsyncImage(
            model = photo.image,
            contentDescription = photo.description,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0x99000000)),
                    ),
                ),
        )
        Text(
            text = photo.description,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(8.dp),
        )
        if (selectionMode) {
            Icon(
                imageVector = if (selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Outlined.CheckCircle
                },
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp),
            )
        }
    }
}

/** Sheet offering the two ways to pick a cover photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSourceSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFromGallery: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTakePhoto),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.take_photo),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onChooseFromGallery),
                headlineContent = {
                    Text(
                        text = stringResource(R.string.choose_from_gallery),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                    )
                },
            )
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Lightweight preview confirm for a gallery-picked cover (no metadata). */
@Composable
private fun CoverConfirmDialog(
    imageBytes: ByteArray,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(stringResource(R.string.set_photo)) },
        text = {
            AsyncImage(
                model = imageBytes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Shared create/rename dialog: blank input disables Save; text state is local. */
@Composable
private fun ContainerNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.container_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Mandatory-metadata sheet for the head of the pending queue: preview,
 * required description, optional note and the remaining-images caption.
 * Dismissing discards the whole queue, per the product decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingPhotoSaveSheet(
    imageBytes: ByteArray,
    remainingCount: Int,
    onSave: (String, String?) -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.4f).dp

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            if (remainingCount > 0) {
                Text(
                    text = stringResource(R.string.photos_pending_count, remainingCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            AsyncImage(
                model = imageBytes,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.photo_description_required_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.photo_note_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(
                    onClick = { onSave(description, note.ifBlank { null }) },
                    enabled = description.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save_photo))
                }
            }
        }
    }
}

/**
 * Full-screen preview: edge-to-edge [Dialog] so this composable — and the
 * screen's ViewModel — stay alive while it is open. Metadata is editable in
 * place; Save updates, Delete asks for confirmation first.
 */
@Composable
private fun FullScreenPhotoDialog(
    photo: PhotoEntity,
    onClose: () -> Unit,
    onSave: (String, String?) -> Unit,
    onDelete: () -> Unit,
) {
    var description by remember { mutableStateOf(photo.description) }
    var note by remember { mutableStateOf(photo.note.orEmpty()) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close_preview),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.delete))
                    }
                    Button(
                        onClick = { onSave(description, note.ifBlank { null }) },
                        enabled = description.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
                AsyncImage(
                    model = photo.image,
                    contentDescription = photo.description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.photo_description_required_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.photo_note_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyContainerPlaceholder(
    messageResId: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(messageResId),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
