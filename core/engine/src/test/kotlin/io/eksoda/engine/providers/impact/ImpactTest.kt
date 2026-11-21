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
    fun `the printed link is compared in its canonical form`() {
        val printed = "https://einvoice.impact.gr/p/EL801810254/" +
            "2755C3A08459698199D6E9ED8417127F681845AE/$FAKE_GUID"
        assertThat(ownId).contains(printed)
        // Host case, the default port, hex case and a trailing slash are the same link.
        val equivalent = "https://EINVOICE.impact.gr:443/p/el801810254/" +
            "2755c3a08459698199d6e9ed8417127f681845ae/$FAKE_GUID/"
        assertThat(parseHtml(ownId.replace(printed, equivalent), identity).identity.mark)
            .isEqualTo("400016110662608")
        // A link that does not resolve (another port, another host) is another receipt's.
        for (other in listOf(
            printed.replace("impact.gr/", "impact.gr:8443/"),
            printed.replace("einvoice.impact.gr", "einvoice.impact.gr.example"),
            printed.replace("/p/", "/p%2F"),
        )) {
            assertFailure { parseHtml(ownId.replace(printed, other), identity) }
                .isInstanceOf<InconsistentSource>()
        }
    }

    @Test
    fun `a tip printed before the payment method belongs to that payment`() {
        val payment = parseHtml(ownId, identity).payments.single()
        assertThat(payment.methodLabel).isEqualTo("EPAY A80")
        assertThat(payment.amount.toPlainString()).isEqualTo("22.90")
        // The commercial amount is cross-checked again.
        val amount = "Ποσό Πληρωμής</td>\n               <td class=\"value\">22,90</td>"
        assertThat(ownId).contains(amount)
        val wrongAmount = ownId.replace(amount, amount.replace("22,90", "29,90"))
        assertFailure { parseHtml(wrongAmount, identity) }.isInstanceOf<InconsistentSource>()
    }

    @Test
    fun `a commercial tip that differs from the tax view is refused`() {
        val tip = "<td class=\"label\">Φιλοδώρημα</td>\n" +
            "               <td class=\"value\">0,00</td>"
        assertThat(ownId).contains(tip)
        val wrongTip = ownId.replace(tip, tip.replace("0,00", "2,00"))
        assertFailure { parseHtml(wrongTip, identity) }.isInstanceOf<InconsistentSource>()
    }

    @Test
    fun `sanitizing redacts the myDATA QR token wherever it is`() {
        val raw = ownId
            .replace("QRInfo?q=REDACTED", "QRInfo?q=FakeQrTokenXyz%2b456")
            .replace(
                "<fluent-anchor id=\"erpQrBtn\"",
                "<span data-url=\"https://mydatapi.aade.gr/myDATA/TimologioQR/QRInfo?q=" +
                    "FakeQrTokenAbc\">https://mydatapi.aade.gr/myDATA/TimologioQR/QRInfo?q=" +
                    "FakeQrTokenDef</span><fluent-anchor id=\"erpQrBtn\"",
            )
        val result = sanitizeHtml(raw)
        assertThat(result.html).doesNotContain("FakeQrToken")
        assertThat(result.html).contains(
            "<fluent-anchor id=\"erpQrBtn\" href=\"https://mydatapi.aade.gr/myDATA/" +
                "TimologioQR/QRInfo?q=REDACTED\"",
        )
        assertThat(result.replaced["mydata_qr_token"]).isEqualTo(3)
    }
}
