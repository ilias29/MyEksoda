package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

public data class MonthlyPlan(
    val spendingLimitMinor: Long,
    val expectedIncomeMinor: Long,
    val savingsGoalMinor: Long,
) {
    init {
        require(spendingLimitMinor > 0)
        require(expectedIncomeMinor >= 0 && savingsGoalMinor >= 0)
    }
}

public enum class BudgetStatus { WITHIN, NEAR, AT_LIMIT, OVER }

public data class PlanProgress(
    val budgetRemainingMinor: BigDecimal,
    val status: BudgetStatus,
    val spendingFraction: Double,
    val savingsGapMinor: BigDecimal,
    val projectedExpensesMinor: BigDecimal?,
    val projectedSavingsMinor: BigDecimal?,
    val goalCompatibleWithPlan: Boolean,
)

public object MonthlyPlanning {
    public fun evaluate(
        month: YearMonth,
        today: LocalDate,
        plan: MonthlyPlan,
        actual: MonthlyOverview,
    ): PlanProgress {
        require(!month.isAfter(YearMonth.from(today)))
        val limit = BigDecimal.valueOf(plan.spendingLimitMinor)
        val income = BigDecimal.valueOf(plan.expectedIncomeMinor)
        val goal = BigDecimal.valueOf(plan.savingsGoalMinor)
        val spent = BigDecimal.valueOf(actual.netExpensesMinor)
        val fraction = spent.max(BigDecimal.ZERO).divide(limit, 8, RoundingMode.HALF_UP).toDouble()
        val status = when {
            spent > limit -> BudgetStatus.OVER
            spent == limit -> BudgetStatus.AT_LIMIT
            spent * BigDecimal.TEN >= limit * BigDecimal.valueOf(8) -> BudgetStatus.NEAR
            else -> BudgetStatus.WITHIN
        }
        val elapsed = if (month ==
            YearMonth.from(today)
        ) {
            today.dayOfMonth
        } else {
            month.lengthOfMonth()
        }
        // A partial month needs at least seven days of recorded history before displaying a pace estimate.
        val forecast = when {
            elapsed == month.lengthOfMonth() -> spent

            elapsed < 7 || actual.movementCount == 0 -> null

            else -> spent.max(
                BigDecimal.ZERO,
            ).multiply(BigDecimal.valueOf(month.lengthOfMonth().toLong()))
                .divide(BigDecimal.valueOf(elapsed.toLong()), 0, RoundingMode.HALF_UP)
        }
        val estimatedIncome = if (elapsed ==
            month.lengthOfMonth()
        ) {
            BigDecimal.valueOf(actual.incomeMinor)
        } else {
            income
        }
        return PlanProgress(
            limit - spent,
            status,
            fraction,
            goal - BigDecimal.valueOf(actual.netFlowMinor),
            forecast,
            forecast?.let { estimatedIncome - it },
            income - limit >= goal,
        )
    }
}
