package io.eksoda.engine.validation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import io.eksoda.engine.canonical.Line
import io.eksoda.engine.canonical.PriceBasis
import org.junit.jupiter.api.Test

/** Unit prices before VAT (R5). How a parser tells them apart: [PriceBasisInferenceTest]. */
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
        // 2.50 × 1.13 = 2.825
        val wrong = netPriced("2.50") {
            it.copy(grossBeforeDiscount = d("2.53"), discount = d("0.50"))
        }
        assertThat(found(wrong))
            .containsExactly(listOf("unit_price_mismatch", 1, "2.83", "2.53"))
    }

    @Test
    fun `a discounted line with its net is checked against the gross before the discount`() {
        // The line keeps its net 1.80 and VAT 0.23; 2.53 − 0.50 = 2.03.
        fun discounted(price: String) = withFirstLine {
            it.copy(
                quantity = d("1"),
                unitPrice = d(price),
                unitPriceBasis = PriceBasis.NET,
                grossBeforeDiscount = d("2.53"),
                discount = d("0.50"),
            )
        }
        assertThat(found(discounted("2.24"))).isEmpty()
        assertThat(found(discounted("2.50")))
            .containsExactly(listOf("unit_price_mismatch", 1, "2.83", "2.53"))
    }

    @Test
    fun `a printed zero discount compares with the printed value before it, as Masoutis prints`() {
        fun zeroDiscount(price: String) = netPriced(price) {
            it.copy(grossBeforeDiscount = d("2.03"), discount = d("0.00"))
        }
        assertThat(found(zeroDiscount("1.80"))).isEmpty()
        assertThat(found(zeroDiscount("2.00")))
            .containsExactly(listOf("unit_price_mismatch", 1, "2.26", "2.03"))
    }

    @Test
    fun `a discounted line without the value before the discount is not checked, net or not`() {
        val withNet = withFirstLine {
            it.copy(
                quantity = d("1"),
                unitPrice = d("9.99"),
                unitPriceBasis = PriceBasis.NET,
                discount = d("0.17"),
            )
        }
        assertThat(codes(withNet)).doesNotContain("unit_price_mismatch")
        val withoutNet = netPriced("9.99") { it.copy(discount = d("0.17")) }
        assertThat(codes(withoutNet)).doesNotContain("unit_price_mismatch")
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
}
