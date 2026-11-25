package io.eksoda.engine.finance

import java.math.BigDecimal
import java.time.LocalDate

enum class MovementKind { INCOME, EXPENSE, TRANSFER, REFUND }

data class BankMovement(
    val accountId: String,
    val date: LocalDate,
    val description: String,
    val amountMinor: Long,
    val kind: MovementKind,
    val sourceId: String? = null,
) {
    init {
        require(accountId.isNotBlank())
        require(amountMinor != 0L)
        require(kind != MovementKind.INCOME || amountMinor > 0)
        require(kind != MovementKind.REFUND || amountMinor > 0)
        require(kind != MovementKind.EXPENSE || amountMinor < 0)
    }
}

data class CashFlow(val incomeMinor: Long, val spendingMinor: Long)

object BankLedger {
    fun amountMinor(value: String, decimalSeparator: Char): Long {
        require(decimalSeparator == '.' || decimalSeparator == ',')
        val normalized = value.trim().replace(decimalSeparator, '.')
        require(Regex("[+-]?[0-9]+(\\.[0-9]{1,2})?").matches(normalized))
        return BigDecimal(normalized).movePointRight(2).longValueExact()
    }

    fun summarize(movements: Iterable<BankMovement>): CashFlow {
        var income = 0L
        var spending = 0L
        for (movement in movements) {
            when (movement.kind) {
                MovementKind.INCOME -> income = Math.addExact(income, movement.amountMinor)
                MovementKind.EXPENSE, MovementKind.REFUND ->
                    spending = Math.subtractExact(spending, movement.amountMinor)
                MovementKind.TRANSFER -> Unit
            }
        }
        return CashFlow(income, spending)
    }
}
