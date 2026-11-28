package io.eksoda.core.database.entity

import androidx.room.Entity

@Entity(tableName = "finance_portfolio_snapshot", primaryKeys = ["broker", "day"])
data class PortfolioSnapshotRow(
    val broker: String,
    val day: String,
    val valueMinor: Long,
    val depositedMinor: Long,
    val withdrawnMinor: Long,
)
