package io.eksoda.engine.finance

import java.time.YearMonth
import java.util.Locale

data class SubscriptionSuggestion(
    val description: String,
    val paymentAmountMinor: Long,
    val monthsObserved: Int,
)

/** Explicit subscriptions are separate from automated suggestions and never create a second expense. */
data class SubscriptionRecord(
    val name: String,
    val expectedMonthlyMinor: Long,
    val confirmedByUser: Boolean,
) {
    init {
        require(name.isNotBlank())
        require(expectedMonthlyMinor > 0)
    }
}

object Subscriptions {
    /** Two equal debits in different months within a rolling three-month window prompt a question. */
    fun suggest(movements: Iterable<BankMovement>): List<SubscriptionSuggestion> = movements
        .filter { it.kind == MovementKind.EXPENSE && it.description.isNotBlank() }
        .groupBy { it.description.trim().uppercase(Locale.ROOT) to it.amountMinor }
        .mapNotNull { (key, payments) ->
            val byMonth = payments.groupBy { YearMonth.from(it.date) }
            if (byMonth.size < 2) return@mapNotNull null
            val months = byMonth.keys.sorted()
            if (months.zipWithNext().none { (a, b) -> !a.plusMonths(2).isBefore(b) })
                return@mapNotNull null
            SubscriptionSuggestion(key.first, Math.negateExact(key.second), months.size)
        }
        .sortedWith(compareBy({ it.description }, { it.paymentAmountMinor }))
}
