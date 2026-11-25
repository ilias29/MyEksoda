package io.eksoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.eksoda.engine.finance.AdviceKind
import io.eksoda.engine.finance.FinanceObservation
import io.eksoda.engine.finance.SpendingCategory
import java.time.LocalDate

@Composable
internal fun FinanceAdvisorPanel(model: AppViewModel) {
    val today = LocalDate.now()
    var confirmed by remember(today) { mutableStateOf(false) }
    val stream = remember(model, today, confirmed) { model.financeAdvice(today, confirmed) }
    key(today, confirmed) {
        val state by stream.collectAsStateWithLifecycle(initialValue = null)
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Οικονομικός σύμβουλος", style = MaterialTheme.typography.headlineSmall)
                Text("Τοπικοί κανόνες από τα καταγεγραμμένα στοιχεία, όχι τραπεζικό υπόλοιπο ή εξατομικευμένη επενδυτική συμβουλή.")
                Row {
                    Checkbox(confirmed, { confirmed = it })
                    Text("Έχω ενημερώσει όλα τα έσοδα/έξοδα για τον τρέχοντα και προηγούμενο μήνα έως τις αντίστοιχες ημέρες.")
                }
                val result = state
                val advice = result?.getOrNull()
                when {
                    result == null -> Text("Φόρτωση…")
                    advice == null -> Text("Αδυναμία αξιολόγησης. Έλεγξε τα δεδομένα.")
                    else -> {
                        if (advice.observations.isEmpty()) Text("Δεν προέκυψε κάποια παρατήρηση από τα διαθέσιμα στοιχεία.")
                        advice.observations.forEach { observation ->
                            val warning = observation.kind in setOf(AdviceKind.NEGATIVE_FLOW,
                                AdviceKind.BUDGET_EXCEEDED, AdviceKind.FORECAST_OVER,
                                AdviceKind.PAYDAY_SHORTFALL, AdviceKind.PLAN_CONFLICT)
                            Text(adviceText(observation, advice.comparedDays),
                                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                        if (advice.categories.isNotEmpty()) {
                            Text("Μεγαλύτερες κατηγορίες εξόδων πριν από επιστροφές",
                                style = MaterialTheme.typography.titleMedium)
                            advice.categories.take(5).forEach {
                                Text(categoryLabel(it.category) + ": " +
                                    it.amountMinor.movePointLeft(2).toPlainString() + " €")
                            }
                        }
                    }
                }
                Text("Οι εκτιμήσεις ρυθμού δαπανών είναι απλές αναγωγές και μπορεί να αλλάξουν. Οι συνδρομές δεν αφαιρούνται δεύτερη φορά.")
            }
        }
    }
}

private fun categoryLabel(category: SpendingCategory) = when (category) {
    SpendingCategory.RENT -> "Ενοίκιο"
    SpendingCategory.LOAN_PAYMENT -> "Δόσεις δανείου"
    SpendingCategory.GROCERIES -> "Τρόφιμα"
    SpendingCategory.UTILITIES -> "Λογαριασμοί"
    SpendingCategory.UNCLASSIFIED -> "Χωρίς κατηγορία"
}

private fun adviceText(item: FinanceObservation, days: Int): String {
    val amount = item.amountMinor?.movePointLeft(2)?.toPlainString()?.replace('.', ',')?.plus(" €").orEmpty()
    return when (item.kind) {
        AdviceKind.NO_MOVEMENTS -> "Δεν υπάρχουν καταγεγραμμένες κινήσεις αυτόν τον μήνα. Ενημέρωσε τα στοιχεία."
        AdviceKind.NEGATIVE_FLOW -> "Τα καταγεγραμμένα έξοδα υπερβαίνουν τα έσοδα κατά $amount."
        AdviceKind.BUDGET_EXCEEDED -> "Ξεπέρασες τον προϋπολογισμό κατά $amount."
        AdviceKind.BUDGET_NEAR -> "Πλησιάζεις ή έφτασες το όριο. Απομένουν $amount."
        AdviceKind.FORECAST_OVER -> "Με τον καταγεγραμμένο ρυθμό, εκτιμώμενη υπέρβαση $amount."
        AdviceKind.SAVINGS_GAP -> "Η εκτίμηση αποταμίευσης υπολείπεται του στόχου κατά $amount."
        AdviceKind.SAVINGS_ON_TRACK -> "Η εκτίμηση αποταμίευσης καλύπτει τον στόχο, αν τηρηθούν οι υποθέσεις."
        AdviceKind.PLAN_CONFLICT -> "Το όριο εξόδων και το αναμενόμενο εισόδημα δεν επαρκούν μαζί για τον στόχο αποταμίευσης. Αναθεώρησε το πλάνο."
        AdviceKind.UNCLASSIFIED -> "Έξοδα $amount χωρίς κατηγορία. Ταξινόμησέ τα για χρήσιμη ανάλυση."
        AdviceKind.SPENDING_UP -> "Καθαρά καταγεγραμμένα έξοδα αυξημένα κατά $amount στις πρώτες $days ημέρες σε σχέση με τον προηγούμενο μήνα."
        AdviceKind.SPENDING_DOWN -> "Καθαρά καταγεγραμμένα έξοδα μειωμένα κατά $amount στις πρώτες $days ημέρες σε σχέση με τον προηγούμενο μήνα."
        AdviceKind.SUBSCRIPTIONS -> "Δηλωμένες μηνιαίες συνδρομές $amount. Έλεγξε ποιες χρησιμοποιείς."
        AdviceKind.WORTH_UP -> "Η καθαρή περιουσία αυξήθηκε κατά $amount μεταξύ των δύο τελευταίων αποτιμήσεων."
        AdviceKind.WORTH_DOWN -> "Η καθαρή περιουσία μειώθηκε κατά $amount μεταξύ των δύο τελευταίων αποτιμήσεων."
        AdviceKind.PAYDAY_SHORTFALL -> "Λείπουν $amount για τις καταχωρισμένες υποχρεώσεις πριν τον μισθό."
        AdviceKind.PAYDAY_REVIEW -> "Ενημέρωσε το σημερινό διαθέσιμο υπόλοιπο για πρόβλεψη μέχρι τον μισθό."
    }
}
