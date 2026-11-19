package io.eksoda.engine.sync

/**
 * The household's data after merging two phones' snapshots: the same on both phones (apart from
 * their local columns), whichever side merges.
 */
public class MergeResult internal constructor(
    /** Every synced table (parents first) and the tombstones, as they now are. */
    public val tables: Map<String, List<Row>>,
    /** Ids that are no longer used, by table: old id → the id kept for the same thing. */
    public val remaps: Map<String, Map<String, String>>,
    /** Rows this phone did not have, rows it had that changed, and rows it had that are gone. */
    public val inserted: Int,
    public val updated: Int,
    public val deleted: Int,
)

/**
 * Household sync's merge (docs/design/household-sync.md, "Merge rules"). It depends only on the
 * two row sets, never on which phone merges or in what order phones sync, and merging the same
 * snapshot again changes nothing, so all the household's phones end up with the same data.
 */
public object SyncMerge {
    /** Throws [SyncException]: the schemas differ, or [remote] holds rows it cannot read. */
    public fun merge(local: Snapshot, remote: Snapshot): MergeResult {
        if (local.schema != remote.schema) throw SyncException(SyncException.SCHEMA_MISMATCH)
        return try {
            Merger(local, remote).run()
        } catch (e: ClassCastException) {
            throw SyncException(SyncException.DAMAGED, e)
        } catch (e: NoSuchElementException) {
            throw SyncException(SyncException.DAMAGED, e)
        }
    }
}

private class Merger(private val local: Snapshot, private val remote: Snapshot) {
    private val tombstones = Tombstones(
        local.rows(SyncSchema.TOMBSTONE) + remote.rows(SyncSchema.TOMBSTONE),
    )

    /** By table, every id seen → the id kept. */
    private val remaps = HashMap<String, Map<String, String>>()
    private val entities = LinkedHashMap<String, Map<String, Entity>>()

    /** By table, the copies of merged-away products (counted as deleted where they were). */
    private val replaced = HashMap<String, List<Member>>()

    fun run(): MergeResult {
        SyncSchema.TABLES.forEach(::resolve)
        Liveness(entities).resolve()
        return result()
    }

    /** Groups the table's rows into things, after its parents' ids are settled. */
    private fun resolve(spec: TableSpec) {
        val members = members(spec, Side.LOCAL, local) + members(spec, Side.REMOTE, remote)
        val ids = UnionFind()
        members.forEach { ids.add(it.id) }
        val gone = if (spec.name == SyncSchema.PRODUCT) {
            replacedProducts(members, ids)
        } else {
            emptySet()
        }
        val kept = members.filter { it.id !in gone }
        spec.keys.forEachIndexed { index, key -> unionByKey(ids, kept, index, key) }
        if (spec.name == SyncSchema.PRODUCT) unionDuplicateProducts(ids)

        // The smallest id that still has a row, so a merged-away id never comes back.
        val withRows = kept.map { it.id }.toSet()
        val canonical = ids.groups().flatMap { group ->
            val keep = group.filter { it in withRows }.minOrNull() ?: group.min()
            group.map { it to keep }
        }.toMap()
        remaps[spec.name] =
            canonical + members.associate { it.originalId to canonical.getValue(it.id) }
        entities[spec.name] = kept.groupBy { canonical.getValue(it.id) }
            .mapValues { (id, copies) -> Entity(spec, id, copies).also { markDeleted(it) } }
        replaced[spec.name] = members.filter { it.id in gone }
    }

    private fun members(spec: TableSpec, side: Side, snapshot: Snapshot): List<Member> =
        snapshot.rows(spec.name).map { source ->
            val stored = if (side == Side.REMOTE) source - spec.localColumns else source
            val pointed = stored + spec.foreignKeys.mapNotNull { foreignKey ->
                val value = stored[foreignKey.column] as? String ?: return@mapNotNull null
                foreignKey.column to (remaps[foreignKey.parent]?.get(value) ?: value)
            }
            val original = source.getValue(SyncSchema.ID) as String
            val id = spec.derivedId?.invoke(pointed) ?: original
            Member(side, original, source, pointed + (SyncSchema.ID to id))
        }

    private fun unionByKey(ids: UnionFind, members: List<Member>, index: Int, key: List<String>) {
        val first = HashMap<String, String>()
        members.forEach { member ->
            val value = SyncSchema.keyString(index, key, member.row) ?: return@forEach
            first.putIfAbsent(value, member.id)?.let { ids.union(it, member.id) }
        }
    }

