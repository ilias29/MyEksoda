package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MonthlyOverviewPanel(model: AppViewModel) {
    val today = LocalDate.now()
    val current = YearMonth.from(today)
    var month by remember { mutableStateOf(current) }
    val data = remember(model, month, today) { model.monthlyOverview(month, today) }
    // Key the collection by month so a previous month's totals never appear under the new title.
    androidx.compose.runtime.key(month, today) {
        val result by data.collectAsStateWithLifecycle(initialValue = null)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Μηνιαία οικονομική εικόνα", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                    Text(
                        month.format(
                            DateTimeFormatter.ofPattern(
                                "LLLL yyyy",
                                Locale.forLanguageTag("el-GR"),
                            ),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = month < current, onClick = {
                        month = month.plusMonths(1)
                    }) { Text("›") }
                }
                val state = result
                val overview = state?.getOrNull()
                when {
                    state == null -> Text("Φόρτωση κινήσεων…")

                    overview == null -> Text(
                        "Αδυναμία υπολογισμού. Έλεγξε τις καταχωρισμένες κινήσεις.",
                    )

                    else -> {
                        Text("Καθαρή ροή μήνα", style = MaterialTheme.typography.titleMedium)
                        Text(
                            euro(overview.netFlowMinor),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (overview.netFlowMinor < 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        Text("Έσοδα: " + euro(overview.incomeMinor))
                        Text("  Τράπεζες: " + euro(overview.bankIncomeMinor))
                        Text("  Μετρητά: " + euro(overview.cashIncomeMinor))
                        Text("Έξοδα πριν από επιστροφές: " + euro(overview.grossExpensesMinor))
                        Text("  Τράπεζες: " + euro(overview.bankExpensesMinor))
                        Text("  Μετρητά: " + euro(overview.cashExpensesMinor))
                        Text("Επιστροφές: " + euro(overview.refundsMinor))
                        Text("Καθαρά έξοδα: " + euro(overview.netExpensesMinor))
                        Text(
                            "Από τα έξοδα: ενοίκιο " + euro(overview.rentMinor) +
                                " · δάνεια " + euro(overview.loanPaymentsMinor),
                        )
                        MonthlyPlanPanel(model, month, today, overview)
                        if (overview.movementCount ==
                            0
                        ) {
                            Text("Δεν υπάρχουν καταχωρισμένες οικονομικές κινήσεις.")
                        }
                        Text(
                            "Δεν είναι υπόλοιπο λογαριασμού. Οι μεταφορές εξαιρούνται. " +
                                "Οι συνδρομές εμφανίζονται ξεχωριστά, χωρίς δεύτερη χρέωση.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun euro(minor: Long): String =
    BigDecimal.valueOf(minor, 2).toPlainString().replace('.', ',') + " €"
