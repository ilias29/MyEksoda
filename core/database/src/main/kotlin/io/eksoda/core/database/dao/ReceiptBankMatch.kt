package io.eksoda.core.database.dao

data class ReceiptBankMatch(
    val receiptId: String,
    val merchant: String,
    val receiptDay: String,
    val amountMinor: Long,
    val account: String,
    val reference: String,
    val bankDay: String,
    val description: String,
)
