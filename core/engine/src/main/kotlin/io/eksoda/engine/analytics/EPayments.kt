package io.eksoda.engine.analytics

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// The Greek e-payment requirement (ΚΦΕ ν. 4172/2013, article 15 §6, checked against the codified
// text in November 2026): 30% of the real income from salary, pension and business has to be
// spent electronically, at most 20.000 € of spending; the shortfall is taxed at 22%.
//
// Not modelled, so the target is an estimate: it is per person (each spouse has their own, §6ε);
// it drops to 20% when tax, ENFIA, loan payments and rent exceed 60% of the income (§6στ);
// some taxpayers are exempt (70 and over, disability of 80% or more, and others, §6γ); and some
// health spending counts double in 2022-2027 (§6δ). Eksoda sees only the receipts it imported,
// so its total is a lower bound of what the tax office counts.

/** The share of the annual income that has to be paid electronically. */
public val E_PAYMENT_SHARE: BigDecimal = BigDecimal("0.30")

/** The most that has to be paid electronically in a year, whatever the income. */
public val E_PAYMENT_CAP: BigDecimal = BigDecimal("20000")

/** Stored payment method codes that count as electronic payments. */
public val ELECTRONIC_METHODS: Set<String> = setOf("card", "bank")

private const val MONEY_SCALE = 2
private const val MONTHS_PER_YEAR = 12
private val HUNDRED = BigDecimal("100")

/** The year's target: [E_PAYMENT_SHARE] of [income], at most [E_PAYMENT_CAP], never negative. */
public fun ePaymentTarget(income: BigDecimal): BigDecimal =
    (income * E_PAYMENT_SHARE).min(E_PAYMENT_CAP).max(BigDecimal.ZERO)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP)

/** One payment of a receipt paid in several ways: its stored method code and amount. */
public data class PaymentPart(val method: String?, val amount: BigDecimal)

/** The electronic part of a receipt paid in several ways: its card and bank payments. */
public fun electronicPart(payments: List<PaymentPart>): BigDecimal =
    payments.filter { it.method in ELECTRONIC_METHODS }
        .fold(BigDecimal.ZERO) { sum, part -> sum + part.amount }

/**
 * The electronic part of one receipt paid in several ways, read from its stored canonical JSON
 * (the `payments` array of `CanonicalJson`). A [creditNote] gives money back, so its part is
 * negative. Data that cannot be read counts 0, and a payment whose amount is not a number is left
 * out: one bad receipt never hides the year's total.
 */
public fun electronicPartOf(canonicalJson: String, creditNote: Boolean): BigDecimal {
    val part = electronicPart(paymentsOf(canonicalJson))
    return if (creditNote) part.negate() else part
}

private fun paymentsOf(canonicalJson: String): List<PaymentPart> {
    val root = try {
        Json.parseToJsonElement(canonicalJson)
    } catch (_: SerializationException) {
        return emptyList()
    }
    val payments = (root as? JsonObject)?.get("payments") as? JsonArray ?: return emptyList()
    return payments.mapNotNull { element ->
        val payment = element as? JsonObject ?: return@mapNotNull null
        val amount = payment.text("amount")?.toBigDecimalOrNull() ?: return@mapNotNull null
        PaymentPart(payment.text("method"), amount)
    }
}

/** A string or number field's text; null when it is missing, null, or not a primitive. */
private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/** How the year is going against the target ([ePaymentPace]). */
public data class EPaymentPace(
    /** What is still to be paid electronically; 0 once the target is reached. */
    val remaining: BigDecimal,
    /** [remaining] spread over the months left, this one included. */
    val perMonth: BigDecimal,
    /** The year's total if the pace so far continues to December 31. */
    val projected: BigDecimal,
    /** The amount paid in percent of the target (2 places), or null for a target of 0. */
    val paidPercent: BigDecimal?,
)

/** The year's [target] against what was [paid] electronically up to [today], that day included. */
public fun ePaymentPace(target: BigDecimal, paid: BigDecimal, today: LocalDate): EPaymentPace {
    val remaining = (target - paid).max(BigDecimal.ZERO)
    val monthsLeft = MONTHS_PER_YEAR - today.monthValue + 1
    val projected = (paid * BigDecimal(today.lengthOfYear()))
        .divide(BigDecimal(today.dayOfYear), MONEY_SCALE, RoundingMode.HALF_UP)
    return EPaymentPace(
        remaining = remaining.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        perMonth = remaining.divide(BigDecimal(monthsLeft), MONEY_SCALE, RoundingMode.HALF_UP),
        projected = projected,
        paidPercent = if (target.signum() == 0) {
            null
        } else {
            (paid * HUNDRED).divide(target, MONEY_SCALE, RoundingMode.HALF_UP)
        },
    )
}
