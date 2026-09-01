package com.inventory.feature.boxes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.inventory.R
import com.inventory.database.BoxEntity
import com.inventory.database.BoxWithPhotoCount
import com.inventory.database.LocationEntity

/**
 * Master list of the boxes inside one location.
 *
 * Navigation is owned by the caller: tapping a row invokes [onOpenBox] with
 * the box id, the back arrow invokes [onBack], and the cover "Take photo"
 * option invokes [onOpenCoverCapture] with the "box" target and the box id.
 * Rename, Move to location, Set photo, Remove photo and Delete are offered
 * per row through an overflow menu (trailing ⋮ or long-press); the move
 * picker and the cover source picker are separate screen-level
 * [ModalBottomSheet]s, and gallery picks pass through a lightweight confirm
 * dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxesScreen(
    onOpenBox: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenCoverCapture: (String, Long) -> Unit,
    viewModel: BoxesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val otherLocations by viewModel.otherLocations.collectAsStateWithLifecycle()
    val pendingCover by viewModel.pendingCover.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var boxToRename by remember { mutableStateOf<BoxEntity?>(null) }
    var boxToMove by remember { mutableStateOf<BoxEntity?>(null) }
    var boxToDelete by remember { mutableStateOf<BoxWithPhotoCount?>(null) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var coverTargetId by remember { mutableStateOf<Long?>(null) }

    val coverPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val boxId = coverTargetId
        if (uri != null && boxId != null) {
            viewModel.onCoverImagePicked(boxId, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BoxesViewModel.BoxesEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(context.getString(event.resId))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.locationName.ifBlank {
                            stringResource(R.string.location_fallback_title)
                        },
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
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_box),
                )
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

                uiState.boxes.isEmpty() -> {
                    EmptyBoxesPlaceholder(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = uiState.boxes,
                            key = { entry -> entry.box.id },
                        ) { entry ->
                            BoxRow(
                                entry = entry,
                                onOpen = { onOpenBox(entry.box.id) },
                                onRename = { boxToRename = entry.box },
                                onMove = { boxToMove = entry.box },
                                onSetPhoto = {
                                    coverTargetId = entry.box.id
                                    showCoverSheet = true
                                },
                                onRemovePhoto = { viewModel.removeCoverImage(entry.box.id) },
                                onDelete = { boxToDelete = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        BoxNameDialog(
            title = stringResource(R.string.add_box),
            initialName = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createBox(name)
                showCreateDialog = false
            },
        )
    }

    boxToRename?.let { box ->
        key(box.id) {
            BoxNameDialog(
                title = stringResource(R.string.rename_box),
                initialName = box.name,
                onDismiss = { boxToRename = null },
                onConfirm = { name ->
                    viewModel.renameBox(box.id, name)
                    boxToRename = null
                },
            )
        }
    }

    boxToMove?.let { box ->
        MoveBoxSheet(
            locations = otherLocations,
            onDismiss = { boxToMove = null },
            onLocationSelected = { location ->
                viewModel.moveBox(box.id, location.id)
                boxToMove = null
            },
        )
    }

    boxToDelete?.let { entry ->
        DeleteBoxDialog(
            name = entry.box.name,
            photoCount = entry.photoCount,
            onDismiss = { boxToDelete = null },
            onConfirm = {
                viewModel.deleteBox(entry.box.id)
                boxToDelete = null
            },
        )
    }

    if (showCoverSheet) {
        CoverSourceSheet(
            onDismiss = { showCoverSheet = false },
            onTakePhoto = {
                showCoverSheet = false
                coverTargetId?.let { boxId -> onOpenCoverCapture("box", boxId) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxRow(
    entry: BoxWithPhotoCount,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onSetPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true },
            ),
        leadingContent = {
            CoverThumbnail(entry.box.coverImage)
        },
        headlineContent = {
            Text(
                text = entry.box.name,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = pluralStringResource(R.plurals.photo_count, entry.photoCount, entry.photoCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.box_options),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_box_to_location)) },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.set_photo)) },
                        onClick = {
                            menuExpanded = false
                            onSetPhoto()
                        },
                    )
                    if (entry.box.coverImage != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_photo)) },
                            onClick = {
                                menuExpanded = false
                                onRemovePhoto()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        },
    )
    HorizontalDivider()
}

/** Row thumbnail: the saved cover, or a neutral image placeholder. */
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
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
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
private fun BoxNameDialog(
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
                label = { Text(stringResource(R.string.box_name_label)) },
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

@Composable
private fun DeleteBoxDialog(
    name: String,
    photoCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_box_title)) },
        text = { Text(stringResource(R.string.delete_box_message, name, photoCount)) },
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

/**
 * Move-to-location picker: one shared sheet listing every location except
 * the parent of the current screen. Tapping a row moves the box and closes
 * the sheet; an explanatory message is shown when no other location exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveBoxSheet(
    locations: List<LocationEntity>,
    onDismiss: () -> Unit,
    onLocationSelected: (LocationEntity) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(R.string.move_box_to_location),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (locations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_other_locations),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    items = locations,
                    key = { location -> location.id },
                ) { location ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLocationSelected(location) },
                        headlineContent = {
                            Text(
                                text = location.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Place,
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

@Composable
private fun EmptyBoxesPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inventory2,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_boxes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
