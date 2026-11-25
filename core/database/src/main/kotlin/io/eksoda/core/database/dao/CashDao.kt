package io.eksoda.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import io.eksoda.core.database.entity.BankRow
import androidx.room.Transaction
import androidx.room.Query
import io.eksoda.core.database.entity.CashRow
import kotlinx.coroutines.flow.Flow

@Dao
interface CashDao {
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
             AND kind = :previousKind AND category = :previousCategory""",
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
