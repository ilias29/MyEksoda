package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BankImportPreviewTest {
    @Test
    fun invalidRowsBlockCommitAndIdentifyTheirLine() {
        val preview = BankCsv.preview(
            "date;description;amount;id\n25/11/2026;Ενοίκιο;-500,00;one\nwrong;Δόση δανείου;-200,00;two\n",
            "bank",
            CsvMapping(0, 1, 2, 3),
        )
        assertFalse(preview.canCommit)
        assertEquals(3, preview.problems.single().line)
        assertEquals(SpendingCategory.RENT, preview.movements.single().category)
    }

    @Test
    fun loanSuggestionCanBeCorrectedAndDisbursementIsNotARepayment() {
        val csv = "25/11/2026;Δόση δανείου;-200,00\n25/11/2026;Εκταμίευση δανείου;200,00"
        val preview = BankCsv.preview(csv, "bank", CsvMapping(0, 1, 2, hasHeader = false))
        assertTrue(preview.canCommit)
        assertEquals(SpendingCategory.LOAN_PAYMENT, preview.movements.first().category)
        assertEquals(SpendingCategory.UNCLASSIFIED, preview.movements.last().category)
        assertEquals(
            SpendingCategory.UTILITIES,
            preview.movements.first().copy(category = SpendingCategory.UTILITIES).category,
        )
    }

    @Test
    fun onlyMatchingSourceIdsAreDeduplicated() {
        val date = LocalDate.of(2026, 11, 25)
        fun payment(account: String, id: String?, amount: Long = -500L) =
            BankMovement(account, date, "Rent", amount, MovementKind.EXPENSE, id)
        val original = payment("account1", "bank-id")
        val result = BankImportReconciliation.reconcile(
            listOf(original),
            listOf(
                original,
                original,
                payment("account1", "bank-id", -600),
                payment("account2", "bank-id"),
                payment("account1", null),
                payment("account1", null),
            ),
        )
        assertEquals(2, result.alreadyImported.size)
        assertEquals(1, result.conflicts.size)
        assertEquals(3, result.newMovements.size)
        assertFalse(result.canCommit)
    }
}
