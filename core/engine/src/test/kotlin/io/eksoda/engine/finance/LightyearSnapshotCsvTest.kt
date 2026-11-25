package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class LightyearSnapshotCsvTest {
    private val today = LocalDate.of(2026, 11, 26)

    @Test
    fun importsDatedPortfolioAndCumulativeFunding() {
        val parsed = LightyearSnapshotCsv.parse(
            "date,currency,value,deposits,withdrawals\n2026-11-26,EUR,1200,1100,0\n2026-11-01,EUR,1050,1000,0",
            today,
        ) as LightyearSnapshotImport.Portfolio
        assertEquals(2, parsed.rows.size)
        assertEquals(120_000L, parsed.rows.last().valueMinor)
        assertEquals(BigDecimal("5000"), InvestmentTracking.change(parsed.rows.first(), parsed.rows.last()).gainChangeMinor)
    }

    @Test
    fun importsQuotedHoldingsAndOptionalCost() {
        val parsed = LightyearSnapshotCsv.parse(
            "date;currency;instrument;quantity;value;cost\n2026-11-26;EUR;\"VUAA XETR\";1,5;200,25;",
            today,
        ) as LightyearSnapshotImport.Holdings
        assertEquals(BigDecimal("1.5"), parsed.rows.single().quantity)
        assertEquals(20_025L, parsed.rows.single().valueMinor)
        assertEquals(null, parsed.rows.single().costMinor)
    }

    @Test
    fun rejectsUnsupportedCurrencyDuplicatesAndFutureDates() {
        val header = "date,currency,value,deposits,withdrawals\n"
        listOf(
            "2026-11-26,USD,100,100,0",
            "2026-11-27,EUR,100,100,0",
            "2026-11-26,EUR,100,100,0\n2026-11-26,EUR,100,100,0",
            "2026-11-01,EUR,100,100,0\n2026-11-26,EUR,100,90,0",
        ).forEach { rows ->
            assertThrows(IllegalArgumentException::class.java) { LightyearSnapshotCsv.parse(header + rows, today) }
        }
    }
}
