package io.eksoda.engine.providers.s1ecos

import io.eksoda.engine.InvalidUrl
import io.eksoda.engine.providers.UrlIdentity
import io.eksoda.engine.providers.impact.COUNTRY_BY_PREFIX
import io.eksoda.engine.url.ParsedUrl
import io.eksoda.engine.url.parseSubmittedUrl
import java.util.Locale
import kotlin.text.RegexOption.IGNORE_CASE

// s1ecos receipt URL recognition.
//
// einvoice.s1ecos.gr serves Impact's receipt pages under its own host (observed 2026-11-21),
// with two forms of link to one receipt:
//   /v/{prefix}{VAT}-{number}-{40-hex provider authentication code}-{32-hex access GUID}
//       the receipt's QR code;
//   /p/{prefix}{VAT}/{40-hex document id}/{32-hex access GUID}
//       Impact's form, printed in the page's tax view.
// Neither 40-hex id is the myDATA UID.

public const val PROVIDER_CODE: String = "s1ecos"
public const val HOST: String = "einvoice.s1ecos.gr"

internal enum class LinkForm(val segment: String) { VIEW("v"), PAGE("p") }

/** Per form, groups: 1 the path after the form segment, 2 prefix, 3 VAT, 4 40-hex id, 5 GUID. */
private val PATH_RES = mapOf(
    LinkForm.VIEW to
        Regex("""^/v/(([A-Z]{2})(\d{9})-\d{1,19}-([0-9A-F]{40})-([0-9A-F]{32}))/?$""", IGNORE_CASE),
    LinkForm.PAGE to
        Regex("""^/p/(([A-Z]{2})(\d{9})/([0-9A-F]{40})/([0-9A-F]{32}))/?$""", IGNORE_CASE),
)

internal data class S1ecosLink(
    val form: LinkForm,
    val country: String,
    val vat: String,
    /** The authentication code of a /v/ link, the document id of a /p/ link (uppercase). */
    val linkId: String,
    /** Uppercase. */
    val guid: String,
    /** The receipt link: a credential. Never log it. */
    val canonicalUrl: String,
)

internal fun split(url: ParsedUrl): S1ecosLink? {
    if (url.host != HOST || url.query.isNotEmpty()) return null
    val (form, match) = PATH_RES.firstNotNullOfOrNull { (form, regex) ->
        regex.matchEntire(url.path)?.let { form to it }
    } ?: return null
    // Only letters and hex digits change: the canonical path is the matched one in uppercase.
    val groups = match.groupValues.map { it.uppercase(Locale.ROOT) }
    val country = COUNTRY_BY_PREFIX[groups[2]] ?: return null
    return S1ecosLink(
        form = form,
        country = country,
        vat = groups[3],
        linkId = groups[4],
        guid = groups[5],
        canonicalUrl = "https://$HOST/${form.segment}/${groups[1]}",
    )
}

public fun recognize(url: ParsedUrl): UrlIdentity? {
    val link = split(url) ?: return null
    return UrlIdentity(
        providerCode = PROVIDER_CODE,
        identity = "${link.country}:${link.vat}:${link.linkId}",
        canonicalUrl = link.canonicalUrl,
        issuerCountry = link.country,
        issuerVat = link.vat,
        // Only a /p/ link carries a document id; the page's UID never equals it.
        uid = link.linkId.takeIf { link.form == LinkForm.PAGE },
    )
}

/**
 * Whether [printed], the link in the page's tax view, is the receipt of [submitted]: the same
 * issuer and access GUID, and the same id when both links have the same form.
 */
internal fun printsLink(printed: String, submitted: UrlIdentity): Boolean {
    val page = linkOf(printed) ?: return false
    val link = linkOf(submitted.canonicalUrl) ?: return false
    return page.country == link.country && page.vat == link.vat && page.guid == link.guid &&
        (page.form != link.form || page.linkId == link.linkId)
}

private fun linkOf(text: String): S1ecosLink? = try {
    split(parseSubmittedUrl(text))
} catch (_: InvalidUrl) {
    null
} catch (_: IllegalArgumentException) {
    // urlsplit's malformed bracketed host.
    null
}
