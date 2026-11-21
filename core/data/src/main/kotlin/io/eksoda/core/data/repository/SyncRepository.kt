package io.eksoda.core.data.repository

import androidx.paging.PagingData
import io.eksoda.core.model.SyncEvent
import io.eksoda.core.model.SyncJoinResult
import io.eksoda.core.model.SyncPeer
import io.eksoda.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Household sync between phones on the same Wi-Fi (docs/design/household-sync.md): the on/off
 * switch, the paired phones and the sync history. Everything here is local to this phone.
 */
interface SyncRepository {
    /** Off by default. While off, the app opens no socket and announces nothing. */
    val enabled: Flow<Boolean>

    /** Turning sync on the first time makes the household key, unless this phone joined one. */
    suspend fun setEnabled(enabled: Boolean)

    /** Whether this phone holds a household key (it made one, or joined a household). */
    val hasHouseholdKey: Flow<Boolean>

    /**
     * The code another phone scans to join this household (QR text, `PairingCode`). It holds
     * the household key: show it only while asked for, and never keep it.
     */
    suspend fun pairingCode(): Result<String>

    /** Joins the household of the phone whose code [text] is, and turns sync on. */
    suspend fun join(text: String): SyncJoinResult

    /** Forgets the household key and the paired phones and turns sync off; the data stays. */
    suspend fun leave(): Result<Unit>

    /** The paired phones, by name. */
    val peers: Flow<List<SyncPeer>>

    /** The sync history, newest first, paged. */
    fun getHistory(): Flow<PagingData<SyncEvent>>

    /** When the last sync that went through finished; null before the first one. */
    val lastSuccess: Flow<Instant?>

    /** Listening, phones found, syncing: while the app is open. */
    val status: Flow<SyncStatus>

    /** "Sync now": with every household phone it can reach; the outcome goes to the history. */
    suspend fun syncNow()
}
