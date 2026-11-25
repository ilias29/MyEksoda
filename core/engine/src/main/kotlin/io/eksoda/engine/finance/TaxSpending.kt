package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode

enum class TaxTargetMode(val percent: Int) {
    STANDARD(30), REDUCED(20), EXEMPT(0),
}

enum class TaxReview { PENDING, EXCLUDED, ELIGIBLE, DOUBLE_HEALTH }

data class AnnualTaxPlan(
    val year: Int,
    val incomeMinor: Long,
    val mode: TaxTargetMode,
    val specialCap: Boolean,
) {
    init {
        require(year in 2022..2027)
        require(incomeMinor >= 0)
    }
}

data class TaxPayment(val movement: BankMovement, val review: TaxReview)

data class TaxSpendingSummary(
    val targetMinor: BigDecimal,
    val actualMinor: BigDecimal,
    val weightedMinor: BigDecimal,
    val remainingMinor: BigDecimal,
    val potentialExtraTaxMinor: BigDecimal,
    val pendingCount: Int,
)

object TaxSpending {
    fun allowed(movement: BankMovement): Boolean =
        (movement.kind == MovementKind.EXPENSE && movement.amountMinor < 0 &&
            movement.category != SpendingCategory.RENT && movement.category != SpendingCategory.LOAN_PAYMENT) ||
            (movement.kind == MovementKind.REFUND && movement.amountMinor > 0)

    fun summarize(plan: AnnualTaxPlan, payments: Iterable<TaxPayment>): TaxSpendingSummary {
        val cap = BigDecimal(if (plan.specialCap) 500_000 else 2_000_000)
        val target = BigDecimal.valueOf(plan.incomeMinor).multiply(BigDecimal(plan.mode.percent))
            .movePointLeft(2).setScale(0, RoundingMode.HALF_UP).min(cap)
        var actual = BigDecimal.ZERO
        var weighted = BigDecimal.ZERO
        var pending = 0
        val seen = mutableSetOf<Pair<String, String>>()
        for ((movement, review) in payments) {
            if (movement.date.year != plan.year || !allowed(movement)) continue
            val reference = requireNotNull(movement.sourceId) { "Bank reference required" }
            require(reference.isNotBlank() && seen.add(movement.accountId to reference)) { "Duplicate bank movement" }
            when (review) {
                TaxReview.PENDING -> pending++
                TaxReview.EXCLUDED -> Unit
                TaxReview.ELIGIBLE, TaxReview.DOUBLE_HEALTH -> {
                    val amount = BigDecimal.valueOf(movement.amountMinor).negate()
                    actual += amount
                    weighted += amount.multiply(BigDecimal(if (review == TaxReview.DOUBLE_HEALTH) 2 else 1))
                }
            }
        }
        val remaining = (target - weighted.max(BigDecimal.ZERO)).max(BigDecimal.ZERO)
        return TaxSpendingSummary(
            target, actual, weighted, remaining,
            remaining.multiply(BigDecimal("0.22")).setScale(0, RoundingMode.HALF_UP), pending,
        )
    }
}
