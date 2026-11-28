package io.eksoda.engine.finance

import java.time.DateTimeException
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
public data class BankImportPreview(
    val movements: List<BankMovement>,
    val problems: List<ImportProblem>,
) {
    public val canCommit: Boolean get() = problems.isEmpty() && movements.isNotEmpty()
}

public object BankCsv {
    public fun parse(text: String, accountId: String, mapping: CsvMapping): List<BankMovement> {
        val preview = preview(text, accountId, mapping)
        require(preview.problems.isEmpty()) {
            preview.problems.joinToString { "line ${it.line}: ${it.reason}" }
        }
        return preview.movements
    }

    /** Nothing should be persisted until every input row has passed validation. */
    public fun preview(text: String, accountId: String, mapping: CsvMapping): BankImportPreview =
        previewRows(rows(text.removePrefix("\uFEFF"), mapping.delimiter), accountId, mapping)

    public fun previewRows(
        rows: List<List<String>>,
        accountId: String,
        mapping: CsvMapping,
    ): BankImportPreview {
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
            val requiredIndex = maxOf(
                mapping.date,
                mapping.description,
                mapping.amount,
                mapping.sourceId ?: 0,
            )
            if (row.size <= requiredIndex) {
                problems.add(ImportProblem(line, "missing mapped column"))
                return@forEachIndexed
            }
            try {
                movements.add(parseRow(row, accountId, mapping, formatter))
            } catch (e: DateTimeException) {
                problems.add(ImportProblem(line, "invalid date: ${e.message.orEmpty()}"))
            } catch (e: ArithmeticException) {
                problems.add(ImportProblem(line, "invalid amount: ${e.message.orEmpty()}"))
            } catch (e: IllegalArgumentException) {
                problems.add(ImportProblem(line, "invalid movement: ${e.message.orEmpty()}"))
            }
        }
        return BankImportPreview(movements, problems)
    }

    private fun parseRow(
        row: List<String>,
        accountId: String,
        mapping: CsvMapping,
        formatter: DateTimeFormatter,
    ): BankMovement {
        val amount = BankLedger.amountMinor(row[mapping.amount], mapping.decimalSeparator)
        val movement = BankMovement(
            accountId = accountId,
            date = LocalDate.parse(row[mapping.date].trim(), formatter),
            description = row[mapping.description].trim(),
            amountMinor = amount,
            kind = if (amount > 0) MovementKind.INCOME else MovementKind.EXPENSE,
            sourceId = mapping.sourceId?.let { row[it].trim().ifEmpty { null } },
        )
        return movement.copy(category = SpendingClassifier.suggest(movement))
    }

    internal fun rows(text: String, delimiter: Char): List<List<String>> {
        require(delimiter != '"' && delimiter != '\n' && delimiter != '\r')
        val state = CsvState()
        val result = state.result
        val row = state.row
        val field = state.field
        var i = 0
        while (i < text.length) {
            i = consume(text, i, delimiter, state)
        }
        require(!state.quoted)
        if (row.isNotEmpty() || field.isNotEmpty() || state.closed) {
            row.add(field.toString())
            result.add(row.toList())
        }
        return result
    }

    private class CsvState {
        var quoted = false
        var closed = false
        val row = mutableListOf<String>()
        val field = StringBuilder()
        val result = mutableListOf<List<String>>()
    }

    private fun consume(
        text: String,
        index: Int,
        delimiter: Char,
        state: CsvState,
    ): Int {
        val row = state.row
        val field = state.field
        val result = state.result
        var next = index + 1
        val c = text[index]
        if (state.quoted) return consumeQuoted(text, next, c, state, field)
        when {
            c == delimiter -> {
                row.add(field.toString())
                field.clear()
                state.closed = false
            }

            c == '\n' || c == '\r' -> {
                if (c == '\r' && next < text.length && text[next] == '\n') next++
                row.add(field.toString())
                result.add(row.toList())
                row.clear()
                field.clear()
                state.closed = false
            }

            c == '"' -> {
                require(field.isEmpty() && !state.closed)
                state.quoted = true
            }

            else -> {
                require(!state.closed)
                field.append(c)
            }
        }
        return next
    }

    private fun consumeQuoted(
        text: String,
        nextIndex: Int,
        character: Char,
        state: CsvState,
        field: StringBuilder,
    ): Int {
        var next = nextIndex
        if (character == '"' && next < text.length && text[next] == '"') {
            field.append('"')
            next++
        } else if (character == '"') {
            state.quoted = false
            state.closed = true
        } else {
            field.append(character)
        }
        return next
    }
}
