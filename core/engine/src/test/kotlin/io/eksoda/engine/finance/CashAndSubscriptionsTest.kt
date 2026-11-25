package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CashAndSubscriptionsTest {
    @Test
    fun manuallyEnteredIncomeCountsAndRejectsInvalidSign() {
        val date = LocalDate.of(2026, 11, 25)
        val income = ManualCashEntry(date, "Cash", 12_000, MovementKind.INCOME).toMovement("cash")
        val rent = ManualCashEntry(date, "Rent", -50_000, MovementKind.EXPENSE, SpendingCategory.RENT)
            .toMovement("cash")
        assertEquals(CashFlow(12_000, 50_000), BankLedger.summarize(listOf(income, rent)))
        assertThrows(IllegalArgumentException::class.java) {
            ManualCashEntry(date, "invalid", -1, MovementKind.INCOME)
        }
    }

    @Test
    fun twoMatchingPaymentsWithinThreeMonthsPromptForConfirmation() {
        fun payment(month: Int, description: String = "Viber") =
            BankMovement("bank", LocalDate.of(2026, month, 5), description, -599, MovementKind.EXPENSE)
        assertTrue(Subscriptions.suggest(listOf(payment(1))).isEmpty())
        assertEquals(SubscriptionSuggestion("VIBER", 599, 2),
            Subscriptions.suggest(listOf(payment(1), payment(3))).single())
        assertTrue(Subscriptions.suggest(listOf(payment(1), payment(4))).isEmpty())
        assertTrue(Subscriptions.suggest(listOf(payment(1), payment(1))).isEmpty())
        val manual = SubscriptionRecord("Viber", 599, confirmedByUser = true)
        assertEquals(599, manual.expectedMonthlyMinor)
    }
}
