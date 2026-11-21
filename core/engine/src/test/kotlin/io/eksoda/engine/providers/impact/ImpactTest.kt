package io.eksoda.engine.providers.impact

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.eksoda.engine.InconsistentSource
import io.eksoda.engine.TestData
import io.eksoda.engine.providers.REGISTRY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class ImpactTest {
    private val directory = TestData.fixtures.resolve("impact")

    /** A receipt whose link id (2755C3…) is not its myDATA UID (7E7DCA…). */
    private val ownId = directory.resolve("kogimou_3_lines.html").readText()
    private val ownIdUrl = Json.parseToJsonElement(
        directory.resolve("kogimou_3_lines.meta.json").readText(),
    ).jsonObject.getValue("url").jsonPrimitive.content
    private val identity = REGISTRY.resolveUrl(ownIdUrl).second

    @Test
    fun `a link id that is not the UID is accepted when the page prints that link`() {
        val receipt = parseHtml(ownId, identity)
        assertThat(identity.uid).isEqualTo("2755C3A08459698199D6E9ED8417127F681845AE")
        assertThat(receipt.identity.uid).isEqualTo("7E7DCAFD80708F40AD96C286483E14849B9DC564")
    }

    @Test
    fun `a link id that is not the UID is refused when the page does not print the link`() {
        val unlinked = ownId.replace(
            "Url με το οποίο ο λήπτης του παραστατικού θα μπορεί να το λαμβάνει",
            "Άλλο πεδίο",
        )
        assertFailure { parseHtml(unlinked, identity) }.isInstanceOf<InconsistentSource>()
    }

    @Test
    fun `a page that prints another link is refused`() {
        val other = ownId.replace("/" + FAKE_GUID, "/" + "1".repeat(32))
        assertFailure { parseHtml(other, identity) }.isInstanceOf<InconsistentSource>()
    }

    @Test
    fun `sanitizing redacts the myDATA QR address on any element`() {
        val raw = ownId.replace(
            "QRInfo?q=REDACTED",
            "QRInfo?q=FakeQrTokenXyz%2b456",
        )
        val html = sanitizeHtml(raw).html
        assertThat(html).doesNotContain("FakeQrTokenXyz")
        assertThat(html).contains("<fluent-anchor id=\"erpQrBtn\" href=\"$MYDATA_QR_REDACTED\"")
    }
}
