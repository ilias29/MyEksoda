package io.eksoda.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.eksoda.core.designsystem.theme.EksodaColors
import io.eksoda.core.designsystem.theme.EksodaTheme

/**
 * The merchant's logo, when the app has the chain's ([logo]), else its initials, on a neutral
 * tile with a line border. The redesign allows three colors only (docs/design/ui-redesign.md §1),
 * so a logo is drawn in the ink color and merchants are told apart by name and logo or initials,
 * not by color. [id] is kept for callers and future use.
 */
@Suppress("UnusedParameter")
@Composable
fun MerchantAvatar(
    id: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    logo: MerchantLogo? = null,
) {
    val colors = EksodaTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .background(colors.tile, CircleShape)
            .border(1.dp, colors.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            // Decorative: the merchant's name is always shown next to the avatar.
            Image(
                painter = painterResource(logo.drawable),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(logo.widthFraction),
                contentScale = ContentScale.FillWidth,
                // The drawable is an alpha mask: each pixel takes the ink color at its opacity.
                colorFilter = remember(colors.ink) { ColorFilter.tint(colors.ink) },
            )
        } else {
            Initials(name, size, colors)
        }
    }
}

@Composable
private fun Initials(name: String, size: Dp, colors: EksodaColors) {
    // As written: UI text is never put in capitals (derived names are already title-cased).
    val initials = remember(name) {
        name.split(' ', '.', '-')
            .filter { word -> word.firstOrNull()?.isLetterOrDigit() == true }
            .take(2)
            .joinToString("") { it.first().toString() }
    }
    Text(
        text = initials,
        color = colors.ink,
        style = if (size >= 56.dp) {
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.titleSmall
        },
    )
}

@Preview
@Composable
private fun MerchantAvatarPreview() {
    EksodaTheme { MerchantAvatar(id = "1", name = "AB Vasilopoulos") }
}
