package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.engine.finance.BankLedger
import io.eksoda.engine.finance.Subscriptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

@Composable
internal fun SubscriptionPanel(model: AppViewModel) {
    val rows by model.subscriptionRows.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = LocalDate.now()
    val candidates = remember(model, today) { model.subscriptionSuggestions(today) }
    val suggestions by candidates.collectAsStateWithLifecycle(initialValue = emptyList())
    var name by remember { mutableStateOf("") }
    var monthly by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(emptySet<Pair<String, Long>>()) }
    val scope = rememberCoroutineScope()
    val keys = rows.map { it.key }.toSet()
    val total = rows.fold(BigDecimal.ZERO) { sum, row ->
        sum + BigDecimal.valueOf(row.expectedMonthlyMinor, 2)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Συνδρομές", style = MaterialTheme.typography.headlineSmall)
        Text("Εκτιμώμενο μηνιαίο κόστος: €" + total.toPlainString())
        Text("Οι συνδρομές δεν καταχωρίζουν νέες χρεώσεις.")
        suggestions.filter {
            it.description !in keys && (it.description to it.paymentAmountMinor) !in dismissed
        }.forEach { suggestion ->
            Text(
                "Πιθανή συνδρομή: ${suggestion.description} · €" +
                    BigDecimal.valueOf(suggestion.paymentAmountMinor, 2).toPlainString() +
                    " · ${suggestion.monthsObserved} μήνες",
            )
            Row {
                TextButton(enabled = !busy, onClick = {
                    name = suggestion.description
                    monthly = ""
                    message =
                        "Είναι συνδρομή; Συμπλήρωσε το πραγματικό μηνιαίο κόστος και επιβεβαίωσε."
                }) { Text("Εξέταση") }
                TextButton(enabled = !busy, onClick = {
                    dismissed =
                        dismissed + (suggestion.description to suggestion.paymentAmountMinor)
                }) { Text("Όχι τώρα") }
            }
        }
        OutlinedTextField(
            name,
            { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Όνομα συνδρομής") },
            singleLine = true,
        )
        OutlinedTextField(
            monthly,
            { monthly = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Μηνιαίο κόστος σε ευρώ") },
            singleLine = true,
        )
        Button(enabled = !busy, onClick = {
            val amount = runCatching {
                BankLedger.amountMinor(monthly, if (',' in monthly) ',' else '.')
            }.getOrNull()
            if (name.isBlank() || amount == null || amount <= 0) {
                message = "Συμπλήρωσε όνομα και θετικό μηνιαίο κόστος."
            } else {
                val savedName = name.trim()
                busy = true
                scope.launch {
                    try {
                        model.saveSubscription(savedName, amount)
                        name = ""
                        monthly = ""
                        message = "Η συνδρομή αποθηκεύτηκε."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Η αποθήκευση απέτυχε."
                    } finally {
                        busy = false
                    }
                }
            }
        }) { Text("Επιβεβαίωση / ενημέρωση") }
        Text(message)
        rows.forEach { row ->
            Text(
                row.name + " · €" + BigDecimal.valueOf(
                    row.expectedMonthlyMinor,
                    2,
                ).toPlainString() +
                    " / μήνα",
            )
            Row {
                TextButton(enabled = !busy, onClick = {
                    name = row.name
                    monthly = BigDecimal.valueOf(row.expectedMonthlyMinor, 2).toPlainString()
                    message = "Αλλαγή κόστους για την ίδια συνδρομή."
                }) { Text("Επεξεργασία") }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            model.removeSubscription(row.key)
                            message = "Η συνδρομή αφαιρέθηκε. Οι κινήσεις δεν άλλαξαν."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Η αφαίρεση απέτυχε."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Αφαίρεση") }
            }
        }
    }
}
