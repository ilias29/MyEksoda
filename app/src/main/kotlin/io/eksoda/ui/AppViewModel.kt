package io.eksoda.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import io.eksoda.core.data.di.IoDispatcher
import io.eksoda.core.data.repository.HouseholdRepository
import io.eksoda.core.database.dao.ReceiptBankMatch
import io.eksoda.core.database.dao.TaxBankMovement
import io.eksoda.core.database.entity.AnnualTaxPlanRow
import io.eksoda.core.database.entity.BankRow
import io.eksoda.core.database.entity.CashRow
import io.eksoda.core.database.entity.DividendRow
import io.eksoda.core.database.entity.HoldingSnapshotRow
import io.eksoda.core.database.entity.MonthlyPlanRow
import io.eksoda.core.database.entity.NetWorthRow
import io.eksoda.core.database.entity.PaydayPlanRow
import io.eksoda.core.database.entity.PlannedPaymentRow
import io.eksoda.core.database.entity.PortfolioSnapshotRow
import io.eksoda.core.database.entity.SubscriptionRow
import io.eksoda.engine.finance.AnnualTaxPlan
import io.eksoda.engine.finance.BankMovement
import io.eksoda.engine.finance.FinanceAdvisor
import io.eksoda.engine.finance.FinanceSource
import io.eksoda.engine.finance.LedgerEntry
import io.eksoda.engine.finance.LightyearSnapshotImport
import io.eksoda.engine.finance.ManualCashEntry
import io.eksoda.engine.finance.MonthlyLedger
import io.eksoda.engine.finance.MonthlyPlan
import io.eksoda.engine.finance.MovementKind
import io.eksoda.engine.finance.NetWorthSnapshot
import io.eksoda.engine.finance.PaydayCalendar
import io.eksoda.engine.finance.PaydayPlan
import io.eksoda.engine.finance.PaymentState
import io.eksoda.engine.finance.PaymentType
import io.eksoda.engine.finance.PlannedPayment
import io.eksoda.engine.finance.PortfolioSnapshot
import io.eksoda.engine.finance.SpendingCategory
import io.eksoda.engine.finance.SubscriptionRecord
import io.eksoda.engine.finance.Subscriptions
import io.eksoda.engine.finance.TaxReview
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
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

    fun monthlyPlan(month: YearMonth) = flow {
        emitAll(database.get().cashDao().monthlyPlan(month.toString()))
    }.map { row ->
        Result.success(
            row?.let {
                MonthlyPlan(it.spendingLimitMinor, it.expectedIncomeMinor, it.savingsGoalMinor)
            },
        )
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveMonthlyPlan(month: YearMonth, plan: MonthlyPlan) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().saveMonthlyPlan(
                MonthlyPlanRow(
                    month.toString(),
                    plan.spendingLimitMinor,
                    plan.expectedIncomeMinor,
                    plan.savingsGoalMinor,
                ),
            )
        }
    }

    fun monthlyOverview(month: YearMonth, today: LocalDate) = flow {
        val end = minOf(month.atEndOfMonth(), today)
        emitAll(
            database.get().cashDao().monthlyMovements(month.atDay(1).toString(), end.toString()),
        )
    }.map { rows ->
        val entries = rows.map { row ->
            LedgerEntry(
                FinanceSource.valueOf(row.source),
                BankMovement(
                    row.account,
                    LocalDate.parse(row.day),
                    row.description,
                    row.amountMinor,
                    MovementKind.valueOf(row.kind),
                    category = SpendingCategory.valueOf(row.category),
                ),
            )
        }
        val overview = MonthlyLedger.summarize(month, entries)
        // Evaluate derived totals here so any arithmetic failure reaches the error state.
        overview.netFlowMinor
        Result.success(overview)
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    fun receiptMatches(month: YearMonth) = flow {
        val dao = database.get().cashDao()
        val start = month.atDay(1).toString()
        val end = month.atEndOfMonth().toString()
        emitAll(
            kotlinx.coroutines.flow.combine(
                dao.receiptBankCandidates(start, end),
                dao.receiptBankLinks(start, end),
            ) { candidates, links -> Result.success(candidates to links) },
        )
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun linkReceipt(match: ReceiptBankMatch) =
        kotlinx.coroutines.withContext(ioDispatcher) { database.get().cashDao().linkReceipt(match) }

    suspend fun unlinkReceipt(match: ReceiptBankMatch) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            check(
                database.get().cashDao().unlinkReceipt(
                    match.receiptId,
                    match.account,
                    match.reference,
                ) ==
                    1,
            )
        }

    fun taxData(year: Int, today: LocalDate) = flow {
        val dao = database.get().cashDao()
        val start = LocalDate.of(year, 1, 1)
        val end = minOf(LocalDate.of(year, 12, 31), today)
        emitAll(
            kotlinx.coroutines.flow.combine(
                dao.taxPlan(year),
                dao.taxMovements(start.toString(), end.toString()),
            ) { plan, rows -> Result.success(plan to rows) },
        )
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveTaxPlan(plan: AnnualTaxPlan) = kotlinx.coroutines.withContext(ioDispatcher) {
        database.get().cashDao().saveTaxPlan(
            AnnualTaxPlanRow(
                plan.year,
                plan.incomeMinor,
                plan.mode.name,
                plan.specialCap,
            ),
        )
    }

    suspend fun reviewTax(row: TaxBankMovement, review: TaxReview) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().reviewTaxMovement(row, review.name)
        }

    val lightyearDividends = flow {
        emitAll(database.get().cashDao().lightyearDividends())
    }.flowOn(ioDispatcher)

    suspend fun importLightyearSnapshots(data: LightyearSnapshotImport): Int =
        kotlinx.coroutines.withContext(ioDispatcher) {
            val dao = database.get().cashDao()
            when (data) {
                is LightyearSnapshotImport.Valuations ->
                    dao.importValuations(
                        data.rows.map {
                            PortfolioSnapshotRow(
                                "Lightyear",
                                it.date.toString(),
                                it.valueMinor,
                                it.depositedMinor,
                                it.withdrawnMinor,
                            )
                        },
                        data.holdings.map {
                            HoldingSnapshotRow(
                                "Lightyear",
                                it.instrument,
                                it.day.toString(),
                                it.quantity.stripTrailingZeros().toPlainString(),
                                it.valueMinor,
                                it.costMinor,
                            )
                        },
                    )

                is LightyearSnapshotImport.Dividends ->
                    dao.importDividends(
                        data.rows.map {
                            DividendRow(
                                "Lightyear",
                                it.reference,
                                it.day.toString(),
                                it.instrument,
                                it.currency,
                                it.gross.stripTrailingZeros().toPlainString(),
                                it.withheld.stripTrailingZeros().toPlainString(),
                                it.fee.stripTrailingZeros().toPlainString(),
                                it.eurRate.stripTrailingZeros().toPlainString(),
                                it.netMinor,
                            )
                        },
                    )

                is LightyearSnapshotImport.Portfolio ->
                    dao.importPortfolioSnapshots(
                        data.rows.map {
                            PortfolioSnapshotRow(
                                "Lightyear",
                                it.date.toString(),
                                it.valueMinor,
                                it.depositedMinor,
                                it.withdrawnMinor,
                            )
                        },
                    )

                is LightyearSnapshotImport.Holdings ->
                    dao.importHoldingSnapshots(
                        data.rows.map {
                            HoldingSnapshotRow(
                                "Lightyear",
                                it.instrument,
                                it.day.toString(),
                                it.quantity.stripTrailingZeros().toPlainString(),
                                it.valueMinor,
                                it.costMinor,
                            )
                        },
                    )
            }
        }

    val lightyearHistory = flow {
        val dao = database.get().cashDao()
        emitAll(
            kotlinx.coroutines.flow.combine(
                dao.portfolioSnapshots("Lightyear"),
                dao.holdingSnapshots("Lightyear"),
            ) { portfolio, holdings -> Result.success(portfolio to holdings) },
        )
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveLightyearSnapshot(snapshot: PortfolioSnapshot) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            require(!snapshot.date.isAfter(LocalDate.now()))
            database.get().cashDao().savePortfolioSnapshot(
                PortfolioSnapshotRow(
                    "Lightyear",
                    snapshot.date.toString(),
                    snapshot.valueMinor,
                    snapshot.depositedMinor,
                    snapshot.withdrawnMinor,
                ),
            )
        }

    suspend fun saveLightyearHolding(
        instrument: String,
        day: LocalDate,
        quantity: BigDecimal,
        valueMinor: Long,
        costMinor: Long?,
    ) = kotlinx.coroutines.withContext(ioDispatcher) {
        val id = instrument.trim().uppercase(java.util.Locale.ROOT)
        require(id.isNotBlank() && id.length <= 100 && !day.isAfter(LocalDate.now()))
        require(quantity.signum() >= 0 && quantity.scale() in 0..12 && quantity.precision() <= 30)
        require(valueMinor >= 0 && (costMinor == null || costMinor >= 0))
        require(
            quantity.signum() != 0 || (valueMinor == 0L && (costMinor == null || costMinor == 0L)),
        )
        database.get().cashDao().saveHoldingSnapshot(
            HoldingSnapshotRow(
                "Lightyear",
                id,
                day.toString(),
                quantity.stripTrailingZeros().toPlainString(),
                valueMinor,
                costMinor,
            ),
        )
    }

    val netWorthData = flow {
        val dao = database.get().cashDao()
        emitAll(
            kotlinx.coroutines.flow.combine(
                dao.netWorthHistory(),
                dao.portfolioSnapshots("Lightyear"),
            ) { history, portfolio -> Result.success(history to portfolio) },
        )
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun saveNetWorth(snapshot: NetWorthSnapshot, expected: NetWorthRow?) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            require(!snapshot.day.isAfter(LocalDate.now()))
            database.get().cashDao().saveNetWorth(
                NetWorthRow(
                    snapshot.day.toString(),
                    snapshot.bankMinor,
                    snapshot.cashMinor,
                    snapshot.lightyearMinor,
                    snapshot.otherAssetsMinor,
                    snapshot.loanMinor,
                    snapshot.otherDebtsMinor,
                    snapshot.fromLightyear,
                ),
                expected,
            )
        }

    val paydayData = flow {
        val dao = database.get().cashDao()
        emitAll(
            kotlinx.coroutines.flow.combine(dao.paydayPlan(), dao.plannedPayments()) {
                    plan,
                    rows,
                ->
                Result.success(plan to rows)
            },
        )
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    suspend fun savePaydayPlan(plan: PaydayPlan) = kotlinx.coroutines.withContext(ioDispatcher) {
        require(plan.balanceDay == LocalDate.now())
        database.get().cashDao().savePaydayPlan(
            PaydayPlanRow(
                balanceDay = plan.balanceDay.toString(),
                balanceMinor = plan.balanceMinor,
                payday = plan.payday.toString(),
            ),
        )
    }

    suspend fun addPlannedPayment(payment: PlannedPayment) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            require(payment.state == PaymentState.PENDING)
            database.get().cashDao().insertPlannedPayment(
                PlannedPaymentRow(
                    payment.id,
                    payment.name,
                    payment.due.toString(),
                    payment.amountMinor,
                    payment.type.name,
                    payment.state.name,
                ),
            )
        }

    suspend fun resolvePlannedPayment(row: PlannedPaymentRow, paid: Boolean) =
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().changePaymentState(
                row.id,
                row.state,
                if (paid) "PAID" else "CANCELLED",
            )
        }

    fun financeAdvice(today: LocalDate, coverageConfirmed: Boolean) = flow {
        val dao = database.get().cashDao()
        val month = YearMonth.from(today)
        val movements = dao.monthlyMovements(
            month.minusMonths(1).atDay(1).toString(),
            today.toString(),
        )
        val basics = kotlinx.coroutines.flow.combine(
            movements,
            dao.monthlyPlan(month.toString()),
            dao.subscriptions(),
            dao.netWorthHistory(),
        ) { rows, plan, subscriptions, worth ->
            AdvisorInputs(rows, plan, subscriptions, worth)
        }
        val payday = kotlinx.coroutines.flow.combine(dao.paydayPlan(), dao.plannedPayments()) {
                plan,
                rows,
            ->
            plan to
                rows
        }
        emitAll(
            kotlinx.coroutines.flow.combine(basics, payday) { input, (payPlan, payments) ->
                val entries = input.rows.map { r ->
                    LedgerEntry(
                        FinanceSource.valueOf(r.source),
                        BankMovement(
                            r.account,
                            LocalDate.parse(r.day),
                            r.description,
                            r.amountMinor,
                            MovementKind.valueOf(r.kind),
                            category = SpendingCategory.valueOf(r.category),
                        ),
                    )
                }
                val monthly = input.plan?.let {
                    MonthlyPlan(it.spendingLimitMinor, it.expectedIncomeMinor, it.savingsGoalMinor)
                }
                val worth = input.worth.map {
                    NetWorthSnapshot(
                        LocalDate.parse(it.day),
                        it.bankMinor,
                        it.cashMinor,
                        it.lightyearMinor,
                        it.otherAssetsMinor,
                        it.loanMinor,
                        it.otherDebtsMinor,
                        it.fromLightyear,
                    )
                }
                val forecast = if (payPlan == null || payPlan.needsReview ||
                    payPlan.balanceDay != today.toString()
                ) {
                    null
                } else {
                    PaydayCalendar.forecast(
                        PaydayPlan(
                            LocalDate.parse(payPlan.balanceDay),
                            payPlan.balanceMinor,
                            LocalDate.parse(payPlan.payday),
                        ),
                        today,
                        payments.map {
                            PlannedPayment(
                                it.id,
                                it.name,
                                LocalDate.parse(it.due),
                                it.amountMinor,
                                PaymentType.valueOf(it.type),
                                PaymentState.valueOf(it.state),
                            )
                        },
                    )
                }
                Result.success(
                    FinanceAdvisor.evaluate(
                        today,
                        entries,
                        monthly,
                        input.subscriptions.map { it.expectedMonthlyMinor },
                        worth,
                        forecast,
                        payPlan != null && forecast == null,
                        coverageConfirmed,
                    ),
                )
            },
        )
    }.catch { emit(Result.failure(it)) }.flowOn(ioDispatcher)

    val bankAccounts = flow { emitAll(database.get().cashDao().accounts()) }.flowOn(ioDispatcher)

    suspend fun classify(row: BankRow, kind: MovementKind, category: SpendingCategory) {
        val movement = BankMovement(
            row.account,
            LocalDate.parse(row.day),
            row.description,
            row.amount,
            kind,
            row.reference,
            category,
        )
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().classifyAndResetTax(
                row.account,
                row.reference,
                row.kind,
                row.category,
                movement.kind.name,
                movement.category.name,
            )
        }
    }

    fun bankHistory(account: String) = flow {
        emitAll(database.get().cashDao().bankHistory(account))
    }.flowOn(ioDispatcher)

    suspend fun saveBank(rows: List<BankRow>): Int =
        kotlinx.coroutines.withContext(ioDispatcher) { database.get().cashDao().importBank(rows) }

    val subscriptionRows = flow {
        emitAll(database.get().cashDao().subscriptions())
    }.flowOn(ioDispatcher)

    fun subscriptionSuggestions(today: LocalDate) = flow {
        val start = YearMonth.from(today).minusMonths(2).atDay(1)
        emitAll(database.get().cashDao().subscriptionCandidates(start.toString(), today.toString()))
    }.map { rows ->
        val movements = rows.mapNotNull { row ->
            runCatching {
                BankMovement(
                    row.account,
                    LocalDate.parse(row.day),
                    row.description,
                    row.amount,
                    MovementKind.valueOf(row.kind),
                    row.reference,
                    SpendingCategory.valueOf(row.category),
                )
            }.getOrNull()
        }
        Subscriptions.suggest(movements, today)
    }.flowOn(ioDispatcher)

    suspend fun saveSubscription(name: String, monthlyMinor: Long) {
        val record = SubscriptionRecord(name.trim(), monthlyMinor, true)
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().saveSubscription(
                SubscriptionRow(
                    Subscriptions.key(record.name),
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

    suspend fun saveCash(entry: ManualCashEntry) {
        val movement = entry.toMovement("cash")
        kotlinx.coroutines.withContext(ioDispatcher) {
            database.get().cashDao().insert(
                CashRow(
                    java.util.UUID.randomUUID().toString(),
                    movement.date.toString(),
                    movement.description,
                    movement.amountMinor,
                    movement.category.name,
                ),
            )
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
