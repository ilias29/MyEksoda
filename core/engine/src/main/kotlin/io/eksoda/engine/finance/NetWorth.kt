package io.eksoda.engine.finance

import java.math.BigDecimal
import java.time.LocalDate

/** End-of-day balance sheet in EUR cents. All categories must be explicitly confirmed. */
public data class NetWorthSnapshot(
    val day: LocalDate,
    val bankMinor: Long,
    val cashMinor: Long,
    val lightyearMinor: Long,
    val otherAssetsMinor: Long,
    val loanMinor: Long,
    val otherDebtsMinor: Long,
    val fromLightyear: Boolean,
) {
    init {
        require(
            listOf(
                bankMinor,
                cashMinor,
                lightyearMinor,
                otherAssetsMinor,
                loanMinor,
                otherDebtsMinor,
            ).all {
                it >=
                    0
            },
        )
    }

    public val assetsMinor: BigDecimal
        get() = listOf(bankMinor, cashMinor, lightyearMinor, otherAssetsMinor)
            .fold(BigDecimal.ZERO) { sum, amount -> sum + BigDecimal.valueOf(amount) }

    public val debtsMinor: BigDecimal
        get() = BigDecimal.valueOf(loanMinor) + BigDecimal.valueOf(otherDebtsMinor)

    public val netWorthMinor: BigDecimal get() = assetsMinor - debtsMinor

    public val liquidMinor: BigDecimal get() = BigDecimal.valueOf(bankMinor) +
        BigDecimal.valueOf(cashMinor)
}

public data class NetWorthChange(
    val from: LocalDate,
    val to: LocalDate,
    val amountMinor: BigDecimal,
)

public object NetWorth {
    public fun change(previous: NetWorthSnapshot, latest: NetWorthSnapshot): NetWorthChange {
        require(previous.day < latest.day)
        return NetWorthChange(
            previous.day,
            latest.day,
            latest.netWorthMinor - previous.netWorthMinor,
        )
    }

    /** Never fabricate a zero or reuse a price from a different date. */
    public fun lightyearForDay(day: LocalDate, snapshots: Iterable<PortfolioSnapshot>): Long? =
        snapshots.filter { it.date == day }.let {
            require(it.size <= 1) { "Duplicate portfolio snapshot" }
            it.singleOrNull()?.valueMinor
        }
}
