package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "finance_cash")
data class CashRow(
    @PrimaryKey val id: String,
    val day: String,
    val description: String,
    val amountMinor: Long,
    val category: String,
)
