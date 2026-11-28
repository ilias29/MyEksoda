package io.eksoda.core.database.dao

/** Read-only projection; no database schema change. */
data class MonthlyMovementRow(
    val source: String,
    val account: String,
    val day: String,
    val description: String,
    val amountMinor: Long,
    val kind: String,
    val category: String,
)
