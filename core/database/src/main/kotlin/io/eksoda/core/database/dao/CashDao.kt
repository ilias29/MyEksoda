package io.eksoda.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.eksoda.core.database.entity.AnnualTaxPlanRow
import io.eksoda.core.database.entity.BankRow
import io.eksoda.core.database.entity.BankTaxReviewRow
import io.eksoda.core.database.entity.CashRow
import io.eksoda.core.database.entity.DividendRow
import io.eksoda.core.database.entity.HoldingSnapshotRow
import io.eksoda.core.database.entity.MonthlyPlanRow
import io.eksoda.core.database.entity.NetWorthRow
import io.eksoda.core.database.entity.PaydayPlanRow
import io.eksoda.core.database.entity.PlannedPaymentRow
import io.eksoda.core.database.entity.PortfolioSnapshotRow
import io.eksoda.core.database.entity.ReceiptBankLink
import io.eksoda.core.database.entity.SubscriptionRow
import kotlinx.coroutines.flow.Flow

@Dao
interface CashDao {
    @Query("SELECT * FROM finance_payday_plan WHERE id = 'current'")
    fun paydayPlan(): Flow<PaydayPlanRow?>

    @Upsert
    suspend fun savePaydayPlan(row: PaydayPlanRow)

    @Query("SELECT * FROM finance_planned_payment ORDER BY due, id")
    fun plannedPayments(): Flow<List<PlannedPaymentRow>>

    @Insert
    suspend fun insertPlannedPayment(row: PlannedPaymentRow)

    @Query("UPDATE finance_planned_payment SET state = :next WHERE id = :id AND state = :previous")
    suspend fun updatePaymentState(id: String, previous: String, next: String): Int

    @Query("UPDATE finance_payday_plan SET needsReview = 1 WHERE id = 'current'")
    suspend fun invalidatePaydayBalance()

    @Transaction
    suspend fun changePaymentState(id: String, previous: String, next: String) {
        require(previous == "PENDING" && next in setOf("PAID", "CANCELLED"))
        check(updatePaymentState(id, previous, next) == 1)
        if (next == "PAID") invalidatePaydayBalance()
    }

    @Query("SELECT * FROM finance_net_worth ORDER BY day DESC")
    fun netWorthHistory(): Flow<List<NetWorthRow>>

    @Query("SELECT * FROM finance_portfolio_snapshot WHERE broker = 'Lightyear' AND day = :day")
    suspend fun lightyearOnDay(day: String): PortfolioSnapshotRow?

    @Query("SELECT * FROM finance_net_worth WHERE day = :day")
    suspend fun netWorthOnDay(day: String): NetWorthRow?

    @Upsert
    suspend fun upsertNetWorth(row: NetWorthRow)

    @Transaction
    suspend fun saveNetWorth(row: NetWorthRow, expected: NetWorthRow?) {
        check(netWorthOnDay(row.day) == expected) { "Snapshot changed; review before replacing" }
        if (row.fromLightyear) {
            val portfolio = checkNotNull(lightyearOnDay(row.day)) { "Same-day portfolio required" }
            check(portfolio.valueMinor == row.lightyearMinor) { "Portfolio changed; review again" }
        }
        upsertNetWorth(row)
    }

    @Transaction
    suspend fun importValuations(
        portfolio: List<PortfolioSnapshotRow>,
        holdings: List<HoldingSnapshotRow>,
    ): Int {
        val count = importPortfolioSnapshots(portfolio)
        return count + importHoldingSnapshots(holdings)
    }

    @Query("SELECT * FROM finance_dividend WHERE broker = 'Lightyear' ORDER BY day DESC, reference")
    fun lightyearDividends(): Flow<List<DividendRow>>

    @Query("SELECT * FROM finance_dividend WHERE broker = :broker AND reference = :reference")
    suspend fun findDividend(broker: String, reference: String): DividendRow?

    @Insert
    suspend fun insertDividend(row: DividendRow)

