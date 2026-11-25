package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.core.database.entity.PortfolioSnapshotRow
import io.eksoda.engine.finance.BankLedger
import io.eksoda.engine.finance.InvestmentTracking
import io.eksoda.engine.finance.PortfolioSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

@Composable
internal fun LightyearPanel(model: AppViewModel) {
    val state by model.lightyearHistory.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf(LocalDate.now().toString()) }
    var value by remember { mutableStateOf("") }
    var deposits by remember { mutableStateOf("") }
    var withdrawals by remember { mutableStateOf("") }
    var instrument by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var holdingValue by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var portfolioForm by remember { mutableStateOf(false) }
    var holdingForm by remember { mutableStateOf(false) }
    var historyCount by remember { mutableIntStateOf(10) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Επενδύσεις · Lightyear", style = MaterialTheme.typography.headlineSmall)
        Text("Χειροκίνητες αποτιμήσεις σε ευρώ, όχι ζωντανή σύνδεση.")
        LightyearFileImport(model)
        var showOptionalMarket by remember { mutableStateOf(false) }
        TextButton(onClick = { showOptionalMarket = !showOptionalMarket }) {
            Text(if (showOptionalMarket) "Απόκρυψη προαιρετικού API" else "Προαιρετική παρακολούθηση μέσω API")
        }
        if (showOptionalMarket) MarketPricePanel()
        val result = state
        val data = result?.getOrNull()
        when {
            result == null -> Text("Φόρτωση…")
            data == null -> Text("Αδυναμία φόρτωσης.")
            else -> {
                val (portfolio, holdings) = data
                val latest = portfolio.firstOrNull()?.snapshot()
                if (latest != null) {
                    Text("Αξία χαρτοφυλακίου μαζί με μετρητά · ${latest.date}")
                    Text(investEuro(BigDecimal.valueOf(latest.valueMinor)), style = MaterialTheme.typography.headlineMedium)
                    Text("Καθαρές καταθέσεις: " + investEuro(latest.netFundingMinor))
                    Text("Συνολικό αποτέλεσμα έναντι καθαρών καταθέσεων: " + investEuro(latest.gainMinor))
                    portfolio.getOrNull(1)?.let { earlier ->
                        val change = runCatching { InvestmentTracking.change(earlier.snapshot(), latest) }.getOrNull()
                        change?.let { Text("Μεταβολή αποτελέσματος από ${earlier.day}: " + investEuro(it.gainChangeMinor)) }
                    }
                    Text("Ιστορικό αποτιμήσεων")
                    portfolio.take(historyCount).forEach {
                        Text(it.day + " · " + investEuro(BigDecimal.valueOf(it.valueMinor)) +
                            " · αποτέλεσμα " + investEuro(it.snapshot().gainMinor))
                    }
                    if (portfolio.size > historyCount) TextButton(onClick = { historyCount += 10 }) { Text("Περισσότερες") }
                }
                TextButton(enabled = !busy, onClick = {
                    portfolioForm = !portfolioForm
                    if (portfolioForm && latest != null) {
                        deposits = BigDecimal.valueOf(latest.depositedMinor, 2).toPlainString()
                        withdrawals = BigDecimal.valueOf(latest.withdrawnMinor, 2).toPlainString()
                    }
                }) { Text("Καταχώριση συνολικής αποτίμησης") }
                if (portfolioForm) {
                    OutlinedTextField(day, { day = it }, label = { Text("Ημερομηνία ΕΕΕΕ-ΜΜ-ΗΗ") }, enabled = !busy)
                    OutlinedTextField(value, { value = it }, label = { Text("Συνολική αξία μαζί με μετρητά €") }, enabled = !busy)
                    OutlinedTextField(deposits, { deposits = it }, label = { Text("Καταθέσεις από την έναρξη €") }, enabled = !busy)
                    OutlinedTextField(withdrawals, { withdrawals = it }, label = { Text("Αναλήψεις από την έναρξη €") }, enabled = !busy)
                    Text("Ίδια ημερομηνία: αντικαθιστά την προηγούμενη αποτίμηση. Οι εσωτερικές αγορές, πωλήσεις και επανεπενδύσεις δεν είναι καταθέσεις.")
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                model.saveLightyearSnapshot(PortfolioSnapshot(LocalDate.parse(day),
                                    investAmount(value), investAmount(deposits), investAmount(withdrawals)))
                                message = "Αποθηκεύτηκε."
                                portfolioForm = false
                                value = ""
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = "Έλεγξε τα ποσά, την ημερομηνία και τις σωρευτικές καταθέσεις/αναλήψεις." }
                            finally { busy = false }
                        }
                    }) { Text("Αποθήκευση αποτίμησης") }
                }
                Text("Τελευταίες παρατηρήσεις ανά τίτλο", style = MaterialTheme.typography.titleMedium)
                holdings.groupBy { it.instrument }.forEach { (id, history) ->
                    val row = history.first()
                    Text("$id · ${row.day} · ${row.quantity} τεμάχια")
                    Text("Αξία: " + investEuro(BigDecimal.valueOf(row.valueMinor)))
                    row.costMinor?.let { Text("Μη πραγματοποιημένο αποτέλεσμα (βάσει δηλωμένου κόστους): " +
                        investEuro(BigDecimal.valueOf(row.valueMinor) - BigDecimal.valueOf(it))) }
                    history.getOrNull(1)?.let { previous ->
                        Text("Προηγούμενη αξία ${previous.day}: " + investEuro(BigDecimal.valueOf(previous.valueMinor)))
                    }
                }
                TextButton(enabled = !busy, onClick = { holdingForm = !holdingForm }) { Text("Καταχώριση τίτλου") }
                if (holdingForm) {
                    OutlinedTextField(day, { day = it }, label = { Text("Ημερομηνία ΕΕΕΕ-ΜΜ-ΗΗ") }, enabled = !busy)
                    OutlinedTextField(instrument, { instrument = it }, label = { Text("ISIN ή σύμβολο + χρηματιστήριο") }, enabled = !busy)
                    OutlinedTextField(quantity, { quantity = it }, label = { Text("Ποσότητα (0 για κλεισμένη θέση)") }, enabled = !busy)
                    OutlinedTextField(holdingValue, { holdingValue = it }, label = { Text("Αξία θέσης €") }, enabled = !busy)
                    OutlinedTextField(cost, { cost = it }, label = { Text("Κόστος ανοικτής θέσης € (προαιρετικό)") }, enabled = !busy)
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                model.saveLightyearHolding(instrument, LocalDate.parse(day),
                                    BigDecimal(quantity.trim().replace(',', '.')), investAmount(holdingValue),
                                    cost.takeIf { it.isNotBlank() }?.let(::investAmount))
                                message = "Αποθηκεύτηκε ο τίτλος."
                                holdingForm = false
                                instrument = ""; quantity = ""; holdingValue = ""; cost = ""
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = "Έλεγξε τα στοιχεία του τίτλου." }
                            finally { busy = false }
                        }
                    }) { Text("Αποθήκευση τίτλου") }
                }
            }
        }
        Text(message)
        Text("Οι αποτιμήσεις δεν αποτελούν έσοδα/έξοδα ούτε φορολογικά κέρδη. Οι επιμέρους τίτλοι δεν προστίθενται ξανά στη συνολική αξία. Για αποτέλεσμα από την έναρξη απαιτούνται πλήρεις καταθέσεις και αναλήψεις, χωρίς μη καταγεγραμμένες μεταφορές τίτλων.")
    }
}

private fun PortfolioSnapshotRow.snapshot() = PortfolioSnapshot(LocalDate.parse(day), valueMinor, depositedMinor, withdrawnMinor)
private fun investAmount(text: String) = BankLedger.amountMinor(text, if (',' in text) ',' else '.')
private fun investEuro(minor: BigDecimal) = minor.movePointLeft(2).toPlainString().replace('.', ',') + " €"
