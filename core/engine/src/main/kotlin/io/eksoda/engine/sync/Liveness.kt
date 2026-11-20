package io.eksoda.engine.sync

/**
 * Which merged rows survive: a tombstone at least as new as the row deletes it, a deleted parent
 * takes its `CASCADE` children along, and a deleted row that a surviving row still points at
 * through `RESTRICT` is kept (a product deleted on one phone while the other matched new lines
 * to it). Repeated until nothing changes: keeping a row can keep its `CASCADE` children.
 */
internal class Liveness(private val entities: Map<String, Map<String, Entity>>) {
    fun resolve() {
        // Each round keeps at least one more row, so this ends; the bound is a safety net.
        repeat(entities.values.sumOf { it.size } + 1) {
            cascade()
            if (!keepReferenced()) return
        }
    }

    private fun cascade() {
        SyncSchema.TABLES.forEach { spec ->
            entities[spec.name]?.values?.forEach { entity ->
                val deleted = entity.tombstoned && !entity.resurrected
                entity.alive = !deleted && spec.foreignKeys.none { cascadesFrom(it, entity) }
            }
        }
    }

    /** A `CASCADE` parent that is gone, or was never there. */
    private fun cascadesFrom(foreignKey: ForeignKeySpec, entity: Entity): Boolean {
        if (foreignKey.onDelete != OnDelete.CASCADE) return false
        val id = entity.value(foreignKey.column) ?: return false
        return entities[foreignKey.parent]?.get(id)?.alive != true
    }

    private fun keepReferenced(): Boolean {
        var changed = false
        SyncSchema.TABLES.forEach { spec ->
            val restricted = spec.foreignKeys.filter { it.onDelete == OnDelete.RESTRICT }
            entities[spec.name]?.values?.filter { it.alive }?.forEach { child ->
                restricted.forEach { foreignKey -> changed = keep(foreignKey, child) || changed }
            }
        }
        return changed
    }

    private fun keep(foreignKey: ForeignKeySpec, child: Entity): Boolean {
        val id = child.value(foreignKey.column) ?: return false
        val parent = entities[foreignKey.parent]?.get(id) ?: return false
        if (parent.alive || !parent.tombstoned || parent.resurrected) return false
        parent.resurrected = true
        return true
    }
}
