package com.inventory.feature.containers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.inventory.R
import com.inventory.cache.CacheEntry
import com.inventory.ui.rememberBackendModel

/** Maximum number of gallery images the element picker may return at once. */
private const val ITEM_MAX_GALLERY_IMAGES = 10

/**
 * The element screen (route `item/{path}`): header with the element name,
 * note (editable through the marker) and warnings, a 2-column grid of its
 * photos and every element-level action — add photos (camera parked on the
 * element, or gallery), cover set/remove, rename, move, flatten (only a
 * one-photo element), ungroup and delete.
 *
 * Photos open the shared full-screen viewer; its Edit action reuses the
 * metadata editor and its "Set as cover" promotes the current photo to
 * `_cover.jpg`. [onBack] returns to the parent container. When the element
 * disappears (renamed/moved/deleted/flattened/ungrouped, or removed
 * externally) the screen pops itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemScreen(
    onBack: () -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenCoverCapture: (String) -> Unit,
    viewModel: ItemViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingQueue by viewModel.pendingQueue.collectAsStateWithLifecycle()
    val pendingCover by viewModel.pendingCover.collectAsStateWithLifecycle()
    val aiSuggestions by viewModel.aiSuggestions.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val movableTargets by viewModel.movableTargets.collectAsStateWithLifecycle()
    val deleteCandidate by viewModel.deleteCandidate.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showAddPhotosSheet by remember { mutableStateOf(false) }
    var showCoverSourceSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var photoToEdit by remember { mutableStateOf<CacheEntry?>(null) }
    var photoToDelete by remember { mutableStateOf<CacheEntry?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ITEM_MAX_GALLERY_IMAGES),
    ) { uris -> viewModel.onGalleryUrisPicked(uris) }

    val coverPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> viewModel.onCoverImagePicked(uri) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ItemViewModel.ItemEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    context.getString(event.resId, *event.formatArgs.toTypedArray()),
                )
            }
        }
    }

    // The element vanished (deleted/renamed/flattened/ungrouped, or gone
    // from disk): leave the stale route instead of showing an empty shell.
    LaunchedEffect(uiState.isLoading, uiState.item) {
        if (!uiState.isLoading && uiState.item == null) onBack()
    }

    val item = uiState.item

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item?.name?.ifEmpty { null }
                            ?: viewModel.itemName.ifEmpty {
                                stringResource(R.string.item_fallback_title)
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    if (item != null) {
                        ItemOptionsAction(
                            hasCover = item.hasCover,
                            photoCount = uiState.photos.size,
                            onSetPhoto = { showCoverSourceSheet = true },
                            onRemovePhoto = viewModel::removeCover,
                            onRename = { showRenameDialog = true },
                            onMove = {
                                viewModel.requestMove()
                                showMoveSheet = true
                            },
                            onFlatten = viewModel::flatten,
                            onUngroup = viewModel::ungroup,
                            onDelete = viewModel::requestDelete,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddPhotosSheet = true },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.add_photos)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.photo_count,
                            uiState.photos.size,
                            uiState.photos.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { showNoteDialog = true }) {
                        Text(
                            stringResource(
                                if (item?.note.isNullOrBlank()) {
                                    R.string.item_note_add
                                } else {
                                    R.string.edit_note
                                },
                            ),
                        )
                    }
                }
                val note = item?.note
                if (!note.isNullOrBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item?.hasUnexpectedContent == true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.unexpected_content_badge),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    uiState.photos.isEmpty() -> {
                        EmptyItemPlaceholder(
                            onAddPhotos = { showAddPhotosSheet = true },
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
                            items(
                                items = uiState.photos,
                                key = { photo -> photo.relPath },
                            ) { photo ->
                                ItemPhotoTile(
                                    photo = photo,
                                    photoLoader = viewModel::photoModel,
                                    onClick = {
                                        viewerIndex = uiState.photos.indexOfFirst {
                                            it.relPath == photo.relPath
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddPhotosSheet) {
        PhotoSourceSheet(
            onDismiss = { showAddPhotosSheet = false },
            onTakePhoto = {
                showAddPhotosSheet = false
                onOpenCamera(viewModel.itemRel)
            },
            onChooseFromGallery = {
                showAddPhotosSheet = false
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    if (showCoverSourceSheet) {
        PhotoSourceSheet(
            onDismiss = { showCoverSourceSheet = false },
            onTakePhoto = {
                showCoverSourceSheet = false
                onOpenCoverCapture(viewModel.itemRel)
            },
            onChooseFromGallery = {
                showCoverSourceSheet = false
                coverPickLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    if (showRenameDialog) {
        ContainerNameDialog(
            title = stringResource(R.string.rename),
            initialName = item?.name ?: viewModel.itemName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                viewModel.rename(name)
                showRenameDialog = false
            },
        )
    }

    if (showMoveSheet) {
        ContainerPathPickerSheet(
            paths = movableTargets,
            onDismiss = { showMoveSheet = false },
            onPathSelected = { choice ->
                viewModel.moveTo(choice.relPath)
                showMoveSheet = false
            },
        )
    }

    if (showNoteDialog) {
        NoteDialog(
            initialNote = item?.note.orEmpty(),
            onDismiss = { showNoteDialog = false },
            onConfirm = { note ->
                viewModel.updateNote(note)
                showNoteDialog = false
            },
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.item_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (uiState.trashEnabled) {
                            R.string.item_delete_message
                        } else {
                            R.string.item_delete_message_permanent
                        },
                        candidate.name,
                        candidate.photoCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val viewerStart = viewerIndex
    if (viewerStart != null && uiState.photos.isNotEmpty()) {
        PhotoViewerDialog(
            photos = uiState.photos,
            initialIndex = viewerStart,
            photoLoader = viewModel::photoModel,
            onDismiss = { viewerIndex = null },
            onEdit = { photo -> photoToEdit = photo },
            onDelete = { photo -> photoToDelete = photo },
            onUpdateIndex = { index -> viewerIndex = index },
            onSetCover = { photo -> viewModel.setCoverFromPhoto(photo.relPath) },
        )
    }

    photoToEdit?.let { photo ->
        key(photo.relPath) {
            FullScreenPhotoDialog(
                photo = photo,
                model = rememberBackendModel(photo.relPath, viewModel::photoModel),
                onClose = { photoToEdit = null },
                onSave = { description, note ->
                    viewModel.updatePhotoDetails(photo, description, note)
                    photoToEdit = null
                },
                onDelete = { photoToDelete = photo },
            )
        }
    }

    photoToDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { photoToDelete = null },
            title = { Text(stringResource(R.string.delete_photo_title)) },
            text = {
                Text(
                    stringResource(
                        if (uiState.trashEnabled) {
                            R.string.delete_photo_message
                        } else {
                            R.string.delete_photo_message_permanent
                        },
                        photo.description ?: photo.name,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePhoto(photo.relPath)
                        if (photoToEdit?.relPath == photo.relPath) photoToEdit = null
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

    pendingQueue.firstOrNull()?.let { head ->
        key(head.id) {
            PendingPhotoSaveSheet(
                imageBytes = head.bytes,
                remainingCount = pendingQueue.size - 1,
                aiEnabled = aiEnabled,
                suggestion = aiSuggestions[head.id],
                duplicateOf = head.duplicateOf,
                onSave = { description, note ->
                    viewModel.savePendingPhoto(description, note)
                },
                onSkipDuplicate = viewModel::skipPendingDuplicate,
                onCancel = viewModel::discardPendingPhotos,
            )
        }
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

/** Element overflow menu: cover, rename, move, flatten, ungroup, delete. */
@Composable
private fun ItemOptionsAction(
    hasCover: Boolean,
    photoCount: Int,
    onSetPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onFlatten: () -> Unit,
    onUngroup: () -> Unit,
    onDelete: () -> Unit,
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
                text = { Text(stringResource(R.string.set_photo)) },
                onClick = {
                    expanded = false
                    onSetPhoto()
                },
            )
            if (hasCover) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_photo)) },
                    onClick = {
                        expanded = false
                        onRemovePhoto()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_container_to)) },
                onClick = {
                    expanded = false
                    onMove()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.flatten_item)) },
                enabled = photoCount == 1,
                onClick = {
                    expanded = false
                    onFlatten()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ungroup_item)) },
                onClick = {
                    expanded = false
                    onUngroup()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/** 1:1 photo tile: image through the active backend, description scrim, conflict badge. */
@Composable
private fun ItemPhotoTile(
    photo: CacheEntry,
    photoLoader: suspend (String) -> Any?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = rememberBackendModel(photo.relPath, photoLoader),
            contentDescription = photo.description ?: photo.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
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
    }
}

/** Centered empty-element state with the one prominent action: add photos. */
@Composable
private fun EmptyItemPlaceholder(
    onAddPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Inventory2,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.item_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAddPhotos) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.add_photos))
        }
    }
}

/** Element-note editor: the note is stored in `_item.json`, not in EXIF. */
@Composable
private fun NoteDialog(
    initialNote: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var note by remember { mutableStateOf(initialNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_note)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.photo_note_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }) {
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
