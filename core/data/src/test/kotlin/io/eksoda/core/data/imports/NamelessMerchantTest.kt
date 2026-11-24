package io.eksoda.core.data.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.eksoda.core.data.imports.ImportHarness.Companion.MYMARKET
import io.eksoda.engine.canonical.CanonicalReceipt
import io.eksoda.engine.canonical.Identity
import io.eksoda.engine.merchants.deriveDisplayName
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * A merchant whose receipts may carry no name (an Epsilon XML with only the VAT number): it is
 * called by its VAT number until a receipt names it, and a nameless receipt never drops a name.
 */
@RunWith(AndroidJUnit4::class)
class NamelessMerchantTest {
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

    /** Stores the parsed receipt with issuer name [legalName], [days] after it was issued. */
    private suspend fun store(legalName: String, days: Long) {
        val receipt = base.copy(
            issuer = base.issuer.copy(legalName = legalName),
            identity = Identity(mark = "40000000000${receipts++}"),
            document = base.document.copy(issuedAt = base.document.issuedAt.plusDays(days)),
        )
        app.coordinator.write { app.persister.persist(receipt, emptyList(), ZONE) }
    }

    private fun merchant(column: String): String? = app.text("SELECT $column FROM merchant")

    @Test
    fun `a nameless merchant is called by its VAT number until named`() = runTest {
        store("", days = 0)
        assertThat(merchant("display_name")).isEqualTo(base.issuer.vatNumber)
        store(NAME, days = 1)
        assertThat(merchant("legal_name")).isEqualTo(NAME)
        assertThat(merchant("display_name")).isEqualTo(deriveDisplayName(NAME))
        // A later receipt without a name keeps it.
        store("", days = 2)
        assertThat(merchant("legal_name")).isEqualTo(NAME)
        assertThat(merchant("display_name")).isEqualTo(deriveDisplayName(NAME))
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Athens")
        const val NAME = "ΝΕΑ ΕΠΩΝΥΜΙΑ Α.Ε."
    }
}
