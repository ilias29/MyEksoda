package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
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
import io.eksoda.engine.finance.BudgetStatus
import io.eksoda.engine.finance.MonthlyOverview
import io.eksoda.engine.finance.MonthlyPlan
import io.eksoda.engine.finance.MonthlyPlanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun MonthlyPlanPanel(model: AppViewModel, month: YearMonth, today: LocalDate, actual: MonthlyOverview) {
    val stream = remember(model, month) { model.monthlyPlan(month) }
    val result by stream.collectAsStateWithLifecycle(initialValue = null)
    val state = result
    val plan = state?.getOrNull()
    var editing by remember(month) { mutableStateOf(false) }
    var limit by remember(month) { mutableStateOf("") }
    var income by remember(month) { mutableStateOf("") }
    var goal by remember(month) { mutableStateOf("") }
    var message by remember(month) { mutableStateOf("") }
    var busy by remember(month) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Μηνιαίος προϋπολογισμός", style = MaterialTheme.typography.titleMedium)
        when {
            state == null -> Text("Φόρτωση πλάνου…")
            state.isFailure -> Text("Αδυναμία φόρτωσης προϋπολογισμού.")
            else -> {
                if (plan != null) {
                    val progress = MonthlyPlanning.evaluate(month, today, plan, actual)
                    Text("Όριο εξόδων: " + planEuro(BigDecimal.valueOf(plan.spendingLimitMinor)))
                    Text("Αναμενόμενα έσοδα: " + planEuro(BigDecimal.valueOf(plan.expectedIncomeMinor)))
                    Text("Στόχος αποταμίευσης: " + planEuro(BigDecimal.valueOf(plan.savingsGoalMinor)))
                    LinearProgressIndicator(
                        progress = { progress.spendingFraction.coerceIn(0.0, 1.0).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val status = when (progress.status) {
                        BudgetStatus.WITHIN -> "Εντός ορίου"
                        BudgetStatus.NEAR -> "Προσοχή: έχει χρησιμοποιηθεί τουλάχιστον το 80%"
                        BudgetStatus.AT_LIMIT -> "Έφτασες στο όριο"
                        BudgetStatus.OVER -> "Υπέρβαση ορίου"
                    }
                    Text(status, color = if (progress.status == BudgetStatus.OVER)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text("Διαφορά από όριο: " + planEuro(progress.budgetRemainingMinor))
                    Text("Απόσταση από στόχο καθαρής ροής: " + planEuro(progress.savingsGapMinor.max(BigDecimal.ZERO)))
                    if (!progress.goalCompatibleWithPlan) Text("Το όριο δαπανών και τα αναμενόμενα έσοδα δεν εξασφαλίζουν τον στόχο.")
                    progress.projectedExpensesMinor?.let { Text("Έξοδα τέλους μήνα (εκτίμηση / τελικό): " + planEuro(it)) }
                    progress.projectedSavingsMinor?.let { Text("Καθαρή ροή τέλους μήνα (εκτίμηση / τελικό): " + planEuro(it)) }
                    Text("Η πρόβλεψη βασίζεται στον μέχρι τώρα ρυθμό εξόδων και στα έσοδα του πλάνου, όχι σε μελλοντικές χρεώσεις ή ημερομηνία μισθού.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!editing) {
                    TextButton(onClick = {
                        limit = plan?.let { BigDecimal.valueOf(it.spendingLimitMinor, 2).toPlainString() } ?: ""
                        income = plan?.let { BigDecimal.valueOf(it.expectedIncomeMinor, 2).toPlainString() } ?: ""
                        goal = plan?.let { BigDecimal.valueOf(it.savingsGoalMinor, 2).toPlainString() } ?: ""
                        editing = true
                    }) { Text(if (plan == null) "Ορισμός πλάνου" else "Αλλαγή πλάνου") }
                } else {
                    OutlinedTextField(limit, { limit = it }, label = { Text("Όριο εξόδων €") }, singleLine = true)
                    OutlinedTextField(income, { income = it }, label = { Text("Αναμενόμενα συνολικά έσοδα €") }, singleLine = true)
                    OutlinedTextField(goal, { goal = it }, label = { Text("Στόχος αποταμίευσης €") }, singleLine = true)
                    Button(enabled = !busy, onClick = {
                        val candidate = runCatching { MonthlyPlan(planAmount(limit), planAmount(income), planAmount(goal)) }.getOrNull()
                        if (candidate == null) message = "Έλεγξε τα ποσά: θετικό όριο και μη αρνητικά έσοδα / στόχος."
                        else {
                            busy = true
                            scope.launch {
                                try {
                                    model.saveMonthlyPlan(month, candidate)
                                    editing = false
                                    message = "Το πλάνο αποθηκεύτηκε."
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = "Η αποθήκευση απέτυχε."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }) { Text("Αποθήκευση πλάνου") }
                    TextButton(enabled = !busy, onClick = { editing = false; message = "" }) { Text("Ακύρωση") }
                }
                Text(message)
            }
        }
    }
}

private fun planAmount(value: String): Long =
    BankLedger.amountMinor(value, if (',' in value) ',' else '.')

private fun planEuro(minor: BigDecimal): String =
    minor.movePointLeft(2).toPlainString().replace('.', ',') + " €"
