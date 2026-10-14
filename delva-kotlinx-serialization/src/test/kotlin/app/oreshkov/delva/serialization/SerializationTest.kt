package app.oreshkov.delva.serialization

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilterTemplate
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyConflict
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.WriteConflictException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalDelvaApi::class)
class SerializationTest {
    @Serializable
    data class Item(val sku: String, val qty: Int)

    @Serializable
    data class Order(val id: String, val customer: String, val items: List<Item>, val note: String? = null)

    @Serializable
    sealed interface Event {
        @Serializable
        @SerialName("created")
        data class Created(val id: String) : Event

        @Serializable
        @SerialName("shipped")
        data class Shipped(val id: String, val carrier: String) : Event
    }

    private val dir: Path = Files.createTempDirectory("delva-serialization")

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    private val order = Order("o-1", "ada", listOf(Item("A1", 2), Item("B2", 0)))

    private fun <T> SearchResult.decodeAll(read: (app.oreshkov.delva.SearchHit) -> T): List<T> = use { r -> r.iterator().asSequence().map(read).toList() }

    @Test
    fun `a value goes in as JSON and comes back as the same value`() {
        JsonStore.openOrCreate(dir.resolve("plain")).use { store ->
            val db = store.blocking()
            val docId = db.insertValue(order, types = mapOf("$" to "Order", "$.items[0]" to "Item"))
            val hits = db.search(KeyValueQuery("id", JsonQueryValue.Str("o-1"))).decodeAll { it.decode<Order>() }
            assertEquals(listOf(order), hits)
            // Members the class leaves at their default are left out, as Json leaves them out.
            assertEquals("""{"id":"o-1","customer":"ada","items":[{"sku":"A1","qty":2},{"sku":"B2","qty":0}]}""", db.search(KeyValueQuery("id", JsonQueryValue.Str("o-1"))).decodeAll { it.readJsonAsString() }.single())
            assertEquals(listOf(NodeRef(docId, 0)), db.objectsOfType("Order"))
            val skus = db.select(NodeRef(docId, 0), JsonPath.compile("$.items[*]")).use { r -> r.iterator().asSequence().map { it.decode<Item>() }.toList() }
            assertEquals(order.items, skus)
            // An element of the store, read as an untyped tree.
            val tree = db.search(KeyValueQuery("sku", JsonQueryValue.Str("B2"), ResultMode.CONTAINING_ELEMENT)).decodeAll { it.decode<JsonElement>() }
            assertEquals(listOf(buildJsonObject { put("sku", "B2"); put("qty", 0) }), tree)
        }
    }

    @Serializable
    data class ReadmeOrder(val id: String, val customer: String, val total: Int)

    @Test
    fun `values go in and come out as the module README shows`() {
        JsonStore.openOrCreate(dir.resolve("readme")).use { store ->
            val db = store.blocking()
            db.insertValue(ReadmeOrder("o-1", "ada", 120), types = mapOf("$" to "Order"))
            val orders = db.search(KeyValueQuery("customer", JsonQueryValue.Str("ada"))).use { result ->
                result.iterator().asSequence().map { it.decode<ReadmeOrder>() }.toList()
            }
            assertEquals(listOf(ReadmeOrder("o-1", "ada", 120)), orders)
            assertEquals(1, db.objectsOfType("Order").size)
        }
    }

    @Test
    fun `the json configuration is used both ways`() {
        val json = Json { classDiscriminator = "kind"; encodeDefaults = true }
        JsonStore.openOrCreate(dir.resolve("config")).use { store ->
            val db = store.blocking()
            db.insertValue<Event>(Event.Shipped("o-1", "post"), json)
            db.insertValue(Event.serializer(), Event.Created("o-2"), json)
            db.insertValue(order, json)
            val events = db.search(KeyValueQuery("kind", JsonQueryValue.Str("shipped"))).decodeAll { it.decode<Event>(json) }
            assertEquals(listOf<Event>(Event.Shipped("o-1", "post")), events)
            val created = db.search(KeyValueQuery("kind", JsonQueryValue.Str("created"))).decodeAll { it.decode(Event.serializer(), json) }
            assertEquals(listOf<Event>(Event.Created("o-2")), created)
            // encodeDefaults wrote the null note.
            assertEquals(1L, db.count(KeyValueQuery("note", JsonQueryValue.Null)))
        }
    }

    @Test
    fun `values that can't be stored or decoded fail as their layer says`() {
        @Serializable
        data class Reading(val value: Double)

        JsonStore.openOrCreate(dir.resolve("failures")).use { store ->
            val db = store.blocking()
            val special = Json { allowSpecialFloatingPointValues = true }
            val rejected = assertFailsWith<DocumentRejectedException> { db.insertValue(Reading(Double.NaN), special) }
            assertEquals(RejectReason.MALFORMED_JSON, rejected.reason)
            assertFailsWith<SerializationException> { db.insertValue(Reading(Double.NaN)) }
            assertEquals(0L, db.info().approximateDocumentCount)

            db.insertValue(Item("A1", 2))
            db.search(KeyValueQuery("sku", JsonQueryValue.Str("A1"))).use { result ->
                val hit = result.iterator().next()
                assertFailsWith<SerializationException> { hit.decode<Order>() }
                assertEquals(Item("A1", 2), hit.decode<Item>())
            }
        }
    }

