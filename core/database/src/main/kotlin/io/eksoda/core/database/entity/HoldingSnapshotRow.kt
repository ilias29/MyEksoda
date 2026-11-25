package io.eksoda.core.database.entity

import androidx.room.Entity

/** An observed position; never inferred from a price or used to sum incomplete portfolios. */
@Entity(tableName = "finance_holding_snapshot", primaryKeys = ["broker", "instrument", "day"])
data class HoldingSnapshotRow(
    val broker: String,
    val instrument: String,
    val day: String,
    val quantity: String,
    val valueMinor: Long,
    val costMinor: Long?,
)
