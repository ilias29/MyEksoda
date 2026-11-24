package io.eksoda.engine.validation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.eksoda.engine.canonical.Line
import io.eksoda.engine.canonical.PriceBasis
import org.junit.jupiter.api.Test

/** Unit prices before VAT (R5) and how a parser tells them from prices with VAT. */
class UnitPriceBasisTest {
    /** The first line (net 1.80, VAT 0.23, gross 2.03 at 13%) without its net and VAT. */
    private fun netPriced(price: String, change: (Line) -> Line = { it }) = withFirstLine {
        change(
            it.copy(
                net = null,
                vat = null,
                quantity = d("1"),
                unitPrice = d(price),
                unitPriceBasis = PriceBasis.NET,
            ),
        )
    }

    @Test
    fun `a price before VAT is compared with the gross once its VAT is added`() {
        // 1.80 × 1.13 = 2.034
        assertThat(found(netPriced("1.80"))).isEmpty()
        // 2.00 × 1.13 = 2.26
        assertThat(found(netPriced("2.00")))
            .containsExactly(listOf("unit_price_mismatch", 1, "2.26", "2.03"))
    }

    @Test
    fun `a discounted price before VAT is compared with the gross before the discount`() {
        // 2.24 × 1.13 = 2.5312; 2.53 − 0.50 = 2.03
        val discounted = netPriced("2.24") {
            it.copy(grossBeforeDiscount = d("2.53"), discount = d("0.50"))
        }
        assertThat(found(discounted)).isEmpty()
        // Discounted, and the value before the discount is not printed: nothing to compare.
        assertThat(codes(netPriced("9.99") { it.copy(discount = d("0.50")) }))
            .doesNotContain("unit_price_mismatch")
    }

    @Test
    fun `each unit may carry half a cent of a price before VAT rounded to the cent`() {
        // 1.99/kg with VAT is printed as 1.76 before VAT; 10 × 1.76 × 1.13 = 19.888 for 19.90.
        val weighed = netPriced("1.76") { it.copy(quantity = d("10"), gross = d("19.90")) }
        assertThat(codes(weighed)).doesNotContain("unit_price_mismatch")
        // A cent per unit is more than rounding: 10 × 1.75 × 1.13 = 19.775.
        val wrong = netPriced("1.75") { it.copy(quantity = d("10"), gross = d("19.90")) }
        assertThat(found(wrong).filter { it.first() == "unit_price_mismatch" })
            .containsExactly(listOf("unit_price_mismatch", 1, "19.78", "19.90"))
    }

    @Test
    fun `a price before VAT without the VAT rate is not checked`() {
        assertThat(found(netPriced("9.99") { it.copy(vatRate = null) })).isEmpty()
    }

    @Test
    fun `prices that fit only before VAT are net`() {
        assertThat(inferPriceBasis(MASOUTIS)).isEqualTo(PriceBasis.NET)
    }

    @Test
    fun `one line that fits neither way is reported alone, not with every other line`() {
        val oneWrong = MASOUTIS.mapIndexed { i, line ->
            if (i == 0) line.copy(unitPrice = d("5.00")) else line
        }
        val basis = inferPriceBasis(oneWrong)
        assertThat(basis).isEqualTo(PriceBasis.NET)
        val lines = oneWrong.map { it.copy(unitPriceBasis = basis) }
        // 5.00 × 1.13 = 5.65
        assertThat(found(CLEAN.copy(lines = lines)).filter { it.first() == "unit_price_mismatch" })
            .containsExactly(listOf("unit_price_mismatch", 1, "5.65", "4.40"))
    }

    @Test
    fun `prices that fit with VAT, as many lines both ways, or none, are gross`() {
        // Printed with VAT.
        val withVat = MASOUTIS.map { it.copy(unitPrice = it.gross) }
        assertThat(inferPriceBasis(withVat)).isEqualTo(PriceBasis.GROSS)
        // Most lines fit with VAT.
        val mostlyWithVat = withVat.mapIndexed { i, line -> if (i == 0) MASOUTIS[0] else line }
        assertThat(inferPriceBasis(mostlyWithVat)).isEqualTo(PriceBasis.GROSS)
        // One line fits only with VAT, another only before it.
        val mixed = listOf(MASOUTIS[0].copy(unitPrice = d("4.40")), MASOUTIS[1])
        assertThat(inferPriceBasis(mixed)).isEqualTo(PriceBasis.GROSS)
        // At 0% VAT a price fits both ways.
        val zeroRate = listOf(MASOUTIS[0].copy(unitPrice = d("4.40"), vatRate = d("0")))
        assertThat(inferPriceBasis(zeroRate)).isEqualTo(PriceBasis.GROSS)
        // The line that misses has no VAT rate to try.
        assertThat(inferPriceBasis(listOf(MASOUTIS[0].copy(vatRate = null))))
            .isEqualTo(PriceBasis.GROSS)
        // No unit prices.
        assertThat(inferPriceBasis(MASOUTIS.map { it.copy(unitPrice = null) }))
            .isEqualTo(PriceBasis.GROSS)
    }

    private companion object {
        /** Masoutis (Entersoft) prints unit prices before VAT. */
        val MASOUTIS = listOf(
            Triple("3.90", "13", "4.40"),
            Triple("2.14", "13", "2.42"),
            Triple("0.09", "24", "0.11"),
        ).mapIndexed { index, (price, rate, gross) ->
            Line(
                lineNumber = index + 1,
                description = "Item ${index + 1}",
                gross = d(gross),
                quantity = d("1"),
                unitPrice = d(price),
                unitPriceBasis = PriceBasis.GROSS,
                vatRate = d(rate),
            )
        }
    }
}
