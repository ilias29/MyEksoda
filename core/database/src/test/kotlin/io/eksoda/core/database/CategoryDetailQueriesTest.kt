package io.eksoda.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import io.eksoda.core.database.dao.AnalyticsDao
import io.eksoda.core.database.entity.CategoryEntity
import io.eksoda.core.database.entity.MerchantEntity
import io.eksoda.core.database.entity.ProductEntity
import io.eksoda.core.database.entity.ReceiptEntity
import io.eksoda.core.database.entity.ReceiptItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queries behind one category opened from Insights count it exactly like its bucket there:
 * product lines (the product's category, else the merchant default) plus what the receipts of
 * merchants whose default it is hold beyond their lines.
 */
@RunWith(AndroidJUnit4::class)
class CategoryDetailQueriesTest {
    private lateinit var database: EksodaDatabase
    private lateinit var analytics: AnalyticsDao

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksodaDatabase::class.java,
        ).allowMainThreadQueries().build()
        analytics = database.analyticsDao()
        seed()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `products, shops and the part off the lines add up to the category's bucket`() = runTest {
        val products = analytics.categoryProducts(MEAT, START, END, EUR)
        // The sausage was returned, so it nets 0 and is left out.
        assertThat(products.map { Triple(it.productId, it.receipts, it.totalMinor) })
            .containsExactlyInAnyOrder(Triple(STEAK, 2, 3_000L))

        val lines = analytics.categoryLinesByMerchant(MEAT, START, END, EUR)
            .associate { it.merchantId to it.totalMinor }
        assertThat(lines).isEqualTo(mapOf(BUTCHER to 2_000L, MARKET to 1_000L))

        // The butcher's sale with a bag (2.00 off the lines) and its sale with no lines (12.00).
        val remainders = analytics.categoryRemaindersByMerchant(MEAT, START, END, EUR)
            .associate { it.merchantId to it.totalMinor }
        assertThat(remainders).isEqualTo(mapOf(BUTCHER to 1_400L))

        // The Insights bucket: lines of the category, plus receipts minus lines by default.
        val bucket = analytics.categoryLines(START, END, EUR)
            .filter { it.lineType == "product" && it.categoryId == MEAT }.sumOf { it.totalMinor } +
            analytics.receiptTotalsByDefaultCategory(START, END, EUR)
                .filter { it.defaultCategoryId == MEAT }.sumOf { it.totalMinor } -
            analytics.lineTotalsByDefaultCategory(START, END, EUR)
                .filter { it.defaultCategoryId == MEAT }.sumOf { it.totalMinor }
        assertThat(lines.values.sum() + remainders.values.sum()).isEqualTo(bucket)
        assertThat(bucket).isEqualTo(4_400L)
    }

    @Test
    fun `a line with no product is listed by its text`() = runTest {
        val products = analytics.categoryProducts(DAIRY, START, END, EUR)
        assertThat(products.map { Triple(it.productId, it.name, it.totalMinor) })
            .containsExactlyInAnyOrder(
                Triple(MILK, "Milk", 500L),
                Triple(null, "FETA PDO", 700L),
            )
    }

    @Test
    fun `no category means products with none at shops with no default`() = runTest {
        // The sausage at the butcher counts to the butcher's default (meat), not here.
        val products = analytics.categoryProducts(null, START, END, EUR)
        assertThat(products.map { Triple(it.productId, it.name, it.totalMinor) })
            .containsExactlyInAnyOrder(
                Triple(SAUSAGE, "Sausage", 300L),
                Triple(null, "GUM", 150L),
            )
        val shops = analytics.categoryLinesByMerchant(null, START, END, EUR)
            .associate { it.merchantId to it.totalMinor }
        assertThat(shops).isEqualTo(mapOf(KIOSK to 450L))
        // The same as the Insights bucket.
        val bucket = analytics.categoryLines(START, END, EUR)
            .filter { it.lineType == "product" && it.categoryId == null }.sumOf { it.totalMinor }
        assertThat(bucket).isEqualTo(450L)
    }

    private suspend fun seed() {
        val categories = database.categoryDao()
        categories.insert(CategoryEntity(MEAT, "meat", "Meat", true, NOW, NOW))
        categories.insert(CategoryEntity(DAIRY, "dairy", "Dairy", true, NOW, NOW))
        val merchants = database.merchantDao()
        merchants.insert(merchant(BUTCHER, "111111111", "Butcher", defaultCategory = MEAT))
        merchants.insert(merchant(MARKET, "222222222", "Market", defaultCategory = DAIRY))
        merchants.insert(merchant(KIOSK, "333333333", "Kiosk", defaultCategory = null))
        val products = database.productDao()
        products.insert(product(STEAK, "Steak", MEAT))
        products.insert(product(SAUSAGE, "Sausage", null))
        products.insert(product(MILK, "Milk", DAIRY))
        val receipts = database.receiptDao()
        // Steak 20.00, a sausage (no category: the butcher's default) 8.00, and a 2.00 bag.
        receipts.insert(receipt("r1", BUTCHER, 3_000))
        receipts.insertItems(listOf(item("r1", 1, STEAK, 2_000), item("r1", 2, SAUSAGE, 800)))
        // Steak 10.00 and milk 5.00 at the market, whose default is dairy; feta not matched.
        receipts.insert(receipt("r2", MARKET, 2_200))
        receipts.insertItems(
            listOf(
                item("r2", 1, STEAK, 1_000),
                item("r2", 2, MILK, 500),
                item("r2", 3, null, 700, "FETA PDO"),
            ),
        )
        // The sausage returned.
        receipts.insert(receipt("r3", BUTCHER, 800, kind = "credit_note"))
        receipts.insertItems(listOf(item("r3", 1, SAUSAGE, 800)))
        // A butcher's receipt with no lines.
        receipts.insert(receipt("r4", BUTCHER, 1_200))
        // At a kiosk with no default category: a sausage and gum not matched to a product.
        receipts.insert(receipt("r6", KIOSK, 450))
        receipts.insertItems(listOf(item("r6", 1, SAUSAGE, 300), item("r6", 2, null, 150, "GUM")))
        // Outside the period.
        receipts.insert(receipt("r5", MARKET, 5_000, date = "2026-10-31"))
        receipts.insertItems(listOf(item("r5", 1, STEAK, 5_000)))
    }

    private fun product(id: String, name: String, categoryId: String?) = ProductEntity(
        id = id,
        name = name,
        normalizedName = name.lowercase(),
        categoryId = categoryId,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun merchant(id: String, vat: String, name: String, defaultCategory: String?) =
        MerchantEntity(
            id = id,
            countryCode = "GR",
            vatNumber = vat,
            legalName = name,
            displayName = name,
            normalizedDisplayName = name.lowercase(),
            detailsAsOf = null,
            defaultCategoryId = defaultCategory,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun receipt(
        id: String,
        merchantId: String,
        totalMinor: Long,
        kind: String = "sale",
        date: String = "2026-11-10",
    ) = ReceiptEntity(
        id = id,
        merchantId = merchantId,
        storeId = null,
        providerCode = "impact",
        parserVersion = "1",
        issuerCountry = "GR",
        issuerVat = merchantId,
        uid = id,
        mark = null,
        cancellationMark = null,
        sourceKey = null,
        documentKind = kind,
        documentTypeCode = null,
        documentNumber = id,
        issuedAt = NOW,
        issuedLocalDate = date,
        currency = EUR,
        grossTotalMinor = totalMinor,
        netTotalMinor = null,
        vatTotalMinor = null,
        discountTotalMinor = null,
        paymentMethod = "card",
        status = "active",
        validationWarnings = "[]",
        sourceUrl = "",
        sourceUrlFingerprint = id,
        sourceData = "{}",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun item(
        receiptId: String,
        line: Int,
        productId: String?,
        grossMinor: Long,
        description: String = productId.orEmpty(),
    ) = ReceiptItemEntity(
        id = "$receiptId-$line",
        receiptId = receiptId,
        lineNumber = line,
        lineType = "product",
        description = description,
        normalizedDescription = description.lowercase(),
        merchantProductCode = null,
        quantityE4 = 10_000,
        unit = "piece",
        unitPriceE4 = null,
        grossAmountMinor = grossMinor,
        netAmountMinor = null,
        vatRateE2 = null,
        vatAmountMinor = null,
        discountAmountMinor = null,
        productId = productId,
        matchStatus = if (productId == null) "needs_review" else "matched",
        normalizedQuantityE6 = null,
        normalizedUnit = null,
        normalizedUnitPriceE4 = null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        const val NOW = 1_763_200_000_000
        const val START = "2026-11-01"
        const val END = "2026-11-30"
        const val EUR = "EUR"
        const val MEAT = "c-meat"
        const val DAIRY = "c-dairy"
        const val BUTCHER = "m-butcher"
        const val MARKET = "m-market"
        const val KIOSK = "m-kiosk"
        const val STEAK = "p-steak"
        const val SAUSAGE = "p-sausage"
        const val MILK = "p-milk"
    }
}
