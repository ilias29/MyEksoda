package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FinanceAdvisorTest {
    private val today = LocalDate.parse("2026-03-31")
    private fun row(date: String, amount: Long, kind: MovementKind = MovementKind.EXPENSE,
                    category: SpendingCategory = SpendingCategory.UNCLASSIFIED) =
        LedgerEntry(FinanceSource.BANK, BankMovement("A", LocalDate.parse(date), "sample",
            amount, kind, category = category))

    private val rows = listOf(
        row("2026-03-02", -30000, category = SpendingCategory.GROCERIES),
        row("2026-03-03", -10000), row("2026-03-31", -5000),
        row("2026-03-04", 5000, MovementKind.REFUND),
        row("2026-03-01", 10000, MovementKind.INCOME),
        row("2026-03-01", -99999, MovementKind.TRANSFER),
        row("2026-02-01", -20000), row("2026-04-01", -99999),
    )

    private fun advice(confirmed: Boolean, entries: List<LedgerEntry> = rows) =
        FinanceAdvisor.evaluate(today, entries, MonthlyPlan(35000, 50000, 20000),
            listOf(1000, 2000), emptyList(), null, false, confirmed)

    @Test
    fun equalDaysAndRecordedAmounts() {
        val result = advice(true)
        val observations = result.observations.associateBy { it.kind }
        assertEquals(28, result.comparedDays)
        assertEquals(BigDecimal("15000"), observations[AdviceKind.SPENDING_UP]?.amountMinor)
        assertEquals(BigDecimal("5000"), observations[AdviceKind.BUDGET_EXCEEDED]?.amountMinor)
        assertEquals(BigDecimal("30000"), observations[AdviceKind.NEGATIVE_FLOW]?.amountMinor)
        assertEquals(BigDecimal("3000"), observations[AdviceKind.SUBSCRIPTIONS]?.amountMinor)
        assertEquals(SpendingCategory.GROCERIES, result.categories.first().category)
    }

    @Test
    fun noUnsupportedTrend() {
        val unconfirmed = advice(false).observations.map { it.kind }
        assertFalse(AdviceKind.SPENDING_UP in unconfirmed)
        assertFalse(AdviceKind.FORECAST_OVER in unconfirmed)
        assertFalse(AdviceKind.SAVINGS_GAP in unconfirmed)
        val empty = advice(true, emptyList()).observations.map { it.kind }
        assertTrue(AdviceKind.NO_MOVEMENTS in empty)
        assertFalse(AdviceKind.SPENDING_UP in empty)
        assertFalse(AdviceKind.SAVINGS_ON_TRACK in empty)
    }
}
