package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "finance_tax_plan")
data class AnnualTaxPlanRow(
    @PrimaryKey val year: Int,
    val incomeMinor: Long,
    val mode: String,
    val specialCap: Boolean,
)
