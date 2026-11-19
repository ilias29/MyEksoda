package io.eksoda.engine.sync

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SnapshotCodecTest {
    @Test
    fun `a snapshot reads back exactly as it was written`() {
        val snapshot = Snapshot(
            7,
            mapOf(
                "receipt" to listOf(
                    mapOf("id" to "r1", "gross_total_minor" to 3210L, "store_id" to null),
                    mapOf("id" to "r2", "user_note" to "σημείωση \"με\" εισαγωγικά\n"),
                ),
                "tombstone" to listOf(
                    mapOf("entity" to "receipt", "id" to "r9", "deleted_at" to 4L),
                ),
            ),
        )

        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))

        assertThat(decoded.schema).isEqualTo(7)
        assertThat(decoded.tables).isEqualTo(snapshot.tables)
    }

    @Test
    fun `whole numbers of any type are written as longs`() {
        val snapshot = Snapshot(7, mapOf("receipt_item" to listOf(mapOf("line_number" to 1))))

        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))

        assertThat(decoded.rows("receipt_item").single()["line_number"]).isEqualTo(1L)
    }

    @Test
    fun `a value SQLite does not store is refused`() {
        val snapshot = Snapshot(7, mapOf("receipt" to listOf(mapOf("excluded" to true))))

        assertFailure { SnapshotCodec.encode(snapshot) }
            .isInstanceOf(IllegalArgumentException::class)
    }

    @Test
    fun `what goes to another phone has no receipt links`() {
        val snapshot = Snapshot(
            7,
            mapOf(
                "receipt" to listOf(mapOf("id" to "r1", "source_url" to "https://a.example/1")),
                "merchant" to listOf(mapOf("id" to "m1", "display_name" to "Shop")),
                "import_job" to listOf(mapOf("id" to "j1", "submitted_url" to "https://a.example")),
            ),
        )

        val sent = snapshot.forPeer()

        assertThat(sent.rows("receipt").single().containsKey("source_url")).isFalse()
        assertThat(sent.rows("merchant").single()["display_name"]).isEqualTo("Shop")
        assertThat(sent.tables.containsKey("import_job")).isFalse()
        assertThat(sent.tables.containsKey("tombstone")).isTrue()
    }

    @Test
    fun `bytes that are not a snapshot are damaged`() {
        listOf(
            "not gzip".toByteArray(),
            gzip(""),
            gzip("{\"format\":2,\"schema\":7}\n"),
            gzip("{\"format\":1,\"schema\":7}\n{\"t\":\"receipt\",\"r\":{\"id\":[1]}}\n"),
            gzip("{\"format\":1,\"schema\":7}\n{\"t\":\"receipt\",\"r\":{\"id\":1.5}}\n"),
            gzip("{\"format\":1,\"schema\":7}\n{\"r\":{}}\n"),
            gzip("{oops"),
        ).forEach { bytes ->
            assertFailure { SnapshotCodec.decode(bytes) }
                .isInstanceOf(SyncException::class)
                .prop(SyncException::code)
                .isEqualTo(SyncException.DAMAGED)
        }
    }

    private fun gzip(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        return bytes.toByteArray()
    }
}
