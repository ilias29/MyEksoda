package io.eksoda.engine.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

data class ImportedHolding(
    val day: LocalDate,
    val instrument: String,
    val quantity: BigDecimal,
    val valueMinor: Long,
    val costMinor: Long?,
)

sealed interface LightyearSnapshotImport {
    data class Portfolio(val rows: List<PortfolioSnapshot>) : LightyearSnapshotImport
    data class Holdings(val rows: List<ImportedHolding>) : LightyearSnapshotImport
}

/** Explicit EUR snapshot interchange format. Not a claim about Lightyear's native export headers. */
object LightyearSnapshotCsv {
    fun parse(text: String, today: LocalDate): LightyearSnapshotImport {
        require(text.length <= 4_000_000)
        val clean = text.removePrefix("\uFEFF")
        val header = clean.lineSequence().firstOrNull().orEmpty()
        val delimiter = if (';' in header) ';' else ','
        val rows = BankCsv.rows(clean, delimiter).filterNot { row -> row.all { it.isBlank() } }
        require(rows.size in 2..10_001)
        val columns = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        require(columns.distinct().size == columns.size)
        val portfolioFields = setOf("date", "currency", "value", "deposits", "withdrawals")
        val holdingFields = setOf("date", "currency", "instrument", "quantity", "value", "cost")
        require(columns.toSet() == portfolioFields || columns.toSet() == holdingFields) {
            "Unsupported snapshot headers"
        }
        val entries = rows.drop(1).map { row ->
            require(row.size == columns.size)
            columns.zip(row.map { it.trim() }).toMap()
        }
        fun date(row: Map<String, String>): LocalDate =
            LocalDate.parse(row.getValue("date")).also { require(!it.isAfter(today)) }
        fun money(row: Map<String, String>, key: String): Long {
            val value = row.getValue(key)
            return BankLedger.amountMinor(value, if (delimiter == ';' && ',' in value) ',' else '.')
                .also { require(it >= 0) }
        }
        require(entries.all { it.getValue("currency") == "EUR" })
        return if (columns.toSet() == portfolioFields) {
            val result = entries.map { row ->
                PortfolioSnapshot(date(row), money(row, "value"), money(row, "deposits"), money(row, "withdrawals"))
            }.sortedBy { it.date }
            require(result.map { it.date }.distinct().size == result.size)
            result.zipWithNext().forEach { (a, b) -> InvestmentTracking.change(a, b) }
            LightyearSnapshotImport.Portfolio(result)
        } else {
            val result = entries.map { row ->
                val id = row.getValue("instrument").uppercase(Locale.ROOT)
                require(id.isNotBlank() && id.length <= 100 && id.none { it.isISOControl() })
                val quantity = BigDecimal(row.getValue("quantity").let {
                    if (delimiter == ';') it.replace(',', '.') else it
                })
                require(quantity.signum() >= 0 && quantity.scale() in 0..12 && quantity.precision() <= 30)
                val value = money(row, "value")
                val cost = row.getValue("cost").takeIf { it.isNotEmpty() }?.let { money(row, "cost") }
                require(quantity.signum() != 0 || (value == 0L && (cost == null || cost == 0L)))
                ImportedHolding(date(row), id, quantity, value, cost)
            }
            require(result.map { it.day to it.instrument }.distinct().size == result.size)
            LightyearSnapshotImport.Holdings(result)
        }
    }
}
