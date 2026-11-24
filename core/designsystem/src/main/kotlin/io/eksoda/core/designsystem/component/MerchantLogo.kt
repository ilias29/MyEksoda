package io.eksoda.core.designsystem.component

import androidx.annotation.DrawableRes

/**
 * A chain's logo for [MerchantAvatar]: a vector used as an alpha mask (white paths, dimmer parts
 * as a lower `fillAlpha`, no ground), tinted with the ink color; and the share of the avatar's
 * width it spans.
 */
data class MerchantLogo(@DrawableRes val drawable: Int, val widthFraction: Float)
