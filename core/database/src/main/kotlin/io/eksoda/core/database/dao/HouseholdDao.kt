package io.eksoda.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.eksoda.core.database.entity.HouseholdEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HouseholdDao {
    /** The household, or null before onboarding created it. */
    @Query("SELECT * FROM household LIMIT 1")
    fun observe(): Flow<HouseholdEntity?>

    /** A one-shot read of [observe], without setting up an invalidation observer. */
    @Query("SELECT * FROM household LIMIT 1")
    suspend fun get(): HouseholdEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(household: HouseholdEntity)

    @Update
    suspend fun update(household: HouseholdEntity)

    /** Sets or clears ([income] null) the annual income; the number of rows changed. */
    @Query("UPDATE household SET annual_income_minor = :income, updated_at = :now")
    suspend fun setAnnualIncome(income: Long?, now: Long): Int
}
