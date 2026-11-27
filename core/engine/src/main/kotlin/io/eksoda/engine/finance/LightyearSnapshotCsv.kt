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
    public data class Valuations(
        val rows: List<PortfolioSnapshot>,
        val holdings: List<ImportedHolding>,
    ) : LightyearSnapshotImport
    public data class Dividends(val rows: List<ImportedDividend>) : LightyearSnapshotImport
}

/** Explicit EUR snapshot interchange format. Not a claim about Lightyear's native export headers. */
public object LightyearSnapshotCsv {
    private const val MAX_TEXT_LENGTH = 4_000_000
    private const val MAX_ROWS = 10_001
    private const val MAX_INSTRUMENT_LENGTH = 100
    private const val MAX_DECIMAL_PRECISION = 24
    private const val MAX_QUANTITY_SCALE = 12
    private const val MAX_QUANTITY_PRECISION = 30

    public fun parse(text: String, today: LocalDate): LightyearSnapshotImport {
        require(text.length <= MAX_TEXT_LENGTH)
        val clean = text.removePrefix("\uFEFF")
        val header = clean.lineSequence().firstOrNull().orEmpty()
        val delimiter = if (';' in header) ';' else ','
        val rows = BankCsv.rows(clean, delimiter).filterNot { row -> row.all { it.isBlank() } }
        require(rows.size in 2..MAX_ROWS)
        val columns = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        require(columns.distinct().size == columns.size)
        val format = formatOf(columns.toSet())
        val entries = rows.drop(1).map { row ->
            require(row.size == columns.size)
            columns.zip(row.map { it.trim() }).toMap()
        }
        val helpers = Helpers(today, delimiter)
        return when (format) {
            Format.DIVIDENDS -> parseDividends(entries, helpers)
            Format.VALUATIONS -> parseValuations(entries, helpers)
            Format.PORTFOLIO -> parsePortfolio(entries, helpers)
            Format.HOLDINGS -> parseHoldings(entries, helpers)
        }
    }

    private enum class Format { PORTFOLIO, HOLDINGS, VALUATIONS, DIVIDENDS }
    private val portfolioFields = setOf("date", "currency", "value", "deposits", "withdrawals")
    private val holdingFields = setOf("date", "currency", "instrument", "quantity", "value", "cost")
    private val valuationFields =
        setOf(
            "date",
            "currency",
            "instrument",
            "quantity",
            "unit_price",
            "eur_rate",
            "deposits_eur",
            "withdrawals_eur",
        )
    private val dividendFields =
        setOf("date", "reference", "instrument", "currency", "gross", "withheld", "fee", "eur_rate")

    private fun formatOf(columns: Set<String>): Format = when (columns) {
        portfolioFields -> Format.PORTFOLIO
        holdingFields -> Format.HOLDINGS
        valuationFields -> Format.VALUATIONS
        dividendFields -> Format.DIVIDENDS
        else -> error("Unsupported snapshot headers")
    }

    private class Helpers(private val today: LocalDate, private val delimiter: Char) {
        fun date(row: Map<String, String>): LocalDate =
            LocalDate.parse(row.getValue("date")).also { require(!it.isAfter(today)) }
        fun money(row: Map<String, String>, key: String): Long = BankLedger.amountMinor(
            row.getValue(key),
            if (delimiter ==
                ';' &&
                ',' in row.getValue(key)
            ) {
                ','
            } else {
                '.'
            },
        ).also { require(it >= 0) }
        fun decimal(row: Map<String, String>, key: String): BigDecimal {
            val valueText = row.getValue(key).let {
                if (delimiter ==
                    ';'
                ) {
                    it.replace(',', '.')
                } else {
                    it
                }
            }
            require(valueText.matches(Regex("[0-9]+(\\.[0-9]{1,12})?")))
            return BigDecimal(valueText).also { require(it.precision() <= MAX_DECIMAL_PRECISION) }
        }
        fun id(row: Map<String, String>): String =
            row.getValue("instrument").uppercase(Locale.ROOT).also {
                require(
                    it.isNotBlank() && it.length <= MAX_INSTRUMENT_LENGTH &&
                        it.none { c -> c.isISOControl() },
                )
            }
    }

