package io.eksoda.engine.finance

import java.time.LocalDate

/** A user-entered cash movement contributes to the personal ledger without a bank connection. */
public data class ManualCashEntry(
    val date: LocalDate,
    val description: String,
    val amountMinor: Long,
    val kind: MovementKind,
    val category: SpendingCategory = SpendingCategory.UNCLASSIFIED,
) {
    init {
        require(description.isNotBlank())
        require(kind == MovementKind.INCOME || kind == MovementKind.EXPENSE)
        require(if (kind == MovementKind.INCOME) amountMinor > 0 else amountMinor < 0)
        require(kind == MovementKind.EXPENSE || category == SpendingCategory.UNCLASSIFIED)
    }

    public fun toMovement(cashAccountId: String): BankMovement = BankMovement(
        accountId = cashAccountId,
        date = date,
        description = description,
        amountMinor = amountMinor,
        kind = kind,
        category = category,
    )
}
