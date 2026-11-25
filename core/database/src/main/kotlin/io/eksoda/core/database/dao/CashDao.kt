package io.eksoda.core.database.dao

import androidx.room.Dao
import androidx.room.Upsert
import io.eksoda.core.database.entity.SubscriptionRow
import androidx.room.Insert
import io.eksoda.core.database.entity.BankRow
import androidx.room.Transaction
import androidx.room.Query
import io.eksoda.core.database.entity.CashRow
import kotlinx.coroutines.flow.Flow

@Dao
interface CashDao {
    // Candidate is not proof: only same amount and a bank posting up to three days later.
    @Query(
        """SELECT r.id AS receiptId, m.display_name AS merchant, r.issued_local_date AS receiptDay,
                  r.gross_total_minor AS amountMinor, b.account, b.reference, b.day AS bankDay, b.description
           FROM receipt r JOIN merchant m ON r.merchant_id = m.id
           JOIN finance_bank b ON b.amount = -r.gross_total_minor
           WHERE r.issued_local_date BETWEEN :start AND :end
             AND r.status = 'active' AND r.document_kind = 'sale' AND r.currency = 'EUR'
             AND r.payment_method IN ('card', 'bank') AND r.gross_total_minor > 0
             AND r.excluded_from_analytics = 0
             AND b.kind = 'EXPENSE' AND b.category NOT IN ('RENT', 'LOAN_PAYMENT')
             AND b.day BETWEEN r.issued_local_date AND date(r.issued_local_date, '+3 days')
             AND NOT EXISTS (SELECT 1 FROM finance_receipt_bank l WHERE l.receiptId = r.id)
             AND NOT EXISTS (SELECT 1 FROM finance_receipt_bank l WHERE l.account = b.account AND l.reference = b.reference)
           ORDER BY r.issued_local_date DESC, r.id, b.day, b.account, b.reference""",
    )
    fun receiptBankCandidates(start: String, end: String): Flow<List<ReceiptBankMatch>>

    @Query(
        """SELECT r.id AS receiptId, m.display_name AS merchant, r.issued_local_date AS receiptDay,
                  r.gross_total_minor AS amountMinor, b.account, b.reference, b.day AS bankDay, b.description
           FROM finance_receipt_bank l JOIN receipt r ON r.id = l.receiptId
           JOIN merchant m ON m.id = r.merchant_id
           JOIN finance_bank b ON b.account = l.account AND b.reference = l.reference
           WHERE r.issued_local_date BETWEEN :start AND :end
           ORDER BY r.issued_local_date DESC, r.id""",
    )
    fun receiptBankLinks(start: String, end: String): Flow<List<ReceiptBankMatch>>

    // Recheck eligibility in the write itself, including stale UI and competing confirmations.
    @Query(
        """INSERT OR IGNORE INTO finance_receipt_bank (receiptId, account, reference)
           SELECT r.id, b.account, b.reference FROM receipt r JOIN finance_bank b
             ON b.amount = -r.gross_total_minor
           WHERE r.id = :receiptId AND b.account = :account AND b.reference = :reference
             AND r.status = 'active' AND r.document_kind = 'sale' AND r.currency = 'EUR'
             AND r.payment_method IN ('card', 'bank') AND r.gross_total_minor > 0
             AND r.excluded_from_analytics = 0
             AND b.kind = 'EXPENSE' AND b.category NOT IN ('RENT', 'LOAN_PAYMENT')
             AND b.day BETWEEN r.issued_local_date AND date(r.issued_local_date, '+3 days')""",
    )
    suspend fun confirmReceiptBank(receiptId: String, account: String, reference: String)

    @Query("SELECT * FROM finance_receipt_bank WHERE receiptId = :receiptId")
    suspend fun receiptBankLink(receiptId: String): io.eksoda.core.database.entity.ReceiptBankLink?

    @Transaction
    suspend fun linkReceipt(match: ReceiptBankMatch) {
        confirmReceiptBank(match.receiptId, match.account, match.reference)
        val actual = receiptBankLink(match.receiptId)
        check(actual != null && actual.account == match.account && actual.reference == match.reference)
    }

    @Query("DELETE FROM finance_receipt_bank WHERE receiptId = :receiptId AND account = :account AND reference = :reference")
    suspend fun unlinkReceipt(receiptId: String, account: String, reference: String): Int

    @Query("SELECT * FROM finance_monthly_plan WHERE month = :month")
    fun monthlyPlan(month: String): Flow<io.eksoda.core.database.entity.MonthlyPlanRow?>

    @Upsert
    suspend fun saveMonthlyPlan(row: io.eksoda.core.database.entity.MonthlyPlanRow)

    @Query(
        """SELECT 'BANK' AS source, account, day, description, amount AS amountMinor, kind, category
           FROM finance_bank WHERE day >= :start AND day <= :end
           UNION ALL
           SELECT 'CASH' AS source, 'cash' AS account, day, description, amountMinor,
                  CASE WHEN amountMinor > 0 THEN 'INCOME' ELSE 'EXPENSE' END AS kind, category
           FROM finance_cash WHERE day >= :start AND day <= :end""",
    )
    fun monthlyMovements(start: String, end: String): Flow<List<MonthlyMovementRow>>

    @Query("SELECT * FROM finance_subscription ORDER BY name")
    fun subscriptions(): Flow<List<SubscriptionRow>>

    @Upsert
    suspend fun saveSubscription(row: SubscriptionRow)

    @Query("DELETE FROM finance_subscription WHERE `key` = :key")
    suspend fun removeSubscription(key: String)

    @Query("SELECT * FROM finance_bank WHERE day >= :start AND day <= :end AND kind = 'EXPENSE'")
    fun subscriptionCandidates(start: String, end: String): Flow<List<BankRow>>

    @Insert
    suspend fun insert(entry: CashRow)

    @Query("SELECT * FROM finance_cash ORDER BY day DESC, rowid DESC")
    fun observe(): Flow<List<CashRow>>

    @Query("SELECT * FROM finance_bank WHERE account = :account AND reference = :reference")
    suspend fun findBank(account: String, reference: String): BankRow?

    @Query("SELECT * FROM finance_bank WHERE account = :account ORDER BY day DESC, reference")
    fun bankHistory(account: String): Flow<List<BankRow>>

    @Query("SELECT DISTINCT account FROM finance_bank ORDER BY account")
    fun accounts(): Flow<List<String>>

    @Query(
        """UPDATE finance_bank SET kind = :kind, category = :category
           WHERE account = :account AND reference = :reference
             AND kind = :previousKind AND category = :previousCategory
             AND ((:kind = 'EXPENSE' AND :category NOT IN ('RENT', 'LOAN_PAYMENT'))
               OR NOT EXISTS (SELECT 1 FROM finance_receipt_bank l
                   WHERE l.account = finance_bank.account AND l.reference = finance_bank.reference))""",
    )
    suspend fun classify(
        account: String,
        reference: String,
        previousKind: String,
        previousCategory: String,
        kind: String,
        category: String,
    ): Int

    @Insert
    suspend fun insertBank(row: BankRow)

    @Transaction
    suspend fun importBank(rows: List<BankRow>): Int {
        var inserted = 0
        for (row in rows) {
            val existing = findBank(row.account, row.reference)
            if (existing == null) {
                insertBank(row)
                inserted++
            } else {
                require(existing.day == row.day && existing.description == row.description &&
                    existing.amount == row.amount) { "Conflicting bank reference" }
            }
        }
        return inserted
    }
}
