package io.eksoda.engine.analytics

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CategoryDetailTest {
    private fun total(key: String, name: String, amount: String) =
        KeyedTotal(key, name, BigDecimal(amount))

    @Test
    fun `a shop's lines and remainder add up, largest first, then by name`() {
        val shops = categoryShopTotals(
            lines = listOf(
                total("a", "Alpha", "10.00"),
                total("b", "Beta", "25.50"),
                total("c", "Gamma", "4.50"),
            ),
            remainders = listOf(total("a", "Alpha", "20.00"), total("d", "Delta", "4.50")),
        )
        assertThat(shops).containsExactly(
            total("a", "Alpha", "30.00"),
            total("b", "Beta", "25.50"),
            total("d", "Delta", "4.50"),
            total("c", "Gamma", "4.50"),
        )
    }

    @Test
    fun `a shop that comes to nothing is left out`() {
        // Bought and returned: the credit note's lines cancel the sale's.
        val shops = categoryShopTotals(
            lines = listOf(total("a", "Alpha", "12.00")),
            remainders = listOf(total("a", "Alpha", "-12.00")),
        )
        assertThat(shops).isEmpty()
        assertThat(categoryShopTotals(emptyList(), emptyList())).isEmpty()
    }
}
