package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.eksoda.engine.finance.MarketPeriod
import io.eksoda.engine.finance.MarketPriceHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MarketPricePanel() {
    var symbol by remember { mutableStateOf("") }
    var mic by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var prices by remember { mutableStateOf<MarketPriceHistory?>(null) }
    var fetchedAt by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(active, refresh) {
        if (active) {
            while (true) {
                loading = true
                try {
                    prices = TwelveDataPrices.fetch(symbol.trim().uppercase(Locale.ROOT),
                        mic.trim().uppercase(Locale.ROOT), apiKey.trim())
                    fetchedAt = Instant.now()
                    error = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = true
                } finally {
                    loading = false
                }
                // Daily bars: frequent polling is wasteful and may exhaust provider quota.
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Αυτόματη παρακολούθηση τιμών", style = MaterialTheme.typography.titleMedium)
        Text("Πηγή Twelve Data. Ιστορικές ημερήσιες τιμές, όχι επιβεβαιωμένα ζωντανά δεδομένα. Η διαθεσιμότητα εξαρτάται από το πακέτο.")
        OutlinedTextField(symbol, { symbol = it; prices = null; fetchedAt = null; error = false },
            enabled = !active, singleLine = true, label = { Text("Σύμβολο τίτλου") })
        OutlinedTextField(mic, { mic = it; prices = null; fetchedAt = null; error = false },
            enabled = !active, singleLine = true, label = { Text("MIC χρηματιστηρίου (π.χ. XNAS)") })
        OutlinedTextField(apiKey, { apiKey = it }, enabled = !active, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), label = { Text("Κλειδί API Twelve Data") })
        Text("Αποστέλλονται μόνο σύμβολο, χρηματιστήριο και κλειδί API στον πάροχο. Το κλειδί μένει στη μνήμη όσο είναι ανοιχτή η ενότητα.")
        Button(enabled = active || (symbol.isNotBlank() && mic.isNotBlank() && apiKey.isNotBlank()), onClick = {
            active = !active
            if (!active) apiKey = ""
        }) { Text(if (active) "Διακοπή παρακολούθησης" else "Έναρξη παρακολούθησης") }
        if (active) {
            Text("Ενημέρωση κατά την έναρξη και ανά 6 ώρες όσο παραμένει ανοιχτή η ενότητα.")
            TextButton(enabled = !loading, onClick = { refresh++ }) { Text("Ανανέωση τώρα") }
        }
        if (loading) Text("Ανάκτηση τιμών…")
        if (error) Text("Δεν ήταν διαθέσιμα νέα δεδομένα. Έλεγξε κλειδί, σύμβολο, χρηματιστήριο, όρια ή σύνδεση.",
            color = MaterialTheme.colorScheme.error)
        prices?.let { history ->
            Text("${history.latest.close.toPlainString()} ${history.currency} · ${history.latest.day}",
                style = MaterialTheme.typography.titleLarge)
            fetchedAt?.let {
                Text("Ανάκτηση: " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.systemDefault()).format(it))
            }
            if (history.latest.day.isBefore(LocalDate.now().minusDays(4))) Text("Παλιά παρατήρηση: έλεγξε τη διαθεσιμότητα.")
            MarketPeriod.entries.forEach { period ->
                val label = when (period) {
                    MarketPeriod.DAY -> "Ημερήσια"
                    MarketPeriod.WEEK -> "Εβδομαδιαία"
                    MarketPeriod.MONTH -> "Μηνιαία"
                    MarketPeriod.YEAR -> "Ετήσια"
                }
                val change = history.change(period)
                Text(if (change == null) "$label: ανεπαρκή στοιχεία" else
                    "$label: ${if (change.percent.signum() > 0) "+" else ""}${change.percent.toPlainString()}% (${change.from} → ${change.to})")
            }
            Text("Μεταβολές τιμής στο νόμισμα διαπραγμάτευσης, προσαρμοσμένες για splits, χωρίς μερίσματα και χωρίς μετατροπή σε ευρώ. Η σημερινή ημερήσια τιμή μπορεί να είναι προσωρινή.")
        }
    }
}
