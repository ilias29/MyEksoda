package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.core.database.dao.ReceiptBankMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.YearMonth

@Composable
internal fun ReceiptMatchPanel(model: AppViewModel) {
    val current = YearMonth.now()
    var month by remember { mutableStateOf(current) }
    val stream = remember(model, month) { model.receiptMatches(month) }
    key(month) {
        val state by stream.collectAsStateWithLifecycle(initialValue = null)
        val scope = rememberCoroutineScope()
        var pending by remember { mutableStateOf<Pair<ReceiptBankMatch, Boolean>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var page by remember { mutableIntStateOf(0) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Αντιστοίχιση αποδείξεων", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(enabled = !busy, onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text(month.toString())
                TextButton(enabled = !busy && month < current, onClick = { month = month.plusMonths(1) }) { Text("›") }
            }
            Text("Ίδιο ποσό, ημερομηνία πληρωμής έως 3 ημέρες μετά. Έλεγξε τον έμπορο και την κίνηση πριν συνδέσεις.")
            val result = state
            val data = result?.getOrNull()
            when {
                result == null -> Text("Φόρτωση…")
                data == null -> Text("Αδυναμία φόρτωσης.")
                else -> {
                    val (candidates, links) = data
                    val rows = links.map { it to true } + candidates.map { it to false }
                    Text("Συνδεδεμένες: ${links.size} · Πιθανές αντιστοιχίσεις: ${candidates.size}")
                    if (rows.isEmpty()) Text("Δεν υπάρχουν συνδέσεις ή προτάσεις για αυτόν τον μήνα.")
                    val start = page.coerceAtMost((rows.size - 1).coerceAtLeast(0) / 10) * 10
                    rows.drop(start).take(10).forEach { (match, linked) ->
                        Text(match.merchant + " · " + match.receiptDay + " · " +
                            BigDecimal.valueOf(match.amountMinor, 2).toPlainString() + " €")
                        Text(match.account + " · " + match.bankDay + " · " + match.description)
                        TextButton(enabled = !busy, onClick = { pending = match to linked }) {
                            Text(if (linked) "Αποσύνδεση" else "Επιβεβαίωση σύνδεσης")
                        }
                    }
                    Row {
                        if (start > 0) TextButton(onClick = { page = start / 10 - 1 }) { Text("Προηγούμενες") }
                        if (start + 10 < rows.size) TextButton(onClick = { page = start / 10 + 1 }) { Text("Επόμενες") }
                    }
                }
            }
            Text(message)
        }
        pending?.let { (match, linked) ->
            AlertDialog(
                onDismissRequest = { if (!busy) pending = null },
                title = { Text(if (linked) "Αφαίρεση σύνδεσης;" else "Είναι η ίδια αγορά;") },
                text = { Text(match.merchant + " · " + match.receiptDay + "\n" +
                    match.account + " · " + match.bankDay + " · " + match.description +
                    "\nΗ σύνδεση δεν δημιουργεί νέα οικονομική κίνηση.") },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                if (linked) model.unlinkReceipt(match) else model.linkReceipt(match)
                                message = if (linked) "Αποσυνδέθηκε." else "Συνδέθηκε."
                                pending = null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Η αλλαγή απέτυχε. Η κίνηση ίσως έχει αλλάξει ή συνδεθεί αλλού."
                                pending = null
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Επιβεβαίωση") }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Ακύρωση") } },
            )
        }
    }
}
