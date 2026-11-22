package io.eksoda.core.data.repository

import androidx.paging.testing.asSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import io.eksoda.core.data.imports.ImportHarness
import io.eksoda.core.data.imports.ImportHarness.Companion.KOGIMOU
import io.eksoda.core.data.imports.ImportHarness.Companion.LIDL
import io.eksoda.core.data.imports.ImportHarness.Companion.METRO_SAMPLE
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import io.eksoda.core.model.DocumentKind
import io.eksoda.core.model.ImportOutcome
import io.eksoda.core.model.ImportStatus
import io.eksoda.core.model.Option
import io.eksoda.core.model.Receipt
import io.eksoda.core.model.ReceiptFilter
import io.eksoda.core.model.ReceiptOrdering
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The receipt list and its filters over four receipts: METRO 21.10 (Nov 21) and 9.15 (Nov 7),
 * a grill house 22.90 (Nov 20), LIDL 75.48 (Jul 13).
 */
@RunWith(AndroidJUnit4::class)
class ReceiptListTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness
    private lateinit var options: ReceiptOptionsRepositoryImpl
    private lateinit var grill: String
    private lateinit var lidl: String

    @Before
    fun setUp() = runTest {
        app = ImportHarness(folder.root)
        listOf(MYMARKET, METRO_SAMPLE, KOGIMOU).forEach(app.fetcher::serve)
        app.fetcher.serveEntersoft(LIDL)
        app.import(MYMARKET)
        app.import(METRO_SAMPLE)
        grill = app.receiptOf(app.import(KOGIMOU))
        lidl = app.receiptOf(app.import(LIDL))
        options = ReceiptOptionsRepositoryImpl(app.database.receiptDao())
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::app.isInitialized) app.close()
    }

    private suspend fun list(filter: ReceiptFilter = ReceiptFilter()): List<Receipt> =
        app.repository.getReceipts(filter).asSnapshot()

    private suspend fun totals(filter: ReceiptFilter = ReceiptFilter()): List<String> =
        list(filter).map { it.totalAmount.amount.toPlainString() }

    private fun text(sql: String, vararg args: Any): String =
        app.database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            check(cursor.moveToFirst()) { "no row for $sql" }
            cursor.getString(0)
        }

    @Test
    fun `the list is in the order chosen`() = runTest {
        assertThat(totals()).isEqualTo(listOf("21.10", "22.90", "9.15", "75.48"))
        assertThat(totals(ReceiptFilter(ordering = ReceiptOrdering.OLDEST)))
            .isEqualTo(listOf("75.48", "9.15", "22.90", "21.10"))
        assertThat(totals(ReceiptFilter(ordering = ReceiptOrdering.HIGHEST_TOTAL)))
            .isEqualTo(listOf("75.48", "22.90", "21.10", "9.15"))
        assertThat(totals(ReceiptFilter(ordering = ReceiptOrdering.LOWEST_TOTAL)))
            .isEqualTo(listOf("9.15", "21.10", "22.90", "75.48"))
    }

    @Test
    fun `each receipt shows its merchant, day and lines`() = runTest {
        val newest = list().first()
        assertThat(newest.issuedDate).isEqualTo(LocalDate.of(2026, 11, 21))
        assertThat(newest.itemCount).isEqualTo(7)
        assertThat(newest.documentKind).isEqualTo(DocumentKind.SALE)
        assertThat(newest.merchant.name).isNotEmpty()
    }

    @Test
    fun `dates and totals narrow the list, both ends included`() = runTest {
        val november = ReceiptFilter(issuedFrom = LocalDate.of(2026, 11, 1))
        assertThat(totals(november)).isEqualTo(listOf("21.10", "22.90", "9.15"))
        val untilNov20 = ReceiptFilter(issuedTo = LocalDate.of(2026, 11, 20))
        assertThat(totals(untilNov20)).isEqualTo(listOf("22.90", "9.15", "75.48"))
        assertThat(totals(ReceiptFilter(minTotal = BigDecimal("21.10"))))
            .isEqualTo(listOf("21.10", "22.90", "75.48"))
        assertThat(totals(ReceiptFilter(maxTotal = BigDecimal("21.10"))))
            .isEqualTo(listOf("21.10", "9.15"))
    }

    @Test
    fun `a merchant and its store narrow the list`() = runTest {
        val metro = options.merchantOptions().first().first()
        assertThat(totals(ReceiptFilter(merchant = metro))).isEqualTo(listOf("21.10", "9.15"))
        val store = checkNotNull(list(ReceiptFilter(merchant = metro)).first().store)
        val atStore = list(ReceiptFilter(merchant = metro, store = Option(store.id, store.name)))
        assertThat(atStore.all { it.store?.id == store.id }).isTrue()
        assertThat(atStore).isNotEmpty()
    }

    @Test
    fun `search finds the merchant's name or a line's description`() = runTest {
        val lidlName = list().first { it.id == lidl }.merchant.name
        assertThat(list(ReceiptFilter(query = lidlName.lowercase())).map { it.id })
            .isEqualTo(listOf(lidl))
        val line = checkNotNull(app.repository.getReceipt(grill).first()).items.first()
        assertThat(list(ReceiptFilter(query = line.description)).map { it.id }).contains(grill)
        // Blank text is no search.
        assertThat(list(ReceiptFilter(query = "  ")).size).isEqualTo(4)
    }

    @Test
    fun `a product or its category narrows the list to the receipts with it`() = runTest {
        val product = text(
            "SELECT product_id FROM receipt_item WHERE receipt_id = ? AND product_id IS NOT NULL",
            lidl,
        )
        assertThat(list(ReceiptFilter(product = Option(product, ""))).map { it.id })
            .contains(lidl)
        val category = text(
            "SELECT p.category_id FROM receipt_item i JOIN product p ON p.id = i.product_id " +
                "WHERE i.receipt_id = ? AND p.category_id IS NOT NULL",
            grill,
        )
        assertThat(list(ReceiptFilter(category = Option(category, ""))).map { it.id })
            .contains(grill)
    }

    @Test
    fun `the flags narrow the list to the receipts they describe`() = runTest {
        // One receipt each: a line to review, a warning, a refund, a cancelled receipt.
        val metro = list().first().id
        val sample = list().first { it.totalAmount.amount.toPlainString() == "9.15" }.id
        val db = app.database.openHelper.writableDatabase
        db.execSQL(
            "UPDATE receipt_item SET match_status = 'matched' WHERE match_status = 'needs_review'",
        )
        db.execSQL("UPDATE receipt SET validation_warnings = '[]'")
        db.execSQL(
            "UPDATE receipt_item SET match_status = 'needs_review' WHERE receipt_id = ? " +
                "AND line_number = 1",
            arrayOf(lidl),
        )
        db.execSQL(
            "UPDATE receipt SET validation_warnings = '[\"total_mismatch\"]' WHERE id = ?",
            arrayOf(grill),
        )
        db.execSQL(
            "UPDATE receipt SET document_kind = 'credit_note' WHERE id = ?",
            arrayOf(sample),
        )
        db.execSQL("UPDATE receipt SET status = 'cancelled' WHERE id = ?", arrayOf(metro))

        suspend fun ids(filter: ReceiptFilter) = list(filter).map { it.id }.toSet()
        assertThat(ids(ReceiptFilter(needsReview = true))).isEqualTo(setOf(lidl))
        assertThat(ids(ReceiptFilter(hasWarnings = true))).isEqualTo(setOf(grill))
        assertThat(ids(ReceiptFilter(documentKind = DocumentKind.CREDIT_NOTE)))
            .isEqualTo(setOf(sample))
        assertThat(ids(ReceiptFilter(documentKind = DocumentKind.SALE)))
            .isEqualTo(setOf(metro, grill, lidl))
        assertThat(ids(ReceiptFilter(hideCancelled = true)))
            .isEqualTo(setOf(sample, grill, lidl))
        // The list itself tells which is which.
        val all = list().associateBy { it.id }
        assertThat(all.getValue(metro).cancelled).isTrue()
        assertThat(all.getValue(sample).documentKind).isEqualTo(DocumentKind.CREDIT_NOTE)
        assertThat(all.getValue(grill).hasWarnings).isTrue()
        assertThat(all.getValue(lidl).needsReview).isTrue()
    }

    @Test
    fun `the imports list shows each job with its receipt`() = runTest {
        val all = app.repository.getImportJobs(null).asSnapshot()
        assertThat(all.size).isEqualTo(4)
        assertThat(all.all { it.outcome == ImportOutcome.CREATED && it.receiptId != null }).isTrue()
        assertThat(app.repository.getImportJobs(ImportStatus.COMPLETED).asSnapshot().size)
            .isEqualTo(4)
        assertThat(app.repository.getImportJobs(ImportStatus.FAILED).asSnapshot())
            .isEqualTo(emptyList())
        // Only a failed import can be retried.
        assertThat(app.repository.retryImport(all.first().id).isFailure).isTrue()
    }

    @Test
    fun `the filter options come from the stored receipts`() = runTest {
        val merchants = options.merchantOptions().first()
        assertThat(merchants.size).isEqualTo(3)
        assertThat(options.storeOptions(merchants.first().id).first()).isNotEmpty()
        assertThat(options.categoryOptions().first().size.toLong())
            .isEqualTo(app.count("SELECT COUNT(*) FROM category"))

        assertThat(options.searchProducts("  ")).isEqualTo(emptyList())
        val name = text("SELECT name FROM product ORDER BY name LIMIT 1")
        assertThat(options.searchProducts(name).map { it.name }).contains(name)
    }
}
