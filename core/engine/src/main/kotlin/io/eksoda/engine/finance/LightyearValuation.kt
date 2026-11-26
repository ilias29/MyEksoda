package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

public object LightyearValuation {
    /** Rate is EUR per ONE unit of original currency, explicitly provided for the observation. */
    public fun euroMinor(amount: BigDecimal, currency: String, rate: BigDecimal): Long {
        require(currency.matches(Regex("[A-Z]{3}")) && currency != "GBX")
        require(amount.signum() >= 0 && amount.precision() <= 40 && amount.scale() in 0..20)
        require(rate.signum() > 0 && rate.precision() <= 24 && rate.scale() in 0..12)
        require(currency != "EUR" || rate.compareTo(BigDecimal.ONE) == 0)
        return amount.multiply(rate).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    public fun total(day: LocalDate, amounts: List<Long>, deposits: Long, withdrawals: Long): PortfolioSnapshot {
        require(amounts.isNotEmpty() && amounts.all { it >= 0 })
        return PortfolioSnapshot(day, amounts.fold(0L, Math::addExact), deposits, withdrawals)
    }
}

public data class ImportedDividend(
    val reference: String,
    val day: LocalDate,
    val instrument: String,
    val currency: String,
    val gross: BigDecimal,
    val withheld: BigDecimal,
    val fee: BigDecimal,
    val eurRate: BigDecimal,
) {
    init {
        require(reference.isNotBlank() && reference.length <= 120 && reference.none { it.isISOControl() })
        require(instrument.isNotBlank() && instrument.length <= 100 && instrument.none { it.isISOControl() })
        require(gross.signum() > 0 && withheld.signum() >= 0 && fee.signum() >= 0 && gross >= withheld + fee)
        LightyearValuation.euroMinor(gross, currency, eurRate)
        LightyearValuation.euroMinor(withheld, currency, eurRate)
        LightyearValuation.euroMinor(fee, currency, eurRate)
    }
    public val netMinor: Long get() = LightyearValuation.euroMinor(gross - withheld - fee, currency, eurRate)
}
