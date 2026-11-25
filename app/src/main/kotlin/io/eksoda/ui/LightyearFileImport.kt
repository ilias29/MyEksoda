package io.eksoda.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.eksoda.engine.finance.BankWorkbook
import io.eksoda.engine.finance.LightyearSnapshotCsv
import io.eksoda.engine.finance.LightyearSnapshotImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate

@Composable
internal fun LightyearFileImport(model: AppViewModel) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var notify by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notify = granted }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<LightyearSnapshotImport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            preview = null
            page = 0
            complete = false
            busy = true
            scope.launch {
                try {
                    preview = withContext(Dispatchers.IO) {
                        val bytes = requireNotNull(resolver.openInputStream(uri)).use {
                            BankWorkbook.readLimited(it, 4_000_000)
                        }
                        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                        LightyearSnapshotCsv.parse(text, LocalDate.now())
                    }
                    message = "Έλεγξε όλες τις εγγραφές πριν αποθηκεύσεις."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = "Μη υποστηριζόμενη μορφή ή μη έγκυρα δεδομένα. Δεν εισήχθη τίποτα." }
                finally { busy = false }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Εισαγωγή ενημερωμένου αρχείου Lightyear")
        Text("CSV UTF-8 με τις παρακάτω μορφές, όχι απευθείας το ανεπιβεβαίωτο statement συναλλαγών.")
        Text("Χαρτοφυλάκιο: date,currency,value,deposits,withdrawals")
        Text("Θέσεις: date,currency,instrument,quantity,value,cost")
        Text("Αυτόματη αποτίμηση: date,currency,instrument,quantity,unit_price,eur_rate,deposits_eur,withdrawals_eur")
        Text("Μερίσματα: date,reference,instrument,currency,gross,withheld,fee,eur_rate")
        Text("eur_rate = ευρώ ανά μονάδα ξένου νομίσματος, από την ημερομηνία της εγγραφής. Μετρητά: CASH:EUR ή CASH:USD κλπ., unit_price=1.")
        Row {
            Checkbox(notify, { enabled ->
                if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else notify = enabled
            })
            Text("Ειδοποίηση στο κινητό μετά από νέα εισαγωγή")
        }
        Text("Ημερομηνία YYYY-MM-DD, currency EUR. Καταθέσεις και αναλήψεις σωρευτικές, αξία μαζί με μετρητά, κόστος θέσης προαιρετικό.")
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Επιλογή αρχείου CSV") }
        Text(message)
        preview?.let { data ->
            if (data is LightyearSnapshotImport.Valuations) {
                Row {
                    Checkbox(complete, { complete = it }, enabled = !busy)
                    Text("Επιβεβαιώνω ότι κάθε ημερομηνία περιλαμβάνει ΟΛΕΣ τις θέσεις και όλα τα μετρητά, με σωστές ισοτιμίες.")
                }
            }
            val lines = when (data) {
                is LightyearSnapshotImport.Valuations -> data.rows.map {
                    "${it.date} · υπολογισμένη αξία ${csvEuro(it.valueMinor)} · καταθέσεις ${csvEuro(it.depositedMinor)} · αναλήψεις ${csvEuro(it.withdrawnMinor)}"
                } + data.holdings.map {
                    "${it.day} · ${it.instrument} · ${it.quantity} · ${csvEuro(it.valueMinor)}"
                }
                is LightyearSnapshotImport.Dividends -> data.rows.map {
                    "${it.day} · ${it.instrument} · ${it.gross} ${it.currency} · καθαρό ${csvEuro(it.netMinor)}"
                }
                is LightyearSnapshotImport.Portfolio -> data.rows.map {
                    "${it.date} · ${csvEuro(it.valueMinor)} · καταθέσεις ${csvEuro(it.depositedMinor)} · αναλήψεις ${csvEuro(it.withdrawnMinor)}"
                }
                is LightyearSnapshotImport.Holdings -> data.rows.map {
                    "${it.day} · ${it.instrument} · ${it.quantity} · ${csvEuro(it.valueMinor)} · κόστος ${it.costMinor?.let(::csvEuro) ?: "άγνωστο"}"
                }
            }
            Text("Εγγραφές: ${lines.size}")
            lines.drop(page * 10).take(10).forEach { Text(it) }
            if (page > 0) TextButton(enabled = !busy, onClick = { page-- }) { Text("Προηγούμενες") }
            if ((page + 1) * 10 < lines.size) TextButton(enabled = !busy, onClick = { page++ }) { Text("Επόμενες") }
            Button(enabled = !busy && (data !is LightyearSnapshotImport.Valuations || complete), onClick = {
                busy = true
                scope.launch {
                    try {
                        val inserted = model.importLightyearSnapshots(data)
                        message = "Νέες εγγραφές: $inserted · ήδη καταχωρισμένες: ${lines.size - inserted}"
                        preview = null
                        if (notify && inserted > 0) runCatching { LightyearNotifications.imported(context) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "Η εισαγωγή ακυρώθηκε. Έλεγξε αντικρουόμενες αποτιμήσεις ή καταθέσεις." }
                    finally { busy = false }
                }
            }) { Text("Επιβεβαίωση εισαγωγής (${lines.size})") }
            TextButton(enabled = !busy, onClick = { preview = null }) { Text("Ακύρωση") }
        }
        Text("Οι ίδιες εγγραφές παραλείπονται. Διαφορετική τιμή για υπάρχουσα ημερομηνία/τίτλο απαιτεί χειροκίνητη διόρθωση. Δεν ανεβαίνει κανένα αρχείο σε διακομιστή.")
    }
}

private fun csvEuro(value: Long) = BigDecimal.valueOf(value, 2).toPlainString() + " €"
