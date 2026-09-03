package com.inventory.feature.containers

import android.Manifest
import android.content.Intent
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.FilterChip
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.inventory.R
import com.inventory.cache.CacheEntry
import com.inventory.cache.ChildCounts
import com.inventory.storage.StorageMode
import com.inventory.ui.rememberBackendModel
import com.inventory.ui.rememberPreviewBitmap

/** Maximum number of images the gallery picker may return at once. */
private const val MAX_GALLERY_IMAGES = 10

/**
 * The one browse screen of the filesystem inventory, used for the storage
 * root (home — wrapped in the storage gate/chooser, top-level containers,
 * global search icon, FAB adds a container) and for any container folder
 * addressed by its RELATIVE path (children + photos, in-container search
 * with a here/subtree scope, long-press multi-select, full-screen
 * preview, cover and subtree actions in the overflow menu, "Rescan
 * storage" and "Storage mode").
 *
 * Rows are cache entries keyed on relative paths; pictures are loaded
 * through the ACTIVE storage backend (Coil models resolved via the
 * ViewModel with remember+LaunchedEffect), so the same UI works over a
 * picked SAF tree and over direct file access.
 *
 * Navigation is owned by the caller: [onOpenContainer] descends into a
 * child folder, [onOpenCamera] and the gallery picker attach photos to
 * this folder, [onOpenCoverCapture] photographs a cover for the addressed
 * folder (relative-path arguments), [onOpenSearch] opens the global
 * search, [onBack] ascends (and also leaves a folder that was just
 * renamed/moved/deleted — its route went stale).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContainerScreen(
    onOpenContainer: (String) -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenCoverCapture: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
    viewModel: ContainerViewModel = hiltViewModel(),
) {
    val isRoot = viewModel.isRoot
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val storage by viewModel.storageStatus.collectAsStateWithLifecycle()
    val pendingQueue by viewModel.pendingQueue.collectAsStateWithLifecycle()
    val pendingCover by viewModel.pendingCover.collectAsStateWithLifecycle()
    val photoMoveTargets by viewModel.photoMoveTargets.collectAsStateWithLifecycle()
    val movableTargets by viewModel.movableTargets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var searchExpanded by remember { mutableStateOf(false) }
    var showFabSheet by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<RenameTarget?>(null) }
    var folderToMove by remember { mutableStateOf<String?>(null) }
    var showPhotoMoveSheet by remember { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<CacheEntry?>(null) }
    var fullScreenPhotoRel by remember { mutableStateOf<String?>(null) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var coverTargetRel by remember { mutableStateOf<String?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_GALLERY_IMAGES),
    ) { uris -> viewModel.onGalleryUrisPicked(uris) }

    val coverPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val target = coverTargetRel
        if (uri != null && target != null) {
            viewModel.onCoverImagePicked(target, uri)
        }
    }

    // SAF folder chooser: ACTION_OPEN_DOCUMENT_TREE launched with the
    // store's hint intent (result arrives as a tree uri to persist).
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onTreePicked(
            if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null,
        )
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.recheckStorage() }

    // Re-check on resume: All Files Access is granted on a system settings
    // page and SAF trees can vanish from under us (the picker flow needs
    // no re-check — results arrive via [treeLauncher] directly).
    LifecycleResumeEffect(Unit) {
        viewModel.recheckStorage()
        onPauseOrDispose { }
    }

    // The moment the active backend is usable, the root must exist and the
    // index must fill.
    LaunchedEffect(storage.ready) {
        if (storage.ready) viewModel.onStorageReady()
    }

    fun launchFileModeAccess() {
        val intent: Intent? = viewModel.fileModeIntent(context)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ContainerViewModel.ContainerEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    context.getString(event.resId, *event.formatArgs.toTypedArray()),
                )
                ContainerViewModel.ContainerEvent.PopBackStack -> onBack()
            }
        }
    }

    // The folder on screen vanished (deleted, or renamed/moved through its
    // own menu): ascend instead of staring at an empty title.
    LaunchedEffect(uiState.folderMissing) {
        if (uiState.folderMissing) onBack()
    }

    // Root destination behind the storage gate: nothing else is reachable
    // until a backend is chosen AND can actually read and write the tree.
    if (isRoot && !storage.ready) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.title_inventory)) })
            },
        ) { innerPadding ->
            StorageModeChooser(
                currentMode = storage.mode,
                onPickFolder = { treeLauncher.launch(viewModel.safPickIntent(context)) },
                onFileMode = {
                    viewModel.switchToFileMode()
                    launchFileModeAccess()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
        return
    }

    val selectedCount = uiState.selectedPaths.size

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
                                    uiState.containerName.ifEmpty {
                                        stringResource(R.string.container_fallback_title)
                                    }
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
                                IconButton(onClick = viewModel::rescanNow) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.rescan_storage),
                                    )
                                }
                                RootOptionsAction(
                                    onRescan = viewModel::rescanNow,
                                    onStorageMode = { showStorageDialog = true },
                                )
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
                                ContainerOptionsAction(
                                    folderRel = viewModel.folderRel,
                                    folderName = uiState.containerName,
                                    hasCover = uiState.hasCover,
                                    onRename = { rel, name -> folderToRename = RenameTarget(rel, name) },
                                    onMove = { rel ->
                                        viewModel.requestMove(rel)
                                        folderToMove = rel
                                    },
                                    onSetPhoto = { rel ->
                                        coverTargetRel = rel
                                        showCoverSheet = true
                                    },
                                    onRemovePhoto = viewModel::removeCoverImage,
                                    onRescan = viewModel::rescanNow,
                                    onStorageMode = { showStorageDialog = true },
                                    onDelete = viewModel::requestDelete,
                                )
                            }
                        },
                    )
                    // The field must stay open while it is being typed in,
                    // even when the current query/scope yields no photos at
                    // all (e.g. subtree searching an empty folder).
                    if (uiState.searchQuery.isNotEmpty() || searchExpanded) {
                        SearchField(
                            query = uiState.searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        )
                        SearchScopeRow(
                            scopeHere = uiState.searchScopeHere,
                            onScopeChange = viewModel::onScopeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (uiState.scanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.conflictCount > 0) {
                ConflictBanner(
                    count = uiState.conflictCount,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    uiState.children.isEmpty() &&
                        (isRoot || (uiState.photos.isEmpty() && uiState.searchQuery.isBlank())) -> {
                        val messageResId = if (isRoot) {
                            R.string.inventory_empty
                        } else {
                            R.string.empty_containers
                        }
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
                                    key = { entry -> entry.relPath },
                                    span = { GridItemSpan(maxLineSpan) },
                                ) { entry ->
                                    ContainerRow(
                                        entry = entry,
                                        counts = uiState.childCounts[entry.relPath],
                                        coverLoader = viewModel::coverModel,
                                        onOpen = { onOpenContainer(entry.relPath) },
                                        onRename = { rel, name -> folderToRename = RenameTarget(rel, name) },
                                        onMove = { rel ->
                                            viewModel.requestMove(rel)
                                            folderToMove = rel
                                        },
                                        onSetPhoto = { rel ->
                                            coverTargetRel = rel
                                            showCoverSheet = true
                                        },
                                        onRemovePhoto = viewModel::removeCoverImage,
                                        onDelete = { rel, name -> viewModel.requestDelete(rel, name) },
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
                                        key = { photo -> photo.relPath },
                                    ) { photo ->
                                        PhotoTile(
                                            photo = photo,
                                            selectionMode = uiState.selectionMode,
                                            selected = photo.relPath in uiState.selectedPaths,
                                            photoLoader = viewModel::photoModel,
                                            onClick = {
                                                if (uiState.selectionMode) {
                                                    viewModel.toggleSelection(photo.relPath)
                                                } else {
                                                    fullScreenPhotoRel = photo.relPath
                                                }
                                            },
                                            onLongClick = { viewModel.enterSelectionMode(photo.relPath) },
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
                onOpenCamera(viewModel.folderRel)
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

    if (showStorageDialog) {
        StorageModeDialog(
            currentMode = storage.mode,
            onDismiss = { showStorageDialog = false },
            onPickFolder = {
                showStorageDialog = false
                treeLauncher.launch(viewModel.safPickIntent(context))
            },
            onFileMode = {
                showStorageDialog = false
                viewModel.switchToFileMode()
                launchFileModeAccess()
            },
        )
    }

    folderToRename?.let { target ->
        key(target.relPath) {
            ContainerNameDialog(
                title = stringResource(R.string.rename_container),
                initialName = target.name,
                onDismiss = { folderToRename = null },
                onConfirm = { name ->
                    viewModel.renameFolder(target.relPath, name)
                    folderToRename = null
                },
            )
        }
    }

    folderToMove?.let { sourceRel ->
        ContainerPathPickerSheet(
            paths = movableTargets,
            onDismiss = { folderToMove = null },
            onPathSelected = { choice ->
                viewModel.moveFolderTo(sourceRel, choice.relPath)
                folderToMove = null
            },
        )
    }

    if (showPhotoMoveSheet) {
        ContainerPathPickerSheet(
            paths = photoMoveTargets,
            onDismiss = { showPhotoMoveSheet = false },
            onPathSelected = { choice ->
                viewModel.moveSelected(choice.relPath)
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
                Text(
                    stringResource(
                        R.string.delete_photo_message,
                        photo.description ?: photo.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePhoto(photo.relPath)
                        if (fullScreenPhotoRel == photo.relPath) fullScreenPhotoRel = null
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

    val fullScreenPhoto = uiState.photos.firstOrNull { it.relPath == fullScreenPhotoRel }
    LaunchedEffect(fullScreenPhotoRel, fullScreenPhoto) {
        if (fullScreenPhotoRel != null && fullScreenPhoto == null) fullScreenPhotoRel = null
    }
    fullScreenPhoto?.let { photo ->
        key(photo.relPath) {
            FullScreenPhotoDialog(
                photo = photo,
                model = rememberBackendModel(photo.relPath, viewModel::photoModel),
                onClose = { fullScreenPhotoRel = null },
                onSave = { description, note ->
                    viewModel.updatePhotoDetails(photo, description, note)
                    fullScreenPhotoRel = null
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
                coverTargetRel?.let { onOpenCoverCapture(it) }
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

/** Rename dialog target: the row/menu entry point only knows relative path and name. */
private data class RenameTarget(val relPath: String, val name: String)

