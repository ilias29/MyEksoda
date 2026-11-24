package io.eksoda.engine.providers.epsilon

import io.eksoda.engine.providers.UrlIdentity
import io.eksoda.engine.url.ParsedUrl
import java.util.Locale

// Epsilon Digital (Epsilon Net) receipt URL recognition.
//
// Some merchants have their own host, e.g. epsilondigital-sklavenitis.epsilonnet.gr; others,
// even a big chain, share one: epsilondigital-3rd.epsilonnet.gr (a fuel station, observed
// 2026-11-20) or epsilondigital-3rdpartb.epsilonnet.gr (Masoutis supermarkets, observed
// 2026-11-24). The receipt's QR code links to /fd/{document id}:{number}, which redirects to the
// viewer /DocViewer/{document UUID}. The viewer is behind a Cloudflare Turnstile check, so the
// app reads the receipt's detailed myDATA XML instead (fetchDocument in Sanitize.kt).

public const val PROVIDER_CODE: String = "epsilon"
internal const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private val UUID_GROUPS = listOf(8, 4, 4, 4, 12)

/** Hosts of a single merchant. Their XML never names the issuer (see [ISSUER_NAMES]). */
public val MERCHANT_HOSTS: Set<String> = setOf("epsilondigital-sklavenitis.epsilonnet.gr")

/**
 * Hosts shared by many merchants. Their XML usually names the issuer (`issuer/name`), but not
 * always (Masoutis on 3rdpartb).
 */
public val SHARED_HOSTS: Set<String> = setOf(
    "epsilondigital-3rd.epsilonnet.gr",
    "epsilondigital-3rdpartb.epsilonnet.gr",
)

public val HOSTS: Set<String> = MERCHANT_HOSTS + SHARED_HOSTS

/**
 * Legal names of issuers whose XML carries only their VAT number, by that VAT number. A VAT
 * number names one company, so the entry holds on every Epsilon host. An issuer without a name in
 * its XML and without an entry here is read with an empty name (the app shows its VAT number
 * until a receipt names it); an entry gives it a proper name from the first receipt.
 */
public val ISSUER_NAMES: Map<String, String> = mapOf(
    "800764388" to "ΕΛΛΗΝΙΚΕΣ ΥΠΕΡΑΓΟΡΕΣ ΣΚΛΑΒΕΝΙΤΗΣ ΑΝΩΝΥΜΗ ΕΜΠΟΡΙΚΗ ΕΤΑΙΡΕΙΑ",
    "094247924" to "ΔΙΑΜΑΝΤΗΣ ΜΑΣΟΥΤΗΣ Α.Ε. ΣΟΥΠΕΡ ΜΑΡΚΕΤ",
)

/** /fd/{32 hex digits}:{number} (the QR code; the number is optional). */
private val SHORT_PATH = Regex("""^/fd/([0-9a-f]{32})(?::(\d{1,9}))?/?$""", RegexOption.IGNORE_CASE)

/** /DocViewer/{UUID} (the address after the redirect, e.g. a link shared from a browser). */
private val VIEWER_PATH = Regex("""^/DocViewer/($UUID)/?$""", RegexOption.IGNORE_CASE)

public data class EpsilonLink(
    val host: String,
    /** Lowercase UUID. */
    val documentId: String,
    /** The receipt link: a credential. Never log it. */
    val canonicalUrl: String,
) {
    /** The detailed myDATA XML of the receipt: lines with descriptions, totals, payments. */
    val documentUrl: String get() = "https://$host/filedocument/GetInvoiceDocDetailed/$documentId"
}

/** 32 hex digits → the dashed UUID form. */
internal fun hexToUuid(hex: String): String {
    var start = 0
    return UUID_GROUPS.joinToString("-") { size ->
        hex.substring(start, start + size).also { start += size }
    }
}

public fun split(url: ParsedUrl): EpsilonLink? {
    if (url.host !in HOSTS || url.query.isNotEmpty()) return null
    val short = SHORT_PATH.matchEntire(url.path)
    val viewer = VIEWER_PATH.matchEntire(url.path)
    return when {
        short != null -> {
            val hex = short.groupValues[1].lowercase(Locale.ROOT)
            val number = short.groupValues[2]
            val suffix = if (number.isEmpty()) "" else ":$number"
            EpsilonLink(url.host, hexToUuid(hex), "https://${url.host}/fd/$hex$suffix")
        }

        viewer != null -> {
            val id = viewer.groupValues[1].lowercase(Locale.ROOT)
            EpsilonLink(url.host, id, "https://${url.host}/DocViewer/$id")
        }

        else -> null
    }
}

public fun recognize(url: ParsedUrl): UrlIdentity? {
    val link = split(url) ?: return null
    return UrlIdentity(
        providerCode = PROVIDER_CODE,
        identity = "epsilon:${link.documentId}",
        canonicalUrl = link.canonicalUrl,
    )
}
