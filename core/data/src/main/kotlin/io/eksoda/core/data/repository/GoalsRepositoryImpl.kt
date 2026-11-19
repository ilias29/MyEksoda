package io.eksoda.core.data.repository

import io.eksoda.core.data.db.WriteCoordinator
import io.eksoda.core.data.di.DefaultDispatcher
import io.eksoda.core.data.util.Fixed
import io.eksoda.core.database.dao.AnalyticsDao
import io.eksoda.core.database.dao.BudgetDao
import io.eksoda.core.database.dao.CategoryDao
import io.eksoda.core.database.dao.HouseholdDao
import io.eksoda.core.database.dao.TombstoneDao
import io.eksoda.core.database.entity.BudgetEntity
import io.eksoda.core.database.entity.CategoryEntity
import io.eksoda.core.database.entity.TombstoneEntity
import io.eksoda.core.model.Budget
import io.eksoda.core.model.BudgetLevel
import io.eksoda.core.model.BudgetProgress
import io.eksoda.core.model.Category
import io.eksoda.core.model.CategorySpending
import io.eksoda.core.model.EPaymentProgress
import io.eksoda.core.model.MonthBudgets
import io.eksoda.core.model.Period
import io.eksoda.core.model.SpendingBucketKind
import io.eksoda.engine.analytics.E_PAYMENT_CAP
import io.eksoda.engine.analytics.E_PAYMENT_SHARE
import io.eksoda.engine.analytics.budgetUse
import io.eksoda.engine.analytics.categoryBudgetLimit
import io.eksoda.engine.analytics.categoryBudgetsFit
import io.eksoda.engine.analytics.ePaymentPace
import io.eksoda.engine.analytics.ePaymentTarget
import io.eksoda.engine.analytics.electronicPartOf
import io.eksoda.engine.analytics.monthElapsedPercent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import io.eksoda.engine.analytics.BudgetLevel as EngineBudgetLevel

