package io.eksoda.feature.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.eksoda.core.data.repository.ReviewedReceiptInput
import io.eksoda.core.data.repository.ReviewedReceiptResult
import io.eksoda.engine.finance.ReceiptTextParser
import io.eksoda.engine.mydata.PaymentMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun ReviewedReceiptDialog(
    recognized: String,
    save: (suspend (ReviewedReceiptInput) -> ReviewedReceiptResult)?,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var text by remember(recognized) { mutableStateOf(recognized) }
    var merchant by remember(recognized) { mutableStateOf("") }
    var vat by remember(recognized) { mutableStateOf("") }
    var date by remember(recognized) { mutableStateOf("") }
    var number by remember(recognized) { mutableStateOf("") }
    var mark by remember(recognized) { mutableStateOf("") }
    var payment by remember(recognized) { mutableStateOf(PaymentMethod.UNKNOWN) }
    var checked by remember(recognized) { mutableStateOf(false) }
    var possible by remember(recognized) { mutableStateOf(false) }
    var distinct by remember(recognized) { mutableStateOf(false) }
    var busy by remember(recognized) { mutableStateOf(false) }
    var error by remember(recognized) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = remember(text) { ReceiptTextParser.parse(text) }
    fun reset() {
        checked = false
        possible = false
        distinct = false
        error = false
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Έλεγχος απόδειξης OCR") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Απόδειξη αγοράς σε ευρώ. Έλεγξε κάθε γραμμή και μη συμπεριλάβεις στοιχεία κάρτας ή προσωπικά στοιχεία.",
                )
                OutlinedTextField(
                    text,
                    {
                        text = it
                        reset()
                    },
                    enabled = !busy,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Κείμενο") },
                )
                ReceiptTextReview(text)
                OutlinedTextField(
                    merchant,
                    {
                        merchant = it
                        reset()
                    },
                    enabled = !busy,
                    label = { Text("Επωνυμία καταστήματος") },
                    singleLine = true,
                )
                OutlinedTextField(
                    vat,
                    {
                        vat = it
                        reset()
                    },
                    enabled = !busy,
                    label = { Text("ΑΦΜ καταστήματος (9 ψηφία)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    date,
                    {
                        date = it
                        reset()
                    },
                    enabled = !busy,
                    label = { Text("Ημερομηνία ΕΕΕΕ-ΜΜ-ΗΗ") },
                    singleLine = true,
                )
                OutlinedTextField(
                    number,
                    {
                        number = it
                        reset()
                    },
                    enabled = !busy,
                    label = { Text("Σειρά και αριθμός απόδειξης") },
                    singleLine = true,
                )
                OutlinedTextField(
                    mark,
                    {
                        mark = it
                        reset()
                    },
                    enabled = !busy,
                    label = { Text("MARK (αν αναγράφεται)") },
                    singleLine = true,
                )
                val method = when (payment) {
                    PaymentMethod.CASH -> "Μετρητά"
                    PaymentMethod.CARD -> "Κάρτα"
                    else -> "Άγνωστος"
                }
                TextButton(enabled = !busy, onClick = {
                    payment = when (payment) {
                        PaymentMethod.UNKNOWN -> PaymentMethod.CASH
                        PaymentMethod.CASH -> PaymentMethod.CARD
                        else -> PaymentMethod.UNKNOWN
                    }
                    reset()
                }) { Text("Τρόπος πληρωμής: $method · αλλαγή") }
                Text("Επιβεβαιώνω ότι όλα τα είδη και τα ποσά συμφωνούν με τη φωτογραφία.")
                Checkbox(checked, { checked = it }, enabled = !busy)
                if (possible) {
                    Text(
                        "Υπάρχει απόδειξη του ίδιου καταστήματος με ίδια ημερομηνία και ποσό. Συνέχισε μόνο αν είναι διαφορετική αγορά.",
                    )
                    Checkbox(distinct, { distinct = it }, enabled = !busy)
                    Text("Επιβεβαιώνω ότι είναι διαφορετική απόδειξη.")
                }
                if (error) {
                    Text(
                        "Δεν αποθηκεύτηκε. Έλεγξε ΑΦΜ, ημερομηνία, αριθμό και ποσά ή πιθανή σύγκρουση MARK.",
                    )
                }
                Text(
                    "Δεν δημιουργείται τραπεζική ή ταμειακή κίνηση. Η αντιστοίχιση πληρωμής θα γίνει χωριστά.",
                )
            }
        },
        confirmButton = {
            if (save != null) {
                Button(
                    enabled = !busy && checked && parsed.reconciles && (!possible || distinct),
                    onClick = {
                        val day = runCatching { LocalDate.parse(date) }.getOrNull()
                        if (day == null) {
                            error = true
                        } else {
                            val input =
                                ReviewedReceiptInput(
                                    text,
                                    merchant,
                                    vat,
                                    day,
                                    number,
                                    mark,
                                    payment,
                                    distinct,
                                )
                            busy = true
                            error = false
                            scope.launch {
                                try {
                                    when (val result = save(input)) {
                                        is ReviewedReceiptResult.Saved -> onSaved(result.id)
                                        ReviewedReceiptResult.PossibleDuplicate -> possible = true
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = true
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                ) { Text("Αποθήκευση απόδειξης") }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onClose) {
                Text(stringResource(R.string.scan_close))
            }
        },
    )
}
