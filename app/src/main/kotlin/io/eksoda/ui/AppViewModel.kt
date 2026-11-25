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

    fun bankHistory(account: String) = flow {
        emitAll(database.get().cashDao().bankHistory(account))
    }.flowOn(ioDispatcher)

    suspend fun saveBank(rows: List<io.eksoda.core.database.entity.BankRow>): Int =
        kotlinx.coroutines.withContext(ioDispatcher) { database.get().cashDao().importBank(rows) }

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
