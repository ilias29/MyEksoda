package io.eksoda.engine.finance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BankLedgerTest {
    @Test
    fun amounts() {
        assertEquals(1234L, BankLedger.amountMinor("12,34", ','))
        assertEquals(-1234L, BankLedger.amountMinor("-12.34", '.'))
        assertThrows(IllegalArgumentException::class.java) { BankLedger.amountMinor("1.234", '.') }
    }

    @Test
    fun cashFlow() {
        val date = LocalDate.of(2026, 11, 25)
        fun movement(amount: Long, kind: MovementKind) =
            BankMovement("account", date, "test", amount, kind)
        val result = BankLedger.summarize(
            listOf(
                movement(100000, MovementKind.INCOME),
                movement(-2500, MovementKind.EXPENSE),
                movement(500, MovementKind.REFUND),
                movement(-10000, MovementKind.TRANSFER),
                movement(10000, MovementKind.TRANSFER),
            ),
        )
        assertEquals(CashFlow(100000, 2000), result)
    }
}
