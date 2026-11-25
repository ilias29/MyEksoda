package io.eksoda.engine.finance

import java.math.BigDecimal
import java.time.LocalDate

/** All values in EUR cents, as displayed or explicitly converted by the account owner. */
data class PortfolioSnapshot(
    val date: LocalDate,
    val valueMinor: Long,
    val depositedMinor: Long,
    val withdrawnMinor: Long,
) {
    init {
        require(valueMinor >= 0 && depositedMinor >= 0 && withdrawnMinor >= 0)
    }

    val netFundingMinor: BigDecimal
        get() = BigDecimal.valueOf(depositedMinor) - BigDecimal.valueOf(withdrawnMinor)

    /** Money gain including cash income, fees and FX, not a time-weighted return. */
    val gainMinor: BigDecimal
        get() = BigDecimal.valueOf(valueMinor) - netFundingMinor
}

data class PortfolioChange(val valueChangeMinor: BigDecimal, val fundingChangeMinor: BigDecimal) {
    val gainChangeMinor: BigDecimal get() = valueChangeMinor - fundingChangeMinor
}

object InvestmentTracking {
    fun change(earlier: PortfolioSnapshot, later: PortfolioSnapshot): PortfolioChange {
        require(earlier.date < later.date)
        require(later.depositedMinor >= earlier.depositedMinor && later.withdrawnMinor >= earlier.withdrawnMinor)
        return PortfolioChange(
            BigDecimal.valueOf(later.valueMinor) - BigDecimal.valueOf(earlier.valueMinor),
            later.netFundingMinor - earlier.netFundingMinor,
        )
    }
}
