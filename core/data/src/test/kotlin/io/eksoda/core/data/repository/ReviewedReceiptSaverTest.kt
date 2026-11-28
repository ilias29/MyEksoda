package io.eksoda.core.data.repository

import io.eksoda.engine.canonical.IssueTime
import io.eksoda.engine.mydata.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class ReviewedReceiptSaverTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val input = ReviewedReceiptInput(
        text = "ΓΑΛΑ 2 x 1,25 2,50\nΨΩΜΙ 1,20\nΕΚΠΤΩΣΗ 0,20\nΦΠΑ 0,40\nΣΥΝΟΛΟ 3,50",
        merchant = "Δοκιμαστικό κατάστημα",
        vatNumber = "123456783",
        date = LocalDate.of(2026, 11, 26),
        documentNumber = "A-001",
        mark = "",
        payment = PaymentMethod.CARD,
        confirmedDistinct = false,
    )

    @Test
    fun mapsReviewedReceiptWithoutInventingUnitOrLink() {
        val receipt = reviewedCanonical(input, zone)
        assertEquals(BigDecimal("3.50"), receipt.totals.gross)
        assertEquals(BigDecimal("0.20"), receipt.totals.discount)
        assertEquals(BigDecimal("0.40"), receipt.totals.vat)
        assertEquals(BigDecimal("-0.20"), receipt.lines.last().gross)
        assertEquals(
            receipt.totals.gross,
            receipt.lines.fold(BigDecimal.ZERO) { sum, line ->
                sum +
                    line.gross
            },
        )
        assertNull(receipt.lines.first().unit)
        assertNull(receipt.lines[1].quantity)
        assertEquals("", receipt.sourceUrl)
        assertEquals(IssueTime.DATE_ONLY, receipt.coverage.issueTime)
        assertEquals(PaymentMethod.CARD, receipt.payments.single().method)
    }

    @Test
    fun sourceIdentityIsStableAndDependsOnReceiptNumber() {
        val first = reviewedCanonical(input, zone)
        val second = reviewedCanonical(input.copy(merchant = "Άλλη ονομασία"), zone)
        val third = reviewedCanonical(input.copy(documentNumber = "A-002"), zone)
        assertEquals(first.identity.sourceKey, second.identity.sourceKey)
        assertNotEquals(first.identity.sourceKey, third.identity.sourceKey)
        assertTrue(
            reviewedCanonical(input.copy(payment = PaymentMethod.UNKNOWN), zone).payments.isEmpty(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidVatRejected() {
        reviewedCanonical(input.copy(vatNumber = "123456784"), zone)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inconsistentTotalRejected() {
        reviewedCanonical(input.copy(text = "ΓΑΛΑ 2,50\nΣΥΝΟΛΟ 2,00"), zone)
    }
}
