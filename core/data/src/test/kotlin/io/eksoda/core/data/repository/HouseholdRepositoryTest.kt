package io.eksoda.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.eksoda.core.data.imports.ImportHarness
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.ZoneId

/** The household onboarding creates and settings edit. */
@RunWith(AndroidJUnit4::class)
class HouseholdRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var app: ImportHarness
    private lateinit var households: HouseholdRepositoryImpl
    private lateinit var zone: HouseholdZone

    @Before
    fun setUp() {
        app = ImportHarness(folder.root)
        val dao = app.database.householdDao()
        households = HouseholdRepositoryImpl(dao, app.coordinator, app.clock)
        zone = HouseholdZone(dao)
    }

    @After
    fun tearDown() {
        // A failed setUp keeps its own error.
        if (::app.isInitialized) app.close()
    }

    @Test
    fun `before onboarding there is no household, and Athens time is used`() = runTest {
        assertThat(households.household.first()).isNull()
        assertThat(zone.current()).isEqualTo(HouseholdZone.DEFAULT)
        assertThat(households.updateHousehold("Home", "EUR", "Europe/Athens").isFailure).isTrue()
        assertThat(households.setAnnualIncome(BigDecimal("1000")).isFailure).isTrue()
    }

    @Test
    fun `a household is created once, with a trimmed name and a known timezone`() = runTest {
        assertThat(households.createHousehold("Home", "EUR", "Nowhere/City").isFailure).isTrue()
        val created = households.createHousehold("  Σπίτι  ", "EUR", "Europe/Berlin").getOrThrow()
        assertThat(created.name).isEqualTo("Σπίτι")
        assertThat(created.annualIncome).isNull()
        assertThat(households.household.first()).isEqualTo(created)
        assertThat(zone.current()).isEqualTo(ZoneId.of("Europe/Berlin"))
        assertThat(households.createHousehold("Again", "EUR", "Europe/Athens").isFailure).isTrue()
    }

    @Test
    fun `the household's name, currency and timezone can change`() = runTest {
        households.createHousehold("Home", "EUR", "Europe/Athens").getOrThrow()
        assertThat(households.updateHousehold("Home", "EUR", "Nowhere/City").isFailure).isTrue()
        households.updateHousehold(" Εξοχή ", "USD", "Europe/Berlin").getOrThrow()
        val updated = checkNotNull(households.household.first())
        assertThat(listOf(updated.name, updated.defaultCurrency, updated.timezone))
            .isEqualTo(listOf("Εξοχή", "USD", "Europe/Berlin"))
    }

    @Test
    fun `the annual income is kept to the cent, and can be cleared`() = runTest {
        households.createHousehold("Home", "EUR", "Europe/Athens").getOrThrow()
        assertThat(households.setAnnualIncome(BigDecimal("-1")).isFailure).isTrue()
        households.setAnnualIncome(BigDecimal("25000.50")).getOrThrow()
        assertThat(households.household.first()?.annualIncome?.toPlainString())
            .isEqualTo("25000.50")
        households.setAnnualIncome(null).getOrThrow()
        assertThat(households.household.first()?.annualIncome).isNull()
    }
}
