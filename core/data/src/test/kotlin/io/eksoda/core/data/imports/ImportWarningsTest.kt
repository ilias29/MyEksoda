package io.eksoda.core.data.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import io.eksoda.core.model.ReceiptDetail
import io.eksoda.core.model.ReceiptWarning
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A receipt the engine warns about: the warnings are stored with it and shown with it. Line 7 of
 * the METRO receipt is 1.918 kg × 7.69 = 14.75 with 7.38 off; here its discount cell is changed.
 */
@RunWith(AndroidJUnit4::class)
class ImportWarningsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness

    @Before
    fun setUp() {
        app = ImportHarness(folder.root)
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::app.isInitialized) app.close()
    }

    /** Imports the receipt with line 7's discount printed as [printed]. */
    private suspend fun importWithDiscount(printed: String): ReceiptDetail {
        app.fetcher.serve(MYMARKET) { page ->
            check(DISCOUNT_CELL in page) { "the fixture changed" }
            page.replace(DISCOUNT_CELL, DISCOUNT_CELL.replace("7,38", printed))
        }
        val receiptId = app.receiptOf(app.import(MYMARKET))
        return checkNotNull(app.repository.getReceipt(receiptId).first())
    }

    @Test
    fun `an unreadable discount is stored and shown as one warning`() = runTest {
        val detail = importWithDiscount("50%")
        assertThat(detail.warnings).isEqualTo(listOf(ReceiptWarning("discount_unreadable", null)))
        assertThat(detail.receipt.hasWarnings).isTrue()
        assertThat(app.repository.getRecentReceipts(1).first().single().hasWarnings).isTrue()
        // The discount is left out, not guessed.
        val unread = "SELECT COUNT(*) FROM receipt_item WHERE line_number = 7 " +
            "AND discount_amount_minor IS NULL"
        assertThat(app.count(unread)).isEqualTo(1)
    }

    @Test
    fun `a discount that explains neither the unit price nor the total is shown`() = runTest {
        val detail = importWithDiscount("3,00")
        assertThat(detail.warnings).isEqualTo(
            listOf(
                ReceiptWarning("unit_price_mismatch", 7),
                ReceiptWarning("line_discounts_mismatch", null),
            ),
        )
        assertThat(detail.receipt.hasWarnings).isTrue()
    }

    private companion object {
        /** Line 7's discount cell in the fixture. */
        const val DISCOUNT_CELL = "<span class=\"value\"> 7,38 </span> </span> </span></td>"
    }
}
