package io.eksoda.engine.finance

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate

class LegacyBankWorkbookTest {
    private fun workbook(configure: (HSSFWorkbook) -> Unit): ByteArray =
        HSSFWorkbook().use { book ->
            configure(book)
            ByteArrayOutputStream().use { out ->
                book.write(out)
                out.toByteArray()
            }
        }

    @Test
    fun importsGreekDebitWithFormattedReference() {
        val bytes = workbook { book ->
            val sheet = book.createSheet("Κινήσεις")
            val header = sheet.createRow(0)
            listOf("Ημερομηνία", "Περιγραφή", "Ποσό", "Κωδικός").forEachIndexed { index, name ->
                header.createCell(index).setCellValue(name)
            }
            val row = sheet.createRow(1)
            row.createCell(0).setCellValue(LocalDate.of(2026, 11, 25).atStartOfDay())
            row.createCell(1).setCellValue("Ενοίκιο")
            row.createCell(2).setCellValue(-510.25)
            row.createCell(3).apply {
                setCellValue(1.0)
                cellStyle = book.createCellStyle().apply { dataFormat = book.createDataFormat().getFormat("0000") }
            }
        }
        val mapping = CsvMapping(0, 1, 2, 3)
        val rows = LegacyBankWorkbook.rows(bytes, 0, 2, ',', mapping.datePattern)
        val movement = BankCsv.previewRows(rows, "test", mapping).movements.single()
        assertEquals(-51025L, movement.amountMinor)
        assertEquals("0001", movement.sourceId)
        assertEquals(LocalDate.of(2026, 11, 25), movement.date)
        assertEquals(SpendingCategory.RENT, movement.category)
    }

    @Test
    fun rejectsFormulaBeforePreview() {
        val bytes = workbook { book ->
            book.createSheet().createRow(0).createCell(0).cellFormula = "1+1"
        }
        assertThrows(IOException::class.java) {
            LegacyBankWorkbook.rows(bytes, 0, 2, ',', "dd/MM/uuuu")
        }
    }
}