    /**
     * Products merged into another on some phone: their ids join the product that replaced
     * them, and their copies are dropped, unless a copy was changed after the merge.
     */
    private fun replacedProducts(members: List<Member>, ids: UnionFind): Set<String> {
        val newest = members.groupBy { it.id }
            .mapValues { (_, copies) -> copies.maxOf { it.version } }
        return tombstones.replacements(SyncSchema.PRODUCT)
            .filter { (product, _, deletedAt) -> (newest[product] ?: Long.MIN_VALUE) <= deletedAt }
            .onEach { (product, target, _) -> ids.union(product, target) }
            .map { (product, _, _) -> product }
            .toSet()
    }

    /**
     * Two phones that each created a product for the same shop line (one mapping key, two
     * mappings): the products are one. A mapping that one phone pointed at another product is
     * the same mapping on both, and its later copy decides; that merges nothing.
     */
    private fun unionDuplicateProducts(ids: UnionFind) {
        val merchants = remaps[SyncSchema.MERCHANT].orEmpty()
        (local.rows(SyncSchema.MAPPING) + remote.rows(SyncSchema.MAPPING))
            .groupBy { mapping ->
                val merchant = mapping["merchant_id"] as? String
                (merchants[merchant] ?: merchant) to mapping["mapping_key"]
            }
            .values
            .forEach { sameLine ->
                val latest = sameLine.groupBy { it[SyncSchema.ID] }.values.map { copies ->
                    copies.maxWith(
                        compareBy<Row> { it[SyncSchema.VERSION] as? Long ?: 0L }
                            .thenBy { it["product_id"] as? String },
                    )
                }
                if (latest.size < 2) return@forEach
                latest.mapNotNull { it["product_id"] as? String }.distinct()
                    .zipWithNext { a, b -> ids.union(a, b) }
            }
    }

    /** A tombstone of any copy (by id, or by unique key) at least as new as the winner. */
    private fun markDeleted(entity: Entity) {
        val table = entity.spec.name
        val byId = entity.members.flatMap { listOf(it.originalId, it.id) }.distinct()
            .mapNotNull { tombstones.byId(table, it)?.deletedAt }
        val byKey = entity.members.flatMap { SyncSchema.naturalKeys(entity.spec, it.row) }
            .distinct()
            .mapNotNull { tombstones.latestByKey(table, it) }
        val deletedAt = (byId + byKey).maxOrNull() ?: return
        entity.tombstoned = deletedAt >= entity.winner.version
    }

    private fun result(): MergeResult {
        val tables = LinkedHashMap<String, List<Row>>()
        var inserted = 0
        var updated = 0
        var deleted = 0
        SyncSchema.TABLES.forEach { spec ->
            val all = entities.getValue(spec.name)
            val rows = all.values.filter { it.alive }.sortedBy { it.id }
                .associate { it.id to it.settled().withoutDeletedParents(spec) }
            tables[spec.name] = rows.values.toList()
            inserted += rows.keys.count { id ->
                all.getValue(id).members.none { it.side == Side.LOCAL }
            }
            val ids = remaps.getValue(spec.name)
            val locals = (all.values.flatMap { it.members } + replaced.getValue(spec.name))
                .filter { it.side == Side.LOCAL }
            locals.forEach { member ->
                val now = ids[member.originalId]?.let(rows::get)
                if (now == null) {
                    deleted++
                } else if (now != member.source) {
                    updated++
                }
            }
        }
        tables[SyncSchema.TOMBSTONE] = tombstones.rows()
        val changed = remaps.mapValues { (_, ids) -> ids.filter { (old, kept) -> old != kept } }
            .filterValues { it.isNotEmpty() }
        return MergeResult(tables, changed, inserted, updated, deleted)
    }

    /** `SET NULL`: a pointer to a row that is gone, or was never there, is cleared. */
    private fun Row.withoutDeletedParents(spec: TableSpec): Row = this + spec.foreignKeys
        .filter { it.onDelete == OnDelete.SET_NULL }
        .filter { foreignKey ->
            val id = this[foreignKey.column] as? String
            id != null && entities[foreignKey.parent]?.get(id)?.alive != true
        }
        .associate { it.column to null }
}
