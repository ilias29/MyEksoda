package io.eksoda.engine.analytics

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// Monthly budgets: the household may set one for the whole month and one per category. A budget
// is "near" from [BUDGET_NEAR_PERCENT] of it and "over" once spending passes it. The categories
// are parts of the month, so together they never exceed the whole month's budget when one is set
// ([categoryBudgetsFit]).

/** The share of a budget, in percent, from which it counts as nearly used up. */
public val BUDGET_NEAR_PERCENT: BigDecimal = BigDecimal("80")

private const val PERCENT_SCALE = 2
private val HUNDRED = BigDecimal("100")

public enum class BudgetLevel { UNDER, NEAR, OVER }

/** How much of a budget this month's spending uses. */
public data class BudgetUse(
    /** The budget minus the spending: negative when over. */
    val left: BigDecimal,
    /** The spending in percent of the budget (2 places); null for a budget of 0. */
    val usedPercent: BigDecimal?,
    val level: BudgetLevel,
)

public fun budgetUse(budget: BigDecimal, spent: BigDecimal): BudgetUse {
    val used = if (budget.signum() == 0) {
        null
    } else {
        (spent * HUNDRED).divide(budget, PERCENT_SCALE, RoundingMode.HALF_UP)
    }
    val level = when {
        spent > budget -> BudgetLevel.OVER
        used != null && used >= BUDGET_NEAR_PERCENT -> BudgetLevel.NEAR
        else -> BudgetLevel.UNDER
    }
    return BudgetUse(left = budget - spent, usedPercent = used, level = level)
}

/**
 * Whether the category budgets fit in the whole month's: their sum is at most [total]. Without a
 * whole-month budget any amounts fit.
 */
public fun categoryBudgetsFit(total: BigDecimal?, categories: Collection<BigDecimal>): Boolean =
    total == null || categories.sum() <= total

/**
 * The most one category's budget can be so that the categories still fit in the whole month's
 * [total] ([categoryBudgetsFit]), given the [otherCategories]' budgets: 0 when they already use it
 * all, null without a whole-month budget.
 */
public fun categoryBudgetLimit(
    total: BigDecimal?,
    otherCategories: Collection<BigDecimal>,
): BigDecimal? = total?.let { (it - otherCategories.sum()).max(BigDecimal.ZERO) }

private fun Collection<BigDecimal>.sum(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

/** How far into its month [today] is, in percent (2 places): the day's end counts. */
public fun monthElapsedPercent(today: LocalDate): BigDecimal =
    (BigDecimal(today.dayOfMonth) * HUNDRED)
        .divide(BigDecimal(today.lengthOfMonth()), PERCENT_SCALE, RoundingMode.HALF_UP)
