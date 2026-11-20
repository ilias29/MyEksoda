package io.eksoda.engine.sync

internal enum class Side { LOCAL, REMOTE }

/**
 * A row as one phone has it. [source] is the row as stored there; [row] has its foreign keys,
 * and its id when the id is derived, pointing at the ids the merge keeps.
 */
internal class Member(val side: Side, val originalId: String, val source: Row, val row: Row) {
    val id: String get() = row.getValue(SyncSchema.ID) as String
    val version: Long get() = row[SyncSchema.VERSION] as? Long ?: 0L
}

/** One thing (a receipt, a product, …) and every copy of it the two phones have. */
internal class Entity(val spec: TableSpec, val id: String, val members: List<Member>) {
    /**
     * The copy that wins: the later version, then the larger content, so both phones choose the
     * same one. Local columns are left out of the comparison, since only one phone has them.
     */
    val winner: Member = members.maxWith(
        compareBy<Member> { it.version }.thenBy { content(it) },
    )

    /** A tombstone at least as new as the winner. */
    var tombstoned = false

    /** Deleted, but a surviving row still points at it (`RESTRICT`): kept. */
    var resurrected = false

    var alive = true

    fun value(column: String): String? = winner.row[column] as? String

    /** The winner under the kept id, with this phone's local columns. */
    fun settled(): Row {
        val locals = members.filter { it.side == Side.LOCAL }
        val local = locals.firstOrNull { it.originalId == id } ?: locals.firstOrNull()
        return winner.row - spec.localColumns + (SyncSchema.ID to id) +
            spec.localColumns.associateWith { local?.source?.get(it) }
    }

    /**
     * Without the id: copies are renamed to the kept id, so a tie must be decided the same way
     * before and after (a phone merges the other's already merged data).
     */
    private fun content(member: Member): String =
        (member.row - spec.localColumns - SyncSchema.ID).toSortedMap().toString()
}

/** Groups of ids that name the same thing. */
internal class UnionFind {
    private val parent = HashMap<String, String>()

    fun add(id: String) {
        parent.putIfAbsent(id, id)
    }

    fun union(a: String, b: String) {
        val rootA = find(a)
        val rootB = find(b)
        if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
    }

    fun groups(): Collection<List<String>> = parent.keys.toList().groupBy(::find).values

    private fun find(id: String): String {
        add(id)
        var root = id
        while (parent.getValue(root) != root) root = parent.getValue(root)
        var node = id
        while (node != root) {
            val next = parent.getValue(node)
            parent[node] = root
            node = next
        }
        return root
    }
}
