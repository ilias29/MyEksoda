package io.eksoda.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.core.database.entity.BankRow
import io.eksoda.engine.finance.BankWorkbook
import io.eksoda.engine.finance.BankCsv
import io.eksoda.engine.finance.CsvMapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

@Composable
internal fun BankImportForm(model: AppViewModel) {
    var account by remember { mutableStateOf("") }
    var columns by remember { mutableStateOf("1,2,3,4") }
    var delimiter by remember { mutableStateOf(";") }
    var decimal by remember { mutableStateOf(",") }
    var pattern by remember { mutableStateOf("dd/MM/uuuu") }
    var preview by remember { mutableStateOf(emptyList<BankRow>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val history by remember(account, model) { model.bankHistory(account.trim()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            preview = emptyList()
            scope.launch {
                try {
                    val indices = columns.split(',').map { it.trim().toInt() - 1 }
                    require(indices.size == 4 && indices.distinct().size == 4 && indices.all { it >= 0 })
                    val mapping = CsvMapping(indices[0], indices[1], indices[2], indices[3],
                        delimiter.single(), decimal.single(), pattern)
                    val result = withContext(Dispatchers.IO) {
                        val data = requireNotNull(resolver.openInputStream(uri)).use {
                            BankWorkbook.readLimited(it, 4_000_000)
                        }
                        if (data.size >= 4 && data[0] == 80.toByte() && data[1] == 75.toByte()) {
                            val rows = BankWorkbook.rows(data, mapping.date, mapping.amount,
                                mapping.decimalSeparator, mapping.datePattern)
                            BankCsv.previewRows(rows, account.trim(), mapping)
                        } else {
                            val csv = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
                            BankCsv.preview(csv, account.trim(), mapping)
                        }
                    }
                    val problem = result.problems.firstOrNull()
                    if (problem != null) {
                        message = "Έλεγξε την εγγραφή " + problem.line
                    } else if (result.movements.any { it.sourceId == null }) {
                        message = "Απαιτείται αναγνωριστικό τράπεζας σε κάθε εγγραφή."
                    } else {
                        preview = result.movements.map {
                            BankRow(it.accountId, requireNotNull(it.sourceId), it.date.toString(), it.description,
                                it.amountMinor, it.kind.name, it.category.name)
                        }
                        message = "Εγγραφές για έλεγχο   " + preview.size
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Έλεγξε τη μορφή CSV/XLSX, τις στήλες και το μέγεθος του αρχείου."
                } finally {
                    busy = false
                }
            }
        }
    }
    Text("Τραπεζικές κινήσεις σε ευρώ")
    OutlinedTextField(account, { account = it; preview = emptyList() }, enabled = !busy, label = { Text("Όνομα λογαριασμού") })
    Text("CSV UTF-8 ή XLSX ενός φύλλου, με επικεφαλίδες και τιμές χωρίς τύπους. Δώσε τις θέσεις των στηλών ξεκινώντας από το 1.")
    OutlinedTextField(columns, { columns = it; preview = emptyList() }, enabled = !busy,
        label = { Text("Ημερομηνία, περιγραφή, ποσό, κωδικός") })
    OutlinedTextField(delimiter, { delimiter = it; preview = emptyList() }, enabled = !busy, label = { Text("Διαχωριστικό στηλών") })
    OutlinedTextField(decimal, { decimal = it; preview = emptyList() }, enabled = !busy, label = { Text("Δεκαδικό σύμβολο") })
    OutlinedTextField(pattern, { pattern = it; preview = emptyList() }, enabled = !busy, label = { Text("Μορφή ημερομηνίας") })
    Button(enabled = !busy && account.isNotBlank(), onClick = { picker.launch(arrayOf("*/*")) }) { Text("Επιλογή CSV ή XLSX") }
    Text(message)
    preview.take(10).forEach { Text(it.day + "   " + it.description + "   " + BigDecimal.valueOf(it.amount, 2) + " €") }
    if (preview.isNotEmpty()) {
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val count = model.saveBank(preview)
                    message = "Νέες κινήσεις   " + count + "   Ήδη καταχωρισμένες   " + (preview.size - count)
                    preview = emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Η εισαγωγή ακυρώθηκε. Έλεγξε για αντικρουόμενους κωδικούς."
                } finally {
                    busy = false
                }
            }
        }) { Text("Αποθήκευση όλων (" + preview.size + ")") }
    }
    if (history.isNotEmpty()) Text("Πρόσφατες τραπεζικές κινήσεις")
    history.take(10).forEach { Text(it.day + "   " + it.description + "   " + BigDecimal.valueOf(it.amount, 2) + " €") }
}
