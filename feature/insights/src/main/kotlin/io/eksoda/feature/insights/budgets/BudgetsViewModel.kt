package io.eksoda.feature.insights.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.eksoda.core.data.repository.CatalogRepository
import io.eksoda.core.data.repository.GoalsRepository
import io.eksoda.core.data.repository.HouseholdRepository
import io.eksoda.core.model.Category
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** A category and its monthly budget, null when it has none. */
data class CategoryBudget(val category: Category, val amount: BigDecimal?)

data class BudgetsUiState(
    val loading: Boolean = true,
    val currency: String = DEFAULT_CURRENCY,
    /** The whole month's budget. */
    val total: BigDecimal? = null,
    val categories: List<CategoryBudget> = emptyList(),
    /**
     * The most each category's budget can be so that the categories fit in the whole month's
     * (`GoalsRepository.categoryBudgetLimit`); empty without a whole-month budget.
     */
    val limits: Map<String, BigDecimal> = emptyMap(),
) {
    /** The category budgets added up. */
    val categoriesTotal: BigDecimal
        get() = categories.mapNotNull { it.amount }.fold(BigDecimal.ZERO, BigDecimal::add)
}

sealed interface BudgetsEvent {
    /** Sets the budget of [categoryId] (null: the whole month); a null [amount] removes it. */
    data class Set(val categoryId: String?, val amount: BigDecimal?) : BudgetsEvent
}

sealed interface BudgetsEffect {
    data object SaveFailed : BudgetsEffect
}

/** The budgets editor. It follows the database, so a saved budget shows by itself. */
@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val goals: GoalsRepository,
    catalog: CatalogRepository,
    households: HouseholdRepository,
) : ViewModel() {
    val state: StateFlow<BudgetsUiState> = combine(
        goals.getBudgets(),
        catalog.getCategoryList(),
        households.household,
    ) { budgets, categories, household ->
        val byCategory = budgets.mapNotNull { budget ->
            budget.category?.let { it.id to budget.amount }
        }.toMap()
        BudgetsUiState(
            loading = false,
            currency = household?.defaultCurrency ?: DEFAULT_CURRENCY,
            total = budgets.firstOrNull { it.category == null }?.amount,
            categories = categories.map { CategoryBudget(it, byCategory[it.id]) },
            limits = categories.mapNotNull { category ->
                goals.categoryBudgetLimit(budgets, category.id)?.let { category.id to it }
            }.toMap(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BudgetsUiState())

    private val _effects = Channel<BudgetsEffect>(Channel.BUFFERED)
    val effects: Flow<BudgetsEffect> = _effects.receiveAsFlow()

    fun onEvent(event: BudgetsEvent) {
        when (event) {
            is BudgetsEvent.Set -> viewModelScope.launch {
                if (goals.setBudget(event.categoryId, event.amount).isFailure) {
                    _effects.send(BudgetsEffect.SaveFailed)
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

private const val DEFAULT_CURRENCY = "EUR"
