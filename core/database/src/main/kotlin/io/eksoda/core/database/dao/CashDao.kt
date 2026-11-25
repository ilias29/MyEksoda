package io.eksoda.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.eksoda.core.database.entity.CashRow
import kotlinx.coroutines.flow.Flow

@Dao
interface CashDao {
    @Insert
    suspend fun insert(entry: CashRow)

    @Query("SELECT * FROM finance_cash ORDER BY day DESC, rowid DESC")
    fun observe(): Flow<List<CashRow>>
}
