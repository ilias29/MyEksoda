package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReceiptTextParserTest {
    @Test
    fun recognizesGreekItemsAndExplicitDiscountWithoutAddingVat() {
        val result = ReceiptTextParser.parse(
            "ΓΑΛΑ 2 x 1,25 2,50\nΨΩΜΙ 1,20\nΕΚΠΤΩΣΗ -0,20\nΦΠΑ 0,40\nΣΥΝΟΛΟ 3,50\nΚΑΡΤΑ 3,50",
        )
        assertEquals(2, result.items.size)
        assertEquals(125L, result.items.first().unitPriceMinor)
        assertEquals(listOf(20L), result.discountsMinor)
        assertEquals(40L, result.vatMinor)
        assertEquals(350L, result.calculatedMinor)
        assertTrue(result.reconciles)
    }

    @Test
    fun rejectsConflictingAndMissingTotals() {
        assertFalse(ReceiptTextParser.parse("Milk 2.50\nTOTAL 2.40").reconciles)
        val ambiguous = ReceiptTextParser.parse("Milk 2.50\nTOTAL 2.50\nTOTAL 3.00")
        assertNull(ambiguous.totalMinor)
        assertFalse(ambiguous.reconciles)
        assertFalse(ReceiptTextParser.parse("Milk 2.50").reconciles)
    }

    @Test
    fun rejectsQuantityMismatchAndDoesNotInventDiscount() {
        val result = ReceiptTextParser.parse("Milk 2 x 1.00 2.50\nTOTAL 2.50")
        assertFalse(result.reconciles)
        assertTrue(result.discountsMinor.isEmpty())
    }
}
