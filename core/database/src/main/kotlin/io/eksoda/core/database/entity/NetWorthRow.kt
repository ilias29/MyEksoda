package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A confirmed historical balance sheet, independent of later quote or transaction changes. */
@Entity(tableName = "finance_net_worth")
data class NetWorthRow(
    @PrimaryKey val day: String,
    val bankMinor: Long,
    val cashMinor: Long,
    val lightyearMinor: Long,
    val otherAssetsMinor: Long,
    val loanMinor: Long,
    val otherDebtsMinor: Long,
    val fromLightyear: Boolean,
)
