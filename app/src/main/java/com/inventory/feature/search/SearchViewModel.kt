package com.inventory.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.cache.CacheDao
import com.inventory.cache.CacheEntry
import com.inventory.storage.InventoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Presents the global search over the storage index: every indexed photo
 * whose EXIF description or note matches, regardless of which folder it
 * lives in. A blank query browses everything (newest first); the DAO caps
 * results at 100 rows. Since descriptions fall back to the file name
 * during a scan, the filename search is implicit.
 *
 * The screen is read-only — no mutations, hence no one-shot event channel
 * as the CRUD features have — so everything it needs lives in [uiState].
 * Thumbnails load the file at [CacheEntry.path] directly.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val dao: CacheDao,
    private val store: InventoryStore,
) : ViewModel() {

    /** One hit plus its folder rendered relative to the inventory root. */
    data class SearchHit(
        val entry: CacheEntry,
        val location: String,
    )

    data class SearchUiState(
        val query: String = "",
        val results: List<SearchHit> = emptyList(),
        val isLoading: Boolean = true,
    )

    private val _query = MutableStateFlow("")

    /** Restarts the search flow on every query change without piling up. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: Flow<List<SearchHit>> = _query
        .map { raw -> raw.trim() }
        .flatMapLatest { query -> dao.searchPhotos(query) }
        .map { rows -> rows.map { SearchHit(it, locationFor(it)) } }

    val uiState: StateFlow<SearchUiState> = combine(_query, results) { query, results ->
        SearchUiState(
            query = query,
            results = results,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState(),
    )

    fun onQueryChange(query: String) {
        _query.value = query
    }

    /** Route target for a hit: the container folder it lives in (root when at top level). */
    fun containerOf(hit: SearchHit): String = hit.entry.parentPath ?: store.rootPath

    /** "Building / Shelf A" for a photo folder, or the root folder's name. */
    private fun locationFor(entry: CacheEntry): String {
        val parentPath = entry.parentPath ?: return store.root.name
        return parentPath
            .removePrefix(store.rootPath)
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
            .joinToString(" / ")
            .ifEmpty { store.root.name }
    }
}
