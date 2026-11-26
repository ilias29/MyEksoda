package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.engine.finance.BankLedger
import io.eksoda.engine.finance.ManualCashEntry
import io.eksoda.engine.finance.MovementKind
import io.eksoda.engine.finance.SpendingCategory
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

@Composable
internal fun CashScreen(model: AppViewModel, back: () -> Unit) {
    val rows by model.cashRows.collectAsStateWithLifecycle(initialValue = emptyList())
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(LocalDate.now().toString()) }
    var choice by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val labels = listOf("Έσοδο", "Έξοδο", "Ενοίκιο", "Δόση δανείου")
    val categories = listOf(SpendingCategory.UNCLASSIFIED, SpendingCategory.UNCLASSIFIED,
        SpendingCategory.RENT, SpendingCategory.LOAN_PAYMENT)
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            TextButton(onClick = back) { Text("Πίσω") }
            Text("Οικονομικά", style = MaterialTheme.typography.headlineMedium)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val sections = listOf("Σύμβουλος", "Μήνας", "Περιουσία", "Μισθός", "Μετρητά",
                "Τράπεζες", "Αποδείξεις", "Αφορολόγητο", "Lightyear", "Συνδρομές")
            sections.forEachIndexed { index, label ->
                OutlinedButton(onClick = { scope.launch { listState.scrollToItem(index) } }) { Text(label) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FinanceAdvisorPanel(model) }
        item { MonthlyOverviewPanel(model) }
        item { NetWorthPanel(model) }
        item { PaydayPanel(model) }
        item {
            Text("Μετρητά", style = MaterialTheme.typography.headlineSmall)
            Text("Καταχώρισε μόνο πραγματικά έσοδα και έξοδα, όχι αναλήψεις ή καταθέσεις μεταξύ δικών σου λογαριασμών.")
            val income = rows.filter { it.amountMinor > 0 }.fold(BigDecimal.ZERO) { total, row ->
                total + BigDecimal.valueOf(row.amountMinor, 2)
            }
            Text("Σύνολο εσόδων μετρητών (όλες οι ημερομηνίες)   €" + income.toPlainString())
            Box {
                TextButton(onClick = { expanded = true }) { Text(labels[choice]) }
                DropdownMenu(expanded, { expanded = false }) {
                    labels.forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { choice = index; expanded = false })
                    }
                }
            }
            OutlinedTextField(day, { day = it }, label = { Text("Ημερομηνία (ΕΕΕΕ-ΜΜ-ΗΗ)") })
            OutlinedTextField(amount, { amount = it }, label = { Text("Ποσό σε ευρώ") })
            OutlinedTextField(description, { description = it }, label = { Text("Περιγραφή") })
            Button(enabled = !busy, onClick = {
                val entry = runCatching {
                    val minor = BankLedger.amountMinor(amount, if (',' in amount) ',' else '.')
                    require(minor > 0)
                    ManualCashEntry(LocalDate.parse(day), description.trim(), if (choice == 0) minor else -minor,
                        if (choice == 0) MovementKind.INCOME else MovementKind.EXPENSE, categories[choice])
                }.getOrNull()
                if (entry == null) {
                    message = "Έλεγξε την ημερομηνία, το θετικό ποσό και την περιγραφή."
                } else {
                    busy = true
                    scope.launch {
                        try {
                            model.saveCash(entry)
                            amount = ""
                            description = ""
                            message = "Αποθηκεύτηκε."
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Η αποθήκευση απέτυχε."
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text("Αποθήκευση") }
            Text(message)
        }
        item { BankImportForm(model) }
        item { ReceiptMatchPanel(model) }
        item { TaxSpendingPanel(model) }
        item { LightyearPanel(model) }
        item { SubscriptionPanel(model) }
        items(rows, key = { it.id }) { row ->
            Text(row.day + "   " + row.description + "   €" + BigDecimal.valueOf(row.amountMinor, 2).toPlainString())
        }
        }
    }
}
