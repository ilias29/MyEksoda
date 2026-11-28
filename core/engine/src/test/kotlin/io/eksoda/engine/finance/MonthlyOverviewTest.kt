package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthlyOverviewTest {
    private val month = YearMonth.of(2026, 11)

    private fun entry(
        source: FinanceSource,
        amount: Long,
        kind: MovementKind,
        category: SpendingCategory = SpendingCategory.UNCLASSIFIED,
        date: LocalDate = month.atDay(1),
    ) = LedgerEntry(
        source,
        BankMovement("account", date, "sample", amount, kind, category = category),
    )

    @Test
    fun combinesSourcesAndExcludesTransfersAndOtherMonths() {
        val overview = MonthlyLedger.summarize(
            month,
            listOf(
                entry(FinanceSource.BANK, 120_000, MovementKind.INCOME),
                entry(FinanceSource.CASH, 10_000, MovementKind.INCOME),
                entry(FinanceSource.BANK, -45_000, MovementKind.EXPENSE, SpendingCategory.RENT),
                entry(
                    FinanceSource.BANK,
                    -12_000,
                    MovementKind.EXPENSE,
                    SpendingCategory.LOAN_PAYMENT,
                ),
                entry(FinanceSource.CASH, -3_000, MovementKind.EXPENSE),
                entry(FinanceSource.BANK, 2_000, MovementKind.REFUND),
                entry(FinanceSource.BANK, -20_000, MovementKind.TRANSFER),
                entry(FinanceSource.BANK, 20_000, MovementKind.TRANSFER),
                entry(
                    FinanceSource.BANK,
                    99_999,
                    MovementKind.INCOME,
                    date = month.minusMonths(1).atEndOfMonth(),
                ),
            ),
        )
        assertEquals(130_000L, overview.incomeMinor)
        assertEquals(10_000L, overview.cashIncomeMinor)
        assertEquals(60_000L, overview.grossExpensesMinor)
        assertEquals(58_000L, overview.netExpensesMinor)
        assertEquals(72_000L, overview.netFlowMinor)
        assertEquals(45_000L, overview.rentMinor)
        assertEquals(12_000L, overview.loanPaymentsMinor)
        assertEquals(6, overview.movementCount)
    }

    @Test
    fun emptyAndNegativeMonthsAreNotBalances() {
        assertEquals(0L, MonthlyLedger.summarize(month, emptyList()).netFlowMinor)
        assertEquals(
            -100L,
            MonthlyLedger.summarize(
                month,
                listOf(
                    entry(FinanceSource.CASH, -100, MovementKind.EXPENSE),
                ),
            ).netFlowMinor,
        )
        assertEquals(
            -100L,
            MonthlyLedger.summarize(
                month,
                listOf(
                    entry(FinanceSource.BANK, 100, MovementKind.REFUND),
                ),
            ).netExpensesMinor,
        )
    }

    @Test
    fun overflowIsNotSilentlyWrapped() {
        assertThrows(ArithmeticException::class.java) {
            MonthlyLedger.summarize(
                month,
                listOf(
                    entry(FinanceSource.BANK, Long.MIN_VALUE, MovementKind.EXPENSE),
                ),
            )
        }
    }
}
