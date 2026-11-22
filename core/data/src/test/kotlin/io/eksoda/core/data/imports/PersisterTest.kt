package io.eksoda.core.data.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import io.eksoda.engine.canonical.Address
import io.eksoda.engine.canonical.Branch
import io.eksoda.engine.canonical.CanonicalReceipt
import io.eksoda.engine.canonical.Identity
import io.eksoda.engine.merchants.deriveDisplayName
import io.eksoda.engine.validation.ValidationIssue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * Storing parsed receipts: METRO's receipt as parsed, and copies of it changed the way later
 * receipts of the same merchant change (another name or address, another branch).
 */
@RunWith(AndroidJUnit4::class)
class PersisterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness
    private lateinit var base: CanonicalReceipt
    private var receipts = 0

    @Before
    fun setUp() = runTest {
        app = ImportHarness(folder.root)
        app.fetcher.serve(MYMARKET)
        base = app.engine.read(app.engine.resolve(Fixtures.url(MYMARKET))).receipt
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::app.isInitialized) app.close()
    }

    /** Stores [receipt] as a receipt of its own (fresh identity), [days] after the parsed one. */
    private suspend fun store(
        receipt: CanonicalReceipt,
        days: Long = 0,
        issues: List<ValidationIssue> = emptyList(),
        identity: Identity = Identity(mark = "40000000000${receipts++}"),
    ): Persisted {
        val document = receipt.document.copy(issuedAt = receipt.document.issuedAt.plusDays(days))
        val copy = receipt.copy(identity = identity, document = document)
        return app.coordinator.write { app.persister.persist(copy, issues, ZONE) }
    }

    private fun merchant(column: String): String? = app.text("SELECT $column FROM merchant")

    /** [receipt] with its lines in lower case, as another till might print them. */
    private fun respelled(receipt: CanonicalReceipt) = receipt.copy(
        lines = receipt.lines.map { it.copy(description = it.description.lowercase()) },
    )

    /** The product of each line of a stored receipt, in line order. */
    private fun productsOf(receiptId: String): List<String?> =
        app.database.openHelper.readableDatabase.query(
            "SELECT product_id FROM receipt_item WHERE receipt_id = ? ORDER BY line_number",
            arrayOf(receiptId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun storeOf(receiptId: String): String? =
        app.text("SELECT store_id FROM receipt WHERE id = ?", receiptId)

    private fun withIssuer(legalName: String, street: String) = base.copy(
        issuer = base.issuer.copy(
            legalName = legalName,
            address = (base.issuer.address ?: Address()).copy(street = street),
        ),
    )

    @Test
    fun `a newer receipt with a new name and address updates the merchant and its name`() =
        runTest {
            store(base)
            store(withIssuer("ΝΕΑ ΕΠΩΝΥΜΙΑ ΜΟΝΟΠΡΟΣΩΠΗ Α.Ε.", "ΝΕΑ ΟΔΟΣ"), days = 1)
            assertThat(app.count("SELECT COUNT(*) FROM merchant")).isEqualTo(1)
            assertThat(merchant("legal_name")).isEqualTo("ΝΕΑ ΕΠΩΝΥΜΙΑ ΜΟΝΟΠΡΟΣΩΠΗ Α.Ε.")
            assertThat(merchant("street")).isEqualTo("ΝΕΑ ΟΔΟΣ")
            assertThat(merchant("display_name"))
                .isEqualTo(deriveDisplayName("ΝΕΑ ΕΠΩΝΥΜΙΑ ΜΟΝΟΠΡΟΣΩΠΗ Α.Ε."))
        }

    @Test
    fun `an older receipt leaves the merchant's details as they are`() = runTest {
        store(base)
        val street = merchant("street")
        store(withIssuer("ΠΑΛΙΑ ΕΠΩΝΥΜΙΑ Α.Ε.", "ΠΑΛΙΑ ΟΔΟΣ"), days = -30)
        assertThat(merchant("legal_name")).isEqualTo(base.issuer.legalName.trim())
        assertThat(merchant("street")).isEqualTo(street)
    }

    @Test
    fun `a name the user gave the merchant survives a new legal name`() = runTest {
        store(base)
        app.database.openHelper.writableDatabase.execSQL(
            "UPDATE merchant SET display_name = 'Το METRO μας', display_name_set_by_user = 1",
        )
        store(withIssuer("ΝΕΑ ΕΠΩΝΥΜΙΑ Α.Ε.", "ΝΕΑ ΟΔΟΣ"), days = 1)
        assertThat(merchant("legal_name")).isEqualTo("ΝΕΑ ΕΠΩΝΥΜΙΑ Α.Ε.")
        assertThat(merchant("display_name")).isEqualTo("Το METRO μας")
    }

    @Test
    fun `a branch keeps its store, whose address follows the newer receipts`() = runTest {
        val athens =
            Address(street = "ΣΤΑΔΙΟΥ", number = "10", city = "ΑΘΗΝΑ", postalCode = "10564")
        val first = store(base.copy(branch = Branch("7", athens)))
        val store = checkNotNull(storeOf(first.receiptId))
        val key = app.text("SELECT normalized_address FROM store WHERE id = ?", store)
        val moved = athens.copy(street = "ΠΑΝΕΠΙΣΤΗΜΙΟΥ", number = "20")
        val later = store(base.copy(branch = Branch("7", moved)), days = 1)
        val earlier = store(base.copy(branch = Branch("7", athens)), days = -1)
        assertThat(listOf(storeOf(later.receiptId), storeOf(earlier.receiptId)))
            .isEqualTo(listOf(store, store))
        assertThat(app.text("SELECT street FROM store WHERE id = ?", store))
            .isEqualTo("ΠΑΝΕΠΙΣΤΗΜΙΟΥ 20")
        // Its address key moved with it.
        assertThat(app.text("SELECT normalized_address FROM store WHERE id = ?", store))
            .isNotEqualTo(key)
    }

    @Test
    fun `without a branch number the address is the store, and without either there is none`() =
        runTest {
            val athens = Address(street = "ΣΤΑΔΙΟΥ", number = "10", city = "ΑΘΗΝΑ")
            val one = store(base.copy(branch = Branch(null, athens)))
            val two = store(base.copy(branch = Branch("", athens)), days = 1)
            assertThat(storeOf(two.receiptId)).isEqualTo(storeOf(one.receiptId))
            // An address-keyed store is not refreshed.
            assertThat(app.text("SELECT street FROM store")).isEqualTo("ΣΤΑΔΙΟΥ 10")

            val none = store(base.copy(branch = Branch(null, Address())))
            assertThat(storeOf(none.receiptId)).isNull()
            val noBranch = store(base.copy(branch = null))
            assertThat(storeOf(noBranch.receiptId)).isNull()
        }

    @Test
    fun `a line printed with another spelling stays the same product, code or not`() = runTest {
        val plain = base.copy(lines = base.lines.map { it.copy(merchantProductCode = null) })
        val coded = store(base)
        val uncoded = store(plain)
        val codedAgain = store(respelled(base), days = 1)
        val uncodedAgain = store(respelled(plain), days = 1)
        assertThat(productsOf(codedAgain.receiptId)).isEqualTo(productsOf(coded.receiptId))
        assertThat(productsOf(uncodedAgain.receiptId)).isEqualTo(productsOf(uncoded.receiptId))
        // The mappings remember the newest spelling.
        val newest = respelled(base).lines.first().description
        assertThat(
            app.count(
                "SELECT COUNT(*) FROM merchant_product_mapping " +
                    "WHERE last_seen_description = '${newest.replace("'", "''")}'",
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `validation warnings are stored with their line and amounts`() = runTest {
        val issues = listOf(
            ValidationIssue("line_total", "line 2 does not add up", 2, "1.00", "1.10"),
            ValidationIssue("totals", "the totals do not add up"),
        )
        val stored = store(base, issues = issues)
        val warnings = checkNotNull(
            app.text("SELECT validation_warnings FROM receipt WHERE id = ?", stored.receiptId),
        )
        assertThat(warnings).contains("\"line_number\":2")
        assertThat(warnings).contains("\"expected\":\"1.00\"")
        assertThat(warnings).contains("\"actual\":null")
    }

    @Test
    fun `a cancelled receipt is stored as cancelled`() = runTest {
        val cancelled = store(base, identity = Identity(mark = "1", cancellationMark = "400000123"))
        val zeros = store(base, identity = Identity(mark = "2", cancellationMark = "000"))
        assertThat(app.text("SELECT status FROM receipt WHERE id = ?", cancelled.receiptId))
            .isEqualTo("cancelled")
        assertThat(app.text("SELECT status FROM receipt WHERE id = ?", zeros.receiptId))
            .isEqualTo("active")
    }

    @Test
    fun `a receipt without any key is never taken for another`() = runTest {
        val one = store(base, identity = Identity())
        val two = store(base, identity = Identity(mark = "", uid = "", sourceKey = ""))
        assertThat(two.receiptId).isNotEqualTo(one.receiptId)
        assertThat(listOf(one.outcome, two.outcome))
            .isEqualTo(listOf(Persister.OUTCOME_CREATED, Persister.OUTCOME_CREATED))
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Athens")
    }
}
