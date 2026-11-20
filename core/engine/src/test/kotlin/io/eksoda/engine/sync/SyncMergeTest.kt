package io.eksoda.engine.sync

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.eksoda.engine.sync.SyncRows.budget
import io.eksoda.engine.sync.SyncRows.category
import io.eksoda.engine.sync.SyncRows.household
import io.eksoda.engine.sync.SyncRows.item
import io.eksoda.engine.sync.SyncRows.mapping
import io.eksoda.engine.sync.SyncRows.merchant
import io.eksoda.engine.sync.SyncRows.product
import io.eksoda.engine.sync.SyncRows.receipt
import io.eksoda.engine.sync.SyncRows.snapshot
import io.eksoda.engine.sync.SyncRows.tombstone
import org.junit.jupiter.api.Test

/** Which copy wins, and how copies imported under different ids become one. */
class SyncMergeTest {
    @Test
    fun `rows only the other phone has are added, and this phone keeps its own links`() {
        val local = snapshot(
            "merchant" to listOf(merchant("m1")),
            "receipt" to listOf(receipt("r1", mark = "100", url = "https://a.example/1")),
        )
        val remote = snapshot(
            "merchant" to listOf(merchant("m1")),
            "receipt" to listOf(receipt("r2", mark = "200")),
        )

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("receipt")).containsExactly("r1", "r2")
        assertThat(merged.row("receipt", "r1")["source_url"]).isEqualTo("https://a.example/1")
        assertThat(merged.row("receipt", "r2")["source_url"]).isNull()
        assertThat(merged.counts()).isEqualTo(Triple(1, 0, 0))
    }

    @Test
    fun `the later copy of a row wins`() {
        val older = snapshot("receipt" to listOf(receipt("r1", note = "old", updatedAt = 1)))
        val newer = snapshot("receipt" to listOf(receipt("r1", note = "new", updatedAt = 2)))

        val pulled = SyncMerge.merge(older, newer)
        assertThat(pulled.row("receipt", "r1")["user_note"]).isEqualTo("new")
        assertThat(pulled.counts()).isEqualTo(Triple(0, 1, 0))

        val kept = SyncMerge.merge(newer, older)
        assertThat(kept.row("receipt", "r1")["user_note"]).isEqualTo("new")
        assertThat(kept.counts()).isEqualTo(Triple(0, 0, 0))
    }

    @Test
    fun `a receipt imported on both phones becomes one, under the smaller id, with my link`() {
        val local = snapshot(
            "merchant" to listOf(merchant("m1")),
            "receipt" to listOf(
                receipt("rB", "400", note = "mine", url = "https://b.example", updatedAt = 5),
            ),
            "receipt_item" to listOf(item("iB", receipt = "rB", line = 1)),
        )
        val remote = snapshot(
            "merchant" to listOf(merchant("m1")),
            "receipt" to listOf(receipt("rA", mark = "400", updatedAt = 3)),
            "receipt_item" to listOf(item("iA", receipt = "rA", line = 1)),
        )

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("receipt")).containsExactly("rA")
        val receipt = merged.row("receipt", "rA")
        assertThat(receipt["user_note"]).isEqualTo("mine")
        assertThat(receipt["source_url"]).isEqualTo("https://b.example")
        assertThat(merged.ids("receipt_item")).containsExactly("iA")
        assertThat(merged.row("receipt_item", "iA")["receipt_id"]).isEqualTo("rA")
        assertThat(merged.remaps["receipt"]).isEqualTo(mapOf("rB" to "rA"))
        assertThat(merged.counts()).isEqualTo(Triple(0, 2, 0))
    }

    @Test
    fun `both phones end up with the same data, and merging again changes nothing`() {
        val a = snapshot(
            "household" to listOf(household("hA", "Home", updatedAt = 3)),
            "category" to listOf(category("cA", "pets")),
            "merchant" to listOf(merchant("m1"), merchant("m2", vat = "999", name = "Old")),
            "product" to listOf(product("pA", "Milk")),
            "merchant_product_mapping" to listOf(mapping("mapA", "m1", "code:1", "pA")),
            "receipt" to listOf(receipt("r1", mark = "1", url = "https://a.example")),
            "receipt_item" to listOf(item("i1", receipt = "r1", line = 1, product = "pA")),
            "tombstone" to listOf(tombstone("receipt", "r9", deletedAt = 4)),
        )
        val b = snapshot(
            "household" to listOf(household("hB", "Σπίτι", updatedAt = 5)),
            "category" to listOf(category("cB", "pets")),
            "budget" to listOf(budget("cB", category = "cB", amount = 10_000)),
            "merchant" to listOf(merchant("m2b", vat = "999", name = "New", updatedAt = 9)),
            "product" to listOf(product("pB", "Γάλα")),
            "merchant_product_mapping" to listOf(mapping("mapB", "m1", "code:1", "pB")),
            "receipt" to listOf(
                receipt("r9", mark = "9", updatedAt = 1),
                receipt("r2", mark = "2", merchant = "m2b"),
            ),
            "receipt_item" to listOf(item("i2", receipt = "r2", line = 1, product = "pB")),
        )

        val ab = SyncMerge.merge(a, b)
        val ba = SyncMerge.merge(b, a)

        assertThat(ab.synced()).isEqualTo(ba.synced())
        assertThat(ab.ids("receipt")).containsExactly("r1", "r2")
        assertThat(ab.row("household", "hA")["name"]).isEqualTo("Σπίτι")
        assertThat(ab.row("merchant", "m2")["display_name"]).isEqualTo("New")
        assertThat(ab.row("receipt", "r2")["merchant_id"]).isEqualTo("m2")

        val again = SyncMerge.merge(Snapshot(TEST_SCHEMA, ab.tables), b)
        assertThat(again.tables).isEqualTo(ab.tables)
        assertThat(again.counts()).isEqualTo(Triple(0, 0, 0))
    }

    @Test
    fun `a tie is decided the same way when the other phone sends its merged data`() {
        // Same version, different names: what a server merges and sends back must not flip.
        val a = snapshot("household" to listOf(household("hA", "Σπίτι")))
        val b = snapshot("household" to listOf(household("hB", "Home")))

        val onServer = SyncMerge.merge(b, a)
        val onClient = SyncMerge.merge(a, Snapshot(TEST_SCHEMA, onServer.tables).forPeer())

        assertThat(onClient.synced()).isEqualTo(onServer.synced())
        assertThat(onClient.row("household", "hA")["name"]).isEqualTo("Σπίτι")
    }

    @Test
    fun `products merged on one phone stay merged, and lines follow the product kept`() {
        // "a-old" sorts first: the kept id must still be the replacement's.
        val local = snapshot(
            "merchant" to listOf(merchant("m1")),
            "product" to listOf(product("a-old", "ΓΑΛΑ 1L"), product("b-kept", "Milk")),
            "receipt" to listOf(receipt("r2", mark = "2")),
            "receipt_item" to listOf(item("i2", receipt = "r2", line = 1, product = "a-old")),
        )
        val remote = snapshot(
            "merchant" to listOf(merchant("m1")),
            "product" to listOf(product("b-kept", "Milk", updatedAt = 10)),
            "tombstone" to listOf(
                tombstone("product", "a-old", deletedAt = 10, replacedBy = "b-kept"),
            ),
        )

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("product")).containsExactly("b-kept")
        assertThat(merged.row("receipt_item", "i2")["product_id"]).isEqualTo("b-kept")
        assertThat(merged.remaps["product"]).isEqualTo(mapOf("a-old" to "b-kept"))
    }

    @Test
    fun `two phones that created a product for the same shop line end up with one`() {
        val local = snapshot(
            "merchant" to listOf(merchant("m1")),
            "product" to listOf(product("pB", "ΓΑΛΑ")),
            "merchant_product_mapping" to listOf(mapping("mapB", "m1", "code:1", "pB")),
            "receipt" to listOf(receipt("r1", mark = "1")),
            "receipt_item" to listOf(item("i1", receipt = "r1", line = 1, product = "pB")),
        )
        val remote = snapshot(
            "merchant" to listOf(merchant("m1")),
            "product" to listOf(product("pA", "ΓΑΛΑ")),
            "merchant_product_mapping" to listOf(mapping("mapA", "m1", "code:1", "pA")),
        )

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("product")).containsExactly("pA")
        assertThat(merged.ids("merchant_product_mapping")).containsExactly("mapA")
        assertThat(merged.row("receipt_item", "i1")["product_id"]).isEqualTo("pA")
    }

    @Test
    fun `pointing a shop line at another product merges no products`() {
        val products = listOf(product("p1", "Milk"), product("p2", "Γάλα"))
        val local = snapshot(
            "product" to products,
            "merchant_product_mapping" to listOf(
                mapping("map1", "m1", "code:1", "p2", updatedAt = 5),
            ),
        )
        val remote = snapshot(
            "product" to products,
            "merchant_product_mapping" to listOf(mapping("map1", "m1", "code:1", "p1")),
        )

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("product")).containsExactly("p1", "p2")
        assertThat(merged.row("merchant_product_mapping", "map1")["product_id"]).isEqualTo("p2")
    }

    @Test
    fun `a budget follows its category when both phones created the category`() {
        val local = snapshot(
            "category" to listOf(category("cB", "pets")),
            "budget" to listOf(budget("cB", category = "cB", amount = 10_000)),
        )
        val remote = snapshot("category" to listOf(category("cA", "pets")))

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("category")).containsExactly("cA")
        assertThat(merged.ids("budget")).containsExactly("cA")
        assertThat(merged.row("budget", "cA")["category_id"]).isEqualTo("cA")
        assertThat(merged.remaps["budget"]).isEqualTo(mapOf("cB" to "cA"))
    }

    @Test
    fun `the total budget keeps its id`() {
        val local = snapshot("budget" to listOf(budget("total", category = null, amount = 1)))
        val remote = snapshot(
            "budget" to listOf(budget("total", category = null, amount = 2, updatedAt = 3)),
        )

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.row("budget", "total")["amount_minor"]).isEqualTo(2L)
    }
}
