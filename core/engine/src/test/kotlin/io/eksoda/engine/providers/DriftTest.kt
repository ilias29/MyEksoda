package io.eksoda.engine.providers

import assertk.assertThat
import assertk.assertions.isEmpty
import io.eksoda.engine.ReceiptException
import io.eksoda.engine.TestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Page drift: a provider changes its page a little and a field goes missing. A parser then reads
 * the page or refuses it with a [ReceiptException] (format changed, inconsistent source...), which
 * the app reports and retries after an update; it never fails any other way, which the app could
 * only call an internal error. Every fixture is parsed again with each of its texts blanked, one
 * at a time, and with each of its elements removed, one at a time.
 */
class DriftTest {
    @TestFactory
    fun blankedTexts(): List<DynamicTest> = eachFixture { page -> page.replaceTexts("") }

    /** A value printed in a way the parser has not seen: "?" for an amount, a date, a code. */
    @TestFactory
    fun garbledTexts(): List<DynamicTest> = eachFixture { page -> page.replaceTexts("?") }

    @TestFactory
    fun removedElements(): List<DynamicTest> = eachFixture { page ->
        val root = page.document.children().toSet()
        val elements = page.document.select("*").drop(1).filter { it !in root }
        val crashes = elements.mapNotNull { element ->
            val parent = checkNotNull(element.parent())
            val index = element.siblingIndex()
            element.remove()
            try {
                page.crash("<${element.tagName()} class='${element.className()}'> removed")
            } finally {
                parent.insertChildren(index, element)
            }
        }
        assertThat(crashes, name = "${elements.size} elements removed one at a time").isEmpty()
    }

    /** A fixture's page as a document to change, and the provider that reads it. */
    private class Page(
        val document: Document,
        private val provider: Provider,
        private val identity: UrlIdentity,
    ) {
        /** Each text of the page replaced with [replacement], one at a time. */
        fun replaceTexts(replacement: String) {
            val texts = document.select("*").flatMap { it.textNodes() }.filter { !it.isBlank }
            val crashes = texts.mapNotNull { node ->
                val original = node.wholeText
                node.text(replacement)
                try {
                    crash("'${original.trim().take(SNIPPET)}' read as '$replacement'")
                } finally {
                    node.text(original)
                }
            }
            assertThat(crashes, name = "${texts.size} texts replaced one at a time").isEmpty()
        }

        /** Null when the page as it is now parses or is refused properly, else the crash. */
        fun crash(change: String): String? = try {
            provider.parseHtml(document.outerHtml(), identity)
            null
        } catch (_: ReceiptException) {
            null
        } catch (
            @Suppress("TooGenericExceptionCaught") crash: RuntimeException,
        ) {
            "$change: $crash"
        }
    }

    private fun eachFixture(check: (Page) -> Unit): List<DynamicTest> = TestData.fixtures
        .listFiles { file -> file.isDirectory }.orEmpty()
        .sortedBy { it.name }
        .flatMap { directory ->
            directory.listFiles { file -> file.name.endsWith(META) }.orEmpty()
                .sortedBy { it.name }
                .map { meta ->
                    val name = meta.name.removeSuffix(META)
                    DynamicTest.dynamicTest("${directory.name}/$name") {
                        check(page(directory, name, meta))
                    }
                }
        }

    private fun page(directory: File, name: String, meta: File): Page {
        val file = PAGE_EXTENSIONS.map { directory.resolve("$name.$it") }.first { it.exists() }
        val url = Json.parseToJsonElement(meta.readText())
            .jsonObject.getValue("url").jsonPrimitive.content
        val (provider, identity) = REGISTRY.resolveUrl(url)
        return Page(parse(file), provider, identity)
    }

    private fun parse(page: File): Document {
        val text = page.readText()
        val document = if (page.extension == "xml") {
            Jsoup.parse(text, "", Parser.xmlParser())
        } else {
            Jsoup.parse(text)
        }
        // The page as it was written, but for the blanked text.
        document.outputSettings().prettyPrint(false)
        return document
    }

    private companion object {
        const val META = ".meta.json"
        const val SNIPPET = 40
        val PAGE_EXTENSIONS = listOf("html", "xml")
    }
}
