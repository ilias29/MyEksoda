package io.eksoda.feature.insights.category

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.eksoda.core.data.repository.AnalyticsRepository
import io.eksoda.core.model.CategoryDetail
import io.eksoda.core.model.Period
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

sealed interface CategoryDetailUiState {
    data object Loading : CategoryDetailUiState

    /** It could not be computed, or the category is gone. */
    data object Failed : CategoryDetailUiState

    data class Loaded(val detail: CategoryDetail) : CategoryDetailUiState
}

/**
 * One category of Insights over the period it was opened with: its total, products and shops.
 * It follows the data, so a recategorized product or a new receipt shows by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryDetailViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** Null for the uncategorized products. */
    private val categoryId: String? = savedStateHandle[CATEGORY_ID]
    private val period = Period.Range(
        LocalDate.parse(checkNotNull(savedStateHandle[FROM])),
        LocalDate.parse(checkNotNull(savedStateHandle[TO])),
    )
    private val attempts = MutableStateFlow(0)

    val state: StateFlow<CategoryDetailUiState> = attempts
        .flatMapLatest { analytics.changes() }
        .mapLatest { load() }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            CategoryDetailUiState.Loading,
        )

    private suspend fun load(): CategoryDetailUiState =
        analytics.getCategoryDetail(categoryId, period).fold(
            onSuccess = { CategoryDetailUiState.Loaded(it) },
            onFailure = { CategoryDetailUiState.Failed },
        )

    fun retry() {
        attempts.value++
    }

    private companion object {
        // The navigation arguments: the property names of the app's CategoryDetailDestination.
        const val CATEGORY_ID = "categoryId"
        const val FROM = "from"
        const val TO = "to"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
