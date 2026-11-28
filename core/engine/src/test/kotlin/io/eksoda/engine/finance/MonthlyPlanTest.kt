package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class MonthlyPlanTest {
    private val month = YearMonth.of(2026, 11)
    private val plan = MonthlyPlan(100_000, 150_000, 40_000)
    private fun actual(spent: Long, refunds: Long = 0) =
        MonthlyOverview(100_000, 0, spent, 0, refunds, 0, 0, 1)

    @Test
    fun thresholdsAndRefunds() {
        fun check(spent: Long) =
            MonthlyPlanning.evaluate(month, month.atDay(15), plan, actual(spent))
        assertEquals(BudgetStatus.WITHIN, check(79_999).status)
        assertEquals(BudgetStatus.NEAR, check(80_000).status)
        assertEquals(BudgetStatus.AT_LIMIT, check(100_000).status)
        assertEquals(BudgetStatus.OVER, check(100_001).status)
        assertEquals(
            BigDecimal("20000"),
            MonthlyPlanning.evaluate(
                month,
                month.atDay(15),
                plan,
                actual(90_000, 10_000),
            ).budgetRemainingMinor,
        )
    }

    @Test
    fun projectionAndGoal() {
        val progress = MonthlyPlanning.evaluate(month, month.atDay(15), plan, actual(60_000))
        assertEquals(BigDecimal("120000"), progress.projectedExpensesMinor)
        assertEquals(BigDecimal("30000"), progress.projectedSavingsMinor)
        assertNull(
            MonthlyPlanning.evaluate(
                month,
                month.atDay(6),
                plan,
                actual(60_000),
            ).projectedExpensesMinor,
        )
        assertEquals(
            BigDecimal("40000"),
            MonthlyPlanning.evaluate(
                month,
                month.atEndOfMonth(),
                plan,
                actual(60_000),
            ).projectedSavingsMinor,
        )
        assertFalse(
            MonthlyPlanning.evaluate(
                month,
                month.atDay(15),
                MonthlyPlan(120_000, 150_000, 40_000),
                actual(60_000),
            ).goalCompatibleWithPlan,
        )
        assertThrows(IllegalArgumentException::class.java) { MonthlyPlan(0, 100, 0) }
    }
}