/**
 * Full-screen gate shown on the root destination until a storage backend
 * is usable: with no mode chosen yet it is the chooser ("Pick a folder" —
 * zero permissions, the default — or "All files access"); with a chosen
 * mode that is currently blocked (grant missing, tree gone) it repeats
 * the rationale with the one action that recovers that mode. Re-checked on
 * resume (grants happen outside the app).
 */
@Composable
private fun StorageModeChooser(
    currentMode: StorageMode?,
    onPickFolder: () -> Unit,
    onFileMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.storage_permission_rationale),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        when (currentMode) {
            null -> {
                // First launch: SAF is the recommended default.
                Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pick_folder))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onFileMode, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.all_files_access))
                }
            }

            StorageMode.SAF -> {
                // Tree revoked or gone: re-picking the same folder restores it.
                Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pick_folder))
                }
            }

            StorageMode.FILE -> {
                // Grant missing: relaunch the settings/runtime flow first,
                // with the zero-permission SAF mode as the escape hatch.
                Button(onClick = onFileMode, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.grant_storage_access))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pick_folder))
                }
            }
        }
    }
}

/** Overflow dialog: shows the active mode and switches to the other one. */
@Composable
private fun StorageModeDialog(
    currentMode: StorageMode?,
    onDismiss: () -> Unit,
    onPickFolder: () -> Unit,
    onFileMode: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_mode)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.storage_permission_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when (currentMode) {
                        StorageMode.SAF -> stringResource(R.string.mode_saf_label)
                        StorageMode.FILE -> stringResource(R.string.mode_file_label)
                        null -> "—"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pick_folder))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onFileMode, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.all_files_access))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Full-width warning strip listing how many sync-conflicted entries await. */
