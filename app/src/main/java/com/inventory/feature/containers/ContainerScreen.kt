package com.inventory.feature.containers

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.inventory.ai.VisionOutcome
import com.inventory.cache.CacheEntry
import com.inventory.cache.ChildCounts
import com.inventory.feature.containers.drag.DragController
import com.inventory.feature.containers.drag.DragKind
import com.inventory.feature.containers.drag.DragPayload
import com.inventory.nav.breadcrumbSegments
import com.inventory.share.ShareReceiver
import com.inventory.storage.StorageMode
import com.inventory.ui.rememberBackendModel
import com.inventory.ui.rememberPreviewBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Maximum number of images the gallery picker may return at once. */
private const val MAX_GALLERY_IMAGES = 10

/** Dwell on a hovered row / the "↑" chip before the drag springs the screen. */
private const val SPRING_LOAD_DWELL_MS = 800L

/** Drop-target id prefix of the ancestor chips under the top bar. */
private const val ANCESTOR_TARGET_PREFIX = "ancestor:"

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

/**
 * Ascends one level from [folderRel]: opens the parent container, or the
 * root destination when the parent's relative path is empty.
 */
private fun navigateToParent(
    folderRel: String,
    onOpenContainer: (String) -> Unit,
    onBack: () -> Unit,
) {
    val parentRel = folderRel.substringBeforeLast('/', "")
    if (parentRel.isEmpty()) onBack() else onOpenContainer(parentRel)
}

