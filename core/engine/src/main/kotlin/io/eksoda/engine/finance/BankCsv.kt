package io.eksoda.engine.finance

import java.time.LocalDate
import java.time.format.DateTimeFormatter

public data class CsvMapping(
    val date: Int,
    val description: Int,
    val amount: Int,
    val sourceId: Int? = null,
    val delimiter: Char = ';',
    val decimalSeparator: Char = ',',
    val datePattern: String = "dd/MM/uuuu",
    val hasHeader: Boolean = true,
)

public data class ImportProblem(val line: Int, val reason: String)
public data class BankImportPreview(val movements: List<BankMovement>, val problems: List<ImportProblem>) {
    public val canCommit: Boolean get() = problems.isEmpty() && movements.isNotEmpty()
}

public object BankCsv {
    public fun parse(text: String, accountId: String, mapping: CsvMapping): List<BankMovement> {
        val preview = preview(text, accountId, mapping)
        require(preview.problems.isEmpty()) { preview.problems.joinToString { "line ${it.line}: ${it.reason}" } }
        return preview.movements
    }

    /** Nothing should be persisted until every input row has passed validation. */
    public fun preview(text: String, accountId: String, mapping: CsvMapping): BankImportPreview {
        return previewRows(rows(text.removePrefix("\uFEFF"), mapping.delimiter), accountId, mapping)
    }

    public fun previewRows(rows: List<List<String>>, accountId: String, mapping: CsvMapping): BankImportPreview {
        require(accountId.isNotBlank())
        require(listOf(mapping.date, mapping.description, mapping.amount).all { it >= 0 })
        require(mapping.sourceId == null || mapping.sourceId >= 0)
        val formatter = DateTimeFormatter.ofPattern(mapping.datePattern)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val movements = mutableListOf<BankMovement>()
        val problems = mutableListOf<ImportProblem>()
        rows.drop(if (mapping.hasHeader) 1 else 0).forEachIndexed { index, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val line = index + if (mapping.hasHeader) 2 else 1
            val requiredIndex = maxOf(mapping.date, mapping.description, mapping.amount, mapping.sourceId ?: 0)
            if (row.size <= requiredIndex) {
                problems.add(ImportProblem(line, "missing mapped column"))
                return@forEachIndexed
            }
            try {
                val amount = BankLedger.amountMinor(row[mapping.amount], mapping.decimalSeparator)
                val movement = BankMovement(
                    accountId = accountId,
                    date = LocalDate.parse(row[mapping.date].trim(), formatter),
                    description = row[mapping.description].trim(),
                    amountMinor = amount,
                    kind = if (amount > 0) MovementKind.INCOME else MovementKind.EXPENSE,
                    sourceId = mapping.sourceId?.let { row[it].trim().ifEmpty { null } },
                )
                movements.add(movement.copy(category = SpendingClassifier.suggest(movement)))
            } catch (e: RuntimeException) {
                problems.add(ImportProblem(line, "invalid date, amount or movement"))
            }
        }
        return BankImportPreview(movements, problems)
    }

    internal fun rows(text: String, delimiter: Char): List<List<String>> {
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
