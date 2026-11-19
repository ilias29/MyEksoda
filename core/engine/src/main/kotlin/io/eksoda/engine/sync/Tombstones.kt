package io.eksoda.engine.sync

/**
 * A deleted row. [naturalKey] reaches the copy another phone imported under its own id
 * ([SyncSchema.naturalKey]); [replacedBy] is set when products were merged: what pointed at the
 * deleted one points at that one.
 */
internal class Tombstone(
    val entity: String,
    val id: String,
    val deletedAt: Long,
    val naturalKey: String?,
    val replacedBy: String?,
) {
    fun toRow(): Row = mapOf(
        "entity" to entity,
        "id" to id,
        "deleted_at" to deletedAt,
        "natural_key" to naturalKey,
        "replaced_by" to replacedBy,
    )
}

/** The tombstones of both phones: one per (entity, id), the latest deletion. */
internal class Tombstones(rows: List<Row>) {
    val all: List<Tombstone> = rows.map(::parse)
        .groupBy { it.entity to it.id }
        .values
        .map { same ->
            val latest = same.maxWith(
                compareBy<Tombstone> { it.deletedAt }.thenBy { it.replacedBy.orEmpty() },
            )
            Tombstone(
                entity = latest.entity,
                id = latest.id,
                deletedAt = latest.deletedAt,
                naturalKey = same.mapNotNull { it.naturalKey }.maxOrNull(),
                replacedBy = latest.replacedBy,
            )
        }
        .sortedWith(compareBy({ it.entity }, { it.id }))

    private val byId = all.associateBy { it.entity to it.id }

    private val byKey: Map<Pair<String, String>, Long> = all
        .mapNotNull { tombstone ->
            tombstone.naturalKey?.let { (tombstone.entity to it) to tombstone }
        }
        .groupBy({ it.first }, { it.second.deletedAt })
        .mapValues { (_, times) -> times.max() }

    fun byId(entity: String, id: String): Tombstone? = byId[entity to id]

    fun latestByKey(entity: String, key: String): Long? = byKey[entity to key]

    /** Merged-away rows of [entity]: (id, the id that replaced it, when). */
    fun replacements(entity: String): List<Triple<String, String, Long>> = all
        .filter { it.entity == entity }
        .mapNotNull { tombstone ->
            tombstone.replacedBy?.let { Triple(tombstone.id, it, tombstone.deletedAt) }
        }

    fun rows(): List<Row> = all.map { it.toRow() }

    private fun parse(row: Row) = Tombstone(
        entity = row.getValue("entity") as String,
        id = row.getValue("id") as String,
        deletedAt = row.getValue("deleted_at") as Long,
        naturalKey = row["natural_key"] as? String,
        replacedBy = row["replaced_by"] as? String,
    )
}
