package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode

public enum class PortfolioDirection { UP, DOWN, FLAT }

public data class PortfolioStatus(
    val totalGainMinor: BigDecimal,
    val totalDirection: PortfolioDirection,
    val recentGainMinor: BigDecimal?,
    val recentDirection: PortfolioDirection?,
    val recentPercent: BigDecimal?,
    val hasCashFlows: Boolean,
)

/** Descriptive status, not a suitability assessment or recommendation. */
public object PortfolioAssessment {
    public fun assess(latest: PortfolioSnapshot, previous: PortfolioSnapshot?): PortfolioStatus {
        val change = previous?.let { InvestmentTracking.change(it, latest) }
        val flows = previous != null && (previous.depositedMinor != latest.depositedMinor ||
            previous.withdrawnMinor != latest.withdrawnMinor)
        // Without cash-flow timing, a period return with external flows would be misleading.
        val percent = if (previous != null && change != null && !flows && previous.valueMinor > 0) {
            change.gainChangeMinor.multiply(BigDecimal(100))
                .divide(BigDecimal.valueOf(previous.valueMinor), 2, RoundingMode.HALF_UP)
        } else null
        return PortfolioStatus(
            latest.gainMinor, direction(latest.gainMinor), change?.gainChangeMinor,
            change?.gainChangeMinor?.let(::direction), percent, flows,
        )
    }

    private fun direction(value: BigDecimal): PortfolioDirection = when (value.signum()) {
        1 -> PortfolioDirection.UP
        -1 -> PortfolioDirection.DOWN
        else -> PortfolioDirection.FLAT
    }
}
