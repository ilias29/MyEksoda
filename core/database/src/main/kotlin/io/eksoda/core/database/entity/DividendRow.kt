package io.eksoda.core.database.entity

import androidx.room.Entity

@Entity(tableName = "finance_dividend", primaryKeys = ["broker", "reference"])
data class DividendRow(
    val broker: String,
    val reference: String,
    val day: String,
    val instrument: String,
    val currency: String,
    val gross: String,
    val withheld: String,
    val fee: String,
    val eurRate: String,
    val netMinor: Long,
)
