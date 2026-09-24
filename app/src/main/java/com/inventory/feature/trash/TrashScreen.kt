package com.inventory.feature.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inventory.R
import com.inventory.storage.TrashManager
import java.text.DateFormat
import java.util.Date

/**
 * The Trash screen: every soft-deleted container/photo with its original
 * location, kind and deletion time, each offering Restore or Delete
 * forever, plus a top-bar "Empty trash". Restored entries return to their
 * original folder (collision-suffixed when the name is taken again); the
 * [TrashViewModel] re-lists and rescans the inventory after each action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var entryToPurge by remember { mutableStateOf<TrashManager.TrashEntry?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TrashViewModel.TrashEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(context.getString(event.resId))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    if (uiState.entries.isNotEmpty()) {
                        IconButton(onClick = { confirmEmptyTrash = true }) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.empty_trash),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

                uiState.entries.isEmpty() -> {
                    EmptyTrashPlaceholder(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = uiState.entries, key = { it.id }) { entry ->
                            TrashRow(
                                entry = entry,
                                onRestore = { viewModel.restore(entry.id) },
                                onDeleteForever = { entryToPurge = entry },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    entryToPurge?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToPurge = null },
            title = { Text(stringResource(R.string.delete_forever)) },
            text = { Text(stringResource(R.string.delete_forever_confirm, entry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteForever(entry.id)
                        entryToPurge = null
                    },
                ) {
                    Text(stringResource(R.string.delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToPurge = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(stringResource(R.string.empty_trash)) },
            text = { Text(stringResource(R.string.empty_trash_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.emptyTrash()
                        confirmEmptyTrash = false
                    },
                ) {
                    Text(stringResource(R.string.empty_trash))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** One trash row: kind icon, name, original location and deletion time, actions. */
@Composable
private fun TrashRow(
    entry: TrashManager.TrashEntry,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.kind == TrashManager.KIND_PHOTO) {
                    Icons.Filled.Image
                } else {
                    Icons.Outlined.Folder
                },
                contentDescription = stringResource(
                    if (entry.kind == TrashManager.KIND_PHOTO) {
                        R.string.trash_kind_photo
                    } else {
                        R.string.trash_kind_container
                    },
                ),
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = stringResource(R.string.trash_was_in, parentLabel(entry.originalParentRel)),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.trash_deleted_on,
                        DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(entry.deletedAt)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(
                        imageVector = Icons.Filled.Restore,
                        contentDescription = stringResource(R.string.restore),
                    )
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(
                        imageVector = Icons.Filled.DeleteForever,
                        contentDescription = stringResource(R.string.delete_forever),
                    )
                }
            }
        },
    )
}

/** "Building/Shelf A" → "Building / Shelf A"; the root shows "Inventory". */
@Composable
private fun parentLabel(parentRel: String): String =
    if (parentRel.isEmpty()) {
        stringResource(R.string.title_inventory)
    } else {
        parentRel.split('/').filter(String::isNotBlank).joinToString(" / ")
    }

/** Centered empty state of the Trash screen. */
@Composable
private fun EmptyTrashPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.trash_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
