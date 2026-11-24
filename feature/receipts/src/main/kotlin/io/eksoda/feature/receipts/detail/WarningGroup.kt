package io.eksoda.feature.receipts.detail

import androidx.annotation.StringRes
import io.eksoda.core.model.ReceiptWarning
import io.eksoda.feature.receipts.R

/**
 * Warnings that read the same, and the receipt lines they concern (in receipt order).
 * [amountCheck]: a check of the receipt's own arithmetic, the amounts kept as printed; otherwise
 * a notice that needs the user's attention (e.g. the QR signature differs from AADE's record).
 */
internal data class WarningGroup(
    @param:StringRes val message: Int,
    val lines: List<Int>,
    val amountCheck: Boolean,
)

/**
 * One group per message, in the order the warnings first give it: three items whose unit price
 * does not fit read as one message naming the three items, not as three identical lines.
 */
internal fun groupWarnings(warnings: List<ReceiptWarning>): List<WarningGroup> =
    warnings.groupBy { warningRes(it.code) }.map { (message, group) ->
        WarningGroup(
            message = message,
            lines = group.mapNotNull { it.lineNumber }.distinct().sorted(),
            amountCheck = group.all { it.code in AMOUNT_CHECKS },
        )
    }

/** The engine's arithmetic checks (`validation`): amounts that do not agree with each other. */
private val AMOUNT_CHECKS = setOf(
    "line_amount_mismatch",
    "unit_price_mismatch",
    "discount_mismatch",
    "discount_unreadable",
    "vat_rate_mismatch",
    "total_mismatch",
    "receipt_amount_mismatch",
    "total_discount_mismatch",
    "line_discounts_mismatch",
    "vat_breakdown_mismatch",
    "vat_breakdown_rate_mismatch",
    "payment_mismatch",
)

/** Validation issue and provider notice codes (the engine's `validation`), as local strings. */
@StringRes
internal fun warningRes(code: String): Int = when (code) {
    "line_amount_mismatch" -> R.string.warning_line_amount
    "unit_price_mismatch" -> R.string.warning_unit_price
    "discount_mismatch" -> R.string.warning_discount
    "discount_unreadable" -> R.string.warning_discount_unreadable
    "vat_rate_mismatch" -> R.string.warning_vat_rate
    "total_mismatch", "receipt_amount_mismatch" -> R.string.warning_total
    "total_discount_mismatch", "line_discounts_mismatch" -> R.string.warning_total_discount
    "vat_breakdown_mismatch", "vat_breakdown_rate_mismatch" -> R.string.warning_vat_breakdown
    "payment_mismatch" -> R.string.warning_payment
    "signature_mismatch" -> R.string.warning_signature_mismatch
    "amount_mismatch" -> R.string.warning_qr_amount
    "mark_missing" -> R.string.warning_mark_missing
    "non_eur_currency" -> R.string.warning_currency
    "unknown_document_type" -> R.string.warning_document_type
    else -> R.string.warning_other
}
