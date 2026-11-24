package io.eksoda.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.eksoda.core.designsystem.component.MerchantAvatar
import io.eksoda.core.designsystem.component.MerchantLogo
import io.eksoda.core.designsystem.theme.EksodaTheme
import io.eksoda.core.model.MerchantBrand
import io.eksoda.core.ui.R

// The width shares keep each logo inside the round avatar: round and square marks span about
// two thirds of it, the Masoutis wordmark (8:1) most of its width.
private const val ROUND_MARK = 0.72f
private const val EMBLEM = 0.6f
private const val TALL_EMBLEM = 0.55f
private const val WIDE_EMBLEM = 0.66f
private const val WORDMARK = 0.8f

/** Built once (the rows read it on every recomposition); the `when` makes a new brand need one. */
private val LOGOS: Map<MerchantBrand, MerchantLogo> = MerchantBrand.entries.associateWith {
    when (it) {
        MerchantBrand.AB_VASILOPOULOS ->
            MerchantLogo(R.drawable.merchant_logo_ab_vasilopoulos, TALL_EMBLEM)

        MerchantBrand.SKLAVENITIS -> MerchantLogo(R.drawable.merchant_logo_sklavenitis, EMBLEM)

        MerchantBrand.MASOUTIS -> MerchantLogo(R.drawable.merchant_logo_masoutis, WORDMARK)

        MerchantBrand.LIDL -> MerchantLogo(R.drawable.merchant_logo_lidl, ROUND_MARK)

        MerchantBrand.MY_MARKET -> MerchantLogo(R.drawable.merchant_logo_my_market, WIDE_EMBLEM)
    }
}

/** The logo [MerchantAvatar][io.eksoda.core.designsystem.component.MerchantAvatar] shows. */
val MerchantBrand.logo: MerchantLogo get() = LOGOS.getValue(this)

@Preview
@Composable
private fun MerchantLogosPreview() {
    EksodaTheme {
        Column(
            modifier = Modifier.background(EksodaTheme.colors.panel).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (size in listOf(38.dp, 40.dp, 56.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MerchantBrand.entries.forEach { brand ->
                        MerchantAvatar(brand.name, brand.name, size = size, logo = brand.logo)
                    }
                    MerchantAvatar("other", "Κωγιμου", size = size)
                }
            }
        }
    }
}
