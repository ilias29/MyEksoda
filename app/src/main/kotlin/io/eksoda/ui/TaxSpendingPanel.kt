package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.engine.finance.AnnualTaxPlan
import io.eksoda.engine.finance.BankLedger
import io.eksoda.engine.finance.BankMovement
import io.eksoda.engine.finance.MovementKind
import io.eksoda.engine.finance.SpendingCategory
import io.eksoda.engine.finance.TaxPayment
import io.eksoda.engine.finance.TaxReview
import io.eksoda.engine.finance.TaxSpending
import io.eksoda.engine.finance.TaxTargetMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

@Composable
internal fun TaxSpendingPanel(model: AppViewModel) {
    val today = LocalDate.now()
    val latestYear = minOf(today.year, 2027)
    if (latestYear < 2022) return
    var year by remember { mutableIntStateOf(latestYear) }
    val stream = remember(model, year, today) { model.taxData(year, today) }
    key(year) {
        val result by stream.collectAsStateWithLifecycle(initialValue = null)
        val scope = rememberCoroutineScope()
        var income by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(TaxTargetMode.STANDARD) }
        var cap by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var page by remember { mutableIntStateOf(0) }
        var pendingOnly by remember { mutableStateOf(true) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Αφορολόγητο · ηλεκτρονικές δαπάνες",
                style = MaterialTheme.typography.headlineSmall,
            )
            Row {
                TextButton(enabled = !busy && year > 2022, onClick = { year-- }) { Text("‹") }
                Text(year.toString())
                TextButton(enabled = !busy && year < latestYear, onClick = { year++ }) { Text("›") }
            }
            Text(
                "Εκτίμηση για ένα άτομο από ελεγμένες τραπεζικές κινήσεις. Δεν προστίθεται στο ποσό της ενότητας αποδείξεων.",
            )
            if (today.year > 2027) Text("Οι κανόνες μετά το 2027 χρειάζονται ενημέρωση.")
            val state = result
            val data = state?.getOrNull()
            when {
                state == null -> Text("Φόρτωση…")

                data == null -> Text("Αδυναμία φόρτωσης.")

                else -> {
                    val (stored, rows) = data
                    val plan = stored?.let {
                        AnnualTaxPlan(
                            it.year,
                            it.incomeMinor,
                            TaxTargetMode.valueOf(it.mode),
                            it.specialCap,
                        )
                    }
                    val summary = plan?.let { p ->
                        runCatching {
                            TaxSpending.summarize(
                                p,
                                rows.map { r ->
                                    TaxPayment(
                                        BankMovement(
                                            r.account,
                                            LocalDate.parse(r.day),
                                            r.description,
                                            r.amount,
                                            MovementKind.valueOf(r.kind),
                                            r.reference,
                                            SpendingCategory.valueOf(r.category),
                                        ),
                                        TaxReview.valueOf(r.review),
                                    )
                                },
                            )
                        }.getOrNull()
                    }
                    if (summary != null) {
                        Text("Στόχος: " + taxEuro(summary.targetMinor))
                        Text("Καθαρές επιλέξιμες πληρωμές: " + taxEuro(summary.actualMinor))
                        Text("Προσμετρούμενο ποσό: " + taxEuro(summary.weightedMinor))
                        Text("Υπόλοιπο στόχου: " + taxEuro(summary.remainingMinor))
                        Text(
                            "Ενδεικτική πρόσθετη επιβάρυνση: " +
                                taxEuro(summary.potentialExtraTaxMinor),
                        )
                    } else if (plan == null) {
                        Text("Όρισε ετήσιο εισόδημα και ισχύοντα κανόνα.")
                    } else {
                        Text("Αδυναμία υπολογισμού. Έλεγξε τα δεδομένα.")
                    }
                    if (!editing) {
                        TextButton(enabled = !busy, onClick = {
                            income =
                                plan?.let { BigDecimal.valueOf(it.incomeMinor, 2).toPlainString() }
                                    ?: ""
                            mode = plan?.mode ?: TaxTargetMode.STANDARD
                            cap = plan?.specialCap ?: false
                            editing = true
                        }) { Text("Ρυθμίσεις φορολογικού έτους") }
                    } else {
                        OutlinedTextField(
                            income,
                            { income = it },
                            enabled = !busy,
                            label = { Text("Σχετικό ετήσιο πραγματικό εισόδημα €") },
                        )
                        Text("Κανόνας: ${mode.percent}%")
                        TextButton(enabled = !busy, onClick = {
                            mode = when (mode) {
                                TaxTargetMode.STANDARD -> TaxTargetMode.REDUCED
                                TaxTargetMode.REDUCED -> TaxTargetMode.EXEMPT
                                TaxTargetMode.EXEMPT -> TaxTargetMode.STANDARD
                            }
                        }) { Text("Αλλαγή κανόνα 30% / 20% / απαλλαγή") }
                        Text(
                            "20% μόνο αν πληρούνται οι νόμιμες προϋποθέσεις. Απαλλαγή και ειδικό πλαφόν μόνο μετά από επιβεβαίωση.",
                        )
                        Row {
                            Checkbox(cap, { cap = it }, enabled = !busy)
                            Text(
                                "Ειδικό πλαφόν 5.000 € (κατασχεμένοι λογαριασμοί, όπου εφαρμόζεται)",
                            )
                        }
                        Button(enabled = !busy, onClick = {
                            val candidate = runCatching {
                                AnnualTaxPlan(
                                    year,
                                    BankLedger.amountMinor(
                                        income,
                                        if (',' in
                                            income
                                        ) {
                                            ','
                                        } else {
                                            '.'
                                        },
                                    ),
                                    mode,
                                    cap,
                                )
                            }.getOrNull()
                            if (candidate == null) {
                                message = "Έλεγξε το εισόδημα."
                            } else {
                                busy = true
                                scope.launch {
                                    try {
                                        model.saveTaxPlan(candidate)
                                        editing = false
                                        message = "Αποθηκεύτηκε."
                                    } catch (
                                        e: CancellationException,
                                    ) {
                                        throw e
                                    } catch (
                                        e: Exception,
                                    ) {
                                        message = "Αποτυχία αποθήκευσης."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }) { Text("Αποθήκευση") }
                        TextButton(enabled = !busy, onClick = {
                            editing = false
                        }) { Text("Ακύρωση") }
                    }
                    Text("Εκκρεμείς έλεγχοι: ${rows.count { it.review == "PENDING" }}")
                    Text(
                        "Επιβεβαίωσε μόνο επιλέξιμες ηλεκτρονικές πληρωμές. Οι επιστροφές αφαιρούνται. Διπλή προσμέτρηση μόνο για τις νόμιμες ειδικές υπηρεσίες υγείας.",
                    )
                    TextButton(onClick = {
                        pendingOnly = !pendingOnly
                        page = 0
                    }) {
                        Text(if (pendingOnly) "Εμφάνιση όλων" else "Μόνο εκκρεμείς")
                    }
                    val visible = if (pendingOnly) rows.filter { it.review == "PENDING" } else rows
                    val start = page.coerceAtMost((visible.size - 1).coerceAtLeast(0) / 10) * 10
                    visible.drop(start).take(10).forEach { r ->
                        key(r.account, r.reference) {
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                r.day + " · " + r.account + " · " + r.description + " · " +
                                    taxEuro(BigDecimal.valueOf(r.amount)),
                            )
                            r.receiptMerchant?.let { Text("Συνδεδεμένη απόδειξη: $it") }
                            Box {
                                TextButton(enabled = !busy, onClick = { expanded = true }) {
                                    Text(taxReviewLabel(TaxReview.valueOf(r.review)) + " · αλλαγή")
                                }
                                DropdownMenu(expanded, { expanded = false }) {
                                    TaxReview.entries.forEach { choice ->
                                        DropdownMenuItem(text = {
                                            Text(taxReviewLabel(choice))
                                        }, onClick = {
                                            expanded = false
                                            busy = true
                                            scope.launch {
                                                try {
                                                    model.reviewTax(r, choice)
                                                    message = "Η ταξινόμηση αποθηκεύτηκε."
                                                } catch (
                                                    e: CancellationException,
                                                ) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    message =
                                                        "Η κίνηση άλλαξε ή δεν αποθηκεύτηκε."
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                    Row {
                        if (start >
                            0
                        ) {
                            TextButton(onClick = {
                                page = start / 10 - 1
                            }) { Text("Προηγούμενες") }
                        }
                        if (start + 10 <
                            visible.size
                        ) {
                            TextButton(onClick = { page = start / 10 + 1 }) { Text("Επόμενες") }
                        }
                    }
                }
            }
            Text(message)
            Text(
                "Έτος με βάση την ημερομηνία της τραπεζικής κίνησης. Μη εισαγόμενες κινήσεις και αποδείξεις χωρίς τραπεζική αντιστοίχιση δεν περιλαμβάνονται. Δεν αποτελεί φορολογική δήλωση.",
            )
        }
    }
}

private fun taxEuro(minor: BigDecimal) =
    minor.movePointLeft(2).toPlainString().replace('.', ',') + " €"

private fun taxReviewLabel(review: TaxReview): String = when (review) {
    TaxReview.PENDING -> "Για έλεγχο"
    TaxReview.EXCLUDED -> "Δεν προσμετράται"
    TaxReview.ELIGIBLE -> "Επιλέξιμη δαπάνη / επιστροφή"
    TaxReview.DOUBLE_HEALTH -> "Ειδική υπηρεσία υγείας ×2"
}
