package io.eksoda.engine.providers.s1ecos

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import io.eksoda.engine.InconsistentSource
import io.eksoda.engine.TestData
import io.eksoda.engine.UnsupportedUrlFormat
import io.eksoda.engine.providers.REGISTRY
import io.eksoda.engine.providers.impact.FAKE_AUTHENTICATION
import io.eksoda.engine.providers.impact.FAKE_GUID
import org.junit.jupiter.api.Test

class S1ecosTest {
    private val page = TestData.fixtures.resolve("s1ecos/sephora_5_lines.html").readText()

    /** The QR's link and the /p/ link the page prints, for the same receipt. */
    private val viewLink =
        "https://einvoice.s1ecos.gr/v/EL094395700-000000000-$FAKE_AUTHENTICATION-$FAKE_GUID"
    private val pageLink = "https://einvoice.s1ecos.gr/p/EL094395700/" +
        "ED23F321F2052E734795A902F931E905155B01C7/$FAKE_GUID"

    private fun parse(link: String) = REGISTRY.resolveUrl(link).let { (provider, identity) ->
        provider.parseHtml(page, identity)
    }

    @Test
    fun `both link forms are recognized and canonicalized`() {
        val (provider, view) = REGISTRY.resolveUrl(
            "https://EINVOICE.s1ecos.gr:443/v/el094395700-000000000-" +
                "${FAKE_AUTHENTICATION.lowercase()}-${FAKE_GUID.lowercase()}/",
        )
        assertThat(provider.code).isEqualTo(PROVIDER_CODE)
        assertThat(view.canonicalUrl).isEqualTo(viewLink)
        assertThat(view.issuerVat).isEqualTo("094395700")
        assertThat(view.uid).isNull()

        val (_, printed) = REGISTRY.resolveUrl(pageLink)
        assertThat(printed.canonicalUrl).isEqualTo(pageLink)
        assertThat(printed.uid).isEqualTo("ED23F321F2052E734795A902F931E905155B01C7")
    }

    @Test
    fun `other paths are not receipt links`() {
        for (link in listOf(
            "$viewLink?x=1",
            viewLink.replace("/v/EL", "/v/XX"),
            viewLink.replace("-$FAKE_GUID", ""),
            "https://einvoice.s1ecos.gr/",
        )) {
            assertFailure { REGISTRY.resolveUrl(link) }.isInstanceOf<UnsupportedUrlFormat>()
        }
    }

    @Test
    fun `either link reads the receipt`() {
        for (link in listOf(viewLink, pageLink)) {
            val receipt = parse(link)
            assertThat(receipt.providerCode).isEqualTo(PROVIDER_CODE)
            assertThat(receipt.parserVersion).isEqualTo(PARSER_VERSION)
            assertThat(receipt.identity.uid).isEqualTo("A315DCE215C219554E83637222BBF06B433AEEFE")
            assertThat(receipt.totals.gross.toPlainString()).isEqualTo("135.90")
        }
    }

    @Test
    fun `a link with another access GUID or document id is refused`() {
        for (link in listOf(
            viewLink.replace(FAKE_GUID, "1".repeat(32)),
            pageLink.replace(FAKE_GUID, "1".repeat(32)),
            pageLink.replace("ED23F321", "ED23F322"),
        )) {
            assertFailure { parse(link) }.isInstanceOf<InconsistentSource>()
        }
    }

    @Test
    fun `a QR link is refused when the page does not print its link`() {
        val unlinked = page.replace(
            "Url με το οποίο ο λήπτης του παραστατικού θα μπορεί να το λαμβάνει",
            "Άλλο πεδίο",
        )
        val (provider, identity) = REGISTRY.resolveUrl(viewLink)
        assertFailure { provider.parseHtml(unlinked, identity) }
            .isInstanceOf<InconsistentSource>()
    }

    @Test
    fun `a page printing an Impact link is not this receipt`() {
        val impact = page.replace(
            "https://einvoice.s1ecos.gr/p/",
            "https://einvoice.impact.gr/p/",
        )
        val (provider, identity) = REGISTRY.resolveUrl(viewLink)
        assertFailure { provider.parseHtml(impact, identity) }.isInstanceOf<InconsistentSource>()
    }
}
