package io.eksoda.feature.insights.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.core.designsystem.chart.ShareBar
import io.eksoda.core.designsystem.component.OverlineText
import io.eksoda.core.designsystem.component.SectionCard
import io.eksoda.core.designsystem.text.monoNumbers
import io.eksoda.core.designsystem.theme.EksodaTheme
import io.eksoda.core.designsystem.theme.tabular
import io.eksoda.core.model.CategoryDetail
import io.eksoda.core.model.CategoryProduct
import io.eksoda.core.model.CategoryShop
import io.eksoda.core.model.Money
import io.eksoda.core.model.Period
import io.eksoda.core.ui.component.DelayedProgressIndicator
import io.eksoda.core.ui.component.HeroMoney
import io.eksoda.core.ui.component.MoneyText
import io.eksoda.core.ui.text.appLocale
import io.eksoda.core.ui.text.categoryName
import io.eksoda.core.ui.text.formatPercent
import io.eksoda.feature.insights.ChangeLine
import io.eksoda.feature.insights.R
import io.eksoda.feature.insights.rangeLabel
import java.math.BigDecimal
import io.eksoda.core.ui.R as UiR

@Composable
fun CategoryDetailRoute(
    onBack: () -> Unit,
    onProductClick: (String) -> Unit,
    onMerchantClick: (String) -> Unit,
    viewModel: CategoryDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CategoryDetailScreen(state, onBack, onProductClick, onMerchantClick, viewModel::retry)
}

/**
 * One category over the Insights period it was opened from: what it came to (and against the
 * period before), on which products, and at which shops.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    state: CategoryDetailUiState,
    onBack: () -> Unit,
    onProductClick: (String) -> Unit,
    onMerchantClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val detail = (state as? CategoryDetailUiState.Loaded)?.detail
    val category = detail?.category
    val title = when {
        detail == null -> ""
        category == null -> stringResource(UiR.string.bucket_uncategorized)
        else -> categoryName(category)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.category_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when (state) {
            CategoryDetailUiState.Loading ->
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    DelayedProgressIndicator()
                }

            CategoryDetailUiState.Failed ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.insights_card_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EksodaTheme.colors.ink2,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.insights_retry)) }
                }

            is CategoryDetailUiState.Loaded -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TotalCard(state.detail)
                val hasSpending = state.detail.products.isNotEmpty() ||
                    state.detail.shops.isNotEmpty()
                if (state.detail.category == null && hasSpending) {
                    Text(
                        stringResource(R.string.category_uncategorized_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EksodaTheme.colors.ink2,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (!hasSpending) {
                    Text(
                        stringResource(R.string.category_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EksodaTheme.colors.ink2,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    ProductsCard(state.detail, onProductClick)
                    ShopsCard(state.detail, onMerchantClick)
                }
            }
        }
    }
}

@Composable
private fun TotalCard(detail: CategoryDetail) {
    val colors = EksodaTheme.colors
    val locale = appLocale()
    SectionCard {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlineText(
                rangeLabel(Period.Range(detail.from, detail.to), locale, withYear = true) +
                    " · " + detail.currency,
            )
            HeroMoney(Money(detail.total, detail.currency))
            detail.share?.let { share ->
                Text(
                    monoNumbers(
                        stringResource(R.string.category_share, formatPercent(share, locale)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink2,
                )
            }
            ChangeLine(
                percent = detail.changePercent,
                previous = Period.Range(detail.previousFrom, detail.previousTo),
                previousAmount = Money(detail.previousTotal, detail.currency),
            )
        }
    }
}

@Composable
private fun ProductsCard(detail: CategoryDetail, onProductClick: (String) -> Unit) {
    val colors = EksodaTheme.colors
    val products = detail.products
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) products else products.take(PRODUCTS_SHOWN)
    SectionCard(
        title = stringResource(R.string.category_products),
        action = if (!showAll && products.size > PRODUCTS_SHOWN) {
            {
                Text(
                    stringResource(R.string.insights_show_all, products.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.compare,
                    modifier = Modifier
                        .clickable { showAll = true }
                        .padding(vertical = 6.dp),
                )
            }
        } else {
            null
        },
    ) {
        shown.forEachIndexed { index, product ->
            if (index > 0) HorizontalDivider(color = colors.line)
            ProductRow(product, detail.currency, onProductClick)
        }
        if (detail.unitemized.signum() != 0) {
            if (shown.isNotEmpty()) HorizontalDivider(color = colors.line)
            AmountRow(
                name = stringResource(R.string.category_unitemized),
                supporting = stringResource(R.string.category_unitemized_hint),
                share = null,
                money = Money(detail.unitemized, detail.currency),
                onClick = null,
            )
        }
    }
}

@Composable
private fun ProductRow(product: CategoryProduct, currency: String, onClick: (String) -> Unit) {
    AmountRow(
        name = product.name,
        supporting = pluralStringResource(
            R.plurals.category_receipts,
            product.receipts,
            product.receipts,
        ),
        share = product.share,
        money = Money(product.total, currency),
        onClick = product.productId?.let { id -> { onClick(id) } },
    )
}

/** A name with a line under it, its share and amount, and a chevron when it opens something. */
@Composable
private fun AmountRow(
    name: String,
    supporting: String,
    share: BigDecimal?,
    money: Money,
    onClick: (() -> Unit)?,
) {
    val colors = EksodaTheme.colors
    val locale = appLocale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                monoNumbers(supporting),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink2,
            )
        }
        Text(
            share?.let { formatPercent(it, locale) }.orEmpty(),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.ink2,
            maxLines = 1,
        )
        MoneyText(
            money,
            negative = money.amount.signum() < 0,
            style = MaterialTheme.typography.bodyMedium,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (onClick != null) colors.ink2 else Color.Transparent,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ShopsCard(detail: CategoryDetail, onMerchantClick: (String) -> Unit) {
    val max = detail.shops.maxOfOrNull { it.total } ?: BigDecimal.ONE
    SectionCard(title = stringResource(R.string.insights_by_shop)) {
        detail.shops.forEach { shop ->
            ShopRow(shop, detail.currency, max) { onMerchantClick(shop.merchantId) }
        }
    }
}

@Composable
private fun ShopRow(shop: CategoryShop, currency: String, max: BigDecimal, onClick: () -> Unit) {
    val colors = EksodaTheme.colors
    val locale = appLocale()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                shop.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                shop.share?.let { formatPercent(it, locale) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = colors.ink2,
            )
            MoneyText(Money(shop.total, currency), style = MaterialTheme.typography.bodyMedium)
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.ink2,
                modifier = Modifier.size(18.dp),
            )
        }
        ShareBar(
            value = shop.total,
            max = max,
            color = colors.spend,
            thickness = 6.dp,
            modifier = Modifier.padding(end = 26.dp),
        )
    }
}

/** Products listed before "Show all". */
private const val PRODUCTS_SHOWN = 10