/** Ring/tint marking the container row currently under an in-flight photo drag. */
private val DropHighlightColor = Color(0xFF009688)

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
 * child folder, [onNavigateToFolder] jumps to an ancestor picked from the
 * app-bar breadcrumb, [onOpenCamera] and the gallery picker attach photos to
 * this folder, [onOpenCoverCapture] photographs a cover for the addressed
 * folder (relative-path arguments), [onOpenSearch] opens the global
 * search, [onOpenSettings] opens the AI auto-labeling settings (root menu
 * only), and [onBack] ascends (and also leaves a folder that was just
 * renamed/moved/deleted — its route went stale).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContainerScreen(
    onOpenContainer: (String) -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onOpenCamera: (String) -> Unit,
    onOpenCoverCapture: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    onBack: () -> Unit,
    dragController: DragController = remember { DragController() },
    viewModel: ContainerViewModel = hiltViewModel(),
) {
    val isRoot = viewModel.isRoot
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val storage by viewModel.storageStatus.collectAsStateWithLifecycle()
    val pendingQueue by viewModel.pendingQueue.collectAsStateWithLifecycle()
    val pendingCover by viewModel.pendingCover.collectAsStateWithLifecycle()
    val aiSuggestions by viewModel.aiSuggestions.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val tidyState by viewModel.tidyState.collectAsStateWithLifecycle()
    val photoMoveTargets by viewModel.photoMoveTargets.collectAsStateWithLifecycle()
    val movableTargets by viewModel.movableTargets.collectAsStateWithLifecycle()
    val pendingShare by ShareReceiver.pending.collectAsStateWithLifecycle()
    val importedNotice by ShareReceiver.importedNotice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val shareScope = rememberCoroutineScope()

    // Drop-target ownership is per screen: the folder this screen addresses.
    val folderRel = viewModel.folderRel
    val dragOwner = folderRel

    var searchExpanded by remember { mutableStateOf(false) }
    var showFabSheet by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<RenameTarget?>(null) }
    var folderToMove by remember { mutableStateOf<String?>(null) }
    var showPhotoMoveSheet by remember { mutableStateOf(false) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }
    var photoToDelete by remember { mutableStateOf<CacheEntry?>(null) }
    // The full-screen viewer's current page; null while it is closed. The
    // viewer's Edit action opens [photoToEdit] (the existing editor) on top.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var photoToEdit by remember { mutableStateOf<CacheEntry?>(null) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var coverTargetRel by remember { mutableStateOf<String?>(null) }
    var sharePickerVisible by remember { mutableStateOf(false) }
    var upChipBounds by remember { mutableStateOf<Rect?>(null) }
    var gridBounds by remember { mutableStateOf<Rect?>(null) }

    val gridState = rememberLazyGridState()
    val density = LocalDensity.current

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

    // While a drag is in flight, keep the drop target under the ghost
    // pointer current; moving off every row clears the highlight. Blocked
    // targets (a dragged container and its subtree) and the payload's own
    // paths never hover: they may not receive a drop.
    LaunchedEffect(
        dragController.pointer,
        dragController.targets,
        dragController.blockedTargets,
    ) {
        val point = dragController.pointer
        if (dragController.payload == null || point == null) {
            dragController.hoveredTarget = null
            return@LaunchedEffect
        }
        val prefix = "$dragOwner|"
        val blocked = dragController.blockedTargets
        val ownPaths = dragController.payload?.relPaths.orEmpty()
        dragController.hoveredTarget = dragController.targets
            .entries
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
    // pointer, targets) lives at the navigation-graph root, so the drag
    // survives the destination change; the dwell restarts whenever the
    // hovered target changes (including to null when the finger leaves).
    LaunchedEffect(dragController.hoveredTarget) {
        val hovered = dragController.hoveredTarget ?: return@LaunchedEffect
        if (hovered == dragOwner) return@LaunchedEffect
        // Ancestor chips are drop-only: hovering one must never navigate.
        if (hovered.startsWith(ANCESTOR_TARGET_PREFIX)) return@LaunchedEffect
        // A blocked row (the dragged container or a descendant) never
        // receives a drop, so dwelling on it must not spring it open either.
        if (hovered in dragController.blockedTargets) return@LaunchedEffect
        delay(SPRING_LOAD_DWELL_MS)
        if (dragController.hoveredTarget == hovered) onOpenContainer(hovered)
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
        if (pointerOverUpChip) navigateToParent(folderRel, onOpenContainer, onBack)
    }

    // Ancestor drop chips: shown under the top bar for the whole drag when
    // the current folder is not the root. They are drop-only.
    val ancestorChipsVisible = dragController.payload != null && !isRoot

    // Drop resolution: the row (or ancestor chip) under the pointer wins,
    // releasing anywhere else cancels. Blocked containers (the dragged
    // subtree) and the payload's own paths are no-ops. The snackbar comes
    // from the ViewModel.
    SideEffect {
        dragController.onDrop = { payload, target ->
            // Ancestor chips carry an "ancestor:" prefix; the rest of the id
            // is the destination's relative path ("" = root).
            val destination = target?.removePrefix(ANCESTOR_TARGET_PREFIX)
            if (destination != null &&
                destination !in dragController.blockedTargets &&
                destination !in payload.relPaths
            ) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                when (payload.kind) {
                    DragKind.CONTAINER ->
                        viewModel.moveFolderTo(payload.relPaths.first(), destination)
                    DragKind.PHOTO ->
                        viewModel.movePhotosTo(payload.relPaths, destination)
                }
            }
        }
    }

    // Drag-time auto-scroll: while the pointer hugs the grid's top or bottom
    // edge, keep scrolling the single LazyVerticalGrid (children and photos
    // share it) so a drop target further down/up can be reached. The speed
    // ramps from 8 to 30 dp/frame as the pointer approaches the very edge.
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
                    fromTop < edge ->
                        -autoScrollSpeed(minSpeed, maxSpeed, edge, fromTop)
                    fromBottom < edge ->
                        autoScrollSpeed(minSpeed, maxSpeed, edge, fromBottom)
                    else -> 0f
                }
                if (speed != 0f) gridState.scrollBy(speed)
            }
            withFrameNanos { }
        }
    }

    // Rows of this screen unregister their drop bounds when it leaves.
    DisposableEffect(dragOwner) {
        onDispose { dragController.clearTargets(dragOwner) }
    }

    // System back cancels an in-flight drag instead of leaving the screen.
    BackHandler(enabled = dragController.payload != null) { dragController.end() }

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

    // Inbound Android share: the ROOT browse screen owns the destination
    // picker. An empty payload (no readable image) reports "No images
    // shared"; a non-empty one opens the "Add to Photo Inventory" sheet.
    LaunchedEffect(pendingShare, isRoot) {
        val images = pendingShare ?: return@LaunchedEffect
        if (!isRoot) return@LaunchedEffect
        if (images.isEmpty()) {
            snackbarHostState.showSnackbar(context.getString(R.string.share_no_images))
            ShareReceiver.clear()
        } else {
            sharePickerVisible = true
        }
    }

    // Confirmation of a completed share import: shown by the container
    // screen the import navigated to (the root screen ignores it). When the
    // share carried duplicates that were skipped, the summary line names
    // both counts instead of the plain "Imported to <label>".
    LaunchedEffect(importedNotice, isRoot) {
        val notice = importedNotice ?: return@LaunchedEffect
        if (isRoot) return@LaunchedEffect
        val text = if (notice.skipped > 0) {
            context.getString(R.string.share_imported_skipped, notice.imported, notice.skipped)
        } else {
            context.getString(R.string.share_imported, notice.containerLabel)
        }
        snackbarHostState.showSnackbar(text)
        ShareReceiver.consumeImportedNotice()
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
                            when {
                                isRoot -> Text(stringResource(R.string.title_inventory))
                                // During a drag the ancestor chips below take
                                // over navigation; the breadcrumb steps aside.
                                dragController.payload != null -> Text(
                                    text = uiState.containerName.ifEmpty {
                                        stringResource(R.string.container_fallback_title)
                                    },
                                )
                                else -> BreadcrumbRow(
                                    folderRel = folderRel,
                                    onNavigate = onNavigateToFolder,
                                )
                            }
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
                                    onExportReport = viewModel::exportReport,
                                    onTidyUp = viewModel::startTidyUp,
                                    onStorageMode = { showStorageDialog = true },
                                    onSettings = onOpenSettings,
                                    onTrash = onOpenTrash,
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
                if (ancestorChipsVisible) {
                    AncestorChipsRow(
                        folderRel = folderRel,
                        hoveredTarget = dragController.hoveredTarget,
                        onRegister = { id, rect -> dragController.registerTarget(dragOwner, id, rect) },
                    )
                }
                if (upChipVisible) {
                    MoveUpChip(
                        contentDescription = stringResource(R.string.move_up_here),
                        onClick = { navigateToParent(folderRel, onOpenContainer, onBack) },
                        modifier = Modifier
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                            .onGloballyPositioned { upChipBounds = it.boundsInRoot() },
                    )
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
                            state = gridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { gridBounds = it.boundsInRoot() },
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
                                    val payload = dragController.payload
                                    val containerDrag = payload?.kind == DragKind.CONTAINER
                                    val draggedRel =
                                        if (containerDrag) payload?.relPaths?.firstOrNull() else null
                                    ContainerRow(
                                        entry = entry,
                                        counts = uiState.childCounts[entry.relPath],
                                        coverLoader = viewModel::coverModel,
                                        highlighted = payload != null &&
                                            dragController.hoveredTarget == entry.relPath,
                                        blocked = containerDrag &&
                                            entry.relPath in dragController.blockedTargets,
                                        dragging = entry.relPath == draggedRel,
                                        blockedResolver = viewModel::blockedMoveTargets,
                                        dragController = dragController,
                                        modifier = Modifier.onGloballyPositioned { coordinates ->
                                            dragController.registerTarget(
                                                dragOwner,
                                                entry.relPath,
                                                coordinates.boundsInRoot(),
                                            )
                                        },
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
                                            selectedPaths = uiState.selectedPaths,
                                            photoLoader = viewModel::photoModel,
                                            onClick = {
                                                if (uiState.selectionMode) {
                                                    viewModel.toggleSelection(photo.relPath)
                                                } else {
                                                    viewerIndex = uiState.photos.indexOfFirst {
                                                        it.relPath == photo.relPath
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                // A long press starts selection (and,
                                                // if the finger moves, a drag). While
                                                // already selecting, a long press that
                                                // turns into a drag must not reset it.
                                                if (!uiState.selectionMode) {
                                                    viewModel.enterSelectionMode(photo.relPath)
                                                }
                                            },
                                            dragController = dragController,
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

    if (sharePickerVisible) {
        ShareTargetSheet(
            paths = photoMoveTargets,
            onDismiss = {
                sharePickerVisible = false
                ShareReceiver.clear()
            },
            onPathSelected = { choice ->
                val images = ShareReceiver.pending.value.orEmpty()
                sharePickerVisible = false
                if (images.isEmpty()) {
                    ShareReceiver.clear()
                } else {
                    shareScope.launch {
                        val result = viewModel.importSharedPhotos(choice.relPath, images)
                        ShareReceiver.clear()
                        if (result.imported > 0 || result.skipped > 0) {
                            ShareReceiver.notifyImported(
                                choice.label,
                                result.imported,
                                result.skipped,
                            )
                            onOpenContainer(choice.relPath)
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.share_import_failed),
                            )
                        }
                    }
                }
            },
        )
    }

    uiState.deleteCandidate?.let { candidate ->
        DeleteContainerDialog(
            candidate = candidate,
            trashEnabled = uiState.trashEnabled,
            onDismiss = viewModel::dismissDelete,
            onConfirm = viewModel::confirmDelete,
        )
    }

    if (confirmDeleteSelection) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelection = false },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Text(
                    stringResource(
                        if (uiState.trashEnabled) {
                            R.string.delete_photos_confirm
                        } else {
                            R.string.delete_photos_confirm_permanent
                        },
                        selectedCount,
                    ),
                )
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

    // The viewer is hosted while its photo list has entries: a delete that
    // empties the container closes it, and otherwise the pager clamps to
    // the following photo. It is composed before the editor and the delete
    // confirmation so those dialogs stack above it.
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
        )
    }

    // Edit opened from the viewer: the existing metadata editor on top. A
    // successful save goes through the VM, whose rescan refreshes the
    // viewer's list and overlay behind it.
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

    tidyState?.let { state ->
        val item = state.queue.getOrNull(state.index)
        key(item?.relPath ?: "tidy-scanning") {
            TidyUpDialog(
                state = state,
                item = item,
                model = item?.let { rememberBackendModel(it.relPath, viewModel::photoModel) },
                onApply = viewModel::applyTidySuggestion,
                onSkip = viewModel::skipTidyItem,
                onRetry = viewModel::describeCurrentTidyItem,
                onCancel = viewModel::cancelTidy,
            )
        }
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

/** App-bar overflow button for the root: rescan, report export, storage mode, trash and AI settings. */
@Composable
private fun RootOptionsAction(
    onRescan: () -> Unit,
    onExportReport: () -> Unit,
    onTidyUp: () -> Unit,
    onStorageMode: () -> Unit,
    onSettings: () -> Unit,
    onTrash: () -> Unit,
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
                text = { Text(stringResource(R.string.export_report)) },
                onClick = {
                    expanded = false
                    onExportReport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tidy_title)) },
                onClick = {
                    expanded = false
                    onTidyUp()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.storage_mode)) },
                onClick = {
                    expanded = false
                    onStorageMode()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash_title)) },
                onClick = {
                    expanded = false
                    onTrash()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_title)) },
                onClick = {
                    expanded = false
                    onSettings()
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
    highlighted: Boolean,
    blocked: Boolean,
    dragging: Boolean,
    blockedResolver: suspend (String) -> Set<String>,
    dragController: DragController,
    onOpen: () -> Unit,
    onRename: (String, String) -> Unit,
    onMove: (String) -> Unit,
    onSetPhoto: (String) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDelete: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Gesture coordinates are local to the row; the session (ghost + drop
    // targets) speaks root coordinates, so the row origin translates them.
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    Box(modifier = modifier) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rowOrigin = it.boundsInRoot().topLeft }
                .alpha(if (blocked || dragging) 0.4f else 1f)
                .then(
                    if (highlighted) {
                        Modifier
                            .background(DropHighlightColor.copy(alpha = 0.12f))
                            .border(2.dp, DropHighlightColor)
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(onClick = onOpen)
                .pointerInput(entry.relPath) {
                    // A long press starts a container drag; releasing it
                    // without moving opens the row's existing menu instead.
                    var moved = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            moved = false
                            dragController.start(
                                DragPayload(
                                    relPaths = listOf(entry.relPath),
                                    label = entry.name,
                                    kind = DragKind.CONTAINER,
                                ),
                            )
                            dragController.pointer = rowOrigin + offset
                            // Cycle guard: the dragged container and its whole
                            // subtree become invalid drop targets for this drag.
                            scope.launch {
                                val resolved = blockedResolver(entry.relPath)
                                val current = dragController.payload
                                if (current != null &&
                                    current.kind == DragKind.CONTAINER &&
                                    current.relPaths.firstOrNull() == entry.relPath
                                ) {
                                    dragController.blockedTargets = resolved
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            moved = true
                            dragController.pointer = rowOrigin + change.position
                        },
                        onDragEnd = {
                            if (moved) {
                                dragController.drop()
                            } else {
                                // Long press held and released without travel:
                                // open the row's existing menu instead.
                                dragController.end()
                                menuExpanded = true
                            }
                        },
                        onDragCancel = {
                            // A destination change (spring-loaded navigation)
                            // disposes this row mid-gesture and cancels the
                            // detector; the root-level tracker owns the session
                            // now, so this must NOT open anything or tear it
                            // down.
                        },
                    )
                },
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
 * The app-bar breadcrumb: a horizontally scrollable row with one text
 * segment per ancestor of [folderRel], root first ("Inventory"), separated
 * by " / ". Every ancestor segment is clickable and navigates through
 * [onNavigate]; the current (last) segment is bold and not clickable. Only
 * shown for a non-root folder while no drag is in flight.
 *
 * The row is scrolled to its end (the current folder) whenever the folder
 * changes, so a deep path never hides the screen's own name. The root
 * segment is exposed to accessibility by description rather than as a
 * second "Inventory" text node, keeping the app-bar title the single
 * textual root marker.
 */
@Composable
private fun BreadcrumbRow(
    folderRel: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootLabel = stringResource(R.string.title_inventory)
    val segments = remember(folderRel, rootLabel) { breadcrumbSegments(folderRel, rootLabel) }
    val scrollState = rememberScrollState()
    LaunchedEffect(folderRel) {
        // Let the row lay out, then reveal the tail of the path.
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        modifier = modifier.horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = segment.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (segment.isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (segment.isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(
                        if (segment.isCurrent) {
                            Modifier
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onNavigate(segment.rel) }
                        },
                    )
                    .padding(horizontal = 2.dp)
                    .then(
                        if (segment.rel.isEmpty()) {
                            Modifier.clearAndSetSemantics {
                                contentDescription = rootLabel
                                onClick(label = rootLabel) {
                                    onNavigate(segment.rel)
                                    true
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

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
                    stringResource(R.string.title_inventory)
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
        label = {
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier.then(
            if (highlighted) {
                Modifier.border(2.dp, DropHighlightColor, RoundedCornerShape(8.dp))
            } else {
                Modifier
            },
        ),
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

/**
 * The Android share target's destination picker: every indexed container
 * ([paths]) as a tappable row, shown by the root browse screen while a
 * shared image waits for a home. A dedicated sheet (rather than the
 * move-picker) so its wording — "Add to Photo Inventory" / "Pick a
 * container" — matches the inbound-share flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareTargetSheet(
    paths: List<ContainerViewModel.FolderChoice>,
    onDismiss: () -> Unit,
    onPathSelected: (ContainerViewModel.FolderChoice) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = stringResource(R.string.share_target_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.pick_container),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
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
    trashEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_container_title)) },
        text = {
            Text(
                stringResource(
                    if (trashEnabled) {
                        R.string.delete_container_message
                    } else {
                        R.string.delete_container_message_permanent
                    },
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
    selectedPaths: Set<String>,
    photoLoader: suspend (String) -> Any?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragController: DragController,
) {
    // Gesture coordinates are local to this tile; the session (ghost + drop
    // targets) speaks root coordinates, so the tile origin translates them.
    var tileOrigin by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    // The drag detector is keyed only on the photo, so the current selection
    // is read through updated-state holders instead (restarting the detector
    // mid-gesture would cancel it).
    val latestSelectionMode = rememberUpdatedState(selectionMode)
    val latestSelectedPaths = rememberUpdatedState(selectedPaths)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { tileOrigin = it.boundsInRoot().topLeft }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .pointerInput(photo.relPath) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        // In selection mode a dragged tile that is part of the
                        // selection carries the WHOLE selection; any other tile
                        // (selected or not) carries just itself.
                        val selection = latestSelectedPaths.value
                        val inSelectionMode = latestSelectionMode.value
                        val paths = if (inSelectionMode && photo.relPath in selection) {
                            selection.toList()
                        } else {
                            listOf(photo.relPath)
                        }
                        dragController.start(
                            DragPayload(
                                relPaths = paths,
                                label = if (paths.size > 1) {
                                    context.getString(R.string.drag_count, paths.size)
                                } else {
                                    photo.description ?: photo.name
                                },
                            ),
                        )
                        dragController.pointer = tileOrigin + offset
                    },
                    onDrag = { change, _ ->
                        dragController.pointer = tileOrigin + change.position
                    },
                    onDragEnd = {
                        dragController.drop()
                    },
                    onDragCancel = {
                        // A destination change (spring-loaded navigation)
                        // disposes this tile mid-gesture and cancels the
                        // detector; the root-level tracker owns the session
                        // now, so this must NOT tear it down.
                    },
                )
            }
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
 * (decoded from the staged bytes), required description, optional note,
 * the remaining-images caption and — when AI auto-labeling is on — the
 * suggestion status plus a blank-guarded pre-fill. Dismissing discards the
 * whole queue, per the product decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PendingPhotoSaveSheet(
    imageBytes: ByteArray,
    remainingCount: Int,
    aiEnabled: Boolean,
    suggestion: VisionOutcome?,
    duplicateOf: String?,
    onSave: (String, String?) -> Unit,
    onSkipDuplicate: () -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val maxHeight = (configuration.screenHeightDp * 0.4f).dp

    // Blank-guarded pre-fill: a late AI reply never overwrites text the
    // user already typed into either field.
    LaunchedEffect(suggestion) {
        val ready = suggestion as? VisionOutcome.Suggested ?: return@LaunchedEffect
        if (description.isBlank()) description = ready.description
        val suggestedNote = ready.note
        if (note.isBlank() && !suggestedNote.isNullOrBlank()) note = suggestedNote
    }

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            if (duplicateOf != null) {
                Text(
                    text = stringResource(R.string.duplicate_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    text = stringResource(R.string.duplicate_message, duplicateOf),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
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
            if (aiEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                AiSuggestionStatus(suggestion)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (duplicateOf != null) {
                    TextButton(onClick = onSkipDuplicate) {
                        Text(stringResource(R.string.duplicate_skip))
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Button(
                        onClick = { onSave(description, note.ifBlank { null }) },
                        enabled = description.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.duplicate_import_anyway))
                    }
                } else {
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
}

/**
 * One-line AI status under the save dialog's fields: in-flight while no
 * reply has arrived, ready on a suggestion, failed otherwise.
 */
@Composable
private fun AiSuggestionStatus(suggestion: VisionOutcome?) {
    val label = when (suggestion) {
        null -> R.string.ai_suggestion_thinking
        is VisionOutcome.Suggested -> R.string.ai_suggestion_ready
        is VisionOutcome.Failed -> R.string.ai_suggestion_failed
    }
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The full-screen "AI tidy-up" review dialog: image preview, cursor
 * progress ("3 / 7"), a blank-guarded pre-fill from the AI suggestion
 * (description required, note optional), an inline failure reason with
 * Skip/Retry, and Apply/Skip/Cancel. A null [item] means the initial
 * full-tree scan is still running and only the spinner is shown.
 */
@Composable
private fun TidyUpDialog(
    state: ContainerViewModel.TidyState,
    item: ContainerViewModel.TidyItem?,
    model: Any?,
    onApply: (String, String?) -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
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
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.tidy_cancel),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (state.queue.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.tidy_progress,
                                state.index + 1,
                                state.queue.size,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (item == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.tidy_scanning),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    return@Column
                }
                var description by remember(item.relPath) { mutableStateOf("") }
                var note by remember(item.relPath) { mutableStateOf("") }
                // Blank-guarded pre-fill: a late AI reply never overwrites
                // text the user already typed into either field.
                LaunchedEffect(item.relPath, state.suggestion) {
                    val ready = state.suggestion as? VisionOutcome.Suggested ?: return@LaunchedEffect
                    if (description.isBlank()) description = ready.description
                    val suggestedNote = ready.note
                    if (note.isBlank() && !suggestedNote.isNullOrBlank()) note = suggestedNote
                }
                AsyncImage(
                    model = model,
                    contentDescription = item.name,
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
                    TidySuggestionStatus(suggestion = state.suggestion, onRetry = onRetry)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.tidy_cancel))
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        OutlinedButton(
                            onClick = onSkip,
                            enabled = !state.applying,
                        ) {
                            Text(stringResource(R.string.tidy_skip))
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(
                            onClick = { onApply(description, note.ifBlank { null }) },
                            enabled = description.isNotBlank() && !state.applying,
                        ) {
                            Text(stringResource(R.string.tidy_apply))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The tidy dialog's AI line: a spinner while the suggestion is in flight,
 * "ready" on success and the key-free failure reason plus a Retry button
 * otherwise.
 */
@Composable
private fun TidySuggestionStatus(suggestion: VisionOutcome?, onRetry: () -> Unit) {
    when (suggestion) {
        null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.ai_suggestion_thinking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is VisionOutcome.Suggested -> Text(
            text = stringResource(R.string.ai_suggestion_ready),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        is VisionOutcome.Failed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ai_suggestion_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = suggestion.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.tidy_retry))
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
