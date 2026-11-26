package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "finance_tax_review",
    primaryKeys = ["account", "reference"],
    foreignKeys = [
        ForeignKey(
            entity = BankRow::class,
            parentColumns = ["account", "reference"],
            childColumns = ["account", "reference"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BankTaxReviewRow(
    val account: String,
    val reference: String,
    val kind: String,
    val category: String,
    val review: String,
)
