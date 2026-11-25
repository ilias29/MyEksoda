package io.eksoda.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import io.eksoda.core.data.di.IoDispatcher
import io.eksoda.core.data.repository.HouseholdRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class AppState {
    /** The database is opening. */
    LOADING,

    /** No household yet: onboarding. */
    ONBOARDING,
    READY,

    /** The database key cannot be unwrapped. Restore or start over is not built yet. */
    UNAVAILABLE,
}

/** Something the app was opened with: a shared link or a notification. */
sealed interface AppLaunch {
    /** Shared text that may hold a receipt link. Kept in memory only. */
    data class Share(val text: String) : AppLaunch

    data class OpenReceipt(val receiptId: String) : AppLaunch
}

/**
 * Whether the household exists, and what the app was opened with. The repository is created
 * lazily on the IO dispatcher: opening the encrypted database must not run on the main thread.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val database: Lazy<io.eksoda.core.database.EksodaDatabase>,
    households: Lazy<HouseholdRepository>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val state: StateFlow<AppState> = flow { emitAll(households.get().household) }
        .map { if (it == null) AppState.ONBOARDING else AppState.READY }
        .catch { emit(AppState.UNAVAILABLE) }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState.LOADING)

    fun monthlyPlan(month: java.time.YearMonth) = flow {
        emitAll(database.get().cashDao().monthlyPlan(month.toString()))
    }.map { row ->
        Result.success(row?.let {
            io.eksoda.engine.finance.MonthlyPlan(it.spendingLimitMinor, it.expectedIncomeMinor, it.savingsGoalMinor)
        })
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveMonthlyPlan(month: java.time.YearMonth, plan: io.eksoda.engine.finance.MonthlyPlan) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().saveMonthlyPlan(
                io.eksoda.core.database.entity.MonthlyPlanRow(
                    month.toString(), plan.spendingLimitMinor, plan.expectedIncomeMinor, plan.savingsGoalMinor,
                ),
            )
        }
    }

    fun monthlyOverview(month: java.time.YearMonth, today: java.time.LocalDate) = flow {
        val end = minOf(month.atEndOfMonth(), today)
        emitAll(database.get().cashDao().monthlyMovements(month.atDay(1).toString(), end.toString()))
    }.map { rows ->
        val entries = rows.map { row ->
            io.eksoda.engine.finance.LedgerEntry(
                io.eksoda.engine.finance.FinanceSource.valueOf(row.source),
                io.eksoda.engine.finance.BankMovement(
                    row.account, java.time.LocalDate.parse(row.day), row.description, row.amountMinor,
                    io.eksoda.engine.finance.MovementKind.valueOf(row.kind),
                    category = io.eksoda.engine.finance.SpendingCategory.valueOf(row.category),
                ),
            )
        }
        val overview = io.eksoda.engine.finance.MonthlyLedger.summarize(month, entries)
        // Evaluate derived totals here so any arithmetic failure reaches the error state.
        overview.netFlowMinor
        Result.success(overview)
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    fun receiptMatches(month: java.time.YearMonth) = flow {
        val dao = database.get().cashDao()
        val start = month.atDay(1).toString()
        val end = month.atEndOfMonth().toString()
        emitAll(kotlinx.coroutines.flow.combine(
            dao.receiptBankCandidates(start, end), dao.receiptBankLinks(start, end),
        ) { candidates, links -> Result.success(candidates to links) })
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun linkReceipt(match: io.eksoda.core.database.dao.ReceiptBankMatch) =
        kotlinx.coroutines.withContext(ioDispatcher) { database.get().cashDao().linkReceipt(match) }

    suspend fun unlinkReceipt(match: io.eksoda.core.database.dao.ReceiptBankMatch) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            check(database.get().cashDao().unlinkReceipt(match.receiptId, match.account, match.reference) == 1)
        }

    fun taxData(year: Int, today: java.time.LocalDate) = flow {
        val dao = database.get().cashDao()
        val start = java.time.LocalDate.of(year, 1, 1)
        val end = minOf(java.time.LocalDate.of(year, 12, 31), today)
        emitAll(kotlinx.coroutines.flow.combine(
            dao.taxPlan(year), dao.taxMovements(start.toString(), end.toString()),
        ) { plan, rows -> Result.success(plan to rows) })
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveTaxPlan(plan: io.eksoda.engine.finance.AnnualTaxPlan) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().saveTaxPlan(io.eksoda.core.database.entity.AnnualTaxPlanRow(
                plan.year, plan.incomeMinor, plan.mode.name, plan.specialCap,
            ))
        }

    suspend fun reviewTax(row: io.eksoda.core.database.dao.TaxBankMovement, review: io.eksoda.engine.finance.TaxReview) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().reviewTaxMovement(row, review.name)
        }

    val lightyearDividends = flow { emitAll(database.get().cashDao().lightyearDividends()) }.flowOn(ioDispatcher)

    suspend fun importLightyearSnapshots(data: io.eksoda.engine.finance.LightyearSnapshotImport): Int =
        kotlinx.coroutines.withContext(ioDispatcher) {
            val dao = database.get().cashDao()
            when (data) {
                is io.eksoda.engine.finance.LightyearSnapshotImport.Valuations ->
                    dao.importValuations(data.rows.map {
                        io.eksoda.core.database.entity.PortfolioSnapshotRow(
                            "Lightyear", it.date.toString(), it.valueMinor, it.depositedMinor, it.withdrawnMinor,
                        )
                    }, data.holdings.map {
                        io.eksoda.core.database.entity.HoldingSnapshotRow(
                            "Lightyear", it.instrument, it.day.toString(), it.quantity.stripTrailingZeros().toPlainString(),
                            it.valueMinor, it.costMinor,
                        )
                    })
                is io.eksoda.engine.finance.LightyearSnapshotImport.Dividends ->
                    dao.importDividends(data.rows.map {
                        io.eksoda.core.database.entity.DividendRow(
                            "Lightyear", it.reference, it.day.toString(), it.instrument, it.currency,
                            it.gross.stripTrailingZeros().toPlainString(), it.withheld.stripTrailingZeros().toPlainString(),
                            it.fee.stripTrailingZeros().toPlainString(), it.eurRate.stripTrailingZeros().toPlainString(), it.netMinor,
                        )
                    })
                is io.eksoda.engine.finance.LightyearSnapshotImport.Portfolio ->
                    dao.importPortfolioSnapshots(data.rows.map {
                        io.eksoda.core.database.entity.PortfolioSnapshotRow(
                            "Lightyear", it.date.toString(), it.valueMinor, it.depositedMinor, it.withdrawnMinor,
                        )
                    })
                is io.eksoda.engine.finance.LightyearSnapshotImport.Holdings ->
                    dao.importHoldingSnapshots(data.rows.map {
                        io.eksoda.core.database.entity.HoldingSnapshotRow(
                            "Lightyear", it.instrument, it.day.toString(), it.quantity.stripTrailingZeros().toPlainString(),
                            it.valueMinor, it.costMinor,
                        )
                    })
            }
        }

    val lightyearHistory = flow {
        val dao = database.get().cashDao()
        emitAll(kotlinx.coroutines.flow.combine(
            dao.portfolioSnapshots("Lightyear"), dao.holdingSnapshots("Lightyear"),
        ) { portfolio, holdings -> Result.success(portfolio to holdings) })
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveLightyearSnapshot(snapshot: io.eksoda.engine.finance.PortfolioSnapshot) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            require(!snapshot.date.isAfter(java.time.LocalDate.now()))
            database.get().cashDao().savePortfolioSnapshot(io.eksoda.core.database.entity.PortfolioSnapshotRow(
                "Lightyear", snapshot.date.toString(), snapshot.valueMinor,
                snapshot.depositedMinor, snapshot.withdrawnMinor,
            ))
        }

    suspend fun saveLightyearHolding(
        instrument: String, day: java.time.LocalDate, quantity: java.math.BigDecimal,
        valueMinor: Long, costMinor: Long?,
    ) = kotlinx.coroutines.withContext(ioDispatcher) {
        val id = instrument.trim().uppercase(java.util.Locale.ROOT)
        require(id.isNotBlank() && id.length <= 100 && !day.isAfter(java.time.LocalDate.now()))
        require(quantity.signum() >= 0 && quantity.scale() in 0..12 && quantity.precision() <= 30)
        require(valueMinor >= 0 && (costMinor == null || costMinor >= 0))
        require(quantity.signum() != 0 || (valueMinor == 0L && (costMinor == null || costMinor == 0L)))
        database.get().cashDao().saveHoldingSnapshot(io.eksoda.core.database.entity.HoldingSnapshotRow(
            "Lightyear", id, day.toString(), quantity.stripTrailingZeros().toPlainString(), valueMinor, costMinor,
        ))
    }

    val netWorthData = flow {
        val dao = database.get().cashDao()
        emitAll(kotlinx.coroutines.flow.combine(
            dao.netWorthHistory(), dao.portfolioSnapshots("Lightyear"),
        ) { history, portfolio -> Result.success(history to portfolio) })
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveNetWorth(
        snapshot: io.eksoda.engine.finance.NetWorthSnapshot,
        expected: io.eksoda.core.database.entity.NetWorthRow?,
    ) = kotlinx.coroutines.withContext(ioDispatcher) {
        require(!snapshot.day.isAfter(java.time.LocalDate.now()))
        database.get().cashDao().saveNetWorth(
            io.eksoda.core.database.entity.NetWorthRow(
                snapshot.day.toString(), snapshot.bankMinor, snapshot.cashMinor, snapshot.lightyearMinor,
                snapshot.otherAssetsMinor, snapshot.loanMinor, snapshot.otherDebtsMinor, snapshot.fromLightyear,
            ), expected,
        )
    }

    val paydayData = flow {
        val dao = database.get().cashDao()
        emitAll(kotlinx.coroutines.flow.combine(dao.paydayPlan(), dao.plannedPayments()) { plan, rows ->
            Result.success(plan to rows)
        })
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun savePaydayPlan(plan: io.eksoda.engine.finance.PaydayPlan) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            require(plan.balanceDay == java.time.LocalDate.now())
            database.get().cashDao().savePaydayPlan(io.eksoda.core.database.entity.PaydayPlanRow(
                balanceDay = plan.balanceDay.toString(), balanceMinor = plan.balanceMinor,
                payday = plan.payday.toString(),
            ))
        }

    suspend fun addPlannedPayment(payment: io.eksoda.engine.finance.PlannedPayment) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            require(payment.state == io.eksoda.engine.finance.PaymentState.PENDING)
            database.get().cashDao().insertPlannedPayment(io.eksoda.core.database.entity.PlannedPaymentRow(
                payment.id, payment.name, payment.due.toString(), payment.amountMinor,
                payment.type.name, payment.state.name,
            ))
        }

    suspend fun resolvePlannedPayment(row: io.eksoda.core.database.entity.PlannedPaymentRow, paid: Boolean) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().changePaymentState(row.id, row.state, if (paid) "PAID" else "CANCELLED")
        }

    val bankAccounts = flow { emitAll(database.get().cashDao().accounts()) }.flowOn(ioDispatcher)

    suspend fun classify(row: io.eksoda.core.database.entity.BankRow, kind: io.eksoda.engine.finance.MovementKind,
        category: io.eksoda.engine.finance.SpendingCategory) {
        val movement = io.eksoda.engine.finance.BankMovement(row.account, java.time.LocalDate.parse(row.day),
            row.description, row.amount, kind, row.reference, category)
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().classifyAndResetTax(row.account, row.reference, row.kind, row.category,
                movement.kind.name, movement.category.name)
        }
    }

    fun bankHistory(account: String) = flow {
        emitAll(database.get().cashDao().bankHistory(account))
    }.flowOn(ioDispatcher)

    suspend fun saveBank(rows: List<io.eksoda.core.database.entity.BankRow>): Int =
        kotlinx.coroutines.withContext(ioDispatcher) { database.get().cashDao().importBank(rows) }

    val subscriptionRows = flow { emitAll(database.get().cashDao().subscriptions()) }.flowOn(ioDispatcher)

    fun subscriptionSuggestions(today: java.time.LocalDate) = flow {
        val start = java.time.YearMonth.from(today).minusMonths(2).atDay(1)
        emitAll(database.get().cashDao().subscriptionCandidates(start.toString(), today.toString()))
    }.map { rows ->
        val movements = rows.mapNotNull { row ->
            runCatching {
                io.eksoda.engine.finance.BankMovement(
                    row.account, java.time.LocalDate.parse(row.day), row.description, row.amount,
                    io.eksoda.engine.finance.MovementKind.valueOf(row.kind), row.reference,
                    io.eksoda.engine.finance.SpendingCategory.valueOf(row.category),
                )
            }.getOrNull()
        }
        io.eksoda.engine.finance.Subscriptions.suggest(movements, today)
    }.flowOn(ioDispatcher)

    suspend fun saveSubscription(name: String, monthlyMinor: Long) {
        val record = io.eksoda.engine.finance.SubscriptionRecord(name.trim(), monthlyMinor, true)
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().saveSubscription(
                io.eksoda.core.database.entity.SubscriptionRow(
                    io.eksoda.engine.finance.Subscriptions.key(record.name),
                    record.name,
                    record.expectedMonthlyMinor,
                ),
            )
        }
    }

    suspend fun removeSubscription(key: String) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().removeSubscription(key)
        }
    }

    val cashRows = flow { emitAll(database.get().cashDao().observe()) }.flowOn(ioDispatcher)

    suspend fun saveCash(entry: io.eksoda.engine.finance.ManualCashEntry) {
        val movement = entry.toMovement("cash")
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().insert(io.eksoda.core.database.entity.CashRow(
                java.util.UUID.randomUUID().toString(), movement.date.toString(), movement.description,
                movement.amountMinor, movement.category.name,
            ))
        }
    }

    private val _launch = MutableStateFlow<AppLaunch?>(null)
    val launch: StateFlow<AppLaunch?> = _launch.asStateFlow()

    fun onLaunch(launch: AppLaunch) {
        _launch.value = launch
    }

    fun onLaunchHandled() {
        _launch.value = null
    }
}
