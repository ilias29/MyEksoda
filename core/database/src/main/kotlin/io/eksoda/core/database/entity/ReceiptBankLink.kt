package io.eksoda.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One receipt per bank debit; no duplicate financial movement is created. */
@Entity(
    tableName = "finance_receipt_bank",
    foreignKeys = [
        ForeignKey(
            entity = ReceiptEntity::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BankRow::class,
            parentColumns = ["account", "reference"],
            childColumns = ["account", "reference"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["account", "reference"], unique = true)],
)
data class ReceiptBankLink(
    @PrimaryKey val receiptId: String,
    val account: String,
    val reference: String,
)
