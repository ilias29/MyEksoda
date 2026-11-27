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

public data class FinanceAdviceInput(
    val today: LocalDate,
    val entries: List<LedgerEntry>,
    val plan: MonthlyPlan?,
    val subscriptionsMinor: List<Long>,
    val worth: List<NetWorthSnapshot>,
    val payday: PaydayForecast?,
    val paydayNeedsReview: Boolean,
    val coverageConfirmed: Boolean,
)

/** Observations about recorded data, not a prediction of unrecorded transactions. */
public object FinanceAdvisor {
    public fun evaluate(input: FinanceAdviceInput): FinanceAdvice {
        val month = YearMonth.from(input.today)
        val prior = month.minusMonths(1)
        val days = minOf(input.today.dayOfMonth, prior.lengthOfMonth())
        val current = currentEntries(input.entries, month, input.today)
        val actual = MonthlyLedger.summarize(month, current)
        val categories = categories(current)
        val observations = mutableListOf<FinanceObservation>()
        fun add(kind: AdviceKind, amount: BigDecimal? = null) {
            observations += FinanceObservation(kind, amount)
        }
        addBasic(actual, categories, ::add)
        addPlan(input, month, actual, ::add)
        addTrend(input, month, prior, current, days, ::add)
        addSubscriptions(input.subscriptionsMinor, ::add)
        addWorth(input.worth, input.today, ::add)
        addPayday(input.payday, input.paydayNeedsReview, ::add)
        return FinanceAdvice(observations, categories, days)
    }

    private fun currentEntries(
        entries: List<LedgerEntry>,
        month: YearMonth,
        today: LocalDate,
    ): List<LedgerEntry> = entries.filter {
        YearMonth.from(it.movement.date) == month && !it.movement.date.isAfter(today)
    }

    private fun categories(entries: List<LedgerEntry>): List<CategoryExpense> = entries
        .filter { it.movement.kind == MovementKind.EXPENSE }
        .groupBy { it.movement.category }
        .map { (category, rows) ->
            CategoryExpense(
                category,
                rows.fold(BigDecimal.ZERO) { sum, row ->
                    sum - BigDecimal.valueOf(row.movement.amountMinor)
                },
            )
        }
        .sortedWith(compareByDescending<CategoryExpense> { it.amountMinor }.thenBy { it.category.name })

    private fun addBasic(
        actual: MonthlyOverview,
        categories: List<CategoryExpense>,
        add: (AdviceKind, BigDecimal?) -> Unit,
    ) {
        if (actual.movementCount == 0) add(AdviceKind.NO_MOVEMENTS, null)
        if (actual.netFlowMinor < 0) {
            add(AdviceKind.NEGATIVE_FLOW, BigDecimal.valueOf(actual.netFlowMinor).abs())
        }
        categories.firstOrNull { it.category == SpendingCategory.UNCLASSIFIED }?.let {
            add(AdviceKind.UNCLASSIFIED, it.amountMinor)
        }
    }

    private fun addPlan(
        input: FinanceAdviceInput,
        month: YearMonth,
        actual: MonthlyOverview,
        add: (AdviceKind, BigDecimal?) -> Unit,
    ) {
        val plan = input.plan ?: return
        val progress = MonthlyPlanning.evaluate(month, input.today, plan, actual)
        when (progress.status) {
            BudgetStatus.OVER -> add(AdviceKind.BUDGET_EXCEEDED, progress.budgetRemainingMinor.abs())
            BudgetStatus.AT_LIMIT, BudgetStatus.NEAR -> add(AdviceKind.BUDGET_NEAR, progress.budgetRemainingMinor)
            BudgetStatus.WITHIN -> Unit
        }
        if (!progress.goalCompatibleWithPlan) add(AdviceKind.PLAN_CONFLICT, null)
        if (input.coverageConfirmed && actual.movementCount > 0) addForecast(progress, plan, add)
    }

    private fun addForecast(
        progress: PlanProgress,
        plan: MonthlyPlan,
        add: (AdviceKind, BigDecimal?) -> Unit,
    ) {
        val limit = BigDecimal.valueOf(plan.spendingLimitMinor)
        progress.projectedExpensesMinor?.let { projection ->
            if (projection > limit) add(AdviceKind.FORECAST_OVER, projection - limit)
        }
        progress.projectedSavingsMinor?.let { saved ->
            val gap = BigDecimal.valueOf(plan.savingsGoalMinor) - saved
            if (gap.signum() > 0) add(AdviceKind.SAVINGS_GAP, gap)
            else add(AdviceKind.SAVINGS_ON_TRACK, null)
        }
    }

    private fun addTrend(
        input: FinanceAdviceInput,
        month: YearMonth,
        prior: YearMonth,
        current: List<LedgerEntry>,
        days: Int,
        add: (AdviceKind, BigDecimal?) -> Unit,
    ) {
        if (!input.coverageConfirmed) return
        val a = MonthlyLedger.summarize(month, current.filter { it.movement.date.dayOfMonth <= days })
        val b = MonthlyLedger.summarize(
            prior,
            input.entries.filter {
                YearMonth.from(it.movement.date) == prior && it.movement.date.dayOfMonth <= days
            },
        )
        if (a.movementCount == 0 || b.movementCount == 0) return
        val delta = BigDecimal.valueOf(a.netExpensesMinor) - BigDecimal.valueOf(b.netExpensesMinor)
        if (delta.signum() > 0) add(AdviceKind.SPENDING_UP, delta)
        if (delta.signum() < 0) add(AdviceKind.SPENDING_DOWN, delta.abs())
    }

    private fun addSubscriptions(
        subscriptionsMinor: List<Long>,
        add: (AdviceKind, BigDecimal?) -> Unit,
    ) {
        require(subscriptionsMinor.all { it > 0 })
        if (subscriptionsMinor.isNotEmpty()) {
            add(
                AdviceKind.SUBSCRIPTIONS,
                subscriptionsMinor.fold(BigDecimal.ZERO) { sum, value ->
                    sum + BigDecimal.valueOf(value)
                },
            )
        }
    }

    private fun addWorth(
        worth: List<NetWorthSnapshot>,
        today: LocalDate,
        add: (AdviceKind, BigDecimal?) -> Unit,
    ) {
        val dated = worth.filter { !it.day.isAfter(today) }.sortedByDescending { it.day }
        if (dated.size < 2) return
        val delta = NetWorth.change(dated[1], dated[0]).amountMinor
        if (delta.signum() > 0) add(AdviceKind.WORTH_UP, delta)
        if (delta.signum() < 0) add(AdviceKind.WORTH_DOWN, delta.abs())
    }

    private fun addPayday(
        payday: PaydayForecast?,
        needsReview: Boolean,
        add: (AdviceKind, BigDecimal?) -> Unit,
    ) {
        if (payday != null && payday.remainingMinor.signum() < 0) {
            add(AdviceKind.PAYDAY_SHORTFALL, payday.remainingMinor.abs())
        } else if (needsReview) {
            add(AdviceKind.PAYDAY_REVIEW, null)
        }
    }
}
