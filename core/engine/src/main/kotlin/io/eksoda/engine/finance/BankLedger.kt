package io.eksoda.engine.finance

import java.math.BigDecimal
import java.time.LocalDate

public enum class MovementKind { INCOME, EXPENSE, TRANSFER, REFUND }

/** Categories are editable: an import suggestion must never replace a user's correction. */
public enum class SpendingCategory { UNCLASSIFIED, RENT, LOAN_PAYMENT, GROCERIES, UTILITIES }

public data class BankMovement(
    val accountId: String,
    val date: LocalDate,
    val description: String,
    val amountMinor: Long,
    val kind: MovementKind,
    val sourceId: String? = null,
    val category: SpendingCategory = SpendingCategory.UNCLASSIFIED,
) {
    init {
        require(accountId.isNotBlank())
        require(amountMinor != 0L)
        require(kind != MovementKind.INCOME || amountMinor > 0)
        require(kind != MovementKind.REFUND || amountMinor > 0)
        require(kind != MovementKind.EXPENSE || amountMinor < 0)
        require(kind == MovementKind.EXPENSE || category == SpendingCategory.UNCLASSIFIED)
    }
}

public data class CashFlow(val incomeMinor: Long, val spendingMinor: Long)

public object BankLedger {
    public fun amountMinor(value: String, decimalSeparator: Char): Long {
        require(decimalSeparator == '.' || decimalSeparator == ',')
        val normalized = value.trim().replace(decimalSeparator, '.')
        require(Regex("[+-]?[0-9]+(\\.[0-9]{1,2})?").matches(normalized))
        return BigDecimal(normalized).movePointRight(2).longValueExact()
    }

    public fun summarize(movements: Iterable<BankMovement>): CashFlow {
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

public object SpendingClassifier {
    /** Suggest only unmistakable descriptions; leave all other payments for manual review. */
    public fun suggest(movement: BankMovement): SpendingCategory {
        if (movement.kind != MovementKind.EXPENSE) return SpendingCategory.UNCLASSIFIED
        val text = java.text.Normalizer.normalize(
            movement.description,
            java.text.Normalizer.Form.NFD,
        )
            .replace(Regex("\\p{M}+"), "").uppercase(java.util.Locale.ROOT)
        return when {
            Regex("\\b(ΕΝΟΙΚΙΟ|ΕΝΟΙΚΙΑ|RENT)\\b").containsMatchIn(text) -> SpendingCategory.RENT

            Regex(
                "\\b(ΔΟΣΗ ΔΑΝΕΙΟΥ|ΔΟΣΗ ΣΤΕΓΑΣΤΙΚΟΥ|LOAN PAYMENT|LOAN INSTALMENT|LOAN INSTALLMENT)\\b",
            )
                .containsMatchIn(text) -> SpendingCategory.LOAN_PAYMENT

            else -> SpendingCategory.UNCLASSIFIED
        }
    }
}
