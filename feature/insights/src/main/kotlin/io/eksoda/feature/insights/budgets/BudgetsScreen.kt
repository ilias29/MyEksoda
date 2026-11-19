package io.eksoda.feature.insights.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.core.designsystem.component.SectionCard
import io.eksoda.core.designsystem.text.monoNumbers
import io.eksoda.core.designsystem.theme.EksodaTheme
import io.eksoda.core.model.Category
import io.eksoda.core.model.Money
import io.eksoda.core.ui.component.AmountDialog
import io.eksoda.core.ui.component.DelayedProgressIndicator
import io.eksoda.core.ui.text.appLocale
import io.eksoda.core.ui.text.categoryName
import io.eksoda.core.ui.text.formatMoney
import io.eksoda.feature.insights.R
import java.math.BigDecimal

@Composable
fun BudgetsRoute(onBack: () -> Unit, viewModel: BudgetsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.budgets_save_failed)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BudgetsEffect.SaveFailed -> snackbarHostState.showSnackbar(saveFailed)
            }
        }
    }
    BudgetsScreen(state, viewModel::onEvent, onBack, snackbarHostState)
}

/** Which budget the dialog edits: the whole month's ([categoryId] null) or a category's. */
private data class Editing(val categoryId: String?)

/**
 * The monthly budgets: one for the whole month, and one per category. Categories with a budget
 * come first; a row opens a dialog to set, change or remove its amount.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(
    state: BudgetsUiState,
    onEvent: (BudgetsEvent) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val colors = EksodaTheme.colors
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTotal by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.budgets_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.budgets_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                DelayedProgressIndicator()
            }
            return@Scaffold
        }
        // Sorted by the name the user sees (system categories are translated).
        val names = state.categories.associate { it.category.id to categoryName(it.category) }
        val sorted = state.categories.sortedWith(
            compareBy<CategoryBudget> { it.amount == null }.thenBy { names[it.category.id] },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.budgets_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            SectionCard {
                BudgetRow(
                    name = stringResource(R.string.budgets_whole_month),
                    supporting = stringResource(R.string.budgets_whole_month_hint),
                    amount = state.total,
                    currency = state.currency,
                    onClick = { editingTotal = true },
                )
            }
            SectionCard(title = stringResource(R.string.budgets_categories)) {
                if (state.categories.any { it.amount != null }) {
                    CategoriesSum(state)
                    HorizontalDivider(color = colors.line)
                }
                sorted.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = colors.line)
                    BudgetRow(
                        name = names[item.category.id].orEmpty(),
                        amount = item.amount,
                        currency = state.currency,
                        onClick = { editing = item.category.id },
                    )
                }
            }
        }
    }
    val editingCategory = editing?.let { id ->
        state.categories.firstOrNull { it.category.id == id }
    }
    val target = when {
        editingTotal -> Editing(null) to state.total
        editingCategory != null -> Editing(editingCategory.category.id) to editingCategory.amount
        else -> null
    }
    target?.let { (scope, amount) ->
        val close = {
            editing = null
            editingTotal = false
        }
        val locale = appLocale()
        val money = { value: BigDecimal -> formatMoney(Money(value, state.currency), locale) }
        val categoryError = stringResource(R.string.budgets_error_category)
        val totalError = stringResource(R.string.budgets_error_total)
        // The categories are parts of the month: together within its budget.
        val validate: (BigDecimal) -> String? = { typed ->
            val categoryId = scope.categoryId
            if (categoryId == null) {
                totalError.format(locale, money(state.categoriesTotal))
                    .takeIf { typed < state.categoriesTotal }
            } else {
                state.limits[categoryId]?.takeIf { typed > it }
                    ?.let { categoryError.format(locale, money(it)) }
            }
        }
        AmountDialog(
            title = stringResource(R.string.budgets_dialog_title),
            description = editingCategory?.let { categoryName(it.category) }
                ?: stringResource(R.string.budgets_whole_month),
            initial = amount,
            currency = state.currency,
            onConfirm = {
                close()
                onEvent(BudgetsEvent.Set(scope.categoryId, it))
            },
            onDismiss = close,
            validate = validate,
            onRemove = amount?.let {
                {
                    close()
                    onEvent(BudgetsEvent.Set(scope.categoryId, null))
                }
            },
        )
    }
}

/** "Together 450 € of the month's 800 €", or without a whole-month budget "Together 450 €". */
@Composable
private fun CategoriesSum(state: BudgetsUiState) {
    val locale = appLocale()
    val sum = formatMoney(Money(state.categoriesTotal, state.currency), locale)
    val total = state.total
    Text(
        monoNumbers(
            if (total == null) {
                stringResource(R.string.budgets_categories_sum, sum)
            } else {
                stringResource(
                    R.string.budgets_categories_sum_of,
                    sum,
                    formatMoney(Money(total, state.currency), locale),
                )
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = EksodaTheme.colors.ink2,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun BudgetRow(
    name: String,
    amount: BigDecimal?,
    currency: String,
    onClick: () -> Unit,
    supporting: String? = null,
) {
    val colors = EksodaTheme.colors
    val locale = appLocale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
            }
        }
        Text(
            amount?.let { monoNumbers(formatMoney(Money(it, currency), locale)) }
                ?: AnnotatedString(stringResource(R.string.budgets_none)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (amount != null) colors.compare else colors.ink2,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.ink2,
        )
    }
}

@Preview
@Composable
private fun BudgetsScreenPreview() {
    EksodaTheme {
        BudgetsScreen(
            state = BudgetsUiState(
                loading = false,
                total = BigDecimal("800"),
                categories = listOf(
                    CategoryBudget(
                        Category("c1", "Groceries", "groceries", true),
                        BigDecimal("350"),
                    ),
                    CategoryBudget(Category("c2", "Coffee", "coffee", false), null),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
