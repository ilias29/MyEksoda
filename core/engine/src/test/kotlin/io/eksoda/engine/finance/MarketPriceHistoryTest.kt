package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class MarketPriceHistoryTest {
    private fun price(day: String, close: String) = DailyMarketPrice(LocalDate.parse(day), BigDecimal(close))

    @Test
    fun periodChangesUseActualDatedBaselines() {
        val history = MarketPriceHistory("EUR", listOf(
            price("2025-11-26", "80"),
            price("2026-11-19", "150"),
            price("2026-11-25", "100"),
            price("2026-10-26", "100"),
            price("2026-11-26", "120"),
        ))
        assertEquals(BigDecimal("20.00"), history.change(MarketPeriod.DAY)?.percent)
        assertEquals(BigDecimal("-20.00"), history.change(MarketPeriod.WEEK)?.percent)
        assertEquals(BigDecimal("20.00"), history.change(MarketPeriod.MONTH)?.percent)
        assertEquals(BigDecimal("50.00"), history.change(MarketPeriod.YEAR)?.percent)
        assertEquals(LocalDate.of(2025, 11, 26), history.change(MarketPeriod.YEAR)?.from)
    }

    @Test
    fun missingAndStaleHistoryDoesNotInventReturns() {
        val latest = price("2026-11-26", "120")
        assertNull(MarketPriceHistory("USD", listOf(latest)).change(MarketPeriod.DAY))
        assertNull(MarketPriceHistory("USD", listOf(latest, price("2025-11-26", "80"))).change(MarketPeriod.WEEK))
        val monday = MarketPriceHistory("EUR", listOf(price("2026-11-23", "100"), price("2026-11-20", "80")))
        assertEquals(BigDecimal("25.00"), monday.change(MarketPeriod.DAY)?.percent)
    }

    @Test
    fun rejectsInvalidSeries() {
        val latest = price("2026-11-26", "120")
        assertThrows(IllegalArgumentException::class.java) { MarketPriceHistory("EUR", listOf(latest, latest)) }
        assertThrows(IllegalArgumentException::class.java) { MarketPriceHistory("GBp", listOf(latest)) }
        assertThrows(IllegalArgumentException::class.java) { MarketPriceHistory("EUR", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { price("2026-11-26", "0") }
    }
}
