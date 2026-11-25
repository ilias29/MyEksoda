package io.eksoda.core.database.entity

import androidx.room.Entity

@Entity(tableName = "finance_bank", primaryKeys = ["account", "reference"])
data class BankRow(
    val account: String,
    val reference: String,
    val day: String,
    val description: String,
    val amount: Long,
    val kind: String,
    val category: String,
)
