package io.eksoda.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.time.LocalDate

@Composable
internal fun LightyearDividendSummary(model: AppViewModel) {
    val rows by model.lightyearDividends.collectAsStateWithLifecycle(initialValue = emptyList())
    var expanded by remember { mutableStateOf(false) }
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    val selected = rows.filter { it.day.startsWith("$year-") }
    val net = selected.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal.valueOf(row.netMinor, 2) }
    Text("Καθαρά μερίσματα $year: ${net.toPlainString()} € (${selected.size} εγγραφές)")
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Απόκρυψη μερισμάτων" else "Ιστορικό μερισμάτων") }
    if (expanded) {
        TextButton(onClick = { year-- }) { Text("Προηγούμενο έτος") }
        TextButton(enabled = year < LocalDate.now().year, onClick = { year++ }) { Text("Επόμενο έτος") }
        selected.forEach {
            Text("${it.day} · ${it.instrument} · μικτά ${it.gross} ${it.currency} · παρακράτηση ${it.withheld} · τέλη ${it.fee} · καθαρά ${BigDecimal.valueOf(it.netMinor, 2)} €")
        }
        Text("Τα μερίσματα δεν προστίθενται ξανά στην αξία ή στο αποτέλεσμα χαρτοφυλακίου. Η μετατροπή χρησιμοποιεί τη δηλωμένη ισοτιμία, όχι φορολογική ισοτιμία κατ' ανάγκη.")
    }
}