    @Transaction
    suspend fun importDividends(rows: List<DividendRow>): Int {
        require(rows.all { it.broker == "Lightyear" })
        require(rows.map { it.reference }.distinct().size == rows.size)
        var count = 0
        for (row in rows) {
            val existing = findDividend(row.broker, row.reference)
            if (existing == null) {
                insertDividend(row)
                count++
            } else {
                require(existing == row) { "Conflicting dividend" }
            }
        }
        return count
    }

    @Transaction
    suspend fun importPortfolioSnapshots(rows: List<PortfolioSnapshotRow>): Int {
        val existing = portfolioHistory("Lightyear").associateBy { it.day }
        require(rows.all { it.broker == "Lightyear" })
        require(rows.map { it.day }.distinct().size == rows.size)
        val combined = (existing.values + rows.filter { it.day !in existing }).sortedBy { it.day }
        require(
            rows.all {
                existing[it.day] == null || existing[it.day] == it
            },
        ) { "Conflicting snapshot" }
        require(
            combined.zipWithNext().all { (a, b) ->
                a.depositedMinor <= b.depositedMinor && a.withdrawnMinor <= b.withdrawnMinor
            },
        )
        val newRows = rows.filter { it.day !in existing }
        newRows.forEach { upsertPortfolioSnapshot(it) }
        return newRows.size
    }

    @Query(
        "SELECT * FROM finance_holding_snapshot WHERE broker = :broker AND instrument = :instrument AND day = :day",
    )
    suspend fun findHolding(broker: String, instrument: String, day: String): HoldingSnapshotRow?

    @Transaction
    suspend fun importHoldingSnapshots(rows: List<HoldingSnapshotRow>): Int {
        require(rows.all { it.broker == "Lightyear" })
        require(rows.map { it.instrument to it.day }.distinct().size == rows.size)
        var count = 0
        for (row in rows) {
            val existing = findHolding(row.broker, row.instrument, row.day)
            if (existing == null) {
                saveHoldingSnapshot(row)
                count++
            } else {
                require(existing == row) { "Conflicting holding snapshot" }
            }
        }
        return count
    }

    @Query("SELECT * FROM finance_portfolio_snapshot WHERE broker = :broker ORDER BY day DESC")
    fun portfolioSnapshots(broker: String): Flow<List<PortfolioSnapshotRow>>

    @Query("SELECT * FROM finance_portfolio_snapshot WHERE broker = :broker ORDER BY day")
    suspend fun portfolioHistory(broker: String): List<PortfolioSnapshotRow>

    @Upsert
    suspend fun upsertPortfolioSnapshot(row: PortfolioSnapshotRow)

    @Transaction
    suspend fun savePortfolioSnapshot(row: PortfolioSnapshotRow) {
        val history = portfolioHistory(row.broker).filter { it.day != row.day }
        require(
            history.all {
                if (it.day <
                    row.day
                ) {
                    it.depositedMinor <= row.depositedMinor &&
                        it.withdrawnMinor <= row.withdrawnMinor
                } else {
                    it.depositedMinor >= row.depositedMinor &&
                        it.withdrawnMinor >= row.withdrawnMinor
                }
            },
        ) { "Cumulative deposits or withdrawals contradict existing history" }
        upsertPortfolioSnapshot(row)
    }

    @Query(
        "SELECT * FROM finance_holding_snapshot WHERE broker = :broker ORDER BY day DESC, instrument",
    )
    fun holdingSnapshots(broker: String): Flow<List<HoldingSnapshotRow>>

    @Upsert
    suspend fun saveHoldingSnapshot(row: HoldingSnapshotRow)

    @Query("SELECT * FROM finance_tax_plan WHERE year = :year")
    fun taxPlan(year: Int): Flow<AnnualTaxPlanRow?>

    @Upsert
    suspend fun saveTaxPlan(row: AnnualTaxPlanRow)

