package io.eksoda.feature.products.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.eksoda.core.designsystem.component.SectionCard
import io.eksoda.core.model.PriceEntry
import io.eksoda.core.model.PriceStats
import java.time.format.DateTimeFormatter

@Composable
internal fun PriceObservatory(stats: PriceStats, priced: List<PriceEntry>) {
    if (stats.normalizedUnit !in setOf("kg", "l", "piece")) return
    val change = remember(priced) { observedPriceChange(priced) }
    val shops = remember(priced) { latestShopObservations(priced) }
    val date = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    if (shops.isEmpty()) return
    SectionCard(title = "Παρατηρητήριο τιμών") {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            change?.let {
                val sign = if (it.percent.signum() > 0) "+" else ""
                Text("Μεταβολή από την πρώτη καταγεγραμμένη αγορά: " +
                    sign + it.percent.toPlainString() + "%",
                    style = MaterialTheme.typography.titleMedium)
                Text("${it.oldest.issuedAt.format(date)} → ${it.latest.issuedAt.format(date)}")
            }
            Text("Τελευταία καταγεγραμμένη τιμή ανά έμπορο")
            shops.forEach { shop ->
                val entry = shop.entry
                Text(entry.merchant.name, style = MaterialTheme.typography.titleSmall)
                if (shop.ambiguousLatest) {
                    Text("Διαφορετικές τιμές την ίδια στιγμή · έλεγξε τις αποδείξεις.")
                } else {
                    val value = pricePerUnit(checkNotNull(entry.normalizedUnitPrice), stats.currency, stats.normalizedUnit)
                    Text("$value · ${entry.issuedAt.format(date)}")
                }
                Text("Αποδείξεις με συγκρίσιμη τιμή: ${shop.purchaseCount}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Ιστορικές τιμές από αγορές, όχι σημερινές τιμές ραφιού. " +
                "Οι εκπτώσεις ολόκληρης της απόδειξης δεν επιμερίζονται στα προϊόντα.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
