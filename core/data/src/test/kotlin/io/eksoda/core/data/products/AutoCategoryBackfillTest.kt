package io.eksoda.core.data.products

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import io.eksoda.core.data.imports.ImportHarness
import io.eksoda.core.data.imports.ImportHarness.Companion.KOGIMOU
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The automatic categories applied to data imported before the rules (a supermarket, a grill). */
@RunWith(AndroidJUnit4::class)
class AutoCategoryBackfillTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness
    private lateinit var scope: CoroutineScope
    private lateinit var backfill: AutoCategoryBackfill

    @Before
    fun setUp() = runTest {
        app = ImportHarness(folder.root)
        listOf(MYMARKET, KOGIMOU).forEach(app.fetcher::serve)
        listOf(MYMARKET, KOGIMOU).forEach { app.import(it) }
        scope = CoroutineScope(SupervisorJob() + app.dispatcher)
        backfill = AutoCategoryBackfill(
            app.database.merchantDao(),
            app.database.productDao(),
            app.coordinator,
            PreferenceDataStoreFactory.create(scope = scope) {
                folder.root.resolve("backfill.preferences_pb")
            },
            app.clock,
        )
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::scope.isInitialized) scope.cancel()
        if (::app.isInitialized) app.close()
    }

    private fun categorizedMerchants() =
        app.count("SELECT COUNT(*) FROM merchant WHERE default_category_id IS NOT NULL")

    private fun categorizedProducts() =
        app.count("SELECT COUNT(*) FROM product WHERE category_id IS NOT NULL")

    /** The data as an import before the automatic categories left it. */
    private fun forgetCategories() {
        val db = app.database.openHelper.writableDatabase
        db.execSQL("UPDATE merchant SET default_category_id = NULL")
        db.execSQL("UPDATE product SET category_id = NULL")
    }

    @Test
    fun `merchants and products without a category get the automatic one`() = runTest {
        val products = categorizedProducts()
        forgetCategories()
        // The grill house's registered activity, known only after its receipt was imported.
        app.database.openHelper.writableDatabase.execSQL(
            "UPDATE merchant SET activity_description = 'ΨΗΤΟΠΩΛΕΙΟ' WHERE id = " +
                "(SELECT merchant_id FROM receipt WHERE gross_total_minor = 2290)",
        )

        backfill.run()
        // The supermarket is a general store: its products are categorized one by one.
        assertThat(categorizedMerchants()).isEqualTo(1L)
        assertThat(categorizedProducts()).isGreaterThan(0L)
        assertThat(categorizedProducts() >= products).isEqualTo(true)
    }

    @Test
    fun `the backfill runs once, so a category removed later stays removed`() = runTest {
        backfill.run()
        forgetCategories()
        backfill.run()
        assertThat(categorizedMerchants()).isEqualTo(0L)
        assertThat(categorizedProducts()).isEqualTo(0L)
    }
}
