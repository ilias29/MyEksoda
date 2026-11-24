package io.eksoda.engine.merchants

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BrandsTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "094025817, AB_VASILOPOULOS",
        "800764388, SKLAVENITIS",
        "094247924, MASOUTIS",
        "093683423, LIDL",
        // METRO ΑΕΒΕ: My Market (Metro Cash & Carry shares the VAT number).
        "094062259, MY_MARKET",
        "' 094247924 ', MASOUTIS",
    )
    fun `a chain is recognized by its VAT number`(vat: String, brand: Brand) {
        assertThat(merchantBrand("GR", vat, "Any", nameSetByUser = false)).isEqualTo(brand)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        // Any other shop, e.g. Sephora.
        "GR, 094395700",
        "GR, 999644611",
        "GR, ''",
        // The same digits in another country are another company.
        "CY, 094247924",
    )
    fun `other merchants have no chain`(country: String, vat: String) {
        assertThat(merchantBrand(country, vat, "Any", nameSetByUser = false)).isNull()
    }

    @Test
    fun `a chain with its own VAT number keeps its logo whatever the household calls it`() {
        assertThat(merchantBrand("GR", "800764388", "Το σούπερ μάρκετ", nameSetByUser = true))
            .isEqualTo(Brand.SKLAVENITIS)
    }

    @Test
    fun `a shared VAT number shows the chain until the household names the other one`() {
        val metro = "094062259"
        assertThat(merchantBrand("GR", metro, "Metro", nameSetByUser = false))
            .isEqualTo(Brand.MY_MARKET)
        assertThat(merchantBrand("GR", metro, "Metro Cash & Carry", nameSetByUser = true)).isNull()
        assertThat(merchantBrand("GR", metro, "Το My Market μας", nameSetByUser = true))
            .isEqualTo(Brand.MY_MARKET)
        assertThat(merchantBrand("GR", metro, "mymarket", nameSetByUser = true))
            .isEqualTo(Brand.MY_MARKET)
    }
}
