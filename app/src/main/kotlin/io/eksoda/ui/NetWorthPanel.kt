package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.core.database.entity.NetWorthRow
import io.eksoda.engine.finance.BankLedger
import io.eksoda.engine.finance.NetWorth
import io.eksoda.engine.finance.NetWorthSnapshot
import io.eksoda.engine.finance.PortfolioSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

@Composable
internal fun NetWorthPanel(model: AppViewModel) {
    val state by model.netWorthData.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var day by remember { mutableStateOf(LocalDate.now().toString()) }
    var bank by remember { mutableStateOf("") }
    var cash by remember { mutableStateOf("") }
    var investments by remember { mutableStateOf("") }
    var otherAssets by remember { mutableStateOf("") }
    var loans by remember { mutableStateOf("") }
    var otherDebts by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    var reviewed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(6) }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Καθαρή περιουσία", style = MaterialTheme.typography.headlineSmall)
            val result = state
            val data = result?.getOrNull()
            when {
                result == null -> Text("Φόρτωση…")

                data == null -> Text("Αδυναμία φόρτωσης.")

                else -> {
                    val (history, portfolio) = data
                    val latest = history.firstOrNull()?.worth()
                    if (latest != null) {
                        Text("Τελευταία επιβεβαιωμένη αποτίμηση: ${latest.day}")
                        Text(
                            worthEuro(latest.netWorthMinor),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text("Περιουσιακά στοιχεία: " + worthEuro(latest.assetsMinor))
                        Text("Οφειλές: " + worthEuro(latest.debtsMinor))
                        Text("Διαθέσιμα εκτός Lightyear: " + worthEuro(latest.liquidMinor))
                        Text("Τράπεζες: " + worthEuro(BigDecimal.valueOf(latest.bankMinor)))
                        Text("Μετρητά: " + worthEuro(BigDecimal.valueOf(latest.cashMinor)))
                        Text(
                            "Lightyear (μαζί με τα μετρητά της): " +
                                worthEuro(BigDecimal.valueOf(latest.lightyearMinor)),
                        )
                        Text(
                            "Λοιπά περιουσιακά στοιχεία: " +
                                worthEuro(BigDecimal.valueOf(latest.otherAssetsMinor)),
                        )
                        Text("Υπόλοιπα δανείων: " + worthEuro(BigDecimal.valueOf(latest.loanMinor)))
                        Text(
                            "Λοιπές οφειλές: " +
                                worthEuro(BigDecimal.valueOf(latest.otherDebtsMinor)),
                        )
                        if (latest.fromLightyear &&
                            portfolio.find { it.day == latest.day.toString() }?.valueMinor !=
                            latest.lightyearMinor
                        ) {
                            Text(
                                "Η αποθηκευμένη αξία Lightyear διαφέρει πλέον από το ιστορικό της. Επανέλεγξε την αποτίμηση.",
                            )
                        }
                        history.getOrNull(1)?.worth()?.let { previous ->
                            val delta = NetWorth.change(previous, latest)
                            val direction = when (delta.amountMinor.signum()) {
                                1 -> "Αύξηση"
                                -1 -> "Μείωση"
                                else -> "Σταθερή"
                            }
                            Text(
                                "$direction από ${delta.from}: " + worthEuro(delta.amountMinor),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text("Ιστορικό")
                        history.take(count).forEach { row ->
                            Text(row.day + " · " + worthEuro(row.worth().netWorthMinor))
                        }
                        if (history.size >
                            count
                        ) {
                            TextButton(onClick = { count += 6 }) { Text("Περισσότερες") }
                        }
                    } else {
                        Text("Δεν έχεις επιβεβαιώσει ακόμη συνολικά υπόλοιπα.")
                    }
                    TextButton(enabled = !busy, onClick = {
                        editing = !editing
                        if (editing) {
                            day = LocalDate.now().toString()
                            bank = ""
                            cash = ""
                            investments = ""
                            otherAssets = ""
                            loans = ""
                            otherDebts =
                                ""
                            auto = true
                            reviewed = false
                            message = ""
                        }
                    }) { Text(if (editing) "Κλείσιμο" else "Νέα αποτίμηση / διόρθωση") }
                    if (editing) {
                        Text(
                            "Όλα τα ποσά της ίδιας ημερομηνίας, σε ευρώ. Γράψε 0 όπου δεν υπάρχει υπόλοιπο.",
                        )
                        OutlinedTextField(
                            day,
                            {
                                day = it
                                reviewed = false
                            },
                            enabled = !busy,
                            label = { Text("Ημερομηνία ΕΕΕΕ-ΜΜ-ΗΗ") },
                        )
                        portfolio.firstOrNull()?.let { row ->
                            TextButton(enabled = !busy, onClick = {
                                day = row.day
                                reviewed = false
                            }) {
                                Text("Ημερομηνία τελευταίας Lightyear: ${row.day}")
                            }
                        }
                        val parsedDay = runCatching { LocalDate.parse(day) }.getOrNull()
                        val existing = history.find { it.day == day }
                        if (existing != null) {
                            Text(
                                "Υπάρχει ήδη αποτίμηση. Η αποθήκευση θα την αντικαταστήσει μόνο μετά από επιβεβαίωση.",
                            )
                            TextButton(enabled = !busy, onClick = {
                                bank = worthField(existing.bankMinor)
                                cash =
                                    worthField(existing.cashMinor)
                                investments = worthField(existing.lightyearMinor)
                                otherAssets =
                                    worthField(existing.otherAssetsMinor)
                                loans = worthField(existing.loanMinor)
                                otherDebts =
                                    worthField(existing.otherDebtsMinor)
                                auto = existing.fromLightyear
                                reviewed = false
                            }) { Text("Φόρτωση αποθηκευμένων ποσών") }
                        }
                        OutlinedTextField(
                            bank,
                            {
                                bank = it
                                reviewed = false
                            },
                            enabled = !busy,
                            label = { Text("Θετικά τραπεζικά υπόλοιπα (εκτός Lightyear)") },
                        )
                        OutlinedTextField(
                            cash,
                            {
                                cash = it
                                reviewed = false
                            },
                            enabled = !busy,
                            label = { Text("Μετρητά εκτός Lightyear") },
                        )
                        Row {
                            Checkbox(auto, {
                                auto = it
                                reviewed = false
                            }, enabled = !busy)
                            Text("Χρήση αποτίμησης Lightyear ίδιας ημερομηνίας")
                        }
                        val autoValue = parsedDay?.let { date ->
                            NetWorth.lightyearForDay(
                                date,
                                portfolio.map {
                                    PortfolioSnapshot(
                                        LocalDate.parse(it.day),
                                        it.valueMinor,
                                        it.depositedMinor,
                                        it.withdrawnMinor,
                                    )
                                },
                            )
                        }
                        if (auto) {
                            Text(
                                autoValue?.let {
                                    "Lightyear: " + worthEuro(BigDecimal.valueOf(it))
                                }
                                    ?: "Δεν υπάρχει αποτίμηση Lightyear αυτής της ημερομηνίας.",
                            )
                        } else {
                            OutlinedTextField(
                                investments,
                                {
                                    investments = it
                                    reviewed = false
                                },
                                enabled = !busy,
                                label = { Text("Συνολική αξία Lightyear ή 0") },
                            )
                        }
                        OutlinedTextField(
                            otherAssets,
                            {
                                otherAssets = it
                                reviewed = false
                            },
                            enabled = !busy,
                            label = { Text("Λοιπά περιουσιακά στοιχεία (π.χ. ακίνητα, όχημα)") },
                        )
                        OutlinedTextField(
                            loans,
                            {
                                loans = it
                                reviewed = false
                            },
                            enabled = !busy,
                            label = { Text("Συνολικό ανεξόφλητο υπόλοιπο δανείων") },
                        )
                        OutlinedTextField(
                            otherDebts,
                            {
                                otherDebts = it
                                reviewed = false
                            },
                            enabled = !busy,
                            label = { Text("Λοιπές οφειλές, κάρτες και αρνητικά υπόλοιπα") },
                        )
                        val preview = runCatching {
                            NetWorthSnapshot(
                                checkNotNull(parsedDay),
                                worthAmount(bank),
                                worthAmount(cash),
                                if (auto) checkNotNull(autoValue) else worthAmount(investments),
                                worthAmount(otherAssets),
                                worthAmount(loans),
                                worthAmount(otherDebts),
                                auto,
                            )
                                .also { require(!it.day.isAfter(LocalDate.now())) }
                        }.getOrNull()
                        // Reset confirmation if source data changes, even without a field edit.
                        LaunchedEffect(preview, existing) { reviewed = false }
                        preview?.let {
                            Text("Νέα καθαρή περιουσία: " + worthEuro(it.netWorthMinor))
                        }
                        Row {
                            Checkbox(reviewed, {
                                reviewed = it
                            }, enabled = !busy && preview != null)
                            Text(
                                "Επιβεβαιώνω πλήρη υπόλοιπα, χωρίς διπλομέτρηση, και τυχόν αντικατάσταση της ίδιας ημερομηνίας.",
                            )
                        }
                        Button(enabled = !busy && reviewed && preview != null, onClick = {
                            val snapshot = preview ?: return@Button
                            busy = true
                            scope.launch {
                                try {
                                    model.saveNetWorth(snapshot, existing)
                                    message = "Η αποτίμηση αποθηκεύτηκε."
                                    editing = false
                                    reviewed = false
                                } catch (
                                    e: CancellationException,
                                ) {
                                    throw e
                                } catch (e: Exception) {
                                    message =
                                        "Η αποθήκευση απέτυχε. Επανέλεγξε τα υπόλοιπα και την ημερομηνία."
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("Αποθήκευση καθαρής περιουσίας") }
                    }
                }
            }
            Text(message)
            Text(
                "Δεν είναι μηνιαίο κέρδος ή διαθέσιμο εισόδημα. Τα μετρητά της Lightyear περιλαμβάνονται μόνο στη Lightyear. Μελλοντικά ενοίκια και δόσεις δεν είναι ανεξόφλητο κεφάλαιο.",
            )
        }
    }
}

private fun NetWorthRow.worth() = NetWorthSnapshot(
    LocalDate.parse(day),
    bankMinor,
    cashMinor,
    lightyearMinor,
    otherAssetsMinor,
    loanMinor,
    otherDebtsMinor,
    fromLightyear,
)
private fun worthAmount(text: String) = BankLedger.amountMinor(text, if (',' in text) ',' else '.')
private fun worthField(minor: Long) = BigDecimal.valueOf(minor, 2).toPlainString()
private fun worthEuro(minor: BigDecimal) =
    minor.movePointLeft(2).toPlainString().replace('.', ',') + " €"
