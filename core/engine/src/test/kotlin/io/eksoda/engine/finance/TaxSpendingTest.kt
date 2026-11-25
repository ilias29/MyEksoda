package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class TaxSpendingTest {
    private val plan = AnnualTaxPlan(2026, 1_500_000, TaxTargetMode.STANDARD, false)

    private fun row(
        id: String,
        amount: Long,
        review: TaxReview = TaxReview.ELIGIBLE,
        kind: MovementKind = MovementKind.EXPENSE,
        category: SpendingCategory = SpendingCategory.UNCLASSIFIED,
        year: Int = 2026,
        account: String = "A",
    ) = TaxPayment(
        BankMovement(account, LocalDate.of(year, 3, 1), "sample", amount, kind, id, category), review,
    )

    private fun calculate(p: AnnualTaxPlan = plan, vararg rows: TaxPayment) =
        TaxSpending.summarize(p, rows.asList())

    private fun money(expected: Long, actual: BigDecimal) =
        assertEquals(0, actual.compareTo(BigDecimal.valueOf(expected)))

    @Test
    fun targetsAndCaps() {
        money(450_000, calculate().targetMinor)
        money(300_000, calculate(plan.copy(mode = TaxTargetMode.REDUCED)).targetMinor)
        money(0, calculate(plan.copy(mode = TaxTargetMode.EXEMPT)).targetMinor)
        money(2_000_000, calculate(plan.copy(incomeMinor = 10_000_000)).targetMinor)
        money(500_000, calculate(plan.copy(incomeMinor = 10_000_000, specialCap = true)).targetMinor)
    }

    @Test
    fun eligibleAmountsRefundsAndPending() {
        val result = calculate(
            plan,
            row("1", -300_000),
            row("2", -50_000, TaxReview.DOUBLE_HEALTH),
            row("3", 10_000, TaxReview.DOUBLE_HEALTH, MovementKind.REFUND),
            row("4", -100, TaxReview.PENDING),
            row("5", -900_000, TaxReview.EXCLUDED),
            row("6", -45_000, category = SpendingCategory.RENT),
            row("7", -30_000, category = SpendingCategory.LOAN_PAYMENT),
            row("8", -100_000, kind = MovementKind.TRANSFER),
            row("9", 100_000, kind = MovementKind.INCOME),
            row("10", -999_999, year = 2025),
        )
        money(340_000, result.actualMinor)
        money(380_000, result.weightedMinor)
        money(70_000, result.remainingMinor)
        money(15_400, result.potentialExtraTaxMinor)
        assertEquals(1, result.pendingCount)
        money(0, calculate(plan, row("1", -500_000)).remainingMinor)
        money(450_000, calculate(plan, row("1", 50_000, kind = MovementKind.REFUND)).remainingMinor)
    }

    @Test
    fun duplicateAndInvalidPlansRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            calculate(plan, row("1", -100), row("1", -100))
        }
        money(200, calculate(plan, row("1", -100), row("1", -100, account = "B")).actualMinor)
        assertThrows(IllegalArgumentException::class.java) { plan.copy(year = 2028) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(incomeMinor = -1) }
    }
}