@Composable
private fun ConflictBanner(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = pluralStringResource(R.plurals.conflict_banner, count, count),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The two search-scope chips: photos of this folder only, or of the subtree. */
@Composable
private fun SearchScopeRow(
    scopeHere: Boolean,
    onScopeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = scopeHere,
            onClick = { onScopeChange(true) },
            label = { Text(stringResource(R.string.search_scope_here)) },
        )
        FilterChip(
            selected = !scopeHere,
            onClick = { onScopeChange(false) },
            label = { Text(stringResource(R.string.search_scope_subtree)) },
        )
    }
}

/** App-bar overflow button for the root: rescan + the storage-mode entry point. */
@Composable
private fun RootOptionsAction(
    onRescan: () -> Unit,
    onStorageMode: () -> Unit,
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
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rescan_storage)) },
                onClick = {
                    expanded = false
                    onRescan()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.storage_mode)) },
                onClick = {
                    expanded = false
                    onStorageMode()
                },
            )
        }
    }
}

/** App-bar overflow button opening the folder actions menu. */
@Composable
private fun ContainerOptionsAction(
    folderRel: String,
    folderName: String,
    hasCover: Boolean,
    onRename: (String, String) -> Unit,
    onMove: (String) -> Unit,
    onSetPhoto: (String) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onRescan: () -> Unit,
    onStorageMode: () -> Unit,
    onDelete: (String, String) -> Unit,
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
            FolderMenuItems(
                folderRel = folderRel,
                folderName = folderName,
                hasCover = hasCover,
                onRequestClose = { expanded = false },
                onRename = onRename,
                onMove = onMove,
                onSetPhoto = onSetPhoto,
                onRemovePhoto = onRemovePhoto,
                onRescan = onRescan,
                onStorageMode = onStorageMode,
                onDelete = onDelete,
            )
        }
    }
}

/**
 * The shared action list: rename, move, cover set/remove, rescan and
 * storage mode (only where offered — the screen menu, not child rows),
 * delete.
 */
