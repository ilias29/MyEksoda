package io.eksoda.core.database.dao

data class TaxBankMovement(
    val account: String,
    val reference: String,
    val day: String,
    val description: String,
    val amount: Long,
    val kind: String,
    val category: String,
    val review: String,
    val receiptMerchant: String?,
)
