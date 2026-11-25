package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class LightyearValuationTest {
    private val day = LocalDate.of(2026, 11, 26)
    private fun b(value: String) = BigDecimal(value)

    @Test
    fun convertsAndSumsUsingExplicitRates() {
        assertEquals(18400L, LightyearValuation.euroMinor(b("200"), "USD", b("0.92")))
        assertEquals(101L, LightyearValuation.euroMinor(b("1.005"), "EUR", b("1")))
        val total = LightyearValuation.total(day, listOf(18400L, 10000L, 5000L), 30000, 0)
        assertEquals(33400L, total.valueMinor)
        assertEquals(b("3400"), total.gainMinor)
    }

    @Test
    fun rejectsInvalidRatesAndCurrency() {
        assertThrows(IllegalArgumentException::class.java) {
            LightyearValuation.euroMinor(b("1"), "EUR", b("0.9"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LightyearValuation.euroMinor(b("1"), "USD", b("0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LightyearValuation.euroMinor(b("1"), "GBX", b("1"))
        }
        assertThrows(ArithmeticException::class.java) {
            LightyearValuation.total(day, listOf(Long.MAX_VALUE, 1L), 0, 0)
        }
    }

    @Test
    fun netDividendSubtractsWithholdingAndFees() {
        val dividend = ImportedDividend("ref", day, "ABC", "USD", b("20"), b("3"), b("1"), b("0.92"))
        assertEquals(1472L, dividend.netMinor)
        assertThrows(IllegalArgumentException::class.java) { dividend.copy(withheld = b("21")) }
    }

    @Test
    fun parsesCompleteValuationAndDividends() {
        val valuation = LightyearSnapshotCsv.parse(
            "date,currency,instrument,quantity,unit_price,eur_rate,deposits_eur,withdrawals_eur\n" +
                "2026-11-26,USD,ABC XNAS,2,100,0.92,200,0\n" +
                "2026-11-26,EUR,CASH:EUR,50,1,1,200,0",
            day,
        ) as LightyearSnapshotImport.Valuations
        assertEquals(23400L, valuation.rows.single().valueMinor)
        assertEquals(18400L, valuation.holdings.single().valueMinor)
        val dividends = LightyearSnapshotCsv.parse(
            "date,reference,instrument,currency,gross,withheld,fee,eur_rate\n" +
                "2026-11-26,ref,ABC XNAS,USD,20,3,1,0.92",
            day,
        ) as LightyearSnapshotImport.Dividends
        assertEquals(1472L, dividends.rows.single().netMinor)
    }
}
