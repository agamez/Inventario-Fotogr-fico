package com.inventory.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inventory.desktop.DesktopAppController
import com.inventory.storage.TrashManager
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * The Trash view: every soft-deleted container/photo with its original
 * location, kind and deletion time, each offering Restore or Delete
 * forever, plus an "Empty trash" action. It is the desktop twin of the
 * Android [com.inventory.feature.trash.TrashScreen]: disk is the source of
 * truth, so the controller re-lists the trash after every action and
 * re-indexes the inventory after a restore.
 */
@Composable
fun TrashView(
    controller: DesktopAppController,
    onMessage: (String) -> Unit,
) {
    val entries by controller.trashEntries.collectAsState()
    val scope = rememberCoroutineScope()
    var entryToPurge by remember { mutableStateOf<TrashManager.TrashEntry?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { controller.refreshTrash() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = DesktopStrings.TRASH_TITLE,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmEmptyTrash = true }) {
                    Text(DesktopStrings.EMPTY_TRASH)
                }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = DesktopStrings.TRASH_EMPTY,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    TrashRow(
                        entry = entry,
                        onRestore = {
                            scope.launch {
                                if (controller.restore(entry.id)) {
                                    onMessage(DesktopStrings.MSG_TRASH_RESTORED)
                                }
                            }
                        },
                        onDeleteForever = { entryToPurge = entry },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    entryToPurge?.let { entry ->
        ConfirmDialog(
            title = DesktopStrings.DELETE_FOREVER,
            message = DesktopStrings.DELETE_FOREVER_CONFIRM.format(entry.name),
            confirmLabel = DesktopStrings.DELETE_FOREVER,
            onDismiss = { entryToPurge = null },
            onConfirm = {
                entryToPurge = null
                scope.launch {
                    if (controller.deleteForever(entry.id)) {
                        onMessage(DesktopStrings.MSG_TRASH_PURGED)
                    }
                }
            },
        )
    }

    if (confirmEmptyTrash) {
        ConfirmDialog(
            title = DesktopStrings.EMPTY_TRASH,
            message = DesktopStrings.EMPTY_TRASH_CONFIRM,
            confirmLabel = DesktopStrings.EMPTY_TRASH,
            onDismiss = { confirmEmptyTrash = false },
            onConfirm = {
                confirmEmptyTrash = false
                scope.launch {
                    controller.emptyTrash()
                    onMessage(DesktopStrings.MSG_TRASH_EMPTIED)
                }
            },
        )
    }
}

/** One trash row: name, original location, kind + deletion time, actions. */
@Composable
private fun TrashRow(
    entry: TrashManager.TrashEntry,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val kindLabel = if (entry.kind == TrashManager.KIND_PHOTO) {
        DesktopStrings.TRASH_KIND_PHOTO
    } else {
        DesktopStrings.TRASH_KIND_CONTAINER
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DesktopStrings.TRASH_WAS_IN.format(parentLabel(entry.originalParentRel)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = kindLabel + DesktopStrings.COUNT_SEPARATOR +
                    DesktopStrings.TRASH_DELETED_ON.format(deletedOn(entry.deletedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRestore) { Text(DesktopStrings.RESTORE) }
        TextButton(onClick = onDeleteForever) {
            Text(DesktopStrings.DELETE_FOREVER, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** "Building/Shelf A" → "Building / Shelf A"; the root shows "Inventory". */
private fun parentLabel(parentRel: String): String =
    if (parentRel.isEmpty()) {
        DesktopStrings.TITLE_INVENTORY
    } else {
        parentRel.split('/').filter(String::isNotBlank).joinToString(" / ")
    }

private fun deletedOn(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