@Singleton
class GoalsRepositoryImpl @Inject constructor(
    private val budgets: BudgetDao,
    private val categories: CategoryDao,
    private val households: HouseholdDao,
    private val tombstones: TombstoneDao,
    private val analyticsDao: AnalyticsDao,
    private val analytics: AnalyticsRepository,
    private val coordinator: WriteCoordinator,
    private val clock: Clock,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : GoalsRepository {

    override fun getBudgets(): Flow<List<Budget>> =
        combine(budgets.observeAll(), categories.observeAll()) { rows, all ->
            val byId = all.associateBy { it.id }
            rows.mapNotNull { row ->
                val id = row.categoryId
                // A budget whose category is gone (deleted with it) is not shown.
                val category = id?.let { byId[it]?.toModel() ?: return@mapNotNull null }
                Budget(category, money(row.amountMinor))
            }.sortedBy { it.category != null }
        }

    override fun categoryBudgetLimit(budgets: List<Budget>, categoryId: String): BigDecimal? =
        categoryBudgetLimit(
            total = budgets.firstOrNull { it.category == null }?.amount,
            otherCategories = budgets
                .filter { budget -> budget.category.let { it != null && it.id != categoryId } }
                .map { it.amount },
        )

    override suspend fun setBudget(categoryId: String?, amount: BigDecimal?): Result<Unit> =
        runCatching {
            require(amount == null || amount.signum() > 0) { "a budget must be above 0" }
            val id = categoryId ?: BudgetEntity.TOTAL_ID
            coordinator.write {
                val now = clock.millis()
                if (amount == null) {
                    if (budgets.delete(id) == 1) tombstones.insert(TombstoneEntity(BUDGET, id, now))
                } else {
                    if (categoryId != null) {
                        checkNotNull(categories.get(categoryId)) { "no category" }
                    }
                    // The categories are parts of the month: together within its budget.
                    val after = budgets.all().associate { it.id to money(it.amountMinor) } +
                        (id to amount)
                    val parts = after.filterKeys { it != BudgetEntity.TOTAL_ID }.values
                    if (!categoryBudgetsFit(after[BudgetEntity.TOTAL_ID], parts)) {
                        throw BudgetsDoNotFitException()
                    }
                    budgets.upsert(
                        BudgetEntity(
                            id = id,
                            categoryId = categoryId,
                            amountMinor = Fixed.toLong(amount, Fixed.MINOR),
                            createdAt = budgets.get(id)?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
                }
            }
        }

    override suspend fun getMonthBudgets(spending: CategorySpending?): Result<MonthBudgets> =
        compute { monthBudgets(spending) }

    private suspend fun monthBudgets(known: CategorySpending?): MonthBudgets {
        val today = analytics.today()
        val month = YearMonth.from(today)
        val rows = budgets.all()
        val start = month.atDay(1)
        val spending = if (rows.isEmpty()) {
            null
        } else {
            known?.takeIf { it.from == start && it.to == today }
                ?: analytics.getSpendingByCategory(Period.Range(start, today)).getOrThrow()
        }
        val spentByCategory = spending?.buckets.orEmpty()
            .filter { it.kind == SpendingBucketKind.CATEGORY }
            .mapNotNull { bucket -> bucket.category?.let { it.id to bucket.total } }
            .toMap()
        val byId = categories.all().associateBy { it.id }
        val total = rows.firstOrNull { it.categoryId == null }?.let { row ->
            progress(null, money(row.amountMinor), spending?.total ?: BigDecimal.ZERO)
        }
        val perCategory = rows.mapNotNull { row ->
            val category = row.categoryId?.let(byId::get)?.toModel() ?: return@mapNotNull null
            val spent = spentByCategory[category.id] ?: BigDecimal.ZERO
            progress(category, money(row.amountMinor), spent)
        }.sortedWith(
            compareByDescending<BudgetProgress> { it.usedPercent ?: BigDecimal.ZERO }
                .thenBy { it.category?.slug },
        )
        return MonthBudgets(
            currency = spending?.currency ?: currency(),
            month = month,
            elapsedPercent = monthElapsedPercent(today),
            total = total,
            categories = perCategory,
        )
    }

    override suspend fun getEPayments(): Result<EPaymentProgress> = compute {
        val today = analytics.today()
        val household = households.get()
        val currency = household?.defaultCurrency ?: DEFAULT_CURRENCY
        val start = LocalDate.of(today.year, 1, 1).toString()
        val end = today.toString()
        val whole = money(analyticsDao.electronicTotal(start, end, currency))
        // Receipts paid in several ways count with their card and bank part only.
        val split = analyticsDao.mixedPayments(start, end, currency)
            .fold(BigDecimal.ZERO) { sum, row ->
                sum + electronicPartOf(row.sourceData, creditNote = row.documentKind == CREDIT_NOTE)
            }
        val paid = whole + split
        val income = household?.annualIncomeMinor?.let(::money)
        val target = income?.let(::ePaymentTarget)
        val pace = ePaymentPace(target ?: BigDecimal.ZERO, paid, today)
        EPaymentProgress(
            currency = currency,
            year = today.year,
            income = income,
            target = target,
            paid = paid,
            remaining = target?.let { pace.remaining },
            perMonth = target?.let { pace.perMonth },
            projected = pace.projected,
            paidPercent = target?.let { pace.paidPercent },
            sharePercent = E_PAYMENT_SHARE.movePointRight(2),
            cap = E_PAYMENT_CAP,
        )
    }

    /** [block] on the Default dispatcher, its failure as a failed [Result]. */
    private suspend fun <T> compute(block: suspend () -> T): Result<T> =
        withContext(defaultDispatcher) { runCatching { block() } }

    private suspend fun currency(): String = households.get()?.defaultCurrency ?: DEFAULT_CURRENCY

    private fun progress(
        category: Category?,
        budget: BigDecimal,
        spent: BigDecimal,
    ): BudgetProgress {
        val use = budgetUse(budget, spent)
        return BudgetProgress(
            category = category,
            budget = budget,
            spent = spent,
            left = use.left,
            usedPercent = use.usedPercent,
            level = when (use.level) {
                EngineBudgetLevel.UNDER -> BudgetLevel.UNDER
                EngineBudgetLevel.NEAR -> BudgetLevel.NEAR
                EngineBudgetLevel.OVER -> BudgetLevel.OVER
            },
        )
    }

    private companion object {
        const val BUDGET = "budget"
        const val CREDIT_NOTE = "credit_note"
        const val DEFAULT_CURRENCY = "EUR"
    }
}

private fun money(minor: Long): BigDecimal = Fixed.toDecimal(minor, Fixed.MINOR)

private fun CategoryEntity.toModel() = Category(id, name, slug, isSystem)
