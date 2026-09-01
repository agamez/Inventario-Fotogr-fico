package com.inventory.feature.locations

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inventory.R
import com.inventory.database.LocationEntity
import com.inventory.database.LocationWithBoxes

/**
 * Master list of storage locations.
 *
 * Navigation is owned by the caller: tapping a row invokes [onOpenLocation]
 * with the location id. Rename and Delete are offered per row through an
 * overflow menu (trailing ⋮ or long-press).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    onOpenLocation: (Long) -> Unit,
    viewModel: LocationsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var locationToRename by remember { mutableStateOf<LocationEntity?>(null) }
    var locationToDelete by remember { mutableStateOf<LocationEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LocationsViewModel.LocationsEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(context.getString(event.resId))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_locations)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_location),
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

                uiState.locations.isEmpty() -> {
                    EmptyLocationsPlaceholder(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = uiState.locations,
                            key = { entry -> entry.location.id },
                        ) { entry ->
                            LocationRow(
                                entry = entry,
                                onOpen = { onOpenLocation(entry.location.id) },
                                onRename = { locationToRename = entry.location },
                                onDelete = { locationToDelete = entry.location },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        LocationNameDialog(
            title = stringResource(R.string.add_location),
            initialName = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createLocation(name)
                showCreateDialog = false
            },
        )
    }

    locationToRename?.let { location ->
        key(location.id) {
            LocationNameDialog(
                title = stringResource(R.string.rename_location),
                initialName = location.name,
                onDismiss = { locationToRename = null },
                onConfirm = { name ->
                    viewModel.renameLocation(location.id, name)
                    locationToRename = null
                },
            )
        }
    }

    locationToDelete?.let { location ->
        DeleteLocationDialog(
            name = location.name,
            onDismiss = { locationToDelete = null },
            onConfirm = {
                viewModel.deleteLocation(location.id)
                locationToDelete = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocationRow(
    entry: LocationWithBoxes,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val boxCount = entry.boxes.size

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true },
            ),
        headlineContent = {
            Text(
                text = entry.location.name,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = pluralStringResource(boxCount, R.plurals.box_count, boxCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.location_options),
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

/** Shared create/rename dialog: blank input disables Save; text state is local. */
@Composable
private fun LocationNameDialog(
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
                label = { Text(stringResource(R.string.location_name_label)) },
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
private fun DeleteLocationDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_location_title)) },
        text = { Text(stringResource(R.string.delete_location_message, name)) },
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

@Composable
private fun EmptyLocationsPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Place,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_locations),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
