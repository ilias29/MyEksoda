package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class InvestmentTrackingTest {
    private val date = LocalDate.of(2026, 11, 1)
    private val initial = PortfolioSnapshot(date, 110_000, 100_000, 0)

    @Test
    fun depositAndWithdrawalAreNotGains() {
        val deposit = PortfolioSnapshot(date.plusDays(1), 160_000, 150_000, 0)
        val withdrawal = PortfolioSnapshot(date.plusDays(2), 150_000, 150_000, 10_000)
        assertEquals(BigDecimal.ZERO, InvestmentTracking.change(initial, deposit).gainChangeMinor)
        assertEquals(
            BigDecimal.ZERO,
            InvestmentTracking.change(deposit, withdrawal).gainChangeMinor,
        )
        assertEquals(BigDecimal("10000"), withdrawal.gainMinor)
        val loss = withdrawal.copy(date = date.plusDays(3), valueMinor = 145_000)
        assertEquals(
            BigDecimal("-5000"),
            InvestmentTracking.change(withdrawal, loss).gainChangeMinor,
        )
    }

    @Test
    fun rejectsInconsistentHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentTracking.change(initial, initial)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvestmentTracking.change(
                initial,
                initial.copy(date = date.plusDays(1), depositedMinor = 10),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(valueMinor = -1) }
    }
}