    @Test
    fun `replace, upsert and insertAll take values`() {
        val keyed = CreationOptions().withDocumentKey("$.id")
        JsonStore.openOrCreate(dir.resolve("keyed"), keyed).use { store ->
            val db = store.blocking()
            val first = db.insertValue(order)
            assertFailsWith<WriteConflictException> { db.insertValue(order.copy(customer = "bob")) }
            val second = db.upsertValue(order.copy(customer = "bob"))
            assertNull(db.get("o-2"))
            assertEquals(second, db.get("o-1"))
            val third = db.replaceValue(listOf(second), order.copy(customer = "cy"))
            assertFailsWith<WriteConflictException> { db.replaceValue(listOf(first), order) }

            val outcomes = db.insertAll(
                listOf(
                    insertSourceOf(order.copy(id = "o-2"), types = mapOf("$" to "Order")),
                    insertSourceOf(order.copy(customer = "dee"), onExistingKey = KeyConflict.REPLACE, replaces = emptyList()),
                    insertSourceOf(Order.serializer(), order.copy(id = "o-3")),
                    insertSourceOf(order.copy(id = "o-4"), replaces = listOf(third)),
                ),
            )
            assertIs<InsertOutcome.Inserted>(outcomes[0])
            assertIs<InsertOutcome.Inserted>(outcomes[1])
            assertIs<InsertOutcome.Inserted>(outcomes[2])
            // The second source replaced o-1's document already.
            assertIs<InsertOutcome.Conflicted>(outcomes[3])
            val current = db.get("o-1")!!
            val read = db.search(KeyValueQuery("id", JsonQueryValue.Str("o-1"))).decodeAll { it.docId to it.decode<Order>() }
            assertEquals(listOf(current to order.copy(customer = "dee")), read)
            assertEquals(listOf(NodeRef(db.get("o-2")!!, 0)), db.objectsOfType("Order"))
        }
    }

    @Test
    fun `the suspending store takes and gives values too`() = runTest {
        JsonStore.openOrCreate(dir.resolve("suspend"), CreationOptions().withDocumentKey("$.id")).use { store ->
            val docId: DocId = store.insertValue(order)
            val upserted = store.upsertValue(Order.serializer(), order.copy(customer = "bob"))
            val replaced = store.replaceValue(listOf(upserted), order.copy(customer = "cy"))
            assertEquals(replaced, store.get("o-1"))
            store.insertValue(Order.serializer(), order.copy(id = "o-9"))
            val customers = store.search(KeyValueQuery("id", JsonQueryValue.Str("o-1"))).use { result ->
                result.hits.toList().map { it.decode<Order>().customer }
            }
            assertEquals(listOf("cy"), customers)
            assertEquals(false, store.get("o-1") == docId)
        }
    }

    @Test
    fun `json elements become query values for searches and templates`() {
        assertEquals(JsonQueryValue.Str("x"), JsonPrimitive("x").toQueryValue())
        assertEquals(JsonQueryValue.Str("1"), JsonPrimitive("1").toQueryValue())
        assertEquals(JsonQueryValue.Num(BigDecimal("1.50")), JsonPrimitive(BigDecimal("1.50")).toQueryValue())
        assertEquals(JsonQueryValue.Num(7), JsonPrimitive(7).toQueryValue())
        assertEquals(JsonQueryValue.Bool(false), JsonPrimitive(false).toQueryValue())
        assertEquals(JsonQueryValue.Null, JsonNull.toQueryValue())
        assertEquals(JsonQueryValue.Json("{\"a\":[1]}"), JsonObject(mapOf("a" to JsonArray(listOf(JsonPrimitive(1))))).toQueryValue())
        assertEquals(JsonQueryValue.Json("1e3000000000"), JsonUnquotedLiteral("1e3000000000").toQueryValue())

        JsonStore.openOrCreate(dir.resolve("values")).use { store ->
            val db = store.blocking()
            db.insertValue(order)
            db.insertValue(order.copy(id = "o-2", customer = "it's"))
            val element = Json.parseToJsonElement("""{"customer":"it's"}""") as JsonObject
            val byValue = db.search(KeyValueQuery("customer", element.getValue("customer").toQueryValue())).decodeAll { it.decode<Order>().id }
            assertEquals(listOf("o-2"), byValue)
            val ofCustomer = DocumentFilterTemplate.compile("@.customer == \${customer}")
            val query = JsonPathQuery(JsonPath.compile("$.items[0]")).withDocumentFilter(ofCustomer.bind("customer" to element.getValue("customer").toQueryValue()))
            assertEquals(listOf(Item("A1", 2)), db.search(query).decodeAll { it.decode<Item>() })
        }
    }
}
