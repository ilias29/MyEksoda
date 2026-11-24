package io.eksoda.engine.validation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.eksoda.engine.canonical.Line
import io.eksoda.engine.canonical.PriceBasis
import org.junit.jupiter.api.Test

/** How a parser tells unit prices before VAT from prices with VAT ([inferPriceBasis]). */
class PriceBasisInferenceTest {
    @Test
    fun `prices that fit only before VAT are net`() {
        assertThat(inferPriceBasis(MASOUTIS)).isEqualTo(PriceBasis.NET)
    }

    @Test
    fun `a receipt priced before VAT, with weighed, multi-buy and misprinted lines, is net`() {
        val lines = listOf(
            line(1, "1", "3.90", "13", "4.40"),
            // 0.812 kg × 1.76 × 1.13 = 1.6149 for 1.62
            line(2, "0.812", "1.76", "13", "1.62"),
            // 3 × 2.14 × 1.13 = 7.2546 for 7.26
            line(3, "3", "2.14", "13", "7.26"),
            line(4, "1", "0.09", "24", "0.11"),
            // Fits neither way: reported on its own, not with every other line.
            line(5, "1", "5.00", "13", "4.40"),
        )
        assertThat(inferPriceBasis(lines)).isEqualTo(PriceBasis.NET)
        // 5.00 × 1.13 = 5.65
        assertThat(unitPriceIssues(lines, PriceBasis.NET))
            .containsExactly(listOf("unit_price_mismatch", 5, "5.65", "4.40"))
    }

    @Test
    fun `a receipt priced with VAT, with one line that happens to fit before VAT, is gross`() {
        val lines = listOf(
            line(1, "1", "4.40", "13", "4.40"),
            // 0.812 kg × 1.99 = 1.6159 for 1.62
            line(2, "0.812", "1.99", "13", "1.62"),
            line(3, "2", "1.29", "24", "2.58"),
            // 0.09 × 1.24 = 0.1116: fits only before VAT, so it is the one reported.
            line(4, "1", "0.09", "24", "0.11"),
        )
        assertThat(inferPriceBasis(lines)).isEqualTo(PriceBasis.GROSS)
        assertThat(unitPriceIssues(lines, PriceBasis.GROSS))
            .containsExactly(listOf("unit_price_mismatch", 4, "0.09", "0.11"))
    }

    @Test
    fun `as many lines fitting each way is gross`() {
        val lines = listOf(
            line(1, "1", "4.40", "13", "4.40"),
            line(2, "1", "3.90", "13", "4.40"),
            line(3, "1", "5.00", "13", "4.40"),
        )
        assertThat(inferPriceBasis(lines)).isEqualTo(PriceBasis.GROSS)
        // One line fits only with VAT, another only before it.
        val mixed = listOf(MASOUTIS[0].copy(unitPrice = d("4.40")), MASOUTIS[1])
        assertThat(inferPriceBasis(mixed)).isEqualTo(PriceBasis.GROSS)
    }

    @Test
    fun `at 0 percent VAT a price fits both ways, so it decides nothing`() {
        val zeroRate = listOf(
            line(1, "1", "2.00", "0", "2.00"),
            line(2, "2", "1.50", "0", "3.00"),
        )
        // Alone, the tie stays with VAT, and nothing is reported either way.
        assertThat(inferPriceBasis(zeroRate)).isEqualTo(PriceBasis.GROSS)
        assertThat(unitPriceIssues(zeroRate, PriceBasis.GROSS)).isEmpty()
        assertThat(unitPriceIssues(zeroRate, PriceBasis.NET)).isEmpty()
        // With lines priced before VAT, the other lines decide.
        val withNetLine = zeroRate + line(3, "1", "3.90", "13", "4.40")
        assertThat(inferPriceBasis(withNetLine)).isEqualTo(PriceBasis.NET)
    }

    @Test
    fun `without anything to compare, prices are gross`() {
        // The line that misses has no VAT rate to try.
        assertThat(inferPriceBasis(listOf(MASOUTIS[0].copy(vatRate = null))))
            .isEqualTo(PriceBasis.GROSS)
        // No unit prices.
        assertThat(inferPriceBasis(MASOUTIS.map { it.copy(unitPrice = null) }))
            .isEqualTo(PriceBasis.GROSS)
        assertThat(inferPriceBasis(emptyList())).isEqualTo(PriceBasis.GROSS)
    }

    /** The R5 issues of [lines] read on [basis]. */
    private fun unitPriceIssues(lines: List<Line>, basis: PriceBasis) =
        found(CLEAN.copy(lines = lines.map { it.copy(unitPriceBasis = basis) }))
            .filter { it.first() == "unit_price_mismatch" }

    private companion object {
        /** Line [number]: [quantity] × [price] for [gross] at [rate]% VAT. */
        fun line(number: Int, quantity: String, price: String, rate: String, gross: String): Line {
            val description = "Item $number"
            return Line(
                lineNumber = number,
                description = description,
                gross = d(gross),
                quantity = d(quantity),
                unitPrice = d(price),
                unitPriceBasis = PriceBasis.GROSS,
                vatRate = d(rate),
            )
        }

        /** Masoutis (Entersoft) prints unit prices before VAT. */
        val MASOUTIS = listOf(
            line(1, "1", "3.90", "13", "4.40"),
            line(2, "1", "2.14", "13", "2.42"),
            line(3, "1", "0.09", "24", "0.11"),
        )
    }
}
