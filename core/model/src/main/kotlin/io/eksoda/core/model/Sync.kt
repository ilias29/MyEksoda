package io.eksoda.core.model

import java.time.Instant

/** A phone of the household that this phone syncs with. */
public data class SyncPeer(
    val deviceId: String,
    val name: String,
    val pairedAt: Instant,
    val lastSeenAt: Instant?,
    val lastSuccessAt: Instant?,
)

/** One entry of the sync history. */
public data class SyncEvent(
    val id: String,
    /** The other phone's name at the time; null when no phone was reached. */
    val peerName: String?,
    val startedBy: SyncStart,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val result: SyncResult,
    /** Why it failed (peer_unreachable, not_paired, …), mapped to a local string in the UI. */
    val errorCode: String?,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
)

public enum class SyncStart(public val code: String) {
    /** A paired phone was found on the network. */
    AUTO("auto"),

    /** "Sync now". */
    USER("user"),

    /** The other phone connected to this one (its own automatic sync or its "Sync now"). */
    PEER("peer"),
}

public enum class SyncResult(public val code: String) {
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
}

/** What household sync is doing now, while the app is open. */
public data class SyncStatus(
    /** This phone is reachable by the household's phones on this network. */
    val listening: Boolean = false,
    /** The household's phones found on this network now. */
    val phonesFound: Int = 0,
    val syncing: Boolean = false,
)

/** Scanning another phone's household code. */
public sealed interface SyncJoinResult {
    /** This phone is now of [phoneName]'s household. */
    public data class Joined(val phoneName: String) : SyncJoinResult

    /** The QR code is not an Eksoda household code. */
    public data object NotACode : SyncJoinResult

    /** The QR code is this phone's own. */
    public data object OwnCode : SyncJoinResult

    /** The key could not be stored. */
    public data object Failed : SyncJoinResult
}
