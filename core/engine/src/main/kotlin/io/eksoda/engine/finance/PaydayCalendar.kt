package io.eksoda.engine.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

public enum class PaymentType { RENT, LOAN, SUBSCRIPTION, OTHER }
public enum class PaymentState { PENDING, PAID, CANCELLED }

public data class PlannedPayment(
    val id: String,
    val name: String,
    val due: LocalDate,
    val amountMinor: Long,
    val type: PaymentType,
    val state: PaymentState = PaymentState.PENDING,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 120 && amountMinor > 0)
    }
}

public data class PaydayPlan(
    val balanceDay: LocalDate,
    val balanceMinor: Long,
    val payday: LocalDate,
) {
    init {
        require(payday.isAfter(balanceDay))
    }
}

public data class PaydayForecast(
    val reservedMinor: BigDecimal,
    val remainingMinor: BigDecimal,
    val dailyAllowanceMinor: BigDecimal,
    val days: Long,
    val overdueCount: Int,
)

public object PaydayCalendar {
    /** Balance is the user's current liquid funds, not monthly income or portfolio value. */
    public fun forecast(
        plan: PaydayPlan,
        today: LocalDate,
        payments: List<PlannedPayment>,
    ): PaydayForecast {
        require(plan.balanceDay == today && plan.payday.isAfter(today))
        require(payments.map { it.id }.distinct().size == payments.size)
        val due = payments.filter {
            it.state == PaymentState.PENDING && it.due.isBefore(plan.payday)
        }
        val reserve = due.fold(BigDecimal.ZERO) { sum, p ->
            sum + BigDecimal.valueOf(p.amountMinor)
        }
        val remaining = BigDecimal.valueOf(plan.balanceMinor) - reserve
        val days = ChronoUnit.DAYS.between(today, plan.payday)
        return PaydayForecast(
            reserve,
            remaining,
            remaining.max(BigDecimal.ZERO).divide(BigDecimal.valueOf(days), 0, RoundingMode.DOWN),
            days,
            due.count { it.due.isBefore(today) },
        )
    }
}
