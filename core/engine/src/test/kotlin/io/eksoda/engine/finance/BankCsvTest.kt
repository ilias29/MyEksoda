package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BankCsvTest {
    private val mapping = CsvMapping(0, 1, 2, 3)

    @Test
    fun importsQuotedDescriptions() {
        val input = "date;description;amount;id\r\n25/11/2026;\"Αγορά; καφέ\";-2,50;abc\r\n"
        val row = BankCsv.parse(input, "bank", mapping).single()
        assertEquals(-250L, row.amountMinor)
        assertEquals("Αγορά; καφέ", row.description)
        assertEquals("abc", row.sourceId)
        assertEquals(MovementKind.EXPENSE, row.kind)
    }

    @Test
    fun rejectsMalformedQuotes() {
        assertThrows(IllegalArgumentException::class.java) {
            BankCsv.parse("date;description;amount;id\n25/11/2026;\"broken", "bank", mapping)
        }
    }
}
