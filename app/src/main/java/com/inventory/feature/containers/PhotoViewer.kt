package com.inventory.feature.containers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.inventory.R
import com.inventory.cache.CacheEntry
import com.inventory.ui.rememberBackendModel
import kotlinx.coroutines.flow.collect

/** Smallest zoom that still shows the whole photo (fit-center, no upscaling). */
private const val VIEWER_MIN_SCALE = 1f

/** Largest pinch/double-tap zoom the viewer allows. */
private const val VIEWER_MAX_SCALE = 5f

/** Scale a double tap toggles to. */
private const val VIEWER_DOUBLE_TAP_SCALE = 2.5f

/**
 * The full-screen photo viewer: a pager over the container's current photo
 * list (the same filtered list the grid shows), pinch-zoom + double-tap
 * zoom, and an overlay with the photo's description and note plus
 * Edit / Delete / Close.
 *
 * Photo changes in the editing flow go through the caller's existing VM
 * operations; this composable only owns presentation state. It is hosted
 * in an edge-to-edge [Dialog] so the container screen (and its ViewModel)
 * stay alive while it is open and the pager can animate page changes.
 *
 * @param photos the container's current photo list (already filtered/sorted).
 * @param initialIndex the tapped photo's index into [photos].
 * @param photoLoader the active backend's Coil model resolver for a relPath.
 * @param onEdit opens the existing metadata editor for the current photo.
 * @param onDelete asks the caller to run the existing confirm/delete flow.
 * @param onUpdateIndex reports the settled page so the caller can keep the
 *   index in sync (e.g. after a delete shrinks the list).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun PhotoViewerDialog(
    photos: List<CacheEntry>,
    initialIndex: Int,
    photoLoader: suspend (String) -> Any?,
    onDismiss: () -> Unit,
    onEdit: (CacheEntry) -> Unit,
    onDelete: (CacheEntry) -> Unit,
    onUpdateIndex: (Int) -> Unit,
) {
    if (photos.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.lastIndex),
    ) { photos.size }

    // Keep the caller's index current: the settled page drives the overlay
    // and survives edits/deletes that shrink or reorder the list.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page -> onUpdateIndex(page) }
    }

    val current = photos.getOrNull(pagerState.currentPage)

    // The edge-to-edge dialog window keeps the full display height while its
    // content is offset below the status bar, so bottom-anchored content
    // overflows by that inset; pad it back to keep the caption on screen.
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val photo = photos.getOrNull(page) ?: return@HorizontalPager
                    key(photo.relPath) {
                        ZoomablePhoto(
                            model = rememberBackendModel(photo.relPath, photoLoader),
                            contentDescription = photo.description ?: photo.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Top strip: page indicator + Edit / Delete / Close. The
                // actions live here rather than in the bottom overlay so they
                // always stay on screen above the navigation bar.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.viewer_page,
                            pagerState.currentPage + 1,
                            photos.size,
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { current?.let(onEdit) },
                        enabled = current != null,
                    ) {
                        Text(
                            text = stringResource(R.string.viewer_edit),
                            color = Color.White,
                        )
                    }
                    TextButton(
                        onClick = { current?.let(onDelete) },
                        enabled = current != null,
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.viewer_close),
                            tint = Color.White,
                        )
                    }
                }

                // Bottom overlay: description + note.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .padding(bottom = statusBarInset),
                ) {
                    current?.let { photo ->
                        Text(
                            text = photo.description ?: photo.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val note = photo.note
                        if (!note.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = note,
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A fit-center photo with pinch-zoom, pan while zoomed and a double-tap
 * 1x/2.5x toggle.
 *
 * The transform math follows `detectTransformGestures`, but the pointer
 * handler only consumes moves when a second finger is down or the photo is
 * already zoomed: a single-finger swipe at 1x must fall through to the
 * enclosing [HorizontalPager] so paging keeps working (a plain
 * `Modifier.transformable` would swallow it).
 */
@Composable
private fun ZoomablePhoto(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(VIEWER_MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val nextScale =
                                (scale * zoomChange).coerceIn(VIEWER_MIN_SCALE, VIEWER_MAX_SCALE)
                            if (nextScale != scale) scale = nextScale
                            offset = if (nextScale > VIEWER_MIN_SCALE) {
                                offset + panChange
                            } else {
                                Offset.Zero
                            }
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (scale > VIEWER_MIN_SCALE) {
                            val panChange = event.calculatePan()
                            if (panChange != Offset.Zero) {
                                offset += panChange
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
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
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
