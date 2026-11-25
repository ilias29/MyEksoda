package io.eksoda.core.data.repository

import io.eksoda.core.data.db.WriteCoordinator
import io.eksoda.core.data.imports.Persister
import io.eksoda.core.database.dao.MerchantDao
import io.eksoda.core.database.dao.ReceiptDao
import io.eksoda.engine.canonical.CanonicalReceipt
import io.eksoda.engine.canonical.Coverage
import io.eksoda.engine.canonical.Document
import io.eksoda.engine.canonical.Identity
import io.eksoda.engine.canonical.IssueTime
import io.eksoda.engine.canonical.Line
import io.eksoda.engine.canonical.Party
import io.eksoda.engine.canonical.Payment
import io.eksoda.engine.canonical.PriceBasis
import io.eksoda.engine.canonical.Totals
import io.eksoda.engine.finance.ReceiptTextParser
import io.eksoda.engine.mydata.DocumentKind
import io.eksoda.engine.mydata.PaymentMethod
import io.eksoda.engine.text.fingerprint
import io.eksoda.engine.validation.validate
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

data class ReviewedReceiptInput(
    val text: String,
    val merchant: String,
    val vatNumber: String,
    val date: LocalDate,
    val documentNumber: String,
    val mark: String,
    val payment: PaymentMethod,
    val confirmedDistinct: Boolean,
)

sealed interface ReviewedReceiptResult {
    data class Saved(val id: String, val duplicate: Boolean) : ReviewedReceiptResult
    data object PossibleDuplicate : ReviewedReceiptResult
}

/** The user reviews a sale in EUR. Reuses the same transaction and product matcher as QR imports. */
class ReviewedReceiptSaver @Inject constructor(
    private val coordinator: WriteCoordinator,
    private val persister: Persister,
    private val merchants: MerchantDao,
    private val receipts: ReceiptDao,
    private val householdZone: HouseholdZone,
    private val clock: Clock,
) {
    suspend fun save(input: ReviewedReceiptInput): ReviewedReceiptResult {
        val zone = householdZone.current()
        require(!input.date.isAfter(LocalDate.now(clock.withZone(zone))))
        val canonical = reviewedCanonical(input, zone)
        return coordinator.write {
            val identity = canonical.identity
            val existing = receipts.findExisting(identity.mark, "GR", canonical.issuer.vatNumber, null, identity.sourceKey)
            if (existing != null) {
                check(existing.issuerVat == canonical.issuer.vatNumber &&
                    existing.issuedLocalDate == input.date.toString() &&
                    existing.grossTotalMinor == canonical.totals.gross.movePointRight(2).longValueExact())
                return@write ReviewedReceiptResult.Saved(existing.id, true)
            }
            val possible = receipts.possibleReviewedDuplicate(
                canonical.issuer.vatNumber, input.date.toString(),
                canonical.totals.gross.movePointRight(2).longValueExact(),
            )
            if (possible && !input.confirmedDistinct) return@write ReviewedReceiptResult.PossibleDuplicate
            // A user-entered merchant name must not overwrite the legal name imported from QR.
            val merchant = merchants.find("GR", canonical.issuer.vatNumber)
            val safe = if (merchant == null) canonical else canonical.copy(
                issuer = canonical.issuer.copy(legalName = merchant.legalName),
            )
            val saved = persister.persist(safe, validate(safe), zone)
            ReviewedReceiptResult.Saved(saved.receiptId, saved.outcome == Persister.OUTCOME_DUPLICATE)
        }
    }
}

internal fun reviewedCanonical(input: ReviewedReceiptInput, zone: ZoneId): CanonicalReceipt {
    val merchant = input.merchant.trim()
    val vat = input.vatNumber.trim()
    val number = input.documentNumber.trim()
    val mark = input.mark.trim().ifEmpty { null }
    require(merchant.isNotBlank() && merchant.length <= 200)
    require(Regex("[0-9]{9}").matches(vat) && validGreekVat(vat))
    require(number.isNotBlank() && number.length <= 80 && number.none { it.isISOControl() })
    require(mark == null || (Regex("[0-9]{1,30}").matches(mark) && mark.any { it != '0' }))
    require(input.text.length <= 50_000)
    val parsed = ReceiptTextParser.parse(input.text)
    require(parsed.reconciles)
    val total = requireNotNull(parsed.totalMinor)
    require(total > 0)
    val lines = parsed.items.mapIndexed { index, item ->
        require(item.name.length <= 200)
        require((item.quantity?.scale() ?: 0) <= 4)
        // Do not retain possible card identifiers in an OCR product name.
        require(!Regex("[0-9](?:[ -]?[0-9]){12,18}").containsMatchIn(item.name))
        Line(
            lineNumber = index + 1,
            description = item.name,
            gross = minor(item.lineMinor),
            quantity = item.quantity,
            unitPrice = item.unitPriceMinor?.let(::minor),
            unitPriceBasis = if (item.unitPriceMinor == null) PriceBasis.UNKNOWN else PriceBasis.GROSS,
        )
    } + parsed.discountsMinor.mapIndexed { index, amount ->
        Line(lineNumber = parsed.items.size + index + 1, description = "ΕΚΠΤΩΣΗ", gross = minor(-amount))
    }
    val discounts = parsed.discountsMinor.fold(BigDecimal.ZERO) { sum, amount -> sum + minor(amount) }
    val gross = minor(total)
    val sourceKey = "reviewed-ocr:" + fingerprint("GR|$vat|${input.date}|$number")
    return CanonicalReceipt(
        providerCode = "reviewed_ocr",
        parserVersion = "1",
        culture = "el-GR",
        sourceUrl = "",
        issuer = Party("GR", vat, merchant),
        document = Document(
            kind = DocumentKind.SALE,
            issuedAt = input.date.atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime(),
            issuedDate = input.date,
            currency = "EUR",
            formattedNumber = number,
        ),
        identity = Identity(mark = mark, sourceKey = sourceKey),
        totals = Totals(
            gross = gross,
            vat = parsed.vatMinor?.let(::minor),
            grossBeforeDiscount = if (discounts.signum() > 0) gross + discounts else null,
            discount = discounts.takeIf { it.signum() > 0 },
        ),
        lines = lines,
        coverage = Coverage(
            lineTaxAmounts = false,
            officialCodes = false,
            mydataRegistered = false,
            issueTime = IssueTime.DATE_ONLY,
            crossChecked = false,
        ),
        payments = if (input.payment == PaymentMethod.UNKNOWN) emptyList() else listOf(Payment(gross, input.payment)),
    )
}

private fun minor(value: Long): BigDecimal = BigDecimal.valueOf(value, 2)

private fun validGreekVat(value: String): Boolean {
    if (value.all { it == '0' }) return false
    val sum = value.take(8).mapIndexed { i, digit -> digit.digitToInt() * (1 shl (8 - i)) }.sum()
    return sum % 11 % 10 == value.last().digitToInt()
}
