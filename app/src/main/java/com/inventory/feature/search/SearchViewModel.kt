package com.inventory.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inventory.database.PhotoSearchResult
import com.inventory.feature.photos.PhotoRepository
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
 * Presents the global search over every photo in the database, regardless
 * of which box or location it lives in. A blank query browses all items
 * (newest first); any non-blank query filters by description. The DAO caps
 * results at 100 rows.
 *
 * The screen is read-only — no mutations, hence no one-shot event channel
 * as the CRUD features have — so everything it needs lives in [uiState].
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: PhotoRepository,
) : ViewModel() {

    data class SearchUiState(
        val query: String = "",
        val results: List<PhotoSearchResult> = emptyList(),
        val isLoading: Boolean = true,
    )

    private val _query = MutableStateFlow("")

    /** Restarts the search flow on every query change without piling up. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val results: Flow<List<PhotoSearchResult>> = _query
        .map { raw -> raw.trim() }
        .flatMapLatest { query -> repository.searchAll(query) }

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
}
