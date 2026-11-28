package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "finance_payday_plan")
data class PaydayPlanRow(
    @PrimaryKey val id: String = "current",
    val balanceDay: String,
    val balanceMinor: Long,
    val payday: String,
    val needsReview: Boolean = false,
)
