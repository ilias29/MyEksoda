package io.eksoda.core.data.repository

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.eksoda.core.model.MerchantBrand
import io.eksoda.engine.merchants.Brand
import org.junit.Test

class BrandsTest {
    @Test
    fun `the model's brands are the engine's`() {
        assertThat(MerchantBrand.entries.map { it.name }).isEqualTo(Brand.entries.map { it.name })
    }

    @Test
    fun `a chain's merchant has its brand, other merchants none`() {
        assertThat(brandOf("GR", "093683423", "Lidl", nameSetByUser = false))
            .isEqualTo(MerchantBrand.LIDL)
        assertThat(brandOf("GR", "094395700", "Sephora", nameSetByUser = false)).isNull()
    }
}
