package io.eksoda.core.data.sync

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.eksoda.core.data.backup.BackupArchive
import io.eksoda.core.database.EksodaDatabase
import io.eksoda.engine.sync.MergeResult
import io.eksoda.engine.sync.SyncMerge
import io.eksoda.engine.sync.SyncSchema
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Two phones' databases (plain in-memory Room, without SQLCipher) syncing with each other. */
@RunWith(AndroidJUnit4::class)
class SnapshotStoreTest {
    private lateinit var phoneA: EksodaDatabase
    private lateinit var phoneB: EksodaDatabase
    private lateinit var a: SupportSQLiteDatabase
    private lateinit var b: SupportSQLiteDatabase

    @Before
    fun setUp() {
        phoneA = database()
        phoneB = database()
        a = phoneA.openHelper.writableDatabase
        b = phoneB.openHelper.writableDatabase
        // Both phones imported receipt 400001 and created a product for its line; B also has a
        // second receipt and named the household later.
        a.household("hA", "Σπίτι", updatedAt = 1)
        a.merchant("mA")
        a.product("pA")
        a.mapping("mapA", merchant = "mA", product = "pA")
        a.receipt("rA", merchant = "mA", mark = "400001", url = "https://a.invalid/1")
        a.item("iA", receipt = "rA", product = "pA")
        a.job("jA", receipt = "rA")

        b.household("hB", "Home", updatedAt = 5)
        b.merchant("mB")
        b.product("pB")
        b.mapping("mapB", merchant = "mB", product = "pB")
        b.receipt("rB", merchant = "mB", mark = "400001", url = "https://b.invalid/1")
        b.item("iB", receipt = "rB", product = "pB")
        b.receipt("r2", merchant = "mB", mark = "400002", url = "https://b.invalid/2")
        b.item("i2", receipt = "r2", product = "pB")
        b.job("jB", receipt = "rB")
    }

