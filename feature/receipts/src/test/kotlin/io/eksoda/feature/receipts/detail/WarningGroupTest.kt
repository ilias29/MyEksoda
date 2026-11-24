package io.eksoda.feature.receipts.detail

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.eksoda.core.model.ReceiptWarning
import io.eksoda.feature.receipts.R
import org.junit.Test

class WarningGroupTest {
    @Test
    fun `warnings that read the same are one entry naming their lines in receipt order`() {
        val groups = groupWarnings(
            listOf(
                ReceiptWarning("unit_price_mismatch", 3),
                ReceiptWarning("unit_price_mismatch", 1),
                ReceiptWarning("total_mismatch", null),
                ReceiptWarning("unit_price_mismatch", 1),
                ReceiptWarning("unit_price_mismatch", 2),
            ),
        )
        assertThat(groups).containsExactly(
            WarningGroup(R.string.warning_unit_price, listOf(1, 2, 3), amountCheck = true),
            WarningGroup(R.string.warning_total, emptyList(), amountCheck = true),
        )
    }

    @Test
    fun `notices are not amount checks, and each reads on its own`() {
        val groups = groupWarnings(
            listOf(
                ReceiptWarning("signature_mismatch", null),
                ReceiptWarning("mark_missing", null),
                ReceiptWarning("payment_mismatch", null),
                ReceiptWarning("something_new", null),
            ),
        )
        assertThat(groups.map { it.message to it.amountCheck }).containsExactly(
            R.string.warning_signature_mismatch to false,
            R.string.warning_mark_missing to false,
            R.string.warning_payment to true,
            // A code this version does not know needs attention rather than being played down.
            R.string.warning_other to false,
        )
    }

    @Test
    fun `codes with the same message share an entry`() {
        val groups = groupWarnings(
            listOf(
                ReceiptWarning("total_mismatch", null),
                ReceiptWarning("receipt_amount_mismatch", null),
                ReceiptWarning("vat_breakdown_mismatch", null),
                ReceiptWarning("vat_breakdown_rate_mismatch", null),
            ),
        )
        assertThat(groups.map { it.message })
            .containsExactly(R.string.warning_total, R.string.warning_vat_breakdown)
    }

    @Test
    fun `every validation code has its message, and unknown ones a general one`() {
        val messages = mapOf(
            "line_amount_mismatch" to R.string.warning_line_amount,
            "unit_price_mismatch" to R.string.warning_unit_price,
            "discount_mismatch" to R.string.warning_discount,
            "discount_unreadable" to R.string.warning_discount_unreadable,
            "vat_rate_mismatch" to R.string.warning_vat_rate,
            "total_mismatch" to R.string.warning_total,
            "receipt_amount_mismatch" to R.string.warning_total,
            "total_discount_mismatch" to R.string.warning_total_discount,
            "line_discounts_mismatch" to R.string.warning_total_discount,
            "vat_breakdown_mismatch" to R.string.warning_vat_breakdown,
            "vat_breakdown_rate_mismatch" to R.string.warning_vat_breakdown,
            "payment_mismatch" to R.string.warning_payment,
            "signature_mismatch" to R.string.warning_signature_mismatch,
            "amount_mismatch" to R.string.warning_qr_amount,
            "mark_missing" to R.string.warning_mark_missing,
            "non_eur_currency" to R.string.warning_currency,
            "unknown_document_type" to R.string.warning_document_type,
            "something_new" to R.string.warning_other,
        )
        messages.forEach { (code, message) -> assertThat(warningRes(code)).isEqualTo(message) }
    }

    @Test
    fun `no warnings, no entries`() {
        assertThat(groupWarnings(emptyList())).isEmpty()
    }
}
