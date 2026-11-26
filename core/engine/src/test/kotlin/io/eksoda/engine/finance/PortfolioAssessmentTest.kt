package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class PortfolioAssessmentTest {
    private val first = PortfolioSnapshot(LocalDate.of(2026, 11, 1), 100_000, 90_000, 0)

    @Test
    fun separatesRecentAndLifetimeDirection() {
        val latest = first.copy(date = first.date.plusDays(7), valueMinor = 95_000)
        val status = PortfolioAssessment.assess(latest, first)
        assertEquals(PortfolioDirection.UP, status.totalDirection)
        assertEquals(PortfolioDirection.DOWN, status.recentDirection)
        assertEquals(BigDecimal("-5.00"), status.recentPercent)
    }

    @Test
    fun depositsAreNotGainsAndPercentIsNotInvented() {
        val latest = first.copy(
            date = first.date.plusDays(7),
            valueMinor = 120_000,
            depositedMinor = 110_000,
        )
        val status = PortfolioAssessment.assess(latest, first)
        assertEquals(PortfolioDirection.FLAT, status.recentDirection)
        assertEquals(BigDecimal.ZERO, status.recentGainMinor)
        assertNull(status.recentPercent)
    }

    @Test
    fun firstSnapshotHasNoTrend() {
        val status = PortfolioAssessment.assess(first, null)
        assertEquals(PortfolioDirection.UP, status.totalDirection)
        assertNull(status.recentDirection)
        assertNull(status.recentPercent)
    }
}
