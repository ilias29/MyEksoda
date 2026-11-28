package io.eksoda.feature.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.eksoda.engine.finance.ReceiptTextParser
import java.math.BigDecimal

@Composable
internal fun ReceiptTextReview(text: String) {
    val parsed = remember(text) { ReceiptTextParser.parse(text) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.scan_ocr_items), style = MaterialTheme.typography.titleSmall)
        parsed.items.forEach { item ->
            val unit = item.unitPriceMinor
            val detail = if (item.quantity != null && unit != null) {
                " · ${item.quantity} × ${receiptEuro(unit)}"
            } else {
                ""
            }
            Text(item.name + detail + " · " + receiptEuro(item.lineMinor))
        }
        parsed.discountsMinor.forEach {
            Text(stringResource(R.string.scan_ocr_discount, receiptEuro(it)))
        }
        parsed.vatMinor?.let { Text(stringResource(R.string.scan_ocr_vat, receiptEuro(it))) }
        Text(stringResource(R.string.scan_ocr_calculated, receiptEuro(parsed.calculatedMinor)))
        Text(stringResource(R.string.scan_ocr_total, parsed.totalMinor?.let(::receiptEuro) ?: "—"))
        Text(
            stringResource(
                if (parsed.reconciles) R.string.scan_ocr_reconciles else R.string.scan_ocr_mismatch,
            ),
            color = if (parsed.reconciles) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (parsed.warnings.isNotEmpty()) {
            Text(stringResource(R.string.scan_ocr_warnings, parsed.warnings.size))
        }
    }
}

private fun receiptEuro(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString() + " €"