@Composable
private fun FolderMenuItems(
    folderRel: String,
    folderName: String,
    hasCover: Boolean,
    onRequestClose: () -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String) -> Unit,
    onSetPhoto: (String) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onRescan: (() -> Unit)?,
    onStorageMode: (() -> Unit)?,
    onDelete: (String, String) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.rename)) },
        onClick = {
            onRequestClose()
            onRename(folderRel, folderName)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.move_container_to)) },
        onClick = {
            onRequestClose()
            onMove(folderRel)
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.set_photo)) },
        onClick = {
            onRequestClose()
            onSetPhoto(folderRel)
        },
    )
    if (hasCover) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remove_photo)) },
            onClick = {
                onRequestClose()
                onRemovePhoto(folderRel)
            },
        )
    }
    if (onRescan != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.rescan_storage)) },
            onClick = {
                onRequestClose()
                onRescan()
            },
        )
    }
    if (onStorageMode != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.storage_mode)) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                )
            },
            onClick = {
                onRequestClose()
                onStorageMode()
            },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.delete)) },
        onClick = {
            onRequestClose()
            onDelete(folderRel, folderName)
        },
    )
}

/** Full-span child row: cover, name, "n containers · m photos", options menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContainerRow(
    entry: CacheEntry,
    counts: ChildCounts?,
    coverLoader: suspend (String) -> Any?,
    onOpen: () -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String) -> Unit,
    onSetPhoto: (String) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDelete: (String, String) -> Unit,
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
            leadingContent = { CoverThumbnail(entry, coverLoader) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.isConflict) {
                        ConflictBadge()
                    }
                }
            },
            supportingContent = {
                Text(
                    text = pluralStringResource(
                        R.plurals.container_count,
                        counts?.folders ?: 0,
                        counts?.folders ?: 0,
                    ) +
                        " · " +
                        pluralStringResource(
                            R.plurals.photo_count,
                            counts?.photos ?: 0,
                            counts?.photos ?: 0,
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
            FolderMenuItems(
                folderRel = entry.relPath,
                folderName = entry.name,
                hasCover = entry.hasCover,
                onRequestClose = { menuExpanded = false },
                onRename = onRename,
                onMove = onMove,
                onSetPhoto = onSetPhoto,
                onRemovePhoto = onRemovePhoto,
                onRescan = null,
                onStorageMode = null,
                onDelete = onDelete,
            )
        }
        HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Row thumbnail: the folder's `_cover.jpg` through the active backend. */
@Composable
private fun CoverThumbnail(entry: CacheEntry, coverLoader: suspend (String) -> Any?) {
    if (entry.hasCover) {
        AsyncImage(
            model = rememberBackendModel(entry.relPath, coverLoader),
            contentDescription = null,
            placeholder = null,
            error = null,
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

/** Small warning marker on entries whose name carries a sync-conflict stamp. */
@Composable
private fun ConflictBadge(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = stringResource(R.string.conflict_badge),
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier
            .padding(start = 6.dp)
            .size(18.dp),
    )
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
 * Container folder picker shared by the container-move and photo-move
 * sheets: relative paths, path-sorted; an explanatory message when nothing
 * can be listed (e.g. this is the only container).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContainerPathPickerSheet(
    paths: List<ContainerViewModel.FolderChoice>,
    onDismiss: () -> Unit,
    onPathSelected: (ContainerViewModel.FolderChoice) -> Unit,
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
                    key = { choice -> choice.relPath },
                ) { choice ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPathSelected(choice) },
                        headlineContent = {
                            Text(
                                text = choice.label,
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

/** 1:1 grid tile: image through the active backend, scrim with the description, selection cues. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    photo: CacheEntry,
    selectionMode: Boolean,
    selected: Boolean,
    photoLoader: suspend (String) -> Any?,
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
            model = rememberBackendModel(photo.relPath, photoLoader),
            contentDescription = photo.description ?: photo.name,
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
            text = photo.description ?: photo.name,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(8.dp),
        )
        if (photo.isConflict) {
            ConflictBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
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

/** Lightweight preview confirm for a gallery-picked cover (staged bytes; no metadata). */
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
                model = rememberPreviewBitmap(imageBytes),
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
 * Mandatory-metadata sheet for the head of the pending queue: preview
 * (decoded from the staged bytes), required description, optional note and
 * the remaining-images caption. Dismissing discards the whole queue, per
 * the product decision.
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
                model = rememberPreviewBitmap(imageBytes),
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
 * screen's ViewModel — stay alive while it is open. The image arrives as
 * the backend-resolved Coil model [model]; metadata is editable in place;
 * Save updates EXIF (and renames the file), Delete asks for confirmation
 * first.
 */
@Composable
private fun FullScreenPhotoDialog(
    photo: CacheEntry,
    model: Any?,
    onClose: () -> Unit,
    onSave: (String, String?) -> Unit,
    onDelete: () -> Unit,
) {
    var description by remember { mutableStateOf(photo.description ?: photo.name) }
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
                    model = model,
                    contentDescription = photo.description ?: photo.name,
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
