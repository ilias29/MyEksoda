package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class PaydayCalendarTest {
    private val today = LocalDate.of(2026, 11, 26)
    private val plan = PaydayPlan(today, 80_000, today.plusDays(10))
    private fun payment(id: String, days: Long, amount: Long, state: PaymentState = PaymentState.PENDING) =
        PlannedPayment(id, id, today.plusDays(days), amount, PaymentType.OTHER, state)

    @Test
    fun includesOverdueButNotPaidCancelledOrPayday() {
        val result = PaydayCalendar.forecast(plan, today, listOf(
            payment("rent", -2, 30_000), payment("bill", 2, 10_000),
            payment("paid", 1, 20_000, PaymentState.PAID),
            payment("cancelled", 1, 10_000, PaymentState.CANCELLED),
            payment("payday", 10, 50_000),
        ))
        assertEquals(BigDecimal("40000"), result.reservedMinor)
        assertEquals(BigDecimal("40000"), result.remainingMinor)
        assertEquals(BigDecimal("4000"), result.dailyAllowanceMinor)
        assertEquals(1, result.overdueCount)
        assertEquals(10L, result.days)
    }

    @Test
    fun negativeBalanceAndStaleInputs() {
        val result = PaydayCalendar.forecast(plan, today, listOf(payment("large", 0, 90_000)))
        assertEquals(BigDecimal("-10000"), result.remainingMinor)
        assertEquals(BigDecimal.ZERO, result.dailyAllowanceMinor)
        assertThrows(IllegalArgumentException::class.java) {
            PaydayCalendar.forecast(plan, today.plusDays(1), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val p = payment("duplicate", 0, 100)
            PaydayCalendar.forecast(plan, today, listOf(p, p))
        }
    }
}
