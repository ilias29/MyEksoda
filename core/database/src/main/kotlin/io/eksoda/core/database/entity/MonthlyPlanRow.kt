package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "finance_monthly_plan")
data class MonthlyPlanRow(
    @PrimaryKey val month: String,
    val spendingLimitMinor: Long,
    val expectedIncomeMinor: Long,
    val savingsGoalMinor: Long,
)