    private fun parseDividends(
        entries: List<Map<String, String>>,
        h: Helpers,
    ): LightyearSnapshotImport {
        val result = entries.map { row ->
            ImportedDividend(
                row.getValue("reference"),
                h.date(row),
                h.id(row),
                row.getValue("currency"),
                h.decimal(row, "gross"),
                h.decimal(row, "withheld"),
                h.decimal(row, "fee"),
                h.decimal(row, "eur_rate"),
            )
        }
        require(result.map { it.reference }.distinct().size == result.size)
        return LightyearSnapshotImport.Dividends(result)
    }

    private fun parseValuations(
        entries: List<Map<String, String>>,
        h: Helpers,
    ): LightyearSnapshotImport {
        val snapshots = mutableListOf<PortfolioSnapshot>()
        val holdings = mutableListOf<ImportedHolding>()
        for ((day, group) in entries.groupBy { h.date(it) }.toSortedMap()) {
            require(group.map { h.id(it) }.distinct().size == group.size)
            require(group.any { h.id(it).startsWith("CASH:") }) { "Explicit cash balance required" }
            val deposits = group.map { h.money(it, "deposits_eur") }.distinct().single()
            val withdrawals = group.map { h.money(it, "withdrawals_eur") }.distinct().single()
            group.groupBy {
                it.getValue("currency")
            }.values.forEach { rates ->
                require(
                    rates.map {
                        h.decimal(it, "eur_rate").stripTrailingZeros()
                    }.distinct().size == 1,
                )
            }
            val amounts = group.map { row -> valuationAmount(row, day, h, holdings) }
            snapshots += LightyearValuation.total(day, amounts, deposits, withdrawals)
        }
        snapshots.zipWithNext().forEach { (a, b) -> InvestmentTracking.change(a, b) }
        return LightyearSnapshotImport.Valuations(snapshots, holdings)
    }

    private fun valuationAmount(
        row: Map<String, String>,
        day: LocalDate,
        h: Helpers,
        holdings: MutableList<ImportedHolding>,
    ): Long {
        val instrument = h.id(row)
        val currency = row.getValue("currency")
        val quantity = h.decimal(row, "quantity")
        val price = h.decimal(row, "unit_price")
        if (instrument.startsWith("CASH:")) {
            require(instrument == "CASH:$currency" && price.compareTo(BigDecimal.ONE) == 0)
        } else {
            require(price.signum() > 0)
        }
        val amount = LightyearValuation.euroMinor(
            quantity.multiply(price),
            currency,
            h.decimal(row, "eur_rate"),
        )
        if (!instrument.startsWith("CASH:")) {
            holdings += ImportedHolding(day, instrument, quantity, amount, null)
        }
        return amount
    }

    private fun parsePortfolio(
        entries: List<Map<String, String>>,
        h: Helpers,
    ): LightyearSnapshotImport {
        require(entries.all { it.getValue("currency") == "EUR" })
        val result = entries.map { row ->
            PortfolioSnapshot(
                h.date(row),
                h.money(row, "value"),
                h.money(row, "deposits"),
                h.money(row, "withdrawals"),
            )
        }.sortedBy { it.date }
        require(result.map { it.date }.distinct().size == result.size)
        result.zipWithNext().forEach { (a, b) -> InvestmentTracking.change(a, b) }
        return LightyearSnapshotImport.Portfolio(result)
    }

    private fun parseHoldings(
        entries: List<Map<String, String>>,
        h: Helpers,
    ): LightyearSnapshotImport {
        require(entries.all { it.getValue("currency") == "EUR" })
        val result = entries.map { row ->
            val id = h.id(row)
            val quantity = h.decimal(row, "quantity")
            require(
                quantity.signum() >= 0 && quantity.scale() <= MAX_QUANTITY_SCALE &&
                    quantity.precision() <= MAX_QUANTITY_PRECISION,
            )
            val value = h.money(row, "value")
            val cost = row.getValue("cost").takeIf(String::isNotEmpty)?.let { h.money(row, "cost") }
            require(quantity.signum() != 0 || (value == 0L && (cost == null || cost == 0L)))
            ImportedHolding(h.date(row), id, quantity, value, cost)
        }
        require(result.map { it.day to it.instrument }.distinct().size == result.size)
        return LightyearSnapshotImport.Holdings(result)
    }
}
