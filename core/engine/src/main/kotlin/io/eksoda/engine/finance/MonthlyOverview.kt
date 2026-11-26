package io.eksoda.engine.finance

import java.time.YearMonth

public enum class FinanceSource { BANK, CASH }

public data class LedgerEntry(val source: FinanceSource, val movement: BankMovement)

public data class MonthlyOverview(
    val bankIncomeMinor: Long,
    val cashIncomeMinor: Long,
    val bankExpensesMinor: Long,
    val cashExpensesMinor: Long,
    val refundsMinor: Long,
    val rentMinor: Long,
    val loanPaymentsMinor: Long,
    val movementCount: Int,
) {
    public val incomeMinor: Long get() = Math.addExact(bankIncomeMinor, cashIncomeMinor)
    public val grossExpensesMinor: Long get() = Math.addExact(bankExpensesMinor, cashExpensesMinor)
    public val netExpensesMinor: Long get() = Math.subtractExact(grossExpensesMinor, refundsMinor)
    public val netFlowMinor: Long get() = Math.subtractExact(incomeMinor, netExpensesMinor)
}

/** Cash flow, not an account balance. Subscription forecasts are deliberately not movements. */
public object MonthlyLedger {
    public fun summarize(month: YearMonth, entries: Iterable<LedgerEntry>): MonthlyOverview {
        var bankIncome = 0L
        var cashIncome = 0L
        var bankExpenses = 0L
        var cashExpenses = 0L
        var refunds = 0L
        var rent = 0L
        var loans = 0L
        var count = 0
        for ((source, movement) in entries) {
            if (YearMonth.from(movement.date) != month ||
                movement.kind == MovementKind.TRANSFER
            ) {
                continue
            }
            count = Math.incrementExact(count)
            when (movement.kind) {
                MovementKind.INCOME -> {
                    if (source ==
                        FinanceSource.BANK
                    ) {
                        bankIncome =
                            Math.addExact(bankIncome, movement.amountMinor)
                    } else {
                        cashIncome = Math.addExact(cashIncome, movement.amountMinor)
                    }
                }

                MovementKind.EXPENSE -> {
                    val debit = Math.negateExact(movement.amountMinor)
                    if (source ==
                        FinanceSource.BANK
                    ) {
                        bankExpenses = Math.addExact(bankExpenses, debit)
                    } else {
                        cashExpenses = Math.addExact(cashExpenses, debit)
                    }
                    when (movement.category) {
                        SpendingCategory.RENT -> rent = Math.addExact(rent, debit)
                        SpendingCategory.LOAN_PAYMENT -> loans = Math.addExact(loans, debit)
                        else -> Unit
                    }
                }

                MovementKind.REFUND -> refunds = Math.addExact(refunds, movement.amountMinor)

                MovementKind.TRANSFER -> Unit
            }
        }
        return MonthlyOverview(
            bankIncome,
            cashIncome,
            bankExpenses,
            cashExpenses,
            refunds,
            rent,
            loans,
            count,
        )
    }
}