    @Query(
        """SELECT b.account, b.reference, b.day, b.description, b.amount, b.kind, b.category,
                  COALESCE(t.review, 'PENDING') AS review, m.display_name AS receiptMerchant
           FROM finance_bank b LEFT JOIN finance_tax_review t
             ON t.account = b.account AND t.reference = b.reference AND t.kind = b.kind AND t.category = b.category
           LEFT JOIN finance_receipt_bank l ON l.account = b.account AND l.reference = b.reference
           LEFT JOIN receipt r ON r.id = l.receiptId AND r.status = 'active' AND r.excluded_from_analytics = 0
           LEFT JOIN merchant m ON m.id = r.merchant_id
           WHERE b.day BETWEEN :start AND :end
             AND ((b.kind = 'EXPENSE' AND b.amount < 0 AND b.category NOT IN ('RENT', 'LOAN_PAYMENT'))
                OR (b.kind = 'REFUND' AND b.amount > 0))
           ORDER BY b.day DESC, b.account, b.reference""",
    )
    fun taxMovements(start: String, end: String): Flow<List<TaxBankMovement>>

    @Upsert
    suspend fun upsertTaxReview(row: BankTaxReviewRow)

    @Query("DELETE FROM finance_tax_review WHERE account = :account AND reference = :reference")
    suspend fun clearTaxReview(account: String, reference: String)

    @Transaction
    suspend fun reviewTaxMovement(row: TaxBankMovement, review: String) {
        require(review in setOf("PENDING", "EXCLUDED", "ELIGIBLE", "DOUBLE_HEALTH"))
        val current = checkNotNull(findBank(row.account, row.reference))
        check(
            current.kind == row.kind && current.category == row.category &&
                current.amount == row.amount && current.day == row.day,
        )
        check(
            (
                current.kind == "EXPENSE" && current.amount < 0 &&
                    current.category !in setOf("RENT", "LOAN_PAYMENT")
                ) ||
                (current.kind == "REFUND" && current.amount > 0),
        )
        if (review == "PENDING") {
            clearTaxReview(row.account, row.reference)
        } else {
            upsertTaxReview(
                BankTaxReviewRow(
                    row.account,
                    row.reference,
                    row.kind,
                    row.category,
                    review,
                ),
            )
        }
    }

    @Transaction
    suspend fun classifyAndResetTax(
        account: String,
        reference: String,
        previousClassification: String,
        kind: String,
        category: String,
    ) {
        check(classify(account, reference, previousClassification, kind, category) == 1)
        if (previousClassification != "$kind:$category") clearTaxReview(account, reference)
    }

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
    suspend fun receiptBankLink(receiptId: String): ReceiptBankLink?

    @Transaction
    suspend fun linkReceipt(match: ReceiptBankMatch) {
        confirmReceiptBank(match.receiptId, match.account, match.reference)
        val actual = receiptBankLink(match.receiptId)
        check(
            actual != null &&
                actual.account == match.account &&
                actual.reference == match.reference,
        )
    }

    @Query(
        "DELETE FROM finance_receipt_bank WHERE receiptId = :receiptId AND account = :account AND reference = :reference",
    )
    suspend fun unlinkReceipt(receiptId: String, account: String, reference: String): Int

    @Query("SELECT * FROM finance_monthly_plan WHERE month = :month")
    fun monthlyPlan(month: String): Flow<MonthlyPlanRow?>

    @Upsert
    suspend fun saveMonthlyPlan(row: MonthlyPlanRow)

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
             AND kind || ':' || category = :previousClassification
             AND ((:kind = 'EXPENSE' AND :category NOT IN ('RENT', 'LOAN_PAYMENT'))
               OR NOT EXISTS (SELECT 1 FROM finance_receipt_bank l
                   WHERE l.account = finance_bank.account AND l.reference = finance_bank.reference))""",
    )
    suspend fun classify(
        account: String,
        reference: String,
        previousClassification: String,
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
                require(
                    existing.day == row.day && existing.description == row.description &&
                        existing.amount == row.amount,
                ) { "Conflicting bank reference" }
            }
        }
        return inserted
    }
}
