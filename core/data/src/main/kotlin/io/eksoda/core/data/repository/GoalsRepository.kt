package io.eksoda.core.data.repository

import io.eksoda.core.model.Budget
import io.eksoda.core.model.CategorySpending
import io.eksoda.core.model.EPaymentProgress
import io.eksoda.core.model.MonthBudgets
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

/**
 * The household's goals: monthly budgets (for the whole month and per category) and the yearly
 * e-payment target. The rules are in core:engine `analytics/Budgets.kt` and `EPayments.kt`.
 * The annual income the target comes from is set through [HouseholdRepository.setAnnualIncome].
 */
interface GoalsRepository {
    /** The budgets set, the whole month's first. */
    fun getBudgets(): Flow<List<Budget>>

    /**
     * The most [categoryId]'s budget can be so that the category budgets in [budgets] still fit in
     * the whole month's (0 when the others use it all); null without a whole-month budget.
     */
    fun categoryBudgetLimit(budgets: List<Budget>, categoryId: String): BigDecimal?

    /**
     * Sets the budget of [categoryId] (null: the whole month) to [amount], which must be above 0,
     * or removes it when [amount] is null. Fails with [BudgetsDoNotFitException] when the
     * category budgets would add up to more than the whole month's.
     */
    suspend fun setBudget(categoryId: String?, amount: BigDecimal?): Result<Unit>

    /**
     * This month's budgets against the spending so far. A caller that already has this month's
     * [spending] (from the 1st to today) passes it to save the query; any other range is ignored.
     */
    suspend fun getMonthBudgets(spending: CategorySpending? = null): Result<MonthBudgets>

    /** This year's card and bank payments against the target. */
    suspend fun getEPayments(): Result<EPaymentProgress>
}

/** The category budgets would add up to more than the whole month's budget. */
class BudgetsDoNotFitException : IllegalArgumentException("category budgets exceed the month's")
