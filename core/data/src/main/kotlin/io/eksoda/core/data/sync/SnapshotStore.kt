package io.eksoda.core.data.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.eksoda.engine.sync.MergeResult
import io.eksoda.engine.sync.Row
import io.eksoda.engine.sync.Snapshot
import io.eksoda.engine.sync.SyncException
import io.eksoda.engine.sync.SyncSchema

/**
 * Household sync's side of the database: reads this phone's synced tables as a [Snapshot], and
 * writes a [MergeResult] back. Run both inside one transaction (WriteCoordinator), like the
 * backup: a failure rolls everything back.
 */
internal object SnapshotStore {
    private val TABLES = SyncSchema.TABLES.map { it.name }

    /** Every synced table and the tombstones, all columns as stored (the URL included). */
    fun read(db: SupportSQLiteDatabase): Snapshot = Snapshot(
        db.version,
        (TABLES + SyncSchema.TOMBSTONE).associateWith { rows(db, "SELECT * FROM `$it`") },
    )

    /**
     * Makes the tables equal to [result]: first the rows it dropped or renamed go (children
     * first), then changed rows are updated and new ones inserted (parents first). Deleting a
     * renamed receipt clears its import jobs' link (`SET NULL`), so the links are set again
     * afterwards, to the receipt kept, or the job is marked `receipt_deleted`.
     */
    fun apply(db: SupportSQLiteDatabase, result: MergeResult, now: Long) {
        // Rows are checked against their parents at commit, whatever the order of the writes.
        db.execSQL("PRAGMA defer_foreign_keys = ON")
        val links = jobLinks(db)
        val kept = TABLES.associateWith { table ->
            result.tables[table].orEmpty().associateBy { it.getValue(SyncSchema.ID) as String }
        }
        TABLES.asReversed().forEach { table -> removeDropped(db, table, kept.getValue(table).keys) }
        TABLES.forEach { table -> write(db, table, kept.getValue(table)) }
        writeTombstones(db, result.tables[SyncSchema.TOMBSTONE].orEmpty())
        val receipts = kept.getValue(SyncSchema.RECEIPT).keys
        relinkJobs(db, links, result.remaps[SyncSchema.RECEIPT].orEmpty(), receipts, now)
        db.query("PRAGMA foreign_key_check").use {
            if (it.moveToFirst()) throw SyncException(SyncException.MERGE_FAILED)
        }
    }

    private fun removeDropped(db: SupportSQLiteDatabase, table: String, keep: Set<String>) {
        rows(db, "SELECT id FROM `$table`")
            .map { it.getValue(SyncSchema.ID) as String }
            .filterNot { it in keep }
            .forEach { db.delete(table, "id = ?", arrayOf(it)) }
    }

    /** Only what differs is written, so unchanged rows do not wake every screen's queries. */
    private fun write(db: SupportSQLiteDatabase, table: String, rows: Map<String, Row>) {
        val current = rows(db, "SELECT * FROM `$table`")
            .associateBy { it.getValue(SyncSchema.ID) as String }
        rows.forEach { (id, row) ->
            val stored = current[id]
            when {
                stored == null -> db.insert(table, SQLiteDatabase.CONFLICT_ABORT, row.values())

                row.any { (column, value) -> stored[column] != value } -> db.update(
                    table,
                    SQLiteDatabase.CONFLICT_ABORT,
                    (row - SyncSchema.ID).values(),
                    "id = ?",
                    arrayOf(id),
                )
            }
        }
    }

    /** Tombstones are only ever added or made newer. */
    private fun writeTombstones(db: SupportSQLiteDatabase, rows: List<Row>) {
        val current = rows(db, "SELECT * FROM tombstone").associateBy { it.tombstoneKey() }
        rows.forEach { row ->
            val stored = current[row.tombstoneKey()]
            when {
                stored == null ->
                    db.insert(SyncSchema.TOMBSTONE, SQLiteDatabase.CONFLICT_ABORT, row.values())

                stored != row -> db.update(
                    SyncSchema.TOMBSTONE,
                    SQLiteDatabase.CONFLICT_ABORT,
                    row.values(),
                    "entity = ? AND id = ?",
                    arrayOf(row["entity"], row["id"]),
                )
            }
        }
    }

    /** Import jobs that point at a receipt: job id → receipt id. */
    private fun jobLinks(db: SupportSQLiteDatabase): Map<String, String> =
        rows(db, "SELECT id, receipt_id FROM import_job WHERE receipt_id IS NOT NULL")
            .associate { it.getValue("id") as String to it.getValue("receipt_id") as String }

    private fun relinkJobs(
        db: SupportSQLiteDatabase,
        links: Map<String, String>,
        remaps: Map<String, String>,
        receipts: Set<String>,
        now: Long,
    ) {
        links.forEach { (job, receipt) ->
            val target = remaps[receipt] ?: receipt
            when {
                target !in receipts -> db.execSQL(
                    """UPDATE import_job SET receipt_id = NULL, outcome = 'receipt_deleted',
                       updated_at = ? WHERE id = ?""",
                    arrayOf<Any>(now, job),
                )

                target != receipt -> db.execSQL(
                    "UPDATE import_job SET receipt_id = ?, updated_at = ? WHERE id = ?",
                    arrayOf<Any>(target, now, job),
                )
            }
        }
    }

    private fun Row.tombstoneKey() = get("entity") to get("id")

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<Row> =
        db.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.row()) }
        }

    private fun Cursor.row(): Row = (0 until columnCount).associate { index ->
        getColumnName(index) to when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            else -> getString(index)
        }
    }

    private fun Row.values() = ContentValues().also { values ->
        forEach { (column, value) ->
            when (value) {
                null -> values.putNull(column)
                is Number -> values.put(column, value.toLong())
                else -> values.put(column, value.toString())
            }
        }
    }
}
