package io.eksoda.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.eksoda.core.database.entity.MerchantEntity
import io.eksoda.core.database.entity.ReceiptEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The list rows say which receipts came from another phone, without loading any link. */
@RunWith(AndroidJUnit4::class)
class ReceiptListQueriesTest {
    private lateinit var database: EksodaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksodaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a receipt without a link on this phone is from another phone`() = runTest {
        database.merchantDao().insert(
            MerchantEntity(
                id = MERCHANT,
                countryCode = "GR",
                vatNumber = "111111111",
                legalName = "Shop",
                displayName = "Shop",
                normalizedDisplayName = "shop",
                detailsAsOf = null,
                defaultCategoryId = null,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val receipts = database.receiptDao()
        receipts.insert(receipt("scanned", sourceUrl = "https://example.invalid/r"))
        receipts.insert(receipt("synced", sourceUrl = null))

        val rows = receipts.observeRecent(limit = 10).first()
        assertThat(rows.associate { it.id to it.fromOtherPhone })
            .isEqualTo(mapOf("scanned" to false, "synced" to true))
    }

    private fun receipt(id: String, sourceUrl: String?) = ReceiptEntity(
        id = id,
        merchantId = MERCHANT,
        storeId = null,
        providerCode = "impact",
        parserVersion = "1",
        issuerCountry = "GR",
        issuerVat = "111111111",
        uid = id,
        mark = null,
        cancellationMark = null,
        sourceKey = null,
        documentKind = "sale",
        documentTypeCode = null,
        documentNumber = id,
        issuedAt = NOW,
        issuedLocalDate = "2026-11-10",
        currency = "EUR",
        grossTotalMinor = 1_000,
        netTotalMinor = null,
        vatTotalMinor = null,
        discountTotalMinor = null,
        paymentMethod = "card",
        status = "active",
        validationWarnings = "[]",
        sourceUrl = sourceUrl,
        sourceUrlFingerprint = id,
        sourceData = "{}",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private companion object {
        const val NOW = 1_763_200_000_000
        const val MERCHANT = "m-shop"
    }
}
