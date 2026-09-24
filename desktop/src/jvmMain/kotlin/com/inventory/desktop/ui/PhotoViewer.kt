package com.inventory.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.inventory.desktop.DesktopAppController
import com.inventory.desktop.index.DesktopIndex
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Smallest zoom that still shows the whole photo (fit-center, no upscaling). */
private const val VIEWER_MIN_SCALE = 1f

/** Largest scroll-wheel/pinch zoom the viewer allows. */
private const val VIEWER_MAX_SCALE = 5f

/** Scale a double click toggles to. */
private const val VIEWER_DOUBLE_TAP_SCALE = 2.5f

/** How much one scroll-wheel notch changes the zoom. */
private const val VIEWER_SCROLL_STEP = 1.1f

/**
 * The page index to show after the photo at [deletedIndex] is removed from
 * a list that held [previousCount] photos (the viewer advances to the
 * following photo). Deleting the last photo clamps to the new last index;
 * deleting the only photo yields -1, meaning "nothing left — close".
 *
 * Pure so the index-advance rule is unit-testable.
 */
internal fun nextViewerIndex(deletedIndex: Int, previousCount: Int): Int {
    val nextCount = (previousCount - 1).coerceAtLeast(0)
    if (nextCount == 0) return -1
    return deletedIndex.coerceIn(0, nextCount - 1)
}

/**
 * The full-screen desktop photo viewer: a pager over the container's
 * current photo list (the same filtered list the grid shows), prev/next
 * arrows plus keyboard LEFT/RIGHT, scroll-wheel and double-click zoom
 * (drag/trackpad pinch pan while zoomed), and an overlay with the photo's
 * description and note plus Edit / Delete / Close.
 *
 * It renders as an overlay inside the window-content [Box] (not a separate
 * [androidx.compose.ui.window.Dialog]) so the browse surface — and the
 * keyboard focus chain — stay alive underneath. Edit delegates to the
 * caller's existing photo editor, and delete to the existing confirm flow;
 * both go through the controller's normal operations, so the index (and
 * therefore this list) refreshes behind the overlay.
 *
 * @param photos the container's current photo list (already filtered/sorted).
 * @param initialIndex the tapped photo's index into [photos].
 * @param onEdit opens the existing metadata editor for the current photo.
 * @param onDelete asks the caller to run the existing confirm/delete flow.
 * @param onUpdateIndex reports the settled page so the caller can keep the
 *   index in sync (e.g. after a delete shrinks the list).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerOverlay(
    controller: DesktopAppController,
    photos: List<DesktopIndex.IndexedPhoto>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onEdit: (DesktopIndex.IndexedPhoto) -> Unit,
    onDelete: (DesktopIndex.IndexedPhoto) -> Unit,
    onUpdateIndex: (Int) -> Unit,
) {
    if (photos.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.lastIndex),
    ) { photos.size }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page -> onUpdateIndex(page) }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val current = photos.getOrNull(pagerState.currentPage)

    fun goTo(page: Int) {
        if (page in photos.indices) scope.launch { pagerState.animateScrollToPage(page) }
    }

    Surface(
        color = Color.Black,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }

                    Key.DirectionLeft -> {
                        goTo(pagerState.currentPage - 1)
                        true
                    }

                    Key.DirectionRight -> {
                        goTo(pagerState.currentPage + 1)
                        true
                    }

                    else -> false
                }
            },
    ) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val photo = photos.getOrNull(page) ?: return@HorizontalPager
                key(photo.relPath) {
                    ZoomableImage(
                        file = rememberViewerPhotoFile(controller, photo.relPath),
                        contentDescription = photo.description,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            IconButton(
                onClick = { goTo(pagerState.currentPage - 1) },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = DesktopStrings.VIEWER_PREVIOUS,
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = { goTo(pagerState.currentPage + 1) },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = DesktopStrings.VIEWER_NEXT,
                    tint = Color.White,
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DesktopStrings.VIEWER_PAGE.format(pagerState.currentPage + 1, photos.size),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = DesktopStrings.VIEWER_CLOSE,
                        tint = Color.White,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                current?.let { photo ->
                    Text(
                        text = photo.description,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    photo.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = note,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { current?.let(onEdit) },
                        enabled = current != null,
                    ) {
                        Text(DesktopStrings.VIEWER_EDIT, color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { current?.let(onDelete) },
                        enabled = current != null,
                    ) {
                        Text(
                            text = DesktopStrings.DELETE,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text(DesktopStrings.VIEWER_CLOSE, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * A fit-center photo with scroll-wheel / double-click zoom and drag (or
 * trackpad pinch) panning while zoomed. `transformable` is only enabled
 * past 1x so a plain drag at fit scale still reaches the enclosing pager.
 */
@Composable
private fun ZoomableImage(
    file: File?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var scale by remember(file?.absolutePath) { mutableStateOf(VIEWER_MIN_SCALE) }
    var offset by remember(file?.absolutePath) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(VIEWER_MIN_SCALE, VIEWER_MAX_SCALE)
        scale = nextScale
        offset = if (nextScale > VIEWER_MIN_SCALE) offset + panChange else Offset.Zero
    }
    val bitmap = rememberFileBitmap(file)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(transformState, enabled = scale > VIEWER_MIN_SCALE)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val scroll = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (scroll != 0f) {
                            scale = (scale * if (scroll < 0f) VIEWER_SCROLL_STEP else 1f / VIEWER_SCROLL_STEP)
                                .coerceIn(VIEWER_MIN_SCALE, VIEWER_MAX_SCALE)
                            if (scale <= VIEWER_MIN_SCALE) offset = Offset.Zero
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > VIEWER_MIN_SCALE) {
                            scale = VIEWER_MIN_SCALE
                            offset = Offset.Zero
                        } else {
                            scale = VIEWER_DOUBLE_TAP_SCALE
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The photo file behind one viewer page, refreshed when the path changes. */
@Composable
private fun rememberViewerPhotoFile(controller: DesktopAppController, relPath: String): File? =
    produceState<File?>(null, relPath) { value = controller.photoFile(relPath) }.value
