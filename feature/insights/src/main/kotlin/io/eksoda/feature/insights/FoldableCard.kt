package io.eksoda.feature.insights

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import io.eksoda.core.designsystem.component.SectionCard

/** The Insights cards that fold. The summary at the top always stays open. */
enum class InsightsCard { PACE, BUDGETS, CATEGORIES, MERCHANTS, TREND, TOP_PRODUCTS, E_PAYMENTS }

/** Which cards are folded, and how to fold or open one: provided by [InsightsScreen]. */
internal class CardFolds(val folded: Set<InsightsCard>, val onToggle: (InsightsCard) -> Unit)

// Not static: it changes with every fold, and only the cards that read it should redraw.
internal val LocalCardFolds = compositionLocalOf { CardFolds(emptySet()) {} }

/**
 * A [SectionCard] the user can fold to its title: tapping the title or its chevron hides the
 * content, and the choice is remembered (Settings preferences) so the screen stays as arranged.
 */
@Composable
internal fun FoldableCard(
    card: InsightsCard,
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val folds = LocalCardFolds.current
    val expanded = card !in folds.folded
    SectionCard(
        title = title,
        action = action,
        expanded = expanded,
        onToggleExpanded = { folds.onToggle(card) },
        toggleLabel = stringResource(
            if (expanded) R.string.insights_card_collapse else R.string.insights_card_expand,
        ),
        content = content,
    )
}
