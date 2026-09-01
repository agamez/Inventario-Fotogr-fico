package com.inventory.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inventory.R
import com.inventory.database.PhotoSearchResult

/** Row count at which the DAO's `LIMIT 100` starts truncating results. */
private const val SEARCH_RESULT_LIMIT = 100

/**
 * Global search over every item in the database: type to filter by
 * description across all boxes and locations, or leave the field blank to
 * browse everything (newest first). Each row shows where the item lives
 * and, when it lives in a box, tapping jumps to that box's grid via
 * [onOpenPhoto]; detached items stay inert. [onBack] pops this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenPhoto: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        searchFieldFocusRequester.requestFocus()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_search)) },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .focusRequester(searchFieldFocusRequester),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    uiState.results.isEmpty() && uiState.query.isNotBlank() -> {
                        Text(
                            text = stringResource(R.string.no_search_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        )
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (uiState.query.isBlank()) {
                                item {
                                    SearchCaption(
                                        text = stringResource(R.string.search_all_caption),
                                    )
                                }
                            }
                            items(
                                items = uiState.results,
                                key = { result -> result.photoId },
                            ) { result ->
                                SearchResultRow(
                                    result = result,
                                    onClick = result.boxId?.let { boxId ->
                                        { onOpenPhoto(boxId) }
                                    },
                                )
                            }
                            if (uiState.results.size >= SEARCH_RESULT_LIMIT) {
                                item {
                                    SearchCaption(
                                        text = stringResource(
                                            R.string.search_showing_first_100,
                                        ),
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

/** One search hit: the item's description, its box · location, and a jump. */
@Composable
private fun SearchResultRow(
    result: PhotoSearchResult,
    onClick: (() -> Unit)?,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ),
        headlineContent = {
            Text(
                text = result.description,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            val boxName = result.boxName
            Text(
                text = if (boxName == null) {
                    stringResource(R.string.search_no_box)
                } else {
                    stringResource(
                        R.string.search_result_location,
                        boxName,
                        result.locationName.orEmpty(),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
            )
        },
    )
    HorizontalDivider()
}

/** Subtle full-width caption used for the browse-mode hint and the limit note. */
@Composable
private fun SearchCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
