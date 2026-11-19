package io.eksoda.core.model

import java.math.BigDecimal
import java.time.YearMonth

/** A monthly budget: for the whole month when [category] is null, else for that category. */
public data class Budget(val category: Category?, val amount: BigDecimal)

public enum class BudgetLevel { UNDER, NEAR, OVER }

/** One budget against this month's spending so far. */
public data class BudgetProgress(
    /** Null for the whole month's budget. */
    val category: Category?,
    val budget: BigDecimal,
    val spent: BigDecimal,
    /** The budget minus the spending: negative when over. */
    val left: BigDecimal,
    /** Spending in percent of the budget (2 places); null for a budget of 0. */
    val usedPercent: BigDecimal?,
    val level: BudgetLevel,
)

/** This month's budgets. Amounts are in [currency]. */
public data class MonthBudgets(
    val currency: String,
    val month: YearMonth,
    /** How far into the month today is, in percent: where the bars mark "today". */
    val elapsedPercent: BigDecimal,
    val total: BudgetProgress?,
    /** The categories with a budget, most used first. */
    val categories: List<BudgetProgress>,
) {
    val isEmpty: Boolean get() = total == null && categories.isEmpty()

    /** How many budgets this month's spending has passed. */
    val overCount: Int
        get() = categories.count { it.level == BudgetLevel.OVER } +
            if (total?.level == BudgetLevel.OVER) 1 else 0
}

/**
 * This year's card and bank payments against the e-payment target. [target] and the figures
 * that need it are null until the household's annual income is set.
 */
public data class EPaymentProgress(
    val currency: String,
    val year: Int,
    val income: BigDecimal?,
    val target: BigDecimal?,
    /** Paid by card or bank this year, from the receipts in Eksoda. */
    val paid: BigDecimal,
    val remaining: BigDecimal?,
    /** What is left, spread over the months left (this one included). */
    val perMonth: BigDecimal?,
    /** The year's total if the pace so far continues. */
    val projected: BigDecimal,
    val paidPercent: BigDecimal?,
    /** The rule the target follows, for display: this percent of the income, at most [cap]. */
    val sharePercent: BigDecimal,
    val cap: BigDecimal,
)
