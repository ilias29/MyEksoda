package io.eksoda.core.data.repository

import io.eksoda.core.model.MerchantBrand
import io.eksoda.engine.merchants.merchantBrand

/**
 * The chain the UI shows a logo for, from the merchant's own row (core:engine `merchantBrand`).
 * The model's enum mirrors the engine's by name (BrandsTest keeps them in step).
 */
internal fun brandOf(
    countryCode: String,
    vatNumber: String,
    displayName: String,
    nameSetByUser: Boolean,
): MerchantBrand? = merchantBrand(countryCode, vatNumber, displayName, nameSetByUser)
    ?.let { MerchantBrand.valueOf(it.name) }
