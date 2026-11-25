package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class DailyMarketPrice(val day: LocalDate, val close: BigDecimal) {
    init { require(close.signum() > 0) }
}

enum class MarketPeriod { DAY, WEEK, MONTH, YEAR }

data class MarketPriceChange(val from: LocalDate, val to: LocalDate, val percent: BigDecimal)

class MarketPriceHistory(
    val currency: String,
    prices: List<DailyMarketPrice>,
) {
    val prices: List<DailyMarketPrice> = prices.sortedByDescending { it.day }
    init {
        require(currency.matches(Regex("[A-Z]{3}")))
        require(prices.isNotEmpty() && prices.map { it.day }.distinct().size == prices.size)
    }

    val latest: DailyMarketPrice get() = prices.first()

    /** Returns price change only, excluding dividends, FX and actual trading activity. */
    fun change(period: MarketPeriod): MarketPriceChange? {
        val cutoff = when (period) {
            MarketPeriod.DAY -> latest.day.minusDays(1)
            MarketPeriod.WEEK -> latest.day.minusWeeks(1)
            MarketPeriod.MONTH -> latest.day.minusMonths(1)
            MarketPeriod.YEAR -> latest.day.minusYears(1)
        }
        val baseline = prices.firstOrNull { it.day <= cutoff } ?: return null
        // Avoid reporting a distant, stale price as a period comparison.
        if (baseline.day.isBefore(cutoff.minusDays(7))) return null
        val percent = (latest.close - baseline.close).multiply(BigDecimal(100))
            .divide(baseline.close, 2, RoundingMode.HALF_UP)
        return MarketPriceChange(baseline.day, latest.day, percent)
    }
}
