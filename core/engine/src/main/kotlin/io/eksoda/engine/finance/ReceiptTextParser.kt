package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

public data class ReceiptTextItem(
    val name: String,
    val lineMinor: Long,
    val quantity: BigDecimal? = null,
    val unitPriceMinor: Long? = null,
)

public data class ReceiptTextPreview(
    val items: List<ReceiptTextItem>,
    val discountsMinor: List<Long>,
    val vatMinor: Long?,
    val totalMinor: Long?,
    val calculatedMinor: Long,
    val warnings: List<String>,
) {
    public val reconciles: Boolean
        get() = totalMinor != null && totalMinor == calculatedMinor && warnings.isEmpty() &&
            items.isNotEmpty()
}

/** Conservative parser for OCR text. It never creates a transaction or invents a discount. */
public object ReceiptTextParser {
    private val money =
        Regex("""(?<![\d.,])(-?\d+[,.]\d{2})\s*(?:€|EUR)?\s*$""", RegexOption.IGNORE_CASE)
    private val quantity = Regex("""^(.+?)\s+(\d+(?:[,.]\d+)?)\s*[xX×*]\s*(\d+[,.]\d{2})\s*$""")
    private val total =
        Regex("""^(?:ΓΕΝΙΚΟ ΣΥΝΟΛΟ|ΤΕΛΙΚΟ ΣΥΝΟΛΟ|ΣΥΝΟΛΟ|ΠΛΗΡΩΤΕΟ|TOTAL|AMOUNT DUE)$""")
    private val discount = Regex("""^(?:ΕΚΠΤΩΣΗ|ΕΚΠΤΩΣΕΙΣ|ΚΟΥΠΟΝΙ|DISCOUNT|COUPON)(?:\s.*)?$""")
    private val vat = Regex("""^(?:ΦΠΑ|Φ\.Π\.Α\.|VAT)(?:\s.*)?$""")
    private val subtotal = Regex("""^(?:ΥΠΟΣΥΝΟΛΟ|SUBTOTAL|ΣΥΝΟΛΟ ΠΡΙΝ ΕΚΠΤΩΣΕΙΣ)$""")
    private val payment =
        Regex("""^(?:ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΡΕΣΤΑ|CASH|CARD|CHANGE|VISA|MASTERCARD)(?:\s.*)?$""")

    private data class State(
        val items: MutableList<ReceiptTextItem> = mutableListOf(),
        val discounts: MutableList<Long> = mutableListOf(),
        val totals: MutableList<Long> = mutableListOf(),
        val vats: MutableList<Long> = mutableListOf(),
        val subtotals: MutableList<Long> = mutableListOf(),
        val warnings: MutableList<String> = mutableListOf(),
        var calculated: Long = 0L,
    )

    public fun parse(text: String): ReceiptTextPreview {
        val state = State()
        text.lineSequence().forEach { processLine(it.trim(), state) }
        val declaredTotal = state.totals.distinct().singleOrNull()
        if (state.totals.distinct().size > 1) state.warnings += "AMBIGUOUS_TOTAL"
        // VAT is usually included in the payable amount, never add it again.
        val declaredVat = state.vats.singleOrNull()
        if (state.vats.size > 1) state.warnings += "AMBIGUOUS_VAT"
        if (declaredVat != null && declaredTotal != null &&
            declaredVat > declaredTotal
        ) {
            state.warnings += "VAT_EXCEEDS_TOTAL"
        }
        if (state.subtotals.isNotEmpty()) {
            val gross = state.items.fold(BigDecimal.ZERO) { sum, item ->
                sum +
                    BigDecimal.valueOf(item.lineMinor)
            }
            if (state.subtotals.any { BigDecimal.valueOf(it) != gross }) {
                state.warnings += "SUBTOTAL_MISMATCH"
            }
        }
        return ReceiptTextPreview(
            state.items,
            state.discounts,
            declaredVat,
            declaredTotal,
            state.calculated,
            state.warnings,
        )
    }

    private fun processLine(line: String, state: State) {
        if (line.isBlank()) return
        val match = money.find(line) ?: return
        val label = line.substring(0, match.range.first).trim().trimEnd(':').trim()
        val amount = parseAmount(match.groupValues[1])
        if (amount == null || label.isBlank() || !label.any { it.isLetter() }) {
            state.warnings += line
            return
        }
        try {
            classifyLine(line, label, normalize(label), amount, state)
        } catch (_: ArithmeticException) {
            state.warnings += line
        }
    }

    private fun parseAmount(value: String): Long? = runCatching {
        BigDecimal(value.replace(',', '.')).movePointRight(2).longValueExact()
    }.getOrNull()

    private fun classifyLine(
        line: String,
        label: String,
        normalized: String,
        amount: Long,
        state: State,
    ) {
        when {
            total.matches(normalized) -> addNonNegative(amount, state.totals, line, state)
            subtotal.matches(normalized) -> addNonNegative(amount, state.subtotals, line, state)
            vat.matches(normalized) -> addNonNegative(amount, state.vats, line, state)
            payment.matches(normalized) -> Unit
            discount.matches(normalized) -> addDiscount(amount, state)
            amount <= 0 || state.totals.isNotEmpty() -> state.warnings += line
            else -> addItem(line, label, amount, state)
        }
    }

    private fun addNonNegative(
        amount: Long,
        target: MutableList<Long>,
        line: String,
        state: State,
    ) {
        if (amount >= 0) target += amount else state.warnings += line
    }

    private fun addDiscount(amount: Long, state: State) {
        val value = if (amount < 0) Math.negateExact(amount) else amount
        state.calculated = Math.subtractExact(state.calculated, value)
        state.discounts += value
    }

    private fun addItem(line: String, label: String, amount: Long, state: State) {
        val parts = quantity.matchEntire(label)
        val item = if (parts ==
            null
        ) {
            ReceiptTextItem(label, amount)
        } else {
            quantityItem(parts, amount, line, state)
        }
        state.calculated = Math.addExact(state.calculated, amount)
        state.items += item
    }

    private fun quantityItem(
        parts: MatchResult,
        amount: Long,
        line: String,
        state: State,
    ): ReceiptTextItem {
        val count = BigDecimal(parts.groupValues[2].replace(',', '.'))
        val unit = BigDecimal(
            parts.groupValues[3].replace(',', '.'),
        ).movePointRight(2).longValueExact()
        val expected = count.multiply(BigDecimal.valueOf(unit)).setScale(0, RoundingMode.HALF_UP)
        if (count.signum() <= 0 || unit <= 0 ||
            expected != BigDecimal.valueOf(amount)
        ) {
            state.warnings += line
        }
        return ReceiptTextItem(parts.groupValues[1], amount, count, unit)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").uppercase(Locale.ROOT)
}
