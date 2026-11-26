package io.eksoda.engine.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

public enum class AdviceKind {
    NO_MOVEMENTS,
    NEGATIVE_FLOW,
    BUDGET_EXCEEDED,
    BUDGET_NEAR,
    FORECAST_OVER,
    SAVINGS_GAP,
    SAVINGS_ON_TRACK,
    PLAN_CONFLICT,
    UNCLASSIFIED,
    SPENDING_UP,
    SPENDING_DOWN,
    SUBSCRIPTIONS,
    WORTH_UP,
    WORTH_DOWN,
    PAYDAY_SHORTFALL,
    PAYDAY_REVIEW,
}

public data class FinanceObservation(val kind: AdviceKind, val amountMinor: BigDecimal? = null)
public data class CategoryExpense(val category: SpendingCategory, val amountMinor: BigDecimal)
public data class FinanceAdvice(
    val observations: List<FinanceObservation>,
    val categories: List<CategoryExpense>,
    val comparedDays: Int,
)

/** Observations about recorded data, not a prediction of unrecorded transactions. */
public object FinanceAdvisor {
    public fun evaluate(
        today: LocalDate,
        entries: List<LedgerEntry>,
        plan: MonthlyPlan?,
        subscriptionsMinor: List<Long>,
        worth: List<NetWorthSnapshot>,
        payday: PaydayForecast?,
        paydayNeedsReview: Boolean,
        coverageConfirmed: Boolean,
    ): FinanceAdvice {
        val month = YearMonth.from(today)
        val prior = month.minusMonths(1)
        val days = minOf(today.dayOfMonth, prior.lengthOfMonth())
        val current = entries.filter {
            YearMonth.from(it.movement.date) == month &&
                !it.movement.date.isAfter(today)
        }
        val actual = MonthlyLedger.summarize(month, current)
        val categories = current.filter { it.movement.kind == MovementKind.EXPENSE }
            .groupBy { it.movement.category }
            .map { (category, rows) ->
                CategoryExpense(
                    category,
                    rows.fold(BigDecimal.ZERO) { sum, row ->
                        sum - BigDecimal.valueOf(row.movement.amountMinor)
                    },
                )
            }.sortedWith(
                compareByDescending<CategoryExpense> {
                    it.amountMinor
                }.thenBy { it.category.name },
            )
        val observations = mutableListOf<FinanceObservation>()
        fun add(kind: AdviceKind, amount: BigDecimal? = null) {
            observations += FinanceObservation(kind, amount)
        }
        if (actual.movementCount == 0) add(AdviceKind.NO_MOVEMENTS)
        if (actual.netFlowMinor <
            0
        ) {
            add(AdviceKind.NEGATIVE_FLOW, BigDecimal.valueOf(actual.netFlowMinor).abs())
        }
        if (plan != null) {
            val progress = MonthlyPlanning.evaluate(month, today, plan, actual)
            when (progress.status) {
                BudgetStatus.OVER -> add(
                    AdviceKind.BUDGET_EXCEEDED,
                    progress.budgetRemainingMinor.abs(),
                )

                BudgetStatus.AT_LIMIT, BudgetStatus.NEAR -> add(
                    AdviceKind.BUDGET_NEAR,
                    progress.budgetRemainingMinor,
                )

                BudgetStatus.WITHIN -> Unit
            }
            if (!progress.goalCompatibleWithPlan) add(AdviceKind.PLAN_CONFLICT)
            if (coverageConfirmed && actual.movementCount > 0) {
                val projection = progress.projectedExpensesMinor
                if (projection != null &&
                    projection > BigDecimal.valueOf(plan.spendingLimitMinor)
                ) {
                    add(
                        AdviceKind.FORECAST_OVER,
                        projection - BigDecimal.valueOf(plan.spendingLimitMinor),
                    )
                }
                progress.projectedSavingsMinor?.let { saved ->
                    val gap = BigDecimal.valueOf(plan.savingsGoalMinor) - saved
                    if (gap.signum() > 0) {
                        add(AdviceKind.SAVINGS_GAP, gap)
                    } else {
                        add(AdviceKind.SAVINGS_ON_TRACK)
                    }
                }
            }
        }
        categories.firstOrNull { it.category == SpendingCategory.UNCLASSIFIED }?.let {
            add(AdviceKind.UNCLASSIFIED, it.amountMinor)
        }
        // Compare equal elapsed days. Do not claim a trend if either period lacks records.
        if (coverageConfirmed) {
            val a = MonthlyLedger.summarize(
                month,
                current.filter {
                    it.movement.date.dayOfMonth <=
                        days
                },
            )
            val b = MonthlyLedger.summarize(
                prior,
                entries.filter {
                    YearMonth.from(it.movement.date) == prior && it.movement.date.dayOfMonth <= days
                },
            )
            if (a.movementCount > 0 && b.movementCount > 0) {
                val delta =
                    BigDecimal.valueOf(a.netExpensesMinor) - BigDecimal.valueOf(b.netExpensesMinor)
                if (delta.signum() > 0) add(AdviceKind.SPENDING_UP, delta)
                if (delta.signum() < 0) add(AdviceKind.SPENDING_DOWN, delta.abs())
            }
        }
        require(subscriptionsMinor.all { it > 0 })
        if (subscriptionsMinor.isNotEmpty()) {
            add(
                AdviceKind.SUBSCRIPTIONS,
                subscriptionsMinor.fold(BigDecimal.ZERO) { sum, value ->
                    sum + BigDecimal.valueOf(value)
                },
            )
        }
        val dated = worth.filter { !it.day.isAfter(today) }.sortedByDescending { it.day }
        if (dated.size >= 2) {
            val delta = NetWorth.change(dated[1], dated[0]).amountMinor
            if (delta.signum() > 0) add(AdviceKind.WORTH_UP, delta)
            if (delta.signum() < 0) add(AdviceKind.WORTH_DOWN, delta.abs())
        }
        if (payday != null && payday.remainingMinor.signum() < 0) {
            add(AdviceKind.PAYDAY_SHORTFALL, payday.remainingMinor.abs())
        } else if (paydayNeedsReview) {
            add(AdviceKind.PAYDAY_REVIEW)
        }
        return FinanceAdvice(observations, categories, days)
    }
}
