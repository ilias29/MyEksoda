package io.eksoda.engine.finance

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class CsvMapping(
    val date: Int,
    val description: Int,
    val amount: Int,
    val sourceId: Int? = null,
    val delimiter: Char = ';',
    val decimalSeparator: Char = ',',
    val datePattern: String = "dd/MM/uuuu",
)

object BankCsv {
    fun parse(text: String, accountId: String, mapping: CsvMapping): List<BankMovement> {
        val rows = rows(text.removePrefix("\uFEFF"), mapping.delimiter)
        val formatter = DateTimeFormatter.ofPattern(mapping.datePattern)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        return rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.map { row ->
            val amount = BankLedger.amountMinor(row[mapping.amount], mapping.decimalSeparator)
            BankMovement(
                accountId = accountId,
                date = LocalDate.parse(row[mapping.date].trim(), formatter),
                description = row[mapping.description].trim(),
                amountMinor = amount,
                kind = if (amount > 0) MovementKind.INCOME else MovementKind.EXPENSE,
                sourceId = mapping.sourceId?.let { row[it].trim().ifEmpty { null } },
            )
        }
    }

    private fun rows(text: String, delimiter: Char): List<List<String>> {
        require(delimiter != '"' && delimiter != '\n' && delimiter != '\r')
        val result = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            when {
                quoted && c == '"' -> {
                    if (i < text.length && text[i] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                        closed = true
                    }
                }
                quoted -> field.append(c)
                c == delimiter -> {
                    row.add(field.toString())
                    field.clear()
                    closed = false
                }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i < text.length && text[i] == '\n') i++
                    row.add(field.toString())
                    result.add(row.toList())
                    row.clear()
                    field.clear()
                    closed = false
                }
                c == '"' -> {
                    require(field.isEmpty() && !closed)
                    quoted = true
                }
                else -> {
                    require(!closed)
                    field.append(c)
                }
            }
        }
        require(!quoted)
        if (row.isNotEmpty() || field.isNotEmpty() || closed) {
            row.add(field.toString())
            result.add(row.toList())
        }
        return result
    }
}
