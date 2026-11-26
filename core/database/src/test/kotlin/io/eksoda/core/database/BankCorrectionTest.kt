package io.eksoda.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.eksoda.core.database.entity.BankRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Plain in-memory Room on Robolectric, without SQLCipher. */
@RunWith(AndroidJUnit4::class)
class BankCorrectionTest {
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
    fun correctionsSurviveReimportAndAreScopedToOneAccount() = runTest {
        val dao = database.cashDao()
        val debit = BankRow("A", "1", "2026-03-01", "payment", -1234, "EXPENSE", "UNCLASSIFIED")
        val other = debit.copy(account = "B")
        dao.importBank(listOf(debit, other))
        assertThat(
            dao.classify("A", "1", "EXPENSE", "UNCLASSIFIED", "TRANSFER", "UNCLASSIFIED"),
        ).isEqualTo(1)
        assertThat(
            dao.classify("A", "1", "EXPENSE", "UNCLASSIFIED", "EXPENSE", "RENT"),
        ).isEqualTo(0)
        assertThat(dao.importBank(listOf(debit, other))).isEqualTo(0)
        assertThat(dao.bankHistory("A").first().single()).isEqualTo(debit.copy(kind = "TRANSFER"))
        assertThat(dao.bankHistory("B").first().single()).isEqualTo(other)
    }
}