    @After
    fun tearDown() {
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun `two phones that synced hold the same data, each with only its own links`() {
        sync()

        assertThat(synced(a)).isEqualTo(synced(b))
        assertThat(ids(a, "receipt")).containsExactly("r2", "rA")
        assertThat(ids(a, "merchant")).containsExactly("mA")
        assertThat(ids(a, "product")).containsExactly("pA")
        assertThat(value(a, "SELECT name FROM household")).isEqualTo("Home")
        assertThat(value(a, "SELECT source_url FROM receipt WHERE id = 'rA'"))
            .isEqualTo("https://a.invalid/1")
        assertThat(value(a, "SELECT source_url FROM receipt WHERE id = 'r2'")).isNull()
        assertThat(value(b, "SELECT source_url FROM receipt WHERE id = 'rA'"))
            .isEqualTo("https://b.invalid/1")
        assertThat(value(b, "SELECT source_url FROM receipt WHERE id = 'r2'"))
            .isEqualTo("https://b.invalid/2")
        assertThat(value(b, "SELECT product_id FROM receipt_item WHERE id = 'i2'")).isEqualTo("pA")
        // B's import now points at the receipt kept for the same document.
        assertThat(value(b, "SELECT receipt_id FROM import_job WHERE id = 'jB'")).isEqualTo("rA")
        assertThat(value(a, "SELECT receipt_id FROM import_job WHERE id = 'jA'")).isEqualTo("rA")
    }

    @Test
    fun `syncing again changes nothing`() {
        sync()

        val (onA, onB) = sync()

        listOf(onA, onB).forEach { result ->
            assertThat(Triple(result.inserted, result.updated, result.deleted))
                .isEqualTo(Triple(0, 0, 0))
        }
    }

    @Test
    fun `a receipt deleted on one phone goes from the other, and its import is marked deleted`() {
        sync()
        val key = SyncSchema.naturalKey(SyncSchema.RECEIPT, mapOf("mark" to "400001"))
        a.execSQL("DELETE FROM receipt WHERE id = 'rA'")
        a.execSQL(
            "INSERT INTO tombstone (entity, id, deleted_at, natural_key) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("receipt", "rA", 100L, key),
        )

        val (_, onB) = sync()

        assertThat(onB.deleted).isEqualTo(2)
        assertThat(ids(b, "receipt")).containsExactly("r2")
        assertThat(ids(b, "receipt_item")).containsExactly("i2")
        assertThat(value(b, "SELECT outcome FROM import_job WHERE id = 'jB'"))
            .isEqualTo("receipt_deleted")
        assertThat(value(b, "SELECT receipt_id FROM import_job WHERE id = 'jB'")).isNull()
    }

    @Test
    fun `the synced tables are the backup's tables except the local import queue`() {
        val synced = SyncSchema.TABLES.map { it.name }
        assertThat(synced + SyncSchema.TOMBSTONE + "import_job")
            .containsExactlyInAnyOrder(*BackupArchive.TABLES.toTypedArray())
        // In the same order: parents first.
        assertThat(BackupArchive.TABLES.filter { it in synced }).isEqualTo(synced)
    }

    /** Both phones send their snapshot, then each merges the other's. */
    private fun sync(): Pair<MergeResult, MergeResult> {
        val fromA = transaction(a) { SnapshotStore.read(a) }.forPeer()
        val fromB = transaction(b) { SnapshotStore.read(b) }.forPeer()
        val onA = transaction(a) {
            SyncMerge.merge(SnapshotStore.read(a), fromB).also { SnapshotStore.apply(a, it, NOW) }
        }
        val onB = transaction(b) {
            SyncMerge.merge(SnapshotStore.read(b), fromA).also { SnapshotStore.apply(b, it, NOW) }
        }
        return onA to onB
    }

    /** What both phones must agree on: every synced row, without the links. */
    private fun synced(db: SupportSQLiteDatabase) = SnapshotStore.read(db).forPeer().tables
        .mapValues { (_, rows) -> rows.map { it.toSortedMap() }.sortedBy { it.toString() } }

    private fun ids(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("SELECT id FROM `$table` ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun value(db: SupportSQLiteDatabase, sql: String): String? = db.query(sql).use {
        it.moveToFirst()
        if (it.isNull(0)) null else it.getString(0)
    }

    private fun <T> transaction(db: SupportSQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        EksodaDatabase::class.java,
    ).allowMainThreadQueries().build()

    // --- rows ----------------------------------------------------------------------------------

    private fun SupportSQLiteDatabase.household(id: String, name: String, updatedAt: Long) =
        execSQL(
            "INSERT INTO household (id, name, default_currency, timezone, created_at, " +
                "updated_at) VALUES (?, ?, 'EUR', 'Europe/Athens', 1, ?)",
            arrayOf<Any?>(id, name, updatedAt),
        )

    private fun SupportSQLiteDatabase.merchant(id: String) = execSQL(
        "INSERT INTO merchant (id, country_code, vat_number, legal_name, display_name, " +
            "normalized_display_name, display_name_set_by_user, activity_description, " +
            "tax_office, gemi_number, street, street_number, city, postal_code, details_as_of, " +
            "created_at, updated_at) VALUES (?, 'GR', '094014201', 'ΣΚΛΑΒΕΝΙΤΗΣ ΑΕ', " +
            "'Σκλαβενίτης', 'σκλαβενιτης', 0, '', '', '', '', '', '', '', NULL, 1, 1)",
        arrayOf<Any?>(id),
    )

    private fun SupportSQLiteDatabase.product(id: String) = execSQL(
        "INSERT INTO product (id, name, normalized_name, brand, created_at, updated_at) " +
            "VALUES (?, 'Γάλα', 'γαλα', '', 1, 1)",
        arrayOf<Any?>(id),
    )

    private fun SupportSQLiteDatabase.mapping(id: String, merchant: String, product: String) =
        execSQL(
            "INSERT INTO merchant_product_mapping (id, merchant_id, merchant_product_code, " +
                "normalized_description, last_seen_description, product_id, mapping_key, " +
                "created_at, updated_at) VALUES (?, ?, '123', 'γαλα 1l', 'ΓΑΛΑ 1L', ?, " +
                "'code:123', 1, 1)",
            arrayOf<Any?>(id, merchant, product),
        )

    private fun SupportSQLiteDatabase.receipt(
        id: String,
        merchant: String,
        mark: String,
        url: String,
    ) = execSQL(
        "INSERT INTO receipt (id, merchant_id, store_id, provider_code, parser_version, " +
            "issuer_country, issuer_vat, uid, mark, cancellation_mark, source_key, " +
            "document_kind, document_type_code, document_number, issued_at, " +
            "issued_local_date, currency, gross_total_minor, net_total_minor, vat_total_minor, " +
            "discount_total_minor, payment_method, status, validation_warnings, " +
            "excluded_from_analytics, user_note, source_url, source_url_fingerprint, " +
            "source_data, created_at, updated_at) VALUES (?, ?, NULL, 'impact', '1', 'GR', " +
            "'094014201', NULL, ?, NULL, NULL, 'sale', '11.1', 'A-1', 1763200000000, " +
            "'2026-11-15', 'EUR', 321, NULL, NULL, NULL, 'card', 'active', '[]', 0, '', ?, " +
            "'fp', '{}', 1, 1)",
        arrayOf<Any?>(id, merchant, mark, url),
    )

    private fun SupportSQLiteDatabase.item(id: String, receipt: String, product: String) = execSQL(
        "INSERT INTO receipt_item (id, receipt_id, line_number, line_type, description, " +
            "normalized_description, merchant_product_code, gross_amount_minor, product_id, " +
            "match_status, user_note, created_at, updated_at) VALUES (?, ?, 1, 'product', " +
            "'ΓΑΛΑ 1L', 'γαλα 1l', '123', 321, ?, 'matched', '', 1, 1)",
        arrayOf<Any?>(id, receipt, product),
    )

    private fun SupportSQLiteDatabase.job(id: String, receipt: String) = execSQL(
        "INSERT INTO import_job (id, provider_code, url_identity, submitted_url, " +
            "url_fingerprint, source_host, status, outcome, attempt_count, receipt_id, " +
            "created_at, updated_at) VALUES (?, 'impact', ?, 'https://x.invalid', 'fp', " +
            "'x.invalid', 'completed', 'created', 1, ?, 1, 1)",
        arrayOf<Any?>(id, id, receipt),
    )

    private companion object {
        const val NOW = 1_763_300_000_000L
    }
}
