package io.eksoda.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.eksoda.core.data.imports.ImportHarness
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import io.eksoda.core.model.DocumentKind
import io.eksoda.core.model.Option
import io.eksoda.core.model.ReceiptFilter
import io.eksoda.core.model.ReceiptOrdering
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The receipt list's filters, kept between app starts. Today is 2026-11-22. */
@RunWith(AndroidJUnit4::class)
class ReceiptFilterStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var store: ReceiptFilterStoreImpl
    private lateinit var merchant: Option
    private lateinit var shop: Option
    private lateinit var category: Option
    private lateinit var product: Option

    @Before
    fun setUp() = runTest {
        app = ImportHarness(folder.root)
        app.fetcher.serve(MYMARKET)
        app.import(MYMARKET)
        scope = CoroutineScope(SupervisorJob() + app.dispatcher)
        preferences = PreferenceDataStoreFactory.create(scope = scope) {
            folder.root.resolve("filters.preferences_pb")
        }
        store = storeOn(app.clock)
        val options = ReceiptOptionsRepositoryImpl(app.database.receiptDao())
        merchant = options.merchantOptions().first().single()
        shop = options.storeOptions(merchant.id).first().first()
        category = options.categoryOptions().first().first()
        product = options.searchProducts(someProductName()).first()
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::scope.isInitialized) scope.cancel()
        if (::app.isInitialized) app.close()
    }

    private fun storeOn(clock: Clock) = ReceiptFilterStoreImpl(
        preferences,
        app.database.receiptDao(),
        HouseholdZone(app.database.householdDao()),
        clock,
    )

    /** The name of one of the imported products. */
    private fun someProductName(): String = app.database.openHelper.readableDatabase
        .query("SELECT name FROM product ORDER BY name LIMIT 1").use { cursor ->
            check(cursor.moveToFirst()) { "no products" }
            cursor.getString(0)
        }

    private suspend fun saveRaw(json: String) {
        preferences.edit { it[stringPreferencesKey("receipt_filter")] = json }
    }

    @Test
    fun `nothing saved is no filter`() = runTest {
        assertThat(store.load()).isEqualTo(ReceiptFilter())
    }

    @Test
    fun `every filter comes back as it was saved, except the search text`() = runTest {
        val filter = ReceiptFilter(
            query = "milk",
            merchant = merchant,
            store = shop,
            category = category,
            product = product,
            issuedFrom = LocalDate.of(2026, 11, 1),
            issuedTo = LocalDate.of(2026, 11, 30),
            minTotal = BigDecimal("1.50"),
            maxTotal = BigDecimal("99.99"),
            needsReview = true,
            hasWarnings = true,
            documentKind = DocumentKind.CREDIT_NOTE,
            hideCancelled = true,
            ordering = ReceiptOrdering.HIGHEST_TOTAL,
        )
        store.save(filter)
        assertThat(store.load()).isEqualTo(filter.copy(query = null))
    }

    @Test
    fun `the names are not saved, only ids, dates, amounts and flags`() = runTest {
        store.save(ReceiptFilter(merchant = merchant, product = product))
        val saved = checkNotNull(preferences.data.first()[stringPreferencesKey("receipt_filter")])
        assertThat(saved).doesNotContain(merchant.name)
        assertThat(saved).doesNotContain(product.name)
    }

    @Test
    fun `a period saved in an earlier month becomes this month`() = runTest {
        val october = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC)
        val period = ReceiptFilter(
            issuedFrom = LocalDate.of(2026, 10, 1),
            issuedTo = LocalDate.of(2026, 10, 31),
        )
        storeOn(october).save(period)
        assertThat(store.load()).isEqualTo(
            ReceiptFilter(
                issuedFrom = LocalDate.of(2026, 11, 1),
                issuedTo = LocalDate.of(2026, 11, 30),
            ),
        )
        // Without a period there is nothing to move.
        storeOn(october).save(ReceiptFilter(needsReview = true))
        assertThat(store.load()).isEqualTo(ReceiptFilter(needsReview = true))
    }

    @Test
    fun `what is gone or unreadable is left out`() = runTest {
        saveRaw(
            """{"month":"2026-11","merchant":"gone","store":"${shop.id}","category":"gone",
               |"product":"gone","issued_from":"not a date","min_total":"lots",
               |"document_kind":"invoice","ordering":"RANDOM"}
            """.trimMargin(),
        )
        assertThat(store.load()).isEqualTo(ReceiptFilter())
        // A store is only kept with its merchant.
        saveRaw("""{"merchant":"${merchant.id}","store":"another-merchants-store"}""")
        val loaded = store.load()
        assertThat(loaded.merchant).isEqualTo(merchant)
        assertThat(loaded.store).isNull()
    }

    @Test
    fun `a damaged file is no filter`() = runTest {
        saveRaw("{not json")
        assertThat(store.load()).isEqualTo(ReceiptFilter())
    }
}
