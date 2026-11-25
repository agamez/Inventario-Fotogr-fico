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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inventory.desktop.DesktopAppController
import com.inventory.desktop.index.DesktopIndex
import java.io.File
import kotlinx.coroutines.launch

/**
 * The desktop element screen — the twin of Android's `ItemScreen`: the
 * element header (name, marker note, conflict/unexpected-content badges),
 * the element-level actions (add photos, cover, rename, move, flatten,
 * ungroup, delete) and a grid of the photos inside the item folder. The
 * photos are ordinary indexed rows whose parent is the item, so the same
 * full-screen viewer pages through them.
 *
 * Every mutation goes through [DesktopAppController] (disk first, index
 * rebuild after); dialogs that touch other parts of the app (rename, move,
 * delete confirmations, note editing) are owned by [App] and requested
 * through the callbacks.
 */
@Composable
fun ItemView(
    controller: DesktopAppController,
    itemRel: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenPhoto: (List<DesktopIndex.IndexedPhoto>, Int) -> Unit,
    onAddPhotos: (String) -> Unit,
    onSetCoverFile: (String) -> Unit,
    onRename: (DesktopIndex.IndexedItem) -> Unit,
    onMove: (String) -> Unit,
    onEditNote: (DesktopIndex.IndexedItem) -> Unit,
    onFlatten: (DesktopIndex.IndexedItem) -> Unit,
    onUngroup: (DesktopIndex.IndexedItem) -> Unit,
    onDelete: (DesktopIndex.IndexedItem) -> Unit,
) {
    val snapshot by controller.snapshot.collectAsState()
    val item = snapshot.allItems.firstOrNull { it.relPath == itemRel } ?: return
    val photos = remember(snapshot, itemRel) { controller.photosIn(itemRel, "", includeSubtree = false) }
    val scope = rememberCoroutineScope()
    var showCoverPicker by remember(itemRel) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(DesktopStrings.ITEM_BACK) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name.ifBlank { DesktopStrings.ITEM_FALLBACK_TITLE },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.isConflict) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = DesktopStrings.CONFLICT_BADGE,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(18.dp),
                        )
                    }
                    if (item.hasUnexpectedContent) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = DesktopStrings.UNEXPECTED_CONTENT_BADGE,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(18.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } ?: Text(
                        text = DesktopStrings.ITEM_NOTE_ADD,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onEditNote(item) }) { Text(DesktopStrings.EDIT_NOTE) }
                }
                Text(
                    text = if (item.photoCount == 1) {
                        DesktopStrings.PHOTO_COUNTED_ONE.format(1)
                    } else {
                        DesktopStrings.PHOTOS_COUNTED.format(item.photoCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { onAddPhotos(itemRel) }) { Text(DesktopStrings.ADD_PHOTOS) }
            OutlinedButton(onClick = { showCoverPicker = true }) { Text(DesktopStrings.ITEM_COVER_PHOTO_TITLE) }
            TextButton(onClick = { onSetCoverFile(itemRel) }) { Text(DesktopStrings.SET_COVER) }
            TextButton(onClick = { onRename(item) }) { Text(DesktopStrings.RENAME) }
            TextButton(onClick = { onMove(itemRel) }) { Text(DesktopStrings.MOVE_TO) }
            TextButton(
                enabled = item.photoCount == 1,
                onClick = { onFlatten(item) },
            ) { Text(DesktopStrings.FLATTEN_ITEM) }
            TextButton(onClick = { onUngroup(item) }) { Text(DesktopStrings.UNGROUP_ITEM) }
            TextButton(onClick = { onDelete(item) }) {
                Text(DesktopStrings.DELETE, color = MaterialTheme.colorScheme.error)
            }
        }

        if (item.hasUnexpectedContent) {
            Text(
                text = DesktopStrings.UNEXPECTED_CONTENT_BADGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        DesktopStrings.ITEM_EMPTY,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onAddPhotos(itemRel) }) { Text(DesktopStrings.ADD_PHOTOS) }
                }
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "item-photos-header", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = DesktopStrings.PHOTOS_SECTION,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            itemsIndexed(photos, key = { _, photo -> photo.relPath }) { index, photo ->
                ItemPhotoTile(
                    controller = controller,
                    photo = photo,
                    onClick = { onOpenPhoto(photos, index) },
                )
            }
        }
    }

    if (showCoverPicker) {
        PhotoPickerDialog(
            title = DesktopStrings.ITEM_COVER_PHOTO_TITLE,
            photos = photos,
            controller = controller,
            onDismiss = { showCoverPicker = false },
            onPick = { photo ->
                showCoverPicker = false
                scope.launch {
                    val outcome = controller.setItemCoverFromPhoto(itemRel, photo.relPath)
                    onMessage(
                        if (outcome == DesktopAppController.Outcome.Saved) {
                            DesktopStrings.MSG_COVER_UPDATED
                        } else {
                            DesktopStrings.MSG_ITEM_ACTION_FAILED
                        },
                    )
                }
            },
        )
    }
}

/** One photo inside the element grid: fits the cell, description overlay, conflict badge. */
@Composable
private fun ItemPhotoTile(
    controller: DesktopAppController,
    photo: DesktopIndex.IndexedPhoto,
    onClick: () -> Unit,
) {
    val file by rememberItemPhotoFile(controller, photo)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        ImageTile(file = file, size = null, modifier = Modifier.fillMaxSize())
        Text(
            text = photo.description,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
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

/** A small picker listing every photo inside the element (the cover source). */
@Composable
private fun PhotoPickerDialog(
    title: String,
    photos: List<DesktopIndex.IndexedPhoto>,
    controller: DesktopAppController,
    onDismiss: () -> Unit,
    onPick: (DesktopIndex.IndexedPhoto) -> Unit,
) {
    val rowOf: @Composable (DesktopIndex.IndexedPhoto) -> Unit = { photo ->
        val file by rememberItemPhotoFile(controller, photo)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(photo) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImageTile(file = file, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = photo.description,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    PickerDialog(title = title, onDismiss = onDismiss) {
        photos.forEach { photo -> rowOf(photo) }
    }
}

/** The item photo file, refreshed with its path. */
@Composable
private fun rememberItemPhotoFile(
    controller: DesktopAppController,
    photo: DesktopIndex.IndexedPhoto,
): androidx.compose.runtime.State<File?> =
    androidx.compose.runtime.produceState<File?>(null, photo.relPath) {
        value = controller.photoFile(photo.relPath)
    }
