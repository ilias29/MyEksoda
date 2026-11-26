package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.core.database.entity.PlannedPaymentRow
import io.eksoda.engine.finance.BankLedger
import io.eksoda.engine.finance.PaydayCalendar
import io.eksoda.engine.finance.PaydayPlan
import io.eksoda.engine.finance.PaymentState
import io.eksoda.engine.finance.PaymentType
import io.eksoda.engine.finance.PlannedPayment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun PaydayPanel(model: AppViewModel) {
    val state by model.paydayData.collectAsStateWithLifecycle(initialValue = null)
    val today = LocalDate.now()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf("") }
    var payday by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var due by remember { mutableStateOf(today.toString()) }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(PaymentType.RENT) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<PlannedPaymentRow, Boolean>?>(null) }
    var all by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    fun perform(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { action(); message = "Αποθηκεύτηκε." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Η αλλαγή απέτυχε. Έλεγξε τα στοιχεία." }
            finally { busy = false }
        }
    }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Μέχρι τον επόμενο μισθό", style = MaterialTheme.typography.headlineSmall)
            val result = state
            val data = result?.getOrNull()
            when {
                result == null -> Text("Φόρτωση…")
                data == null -> Text("Αδυναμία φόρτωσης.")
                else -> {
                    val (stored, rows) = data
                    val forecast = if (stored == null || stored.needsReview) null else runCatching {
                        PaydayCalendar.forecast(
                            PaydayPlan(LocalDate.parse(stored.balanceDay), stored.balanceMinor, LocalDate.parse(stored.payday)),
                            today,
                            rows.map { PlannedPayment(it.id, it.name, LocalDate.parse(it.due), it.amountMinor,
                                PaymentType.valueOf(it.type), PaymentState.valueOf(it.state)) },
                        )
                    }.getOrNull()
                    if (forecast != null) {
                        Text("Επόμενος μισθός: ${stored?.payday} · ${forecast.days} ημέρες")
                        Text("Τρέχον υπόλοιπο: " + paydayEuro(BigDecimal.valueOf(checkNotNull(stored).balanceMinor)))
                        Text("Ανεξόφλητες υποχρεώσεις: " + paydayEuro(forecast.reservedMinor))
                        Text("Μετά τις υποχρεώσεις: " + paydayEuro(forecast.remainingMinor),
                            style = MaterialTheme.typography.titleLarge)
                        Text("Ημερήσιο διαθέσιμο: " + paydayEuro(forecast.dailyAllowanceMinor))
                        if (forecast.remainingMinor.signum() < 0) Text("Δεν επαρκούν τα διαθέσιμα.", color = MaterialTheme.colorScheme.error)
                        if (forecast.overdueCount > 0) Text("Ληξιπρόθεσμες υποχρεώσεις: ${forecast.overdueCount}")
                    } else Text("Επιβεβαίωσε το σημερινό υπόλοιπο και την επόμενη ημερομηνία μισθοδοσίας για νέα εκτίμηση.")
                    TextButton(enabled = !busy, onClick = {
                        editing = !editing
                        if (editing) {
                            balance = stored?.let { BigDecimal.valueOf(it.balanceMinor, 2).toPlainString() } ?: ""
                            payday = stored?.payday ?: ""
                        }
                    }) { Text("Ενημέρωση σημερινού υπολοίπου / μισθοδοσίας") }
                    if (editing) {
                        OutlinedTextField(balance, { balance = it }, enabled = !busy,
                            label = { Text("Σημερινά διαθέσιμα σε ευρώ") })
                        OutlinedTextField(payday, { payday = it }, enabled = !busy,
                            label = { Text("Επόμενος μισθός ΕΕΕΕ-ΜΜ-ΗΗ") })
                        Text("Το υπόλοιπο περιλαμβάνει όσα έχουν ήδη πληρωθεί. Όχι αξία επενδύσεων ή πιστωτικό όριο.")
                        Button(enabled = !busy, onClick = {
                            perform {
                                model.savePaydayPlan(PaydayPlan(today, paydayAmount(balance), LocalDate.parse(payday)))
                                editing = false
                            }
                        }) { Text("Επιβεβαίωση σημερινού υπολοίπου") }
                    }
                    TextButton(enabled = !busy, onClick = { adding = !adding }) { Text("Προσθήκη υποχρέωσης") }
                    if (adding) {
                        OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("Περιγραφή") })
                        OutlinedTextField(due, { due = it }, enabled = !busy, label = { Text("Ημερομηνία πληρωμής") })
                        OutlinedTextField(amount, { amount = it }, enabled = !busy, label = { Text("Ποσό €") })
                        TextButton(enabled = !busy, onClick = { type = PaymentType.entries[(type.ordinal + 1) % PaymentType.entries.size] }) {
                            Text("Κατηγορία: " + paydayType(type))
                        }
                        Button(enabled = !busy, onClick = {
                            perform {
                                model.addPlannedPayment(PlannedPayment(UUID.randomUUID().toString(), name.trim(),
                                    LocalDate.parse(due), paydayAmount(amount), type))
                                adding = false; name = ""; amount = ""
                            }
                        }) { Text("Αποθήκευση υποχρέωσης") }
                    }
                    TextButton(onClick = { all = !all; page = 0 }) { Text(if (all) "Μόνο ανεξόφλητες" else "Όλες οι υποχρεώσεις") }
                    val visible = if (all) rows else rows.filter { it.state == "PENDING" }
                    val start = page.coerceAtMost((visible.size - 1).coerceAtLeast(0) / 10) * 10
                    visible.drop(start).take(10).forEach { row ->
                        Text(row.due + " · " + row.name + " · " + paydayType(PaymentType.valueOf(row.type)) +
                            " · " + paydayEuro(BigDecimal.valueOf(row.amountMinor)))
                        if (row.state == "PENDING") {
                            Row {
                                TextButton(enabled = !busy, onClick = { pending = row to true }) { Text("Πληρώθηκε") }
                                TextButton(enabled = !busy, onClick = { pending = row to false }) { Text("Ακύρωση") }
                            }
                        } else Text(if (row.state == "PAID") "Πληρωμένη" else "Ακυρωμένη")
                    }
                    Row {
                        if (start > 0) TextButton(onClick = { page = start / 10 - 1 }) { Text("Προηγούμενες") }
                        if (start + 10 < visible.size) TextButton(onClick = { page = start / 10 + 1 }) { Text("Επόμενες") }
                    }
                }
            }
            Text(message)
            Text("Περιλαμβάνει ανεξόφλητες υποχρεώσεις πριν από την ημερομηνία μισθού, ακόμη και ληξιπρόθεσμες. Οι υποχρεώσεις της ημέρας μισθού δεν περιλαμβάνονται. Δεν δημιουργεί οικονομικές κινήσεις και δεν προβλέπει άλλα καθημερινά έξοδα.")
        }
    }
    pending?.let { (row, paid) ->
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text(if (paid) "Επιβεβαίωση πληρωμής" else "Ακύρωση υποχρέωσης") },
            text = { Text(if (paid) "Η πληρωμή δεν καταγράφεται ξανά ως έξοδο. Θα χρειαστεί νέα επιβεβαίωση του σημερινού υπολοίπου." else "Η υποχρέωση θα εξαιρεθεί από την πρόβλεψη.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    perform { model.resolvePlannedPayment(row, paid); pending = null }
                }) { Text("Επιβεβαίωση") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Πίσω") } },
        )
    }
}

private fun paydayAmount(text: String) = BankLedger.amountMinor(text, if (',' in text) ',' else '.')
private fun paydayEuro(minor: BigDecimal) = minor.movePointLeft(2).toPlainString().replace('.', ',') + " €"
private fun paydayType(type: PaymentType) = when (type) {
    PaymentType.RENT -> "Ενοίκιο"
    PaymentType.LOAN -> "Δόση"
    PaymentType.SUBSCRIPTION -> "Συνδρομή"
    PaymentType.OTHER -> "Άλλο"
}
