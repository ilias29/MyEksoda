package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class NetWorthTest {
    private val day = LocalDate.of(2026, 11, 26)
    private val snapshot = NetWorthSnapshot(day, 100000, 20000, 300000, 50000, 150000, 10000, true)

    @Test
    fun assetsAndLiabilities() {
        assertEquals(BigDecimal("470000"), snapshot.assetsMinor)
        assertEquals(BigDecimal("160000"), snapshot.debtsMinor)
        assertEquals(BigDecimal("310000"), snapshot.netWorthMinor)
        assertEquals(BigDecimal("120000"), snapshot.liquidMinor)
        assertEquals(BigDecimal("-40000"), snapshot.copy(loanMinor = 500000).netWorthMinor)
    }

    @Test
    fun principalRepaymentDoesNotChangeWealth() {
        val later = snapshot.copy(day = day.plusDays(1), bankMinor = 90000, loanMinor = 140000)
        assertEquals(BigDecimal.ZERO, NetWorth.change(snapshot, later).amountMinor)
    }

    @Test
    fun exactDayOnly() {
        val portfolio = PortfolioSnapshot(day, 300000, 250000, 0)
        assertEquals(300000L, NetWorth.lightyearForDay(day, listOf(portfolio)))
        assertNull(NetWorth.lightyearForDay(day.plusDays(1), listOf(portfolio)))
        assertThrows(IllegalArgumentException::class.java) {
            NetWorth.lightyearForDay(day, listOf(portfolio, portfolio))
        }
    }
}
