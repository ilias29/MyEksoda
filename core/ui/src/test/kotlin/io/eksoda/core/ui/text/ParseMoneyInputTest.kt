package io.eksoda.core.ui.text

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class ParseMoneyInputTest {
    private fun parsed(text: String): String? = parseMoneyInput(text)?.toPlainString()

    @Test
    fun `thousands separators of either convention are dropped`() {
        assertThat(parsed("18000")).isEqualTo("18000")
        assertThat(parsed("18.000")).isEqualTo("18000")
        assertThat(parsed("18,000")).isEqualTo("18000")
        assertThat(parsed("1.234.567")).isEqualTo("1234567")
        assertThat(parsed("1,234,567")).isEqualTo("1234567")
        assertThat(parsed(" 18 000 ")).isEqualTo("18000")
    }

    @Test
    fun `the decimal point is the last separator, or one before one or two digits`() {
        assertThat(parsed("18.000,50")).isEqualTo("18000.50")
        assertThat(parsed("18,000.50")).isEqualTo("18000.50")
        assertThat(parsed("12,5")).isEqualTo("12.5")
        assertThat(parsed("12.50")).isEqualTo("12.50")
        assertThat(parsed("300,")).isEqualTo("300")
    }

    @Test
    fun `anything else is not an amount`() {
        listOf("", "  ", "abc", "-5", "12,345,6", "1.2.3,456", "12.3456", "€ 10", ",50").forEach {
            assertThat(parsed(it), name = it).isNull()
        }
    }

    @Test
    fun `a grouped amount starts with one to three digits, not a zero`() {
        // Read as grouping these would be 25 million and 500: too far from what was meant.
        listOf("25000.000", "0,500", "1234,567.89", "0.000").forEach {
            assertThat(parsed(it), name = it).isNull()
        }
        assertThat(parsed("025")).isEqualTo("25")
        assertThat(parsed("0,50")).isEqualTo("0.50")
    }
}
