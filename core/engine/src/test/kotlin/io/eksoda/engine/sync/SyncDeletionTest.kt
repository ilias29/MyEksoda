package io.eksoda.engine.sync

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.eksoda.engine.sync.SyncRows.category
import io.eksoda.engine.sync.SyncRows.item
import io.eksoda.engine.sync.SyncRows.mapping
import io.eksoda.engine.sync.SyncRows.merchant
import io.eksoda.engine.sync.SyncRows.product
import io.eksoda.engine.sync.SyncRows.receipt
import io.eksoda.engine.sync.SyncRows.snapshot
import io.eksoda.engine.sync.SyncRows.tombstone
import org.junit.jupiter.api.Test

/** Tombstones, and what goes or stays with the rows they delete. */
class SyncDeletionTest {
    @Test
    fun `a deletion reaches the other phone and takes the receipt's lines along`() {
        val local = snapshot(
            "merchant" to listOf(merchant("m1")),
            "receipt" to listOf(receipt("r1", mark = "1")),
            "receipt_item" to listOf(item("i1", receipt = "r1", line = 1)),
        )
        val remote = snapshot(
            "merchant" to listOf(merchant("m1")),
            "tombstone" to listOf(tombstone("receipt", "r1", deletedAt = 5)),
        )

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("receipt")).isEmpty()
        assertThat(merged.ids("receipt_item")).isEmpty()
        assertThat(merged.ids("tombstone")).containsExactly("r1")
        assertThat(merged.counts()).isEqualTo(Triple(0, 0, 2))
    }

    @Test
    fun `a row changed after its deletion stays`() {
        val local = snapshot("receipt" to listOf(receipt("r1", mark = "1", updatedAt = 9)))
        val remote = snapshot("tombstone" to listOf(tombstone("receipt", "r1", deletedAt = 5)))

        assertThat(SyncMerge.merge(local, remote).ids("receipt")).containsExactly("r1")
    }

    @Test
    fun `a deletion reaches a copy imported under another id through the receipt's key`() {
        val key = SyncSchema.naturalKey("receipt", receipt("rA", mark = "400"))
        assertThat(key).isEqualTo("0:400")
        val local = snapshot("receipt" to listOf(receipt("rB", mark = "400", updatedAt = 2)))
        val remote = snapshot(
            "tombstone" to listOf(tombstone("receipt", "rA", deletedAt = 5, naturalKey = key)),
        )

        assertThat(SyncMerge.merge(local, remote).ids("receipt")).isEmpty()
    }

    @Test
    fun `a deleted product that a surviving line points at is kept, with its mappings`() {
        val local = snapshot(
            "merchant" to listOf(merchant("m1")),
            "product" to listOf(product("p1", "Milk")),
            "merchant_product_mapping" to listOf(mapping("map1", "m1", "code:1", "p1")),
            "receipt" to listOf(receipt("r1", mark = "1", updatedAt = 5)),
            "receipt_item" to listOf(item("i1", receipt = "r1", line = 1, product = "p1")),
        )
        val remote = snapshot("tombstone" to listOf(tombstone("product", "p1", deletedAt = 4)))

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("product")).containsExactly("p1")
        assertThat(merged.ids("merchant_product_mapping")).containsExactly("map1")
    }

    @Test
    fun `a deleted category is cleared from the merchants that had it as default`() {
        val local = snapshot(
            "category" to listOf(category("c1", "pets")),
            "merchant" to listOf(merchant("m1", category = "c1")),
        )
        val remote = snapshot("tombstone" to listOf(tombstone("category", "c1", deletedAt = 3)))

        val merged = SyncMerge.merge(local, remote)

        assertThat(merged.ids("category")).isEmpty()
        assertThat(merged.row("merchant", "m1")["default_category_id"]).isNull()
    }

    @Test
    fun `tombstones of the same row keep the latest deletion and any unique key`() {
        val local = snapshot("tombstone" to listOf(tombstone("receipt", "r1", deletedAt = 3)))
        val remote = snapshot(
            "tombstone" to listOf(tombstone("receipt", "r1", deletedAt = 2, naturalKey = "0:1")),
        )

        val tombstones = SyncMerge.merge(local, remote).tables.getValue("tombstone")

        assertThat(tombstones).containsExactly(tombstone("receipt", "r1", 3, naturalKey = "0:1"))
    }
}
