package io.eksoda.core.data.repository

import io.eksoda.core.model.CategoryDetail
import io.eksoda.core.model.CategorySpending
import io.eksoda.core.model.MerchantSpending
import io.eksoda.core.model.MonthPace
import io.eksoda.core.model.MonthlyTrend
import io.eksoda.core.model.Period
import io.eksoda.core.model.SpendingSummary
import io.eksoda.core.model.TopProduct
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Spending analytics computed from the local database. */
interface AnalyticsRepository {
    /** Emits once at once, then whenever receipts, lines, products or categories change. */
    fun changes(): Flow<Unit>

    /** Today in the household's timezone. */
    suspend fun today(): LocalDate

    suspend fun getSummary(period: Period): Result<SpendingSummary>

    /** [months] calendar months ending with the current one. */
    suspend fun getMonthlyTrend(months: Int): Result<MonthlyTrend>

    suspend fun getSpendingByCategory(period: Period): Result<CategorySpending>

    /**
     * One category of [getSpendingByCategory] (its total is the same) by product and by shop, with
     * the period of equal length before; a null [categoryId] is the uncategorized bucket. Fails
     * when the category does not exist.
     */
    suspend fun getCategoryDetail(categoryId: String?, period: Period): Result<CategoryDetail>

    suspend fun getSpendingByMerchant(period: Period): Result<List<MerchantSpending>>

    suspend fun getTopProducts(
        period: Period,
        byCount: Boolean,
        limit: Int,
    ): Result<List<TopProduct>>

    /** This month's and last month's running totals (the Home pace chart). */
    suspend fun getMonthPace(): Result<MonthPace>
}
