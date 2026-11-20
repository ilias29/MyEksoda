package io.eksoda.engine.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Every synced table of one phone, and its tombstones, by table name. */
public class Snapshot(public val schema: Int, public val tables: Map<String, List<Row>>) {
    public fun rows(table: String): List<Row> = tables[table].orEmpty()

    /** What is sent to another phone: the synced tables without their local columns. */
    public fun forPeer(): Snapshot = Snapshot(
        schema,
        buildMap {
            SyncSchema.TABLES.forEach { spec ->
                put(spec.name, rows(spec.name).map { it - spec.localColumns })
            }
            put(SyncSchema.TOMBSTONE, rows(SyncSchema.TOMBSTONE))
        },
    )
}

/**
 * A snapshot as bytes: gzip of JSON lines, a header `{"format":1,"schema":6}` and then one
 * `{"t":"receipt","r":{…}}` per row. Values keep their stored form (no decimal conversion: both
 * phones have the same schema, or the sync stops).
 */
public object SnapshotCodec {
    private const val FORMAT = 1

    /** Far above any household's data; a larger stream is refused, not read into memory. */
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val BUFFER = 64 * 1024

    public fun encode(snapshot: Snapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter(Charsets.UTF_8).use { out ->
            out.appendLine(
                buildJsonObject {
                    put("format", FORMAT)
                    put("schema", snapshot.schema)
                }.toString(),
            )
            snapshot.tables.forEach { (table, rows) ->
                rows.forEach { row ->
                    val entry = mapOf("t" to JsonPrimitive(table), "r" to row.toJson())
                    out.appendLine(JsonObject(entry).toString())
                }
            }
        }
        return bytes.toByteArray()
    }

    /** Throws [SyncException] with [SyncException.DAMAGED] for anything it cannot read. */
    public fun decode(bytes: ByteArray): Snapshot = try {
        val lines = unzip(bytes).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
        val header = Json.parseToJsonElement(lines.first()).jsonObject
        if (header.getValue("format").jsonPrimitive.int != FORMAT) damaged()
        val tables = LinkedHashMap<String, MutableList<Row>>()
        lines.drop(1).forEach { line ->
            val entry = Json.parseToJsonElement(line).jsonObject
            val table = entry.getValue("t").jsonPrimitive.content
            tables.getOrPut(table) { mutableListOf() } += entry.getValue("r").jsonObject.toRow()
        }
        Snapshot(header.getValue("schema").jsonPrimitive.int, tables)
    } catch (e: IOException) {
        throw SyncException(SyncException.DAMAGED, e)
    } catch (e: SerializationException) {
        throw SyncException(SyncException.DAMAGED, e)
    } catch (e: IllegalArgumentException) {
        throw SyncException(SyncException.DAMAGED, e)
    } catch (e: NoSuchElementException) {
        throw SyncException(SyncException.DAMAGED, e)
    }

    private fun unzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { input ->
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) damaged()
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun Row.toJson(): JsonObject = JsonObject(
        mapValues { (column, value) ->
            when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value.toLong())
                else -> throw IllegalArgumentException("unsupported value in $column")
            }
        },
    )

    private fun JsonObject.toRow(): Row = mapValues { (_, element) -> element.value() }

    private fun JsonElement.value(): Any? = when {
        this is JsonNull -> null
        this !is JsonPrimitive -> damaged()
        isString -> content
        else -> longOrNull ?: damaged()
    }

    private fun damaged(): Nothing = throw SyncException(SyncException.DAMAGED)
}
