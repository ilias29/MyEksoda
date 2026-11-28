package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "finance_planned_payment")
data class PlannedPaymentRow(
    @PrimaryKey val id: String,
    val name: String,
    val due: String,
    val amountMinor: Long,
    val type: String,
    val state: String,
)
