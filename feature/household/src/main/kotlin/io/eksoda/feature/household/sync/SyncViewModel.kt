package io.eksoda.feature.household.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.eksoda.core.data.repository.SyncRepository
import io.eksoda.core.model.SyncEvent
import io.eksoda.core.model.SyncPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val peers: List<SyncPeer> = emptyList(),
)

sealed interface SyncScreenEvent {
    data class EnabledChanged(val enabled: Boolean) : SyncScreenEvent
}

/** The Sync screen: the switch, the paired phones and the history (all local to this phone). */
@HiltViewModel
class SyncViewModel @Inject constructor(private val sync: SyncRepository) : ViewModel() {
    val state: StateFlow<SyncUiState> =
        combine(sync.enabled, sync.peers) { enabled, peers ->
            SyncUiState(loading = false, enabled = enabled, peers = peers)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), SyncUiState())

    /** Readable whether sync is on or off: the history stays after turning it off. */
    val history: Flow<PagingData<SyncEvent>> = sync.getHistory().cachedIn(viewModelScope)

    fun onEvent(event: SyncScreenEvent) {
        when (event) {
            is SyncScreenEvent.EnabledChanged ->
                viewModelScope.launch { sync.setEnabled(event.enabled) }
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
