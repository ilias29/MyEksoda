package io.eksoda.core.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.eksoda.core.designsystem.theme.EksodaTheme
import io.eksoda.core.ui.R

/**
 * The small mark of a receipt that came from another phone by household sync, next to its
 * merchant in the lists. Ink-2, not an accent: a fact, not something to act on
 * (docs/design/ui-redesign.md §1). Its content description says it in words.
 */
@Composable
fun FromOtherPhoneMark(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Icon(
        imageVector = Icons.Filled.SyncAlt,
        contentDescription = stringResource(R.string.from_other_phone),
        tint = EksodaTheme.colors.ink2,
        modifier = modifier.size(size),
    )
}

@Preview
@Composable
private fun FromOtherPhoneMarkPreview() {
    EksodaTheme { FromOtherPhoneMark() }
}
