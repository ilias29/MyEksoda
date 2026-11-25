package io.eksoda.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import io.eksoda.core.database.entity.BankRow
import io.eksoda.engine.finance.MovementKind
import io.eksoda.engine.finance.SpendingCategory
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class Classification(
    val label: String,
    val kind: MovementKind,
    val category: SpendingCategory = SpendingCategory.UNCLASSIFIED,
)

private val transfer = Classification("Μεταφορά μεταξύ δικών μου λογαριασμών", MovementKind.TRANSFER)
private val credits = listOf(
    Classification("Έσοδο", MovementKind.INCOME),
    Classification("Επιστροφή χρημάτων", MovementKind.REFUND),
    transfer,
)
private val debits = listOf(
    Classification("Έξοδο", MovementKind.EXPENSE),
    Classification("Ενοίκιο", MovementKind.EXPENSE, SpendingCategory.RENT),
    Classification("Δόση δανείου", MovementKind.EXPENSE, SpendingCategory.LOAN_PAYMENT),
    Classification("Τρόφιμα", MovementKind.EXPENSE, SpendingCategory.GROCERIES),
    Classification("Λογαριασμοί κοινής ωφέλειας", MovementKind.EXPENSE, SpendingCategory.UTILITIES),
    transfer,
)

@Composable
internal fun BankHistory(rows: List<BankRow>, model: AppViewModel) {
    val scope = rememberCoroutineScope()
    var page by remember(rows.first().account) { mutableIntStateOf(0) }
    val start = page.coerceAtMost(rows.lastIndex / 10) * 10
    val spending = remember(rows) {
        rows.filter { it.kind == "EXPENSE" || it.kind == "REFUND" }.fold(BigDecimal.ZERO) { sum, row ->
            sum.subtract(BigDecimal.valueOf(row.amount, 2))
        }
    }
    Text("Καθαρά έξοδα λογαριασμού   " + spending.toPlainString() + " €")
    for (row in rows.drop(start).take(10)) key(row.account, row.reference) {
        val options = if (row.amount > 0) credits else debits
        val current = options.find { it.kind.name == row.kind && it.category.name == row.category }
        var expanded by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        Text(row.day + "   " + row.description + "   " + BigDecimal.valueOf(row.amount, 2) + " €")
        Box {
            TextButton(enabled = !saving, onClick = { expanded = true }) {
                Text((current?.label ?: "Κατηγορία") + " · Αλλαγή")
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        expanded = false
                        saving = true
                        scope.launch {
                            try {
                                model.classify(row, option.kind, option.category)
                                failed = false
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed = true
                            } finally {
                                saving = false
                            }
                        }
                    })
                }
            }
        }
        if (failed) Text("Η αλλαγή δεν αποθηκεύτηκε. Δοκίμασε ξανά.")
    }
    if (start > 0) TextButton(onClick = { page-- }) { Text("Προηγούμενες κινήσεις") }
    if (start + 10 < rows.size) TextButton(onClick = { page++ }) { Text("Επόμενες κινήσεις") }
}
