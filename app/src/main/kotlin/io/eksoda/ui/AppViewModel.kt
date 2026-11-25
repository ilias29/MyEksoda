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

    val bankAccounts = flow { emitAll(database.get().cashDao().accounts()) }.flowOn(ioDispatcher)

    suspend fun classify(row: io.eksoda.core.database.entity.BankRow, kind: io.eksoda.engine.finance.MovementKind,
        category: io.eksoda.engine.finance.SpendingCategory) {
        val movement = io.eksoda.engine.finance.BankMovement(row.account, java.time.LocalDate.parse(row.day),
            row.description, row.amount, kind, row.reference, category)
        kotlinx.coroutines.withContext(ioDispatcher) {
            check(database.get().cashDao().classify(row.account, row.reference, row.kind, row.category,
                movement.kind.name, movement.category.name) == 1)
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
