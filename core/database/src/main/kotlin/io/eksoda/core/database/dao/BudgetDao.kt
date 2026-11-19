package io.eksoda.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.eksoda.core.database.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget")
    suspend fun all(): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE id = :id")
    suspend fun get(id: String): BudgetEntity?

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Query("DELETE FROM budget WHERE id = :id")
    suspend fun delete(id: String): Int
}
