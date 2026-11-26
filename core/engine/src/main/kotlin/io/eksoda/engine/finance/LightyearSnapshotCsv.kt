package io.eksoda.engine.finance

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

public data class ImportedHolding(
    val day: LocalDate,
    val instrument: String,
    val quantity: BigDecimal,
    val valueMinor: Long,
    val costMinor: Long?,
)

public sealed interface LightyearSnapshotImport {
    public data class Portfolio(val rows: List<PortfolioSnapshot>) : LightyearSnapshotImport
    public data class Holdings(val rows: List<ImportedHolding>) : LightyearSnapshotImport
    public data class Valuations(val rows: List<PortfolioSnapshot>, val holdings: List<ImportedHolding>) : LightyearSnapshotImport
    public data class Dividends(val rows: List<ImportedDividend>) : LightyearSnapshotImport
}

/** Explicit EUR snapshot interchange format. Not a claim about Lightyear's native export headers. */
public object LightyearSnapshotCsv {
    public fun parse(text: String, today: LocalDate): LightyearSnapshotImport {
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
        val valuationFields = setOf("date", "currency", "instrument", "quantity", "unit_price", "eur_rate", "deposits_eur", "withdrawals_eur")
        val dividendFields = setOf("date", "reference", "instrument", "currency", "gross", "withheld", "fee", "eur_rate")
        require(columns.toSet() in setOf(portfolioFields, holdingFields, valuationFields, dividendFields)) {
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
        fun decimal(row: Map<String, String>, key: String): BigDecimal {
            val text = row.getValue(key).let { if (delimiter == ';') it.replace(',', '.') else it }
            require(text.matches(Regex("[0-9]+(\\.[0-9]{1,12})?")))
            return BigDecimal(text).also { require(it.precision() <= 24) }
        }
        fun id(row: Map<String, String>): String = row.getValue("instrument").uppercase(Locale.ROOT).also {
            require(it.isNotBlank() && it.length <= 100 && it.none { c -> c.isISOControl() })
        }
        if (columns.toSet() == dividendFields) {
            val dividends = entries.map { row ->
                ImportedDividend(row.getValue("reference"), date(row), id(row), row.getValue("currency"),
                    decimal(row, "gross"), decimal(row, "withheld"), decimal(row, "fee"), decimal(row, "eur_rate"))
            }
            require(dividends.map { it.reference }.distinct().size == dividends.size)
            return LightyearSnapshotImport.Dividends(dividends)
        }
        if (columns.toSet() == valuationFields) {
            val snapshots = mutableListOf<PortfolioSnapshot>()
            val holdings = mutableListOf<ImportedHolding>()
            for ((day, group) in entries.groupBy { date(it) }.toSortedMap()) {
                require(group.map { id(it) }.distinct().size == group.size)
                require(group.any { id(it).startsWith("CASH:") }) { "Explicit cash balance required" }
                val deposits = group.map { money(it, "deposits_eur") }.distinct().single()
                val withdrawals = group.map { money(it, "withdrawals_eur") }.distinct().single()
                for ((_, rates) in group.groupBy { it.getValue("currency") }) {
                    require(rates.map { decimal(it, "eur_rate").stripTrailingZeros() }.distinct().size == 1)
                }
                val amounts = group.map { row ->
                    val instrument = id(row)
                    val currency = row.getValue("currency")
                    val quantity = decimal(row, "quantity")
                    val price = decimal(row, "unit_price")
                    if (instrument.startsWith("CASH:")) {
                        require(instrument == "CASH:$currency" && price.compareTo(BigDecimal.ONE) == 0)
                    } else require(price.signum() > 0)
                    val amount = LightyearValuation.euroMinor(quantity.multiply(price), currency, decimal(row, "eur_rate"))
                    if (!instrument.startsWith("CASH:")) holdings += ImportedHolding(day, instrument, quantity, amount, null)
                    amount
                }
                snapshots += LightyearValuation.total(day, amounts, deposits, withdrawals)
            }
            snapshots.zipWithNext().forEach { (a, b) -> InvestmentTracking.change(a, b) }
            return LightyearSnapshotImport.Valuations(snapshots, holdings)
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
