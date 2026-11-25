package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

data class ReceiptTextItem(
    val name: String,
    val lineMinor: Long,
    val quantity: BigDecimal? = null,
    val unitPriceMinor: Long? = null,
)

data class ReceiptTextPreview(
    val items: List<ReceiptTextItem>,
    val discountsMinor: List<Long>,
    val vatMinor: Long?,
    val totalMinor: Long?,
    val calculatedMinor: Long,
    val warnings: List<String>,
) {
    val reconciles: Boolean
        get() = totalMinor != null && totalMinor == calculatedMinor && warnings.isEmpty() && items.isNotEmpty()
}

/** Conservative parser for OCR text. It never creates a transaction or invents a discount. */
object ReceiptTextParser {
    private val money = Regex("""(?<![\d.,])(-?\d+[,.]\d{2})\s*(?:€|EUR)?\s*$""", RegexOption.IGNORE_CASE)
    private val quantity = Regex("""^(.+?)\s+(\d+(?:[,.]\d+)?)\s*[xX×*]\s*(\d+[,.]\d{2})\s*$""")
    private val total = Regex("""^(?:ΓΕΝΙΚΟ ΣΥΝΟΛΟ|ΤΕΛΙΚΟ ΣΥΝΟΛΟ|ΣΥΝΟΛΟ|ΠΛΗΡΩΤΕΟ|TOTAL|AMOUNT DUE)$""")
    private val discount = Regex("""^(?:ΕΚΠΤΩΣΗ|ΕΚΠΤΩΣΕΙΣ|ΚΟΥΠΟΝΙ|DISCOUNT|COUPON)(?:\s.*)?$""")
    private val vat = Regex("""^(?:ΦΠΑ|Φ\.Π\.Α\.|VAT)(?:\s.*)?$""")
    private val subtotal = Regex("""^(?:ΥΠΟΣΥΝΟΛΟ|SUBTOTAL|ΣΥΝΟΛΟ ΠΡΙΝ ΕΚΠΤΩΣΕΙΣ)$""")
    private val payment = Regex("""^(?:ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΡΕΣΤΑ|CASH|CARD|CHANGE|VISA|MASTERCARD)(?:\s.*)?$""")

    fun parse(text: String): ReceiptTextPreview {
        val items = mutableListOf<ReceiptTextItem>()
        val discounts = mutableListOf<Long>()
        val totals = mutableListOf<Long>()
        val vats = mutableListOf<Long>()
        val subtotals = mutableListOf<Long>()
        val warnings = mutableListOf<String>()
        var calculated = 0L
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isBlank()) continue
            val match = money.find(line) ?: continue
            val label = line.substring(0, match.range.first).trim().trimEnd(':').trim()
            val amount = runCatching {
                BigDecimal(match.groupValues[1].replace(',', '.')).movePointRight(2).longValueExact()
            }.getOrNull()
            if (amount == null || label.isBlank() || !label.any { it.isLetter() }) {
                warnings += line
                continue
            }
            val normalized = normalize(label)
            try {
                when {
                    total.matches(normalized) -> if (amount >= 0) totals += amount else warnings += line
                    subtotal.matches(normalized) -> if (amount >= 0) subtotals += amount else warnings += line
                    vat.matches(normalized) -> if (amount >= 0) vats += amount else warnings += line
                    payment.matches(normalized) -> Unit
                    discount.matches(normalized) -> {
                        val value = if (amount < 0) Math.negateExact(amount) else amount
                        calculated = Math.subtractExact(calculated, value)
                        discounts += value
                    }
                    amount <= 0 || totals.isNotEmpty() -> warnings += line
                    else -> {
                        val parts = quantity.matchEntire(label)
                        val item = if (parts == null) {
                            ReceiptTextItem(label, amount)
                        } else {
                            val count = BigDecimal(parts.groupValues[2].replace(',', '.'))
                            val unit = BigDecimal(parts.groupValues[3].replace(',', '.')).movePointRight(2).longValueExact()
                            val expected = count.multiply(BigDecimal.valueOf(unit)).setScale(0, RoundingMode.HALF_UP)
                            if (count.signum() <= 0 || unit <= 0 || expected != BigDecimal.valueOf(amount)) warnings += line
                            ReceiptTextItem(parts.groupValues[1], amount, count, unit)
                        }
                        calculated = Math.addExact(calculated, amount)
                        items += item
                    }
                }
            } catch (_: ArithmeticException) {
                warnings += line
            }
        }
        val declaredTotal = totals.distinct().singleOrNull()
        if (totals.distinct().size > 1) warnings += "AMBIGUOUS_TOTAL"
        // VAT is usually included in the payable amount, never add it again.
        val declaredVat = vats.singleOrNull()
        if (vats.size > 1) warnings += "AMBIGUOUS_VAT"
        if (declaredVat != null && declaredTotal != null && declaredVat > declaredTotal) warnings += "VAT_EXCEEDS_TOTAL"
        if (subtotals.isNotEmpty()) {
            val gross = items.fold(BigDecimal.ZERO) { sum, item -> sum + BigDecimal.valueOf(item.lineMinor) }
            if (subtotals.any { BigDecimal.valueOf(it) != gross }) warnings += "SUBTOTAL_MISMATCH"
        }
        return ReceiptTextPreview(items, discounts, declaredVat, declaredTotal, calculated, warnings)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").uppercase(Locale.ROOT)
}
