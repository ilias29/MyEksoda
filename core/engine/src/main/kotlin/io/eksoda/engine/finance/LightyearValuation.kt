package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private const val MAX_AMOUNT_PRECISION = 40
private const val MAX_AMOUNT_SCALE = 20
private const val MAX_RATE_PRECISION = 24
private const val MAX_RATE_SCALE = 12
private const val MAX_REFERENCE_LENGTH = 120
private const val MAX_INSTRUMENT_LENGTH = 100

public object LightyearValuation {
    /** Rate is EUR per ONE unit of original currency, explicitly provided for the observation. */
    public fun euroMinor(amount: BigDecimal, currency: String, rate: BigDecimal): Long {
        require(currency.matches(Regex("[A-Z]{3}")) && currency != "GBX")
        require(
            amount.signum() >= 0 && amount.precision() <= MAX_AMOUNT_PRECISION &&
                amount.scale() in 0..MAX_AMOUNT_SCALE,
        )
        require(
            rate.signum() > 0 && rate.precision() <= MAX_RATE_PRECISION &&
                rate.scale() in 0..MAX_RATE_SCALE,
        )
        require(currency != "EUR" || rate.compareTo(BigDecimal.ONE) == 0)
        return amount.multiply(
            rate,
        ).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    public fun total(
        day: LocalDate,
        amounts: List<Long>,
        deposits: Long,
        withdrawals: Long,
    ): PortfolioSnapshot {
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
        require(
            reference.isNotBlank() && reference.length <= MAX_REFERENCE_LENGTH &&
                reference.none { it.isISOControl() },
        )
        require(
            instrument.isNotBlank() && instrument.length <= MAX_INSTRUMENT_LENGTH &&
                instrument.none { it.isISOControl() },
        )
        require(
            gross.signum() > 0 && withheld.signum() >= 0 && fee.signum() >= 0 &&
                gross >= withheld + fee,
        )
        LightyearValuation.euroMinor(gross, currency, eurRate)
        LightyearValuation.euroMinor(withheld, currency, eurRate)
        LightyearValuation.euroMinor(fee, currency, eurRate)
    }
    public val netMinor: Long get() = LightyearValuation.euroMinor(
        gross - withheld - fee,
        currency,
        eurRate,
    )
}
