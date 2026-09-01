package com.inventory.feature.photos

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.inventory.database.BoxEntity
import com.inventory.database.PhotoEntity

/** Maximum number of images the gallery picker may return at once. */
private const val MAX_GALLERY_IMAGES = 10

/**
 * Photo grid of one box: search, long-press multi-select with move/delete,
 * a FAB that offers camera or gallery capture, a mandatory-description save
 * sheet for every pending image, and a full-screen preview with editable
 * metadata.
 *
 * Navigation is owned by the caller: the camera option invokes
 * [onOpenCamera] (Module 6 wires the route) and the back arrow [onBack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    onOpenCamera: () -> Unit,
    onBack: () -> Unit,
    viewModel: PhotosViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingQueue by viewModel.pendingQueue.collectAsStateWithLifecycle()
    val otherBoxes by viewModel.otherBoxes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var searchExpanded by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<PhotoEntity?>(null) }
    var fullScreenPhotoId by remember { mutableStateOf<Long?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_GALLERY_IMAGES),
    ) { uris -> viewModel.onGalleryUrisPicked(uris) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PhotosViewModel.PhotosEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    context.getString(event.resId, *event.formatArgs.toTypedArray()),
                )
            }
        }
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
                            IconButton(onClick = { showMoveSheet = true }) {
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
                                text = uiState.box?.name
                                    ?: stringResource(R.string.box_fallback_title),
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
                            IconButton(
                                onClick = {
                                    if (searchExpanded) {
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
                        },
                    )
                    if (searchExpanded) {
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
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_photos),
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

                uiState.photos.isEmpty() -> {
                    val messageResId = if (uiState.searchQuery.isNotBlank()) {
                        R.string.no_search_results
                    } else {
                        R.string.no_photos
                    }
                    EmptyPhotosPlaceholder(
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
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddPhotoSheet(
            onDismiss = { showAddSheet = false },
            onTakePhoto = {
                showAddSheet = false
                onOpenCamera()
            },
            onChooseFromGallery = {
                showAddSheet = false
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
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

    if (showMoveSheet) {
        MovePhotosSheet(
            boxes = otherBoxes,
            onDismiss = { showMoveSheet = false },
            onBoxSelected = { box ->
                viewModel.moveSelected(box.id)
                showMoveSheet = false
            },
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

/** FAB sheet offering the two ways to add photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPhotoSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFromGallery: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
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

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
    ) {
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
 * Move-to-box picker: one shared sheet listing every box except the current
 * one. Tapping a row moves the selection and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MovePhotosSheet(
    boxes: List<BoxEntity>,
    onDismiss: () -> Unit,
    onBoxSelected: (BoxEntity) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(R.string.move_photos_to_box),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (boxes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_other_boxes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    items = boxes,
                    key = { box -> box.id },
                ) { box ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBoxSelected(box) },
                        headlineContent = {
                            Text(
                                text = box.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Inventory2,
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
private fun EmptyPhotosPlaceholder(
    messageResId: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoLibrary,
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
