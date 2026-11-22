package io.eksoda.core.data.repository

import androidx.paging.testing.asSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.eksoda.core.data.imports.ImportHarness
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import io.eksoda.core.data.imports.ProductMatcher
import io.eksoda.core.data.products.ReviewService
import io.eksoda.core.data.util.Fixed
import io.eksoda.core.database.entity.ReceiptItemEntity
import io.eksoda.core.model.Category
import io.eksoda.core.model.Merchant
import io.eksoda.core.model.ProductFilter
import io.eksoda.core.model.ProductOrdering
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The paged product and review lists, on an imported receipt (METRO, 7 lines). */
@RunWith(AndroidJUnit4::class)
class ProductListsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness
    private lateinit var products: ProductRepositoryImpl
    private lateinit var review: ReviewRepositoryImpl
    private lateinit var receiptId: String

    @Before
    fun setUp() = runTest {
        app = ImportHarness(folder.root)
        app.fetcher.serve(MYMARKET)
        receiptId = app.receiptOf(app.import(MYMARKET))
        val dao = app.database.productDao()
        val zone = HouseholdZone(app.database.householdDao())
        val service = ReviewService(
            dao,
            app.database.tombstoneDao(),
            ProductMatcher(dao, app.clock),
            app.clock,
        )
        products = ProductRepositoryImpl(dao, service, app.coordinator, zone, app.dispatcher)
        review = ReviewRepositoryImpl(dao, service, app.coordinator, zone)
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::app.isInitialized) app.close()
    }

    /** The receipt's line [number]. */
    private suspend fun line(number: Int): ReceiptItemEntity =
        checkNotNull(app.lines(receiptId)[number]).item

    @Test
    fun `the product list is sorted by name or by lines, and narrowed by text, category, shop`() =
        runTest {
            val all = products.getProducts(ProductFilter()).asSnapshot()
            assertThat(all.size.toLong()).isEqualTo(app.count("SELECT COUNT(*) FROM product"))

            // Line 1's product bought twice: first by lines.
            val chocolate = checkNotNull(line(1).productId)
            products.mergeProducts(chocolate, setOf(checkNotNull(line(2).productId))).getOrThrow()
            val mostBought = ProductFilter(ordering = ProductOrdering.MOST_BOUGHT)
            val byLines = products.getProducts(mostBought).asSnapshot()
            assertThat(byLines.first().id).isEqualTo(chocolate)
            assertThat(byLines.first().lineCount).isEqualTo(2)

            val name = byLines.first().name
            val found = products.getProducts(ProductFilter(query = name.take(5))).asSnapshot()
            assertThat(found.map { it.id }.contains(chocolate)).isTrue()
            val merchantId = checkNotNull(app.text("SELECT id FROM merchant"))
            val atShop = ProductFilter(merchant = Merchant(merchantId, ""))
            assertThat(products.getProducts(atShop).asSnapshot().size).isEqualTo(byLines.size)
            val elsewhere = ProductFilter(merchant = Merchant("another-shop", ""))
            assertThat(products.getProducts(elsewhere).asSnapshot()).isEqualTo(emptyList())

            val category = checkNotNull(app.text("SELECT id FROM category ORDER BY slug LIMIT 1"))
            app.database.openHelper.writableDatabase.execSQL(
                "UPDATE product SET category_id = ? WHERE id = ?",
                arrayOf(category, chocolate),
            )
            val inCategory = ProductFilter(category = Category(category, "", "", true))
            val categorized = products.getProducts(inCategory).asSnapshot()
            assertThat(categorized.map { it.id }.contains(chocolate)).isTrue()
            assertThat(categorized.all { it.category?.id == category }).isTrue()
        }

    @Test
    fun `the review list shows each line to review with its receipt and merchant`() = runTest {
        // A product line nobody has looked at yet.
        val line = line(3)
        app.database.openHelper.writableDatabase.execSQL(
            "UPDATE receipt_item SET match_status = 'needs_review' WHERE id = ?",
            arrayOf(line.id),
        )
        val items = review.getReviewItems().asSnapshot()
        assertThat(items.size.toLong()).isEqualTo(
            app.count("SELECT COUNT(*) FROM receipt_item WHERE match_status = 'needs_review'"),
        )
        val item = items.first { it.id == line.id }
        assertThat(item.receiptId).isEqualTo(receiptId)
        assertThat(item.description).isEqualTo(line.description)
        val amount = Fixed.toDecimal(line.grossAmountMinor, Fixed.MINOR)
        assertThat(item.grossAmount.amount.compareTo(amount)).isEqualTo(0)
        assertThat(item.merchantName.isNotEmpty()).isTrue()
    }
}
