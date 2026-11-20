package io.eksoda.engine.sync

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import assertk.assertions.prop
import io.eksoda.engine.sync.SyncRows.item
import io.eksoda.engine.sync.SyncRows.receipt
import io.eksoda.engine.sync.SyncRows.snapshot
import org.junit.jupiter.api.Test

class SyncSchemaTest {
    @Test
    fun `different schemas stop the merge`() {
        assertFailure { SyncMerge.merge(snapshot(), Snapshot(TEST_SCHEMA + 1, emptyMap())) }
            .isInstanceOf(SyncException::class)
            .prop(SyncException::code)
            .isEqualTo(SyncException.SCHEMA_MISMATCH)
    }

    @Test
    fun `a row it cannot read is damaged`() {
        val remote = snapshot("receipt" to listOf(mapOf("id" to 5L)))

        assertFailure { SyncMerge.merge(snapshot(), remote) }
            .isInstanceOf(SyncException::class)
            .prop(SyncException::code)
            .isEqualTo(SyncException.DAMAGED)
    }

    @Test
    fun `a natural key needs every column of a key without foreign keys`() {
        assertThat(SyncSchema.naturalKey("receipt", receipt("r1"))).isNull()
        val byUid = receipt("r1") + ("uid" to "U1")
        assertThat(SyncSchema.naturalKey("receipt", byUid)).isEqualTo("1:GR\u001f094014201\u001fU1")
        assertThat(SyncSchema.naturalKey("receipt_item", item("i1", "r1", 1))).isNull()
        assertThat(SyncSchema.naturalKey("nothing", emptyMap())).isNull()
    }

    @Test
    fun `parents come before the tables that point at them`() {
        val order = SyncSchema.TABLES.map { it.name }
        SyncSchema.TABLES.forEach { spec ->
            spec.foreignKeys.forEach { foreignKey ->
                assertThat(order.indexOf(foreignKey.parent)).isLessThan(order.indexOf(spec.name))
            }
        }
    }
}
