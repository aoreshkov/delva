package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OracleStoreTest {
    private val example = """{"order":{"id":7,"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":1.0}],"tags":["x","y"]}}"""

    private fun containing(store: OracleStore, key: String, value: OValue, nested: ONestedHits = ONestedHits.ALL, type: String? = null) =
        store.searchAll(OQuery(key, value, OResultMode.CONTAINING_ELEMENT, nested, type = type))
            .map { Triple(it.node.pre, it.memberPre, it.inArray) }

    @Test
    fun `worked example - pre-order numbers and postings`() {
        val store = OracleStore()
        val doc = store.insert(example)
        assertEquals(1L, doc)
        // [0]{"order":[1]{"id":[2]7,"items":[3][[4]{"sku":[5]"A1","qty":[6]2},[7]{"sku":[8]"B2","qty":[9]1.0}],"tags":[10][[11]"x",[12]"y"]}}
        assertEquals(listOf(0L to 12L, 1L to 11L, 2L to 0L, 3L to 6L, 4L to 2L, 5L to 0L, 6L to 0L, 7L to 2L, 8L to 0L, 9L to 0L, 10L to 2L, 11L to 0L, 12L to 0L), store.nodes(doc))

        assertEquals(listOf(Triple(1L, 2L, false)), containing(store, "id", ONum("7")))
        assertEquals(listOf(Triple(4L, 5L, false)), containing(store, "sku", OStr("A1")))
        assertEquals(listOf(Triple(7L, 9L, false)), containing(store, "qty", ONum("1")))
        assertEquals(listOf(Triple(1L, 11L, true)), containing(store, "tags", OStr("x")))
        assertEquals(listOf(Triple(1L, 12L, true)), containing(store, "tags", OStr("y")))
        assertEquals(listOf(Triple(1L, 10L, false)), containing(store, "tags", OArr(OStr("x"), OStr("y"))))
        assertEquals(emptyList(), containing(store, "tags", OArr(OStr("y"), OStr("x"))))
        assertEquals(listOf(Triple(1L, 4L, true)), containing(store, "items", OObj("qty" to ONum("2.0"), "sku" to OStr("A1"))))
        assertEquals(listOf(Triple(0L, 1L, false)), containing(store, "order", store.value(ONodeRef(doc, 1))))
        // Typed equality, and no path-qualified matching.
        assertEquals(emptyList(), containing(store, "id", OStr("7")))
        assertEquals(emptyList(), containing(store, "order.id", ONum("7")))

        val hit = store.search(OQuery("sku", OStr("B2"), OResultMode.CONTAINING_ELEMENT)).hits.single()
        assertEquals("""{"sku":"B2","qty":1.0}""", hit.json)
        val whole = store.search(OQuery("sku", OStr("B2"))).hits.single()
        assertEquals(ONodeRef(doc, 0), whole.node)
        assertEquals(example, whole.json)
    }

    @Test
    fun `arrays are one level deep for member keys, but objects in arrays are containers at any depth`() {
        val store = OracleStore()
        store.insert("""{"k":[[1],{"k":1}],"a":[[{"k":1}]]}""")
        // [0]{ "k":[1][ [2][ [3]1 ], [4]{ "k":[5]1 } ], "a":[6][ [7][ [8]{ "k":[9]1 } ] ] }
        assertEquals(listOf(Triple(4L, 5L, false), Triple(8L, 9L, false)), containing(store, "k", ONum("1")))
        assertEquals(listOf(Triple(0L, 2L, true)), containing(store, "k", OArr(ONum("1"))))
    }

    @Test
    fun `one hit per containing object, keeping the first member`() {
        val store = OracleStore()
        store.insert("""{"tags":["a","b","a"],"x":{"tags":"a"}}""")
        assertEquals(listOf(Triple(0L, 2L, true), Triple(5L, 6L, false)), containing(store, "tags", OStr("a")))
    }

    @Test
    fun `scalar roots have no terms, and top-level arrays hold containers`() {
        val store = OracleStore()
        store.insert("7")
        store.insert("""[{"k":7},{"k":7}]""")
        assertEquals(listOf(Triple(1L, 2L, false), Triple(3L, 4L, false)), containing(store, "k", ONum("7")))
        assertEquals(listOf(ONodeRef(2, 0)), store.searchAll(OQuery("k", ONum("7"))).map { it.node })
    }

    @Test
    fun `nested hits`() {
        val store = OracleStore()
        store.insert("""{"t":"a","child":{"t":"a","deeper":{"t":"a"}},"next":{"t":"a"}}""")
        // [0]{"t":[1],"child":[2]{"t":[3],"deeper":[4]{"t":[5]}},"next":[6]{"t":[7]}}
        assertEquals(listOf(0L, 2L, 4L, 6L), containing(store, "t", OStr("a")).map { it.first })
        assertEquals(listOf(0L), containing(store, "t", OStr("a"), ONestedHits.OUTERMOST_ONLY).map { it.first })
        store.insert("""{"x":{"t":"a","in":{"t":"a"}},"y":{"t":"a"}}""")
        assertEquals(listOf(1L, 5L), store.searchAll(OQuery("t", OStr("a"), OResultMode.CONTAINING_ELEMENT, ONestedHits.OUTERMOST_ONLY)).filter { it.node.docId == 2L }.map { it.node.pre })
    }

    @Test
    fun `pagination returns every hit exactly once, in order`() {
        val gen = Gen { random, size ->
            val store = OracleStore()
            val docs = List(random.nextInt(1, 6)) { JsonGen(JsonProfile.HOT_KEYS).value().generate(random, size) }
            docs.forEach { store.insert(it) }
            val query = OQuery(
                key = JsonGen.HOT_KEYS[random.nextInt(JsonGen.HOT_KEYS.size)],
                value = OStr(JsonGen.HOT_VALUES[random.nextInt(JsonGen.HOT_VALUES.size)]),
                mode = OResultMode.entries[random.nextInt(2)],
                nested = ONestedHits.entries[random.nextInt(2)],
                limit = random.nextInt(1, 5),
            )
            store to query
        }
        forAll("oracle pagination", gen, iterations = 300, maxSize = 40) { (store, query) ->
            val all = store.search(query.copy(limit = Int.MAX_VALUE)).hits
            val paged = store.searchAll(query)
            assertEquals(all, paged)
            assertEquals(all.sortedWith(compareBy({ it.node.docId }, { it.node.pre })), all)
            assertEquals(all.size, all.map { it.cursor }.toSet().size)
            all.forEach { assertEquals(store.read(it.node), it.json) }
        }
    }

    @Test
    fun `types - assign at insert, reassign, remove, filter`() {
        val store = OracleStore()
        val doc = store.insert("""{"content":[{"k":1},{"k":1,"sub":{"k":1}}]}""", mapOf("$.content[1]" to "B", "$[\"content\"][0]" to "A"))
        // [0]{"content":[1][ [2]{"k":[3]}, [4]{"k":[5],"sub":[6]{"k":[7]}} ]}
        assertEquals("A", store.typeOf(ONodeRef(doc, 2)))
        assertEquals("B", store.typeOf(ONodeRef(doc, 4)))
        assertEquals(listOf(4L), containing(store, "k", ONum("1"), type = "B").map { it.first })

        store.assignType(ONodeRef(doc, 6), "B")
        store.assignType(ONodeRef(doc, 4), "A")
        assertEquals(listOf(ONodeRef(doc, 2), ONodeRef(doc, 4)), store.objectsOfType("A"))
        assertEquals(listOf(ONodeRef(doc, 6)), store.objectsOfType("B"))
        assertEquals(listOf(ONodeRef(doc, 4)), store.objectsOfType("A", after = ONodeRef(doc, 2)))
        store.assignType(ONodeRef(doc, 2), null)
        assertNull(store.typeOf(ONodeRef(doc, 2)))
        assertEquals(1, store.countOfType("A"))
        // Whole-document hits match when some containing object has the type.
        assertEquals(listOf(ONodeRef(doc, 0)), store.searchAll(OQuery("k", ONum("1"), type = "B")).map { it.node })

        assertFailsWith<IllegalArgumentException> { store.assignType(ONodeRef(doc, 1), "A") } // an array
        assertFailsWith<IllegalArgumentException> { store.assignType(ONodeRef(doc, 99), "A") }
        assertFailsWith<InvalidTypeNameException> { store.assignType(ONodeRef(doc, 2), "") }
        assertFailsWith<InvalidTypeNameException> { store.assignType(ONodeRef(doc, 2), "a\nb") }
        assertFailsWith<InvalidTypeNameException> { store.assignType(ONodeRef(doc, 2), "x".repeat(257)) }
        assertFailsWith<DocumentNotFoundException> { store.assignType(ONodeRef(77, 0), "A") }
    }

    @Test
    fun `type paths that don't select an object reject the insert and consume the docId`() {
        val store = OracleStore()
        for (path in listOf("$.missing", "$.a", "$.list[5]", "$.list[0]")) {
            val e = assertFailsWith<DocumentRejectedException> { store.insert("""{"a":1,"list":[2]}""", mapOf(path to "T")) }
            assertEquals(RejectReason.TYPE_PATH_UNMATCHED, e.reason)
        }
        assertEquals(5L, store.insert("{}", mapOf("$" to "Root")))
        assertFailsWith<IllegalArgumentException> { store.insert("{}", mapOf("a.b" to "T")) }
        assertFailsWith<IllegalArgumentException> { store.insert("{}", mapOf("$[01]" to "T")) }
        assertFailsWith<InvalidTypeNameException> { store.insert("{}", mapOf("$" to "")) }
        assertEquals(6L, store.peekNextDocId)
        assertFailsWith<DocumentRejectedException> { store.insert("[1,]") }
        assertEquals(7L, store.peekNextDocId)
        assertEquals(listOf(5L), store.liveDocIds())
    }

    @Test
    fun `delete hides the document everywhere and is idempotent`() {
        val store = OracleStore()
        val a = store.insert("""{"k":true}""", mapOf("$" to "T"))
        val b = store.insert("""{"k":true}""")
        assertTrue(store.delete(a))
        assertFalse(store.delete(a))
        assertEquals(listOf(ONodeRef(b, 0)), store.searchAll(OQuery("k", OBool(true))).map { it.node })
        assertEquals(emptyList(), store.objectsOfType("T"))
        assertFailsWith<DocumentNotFoundException> { store.read(ONodeRef(a, 0)) }
        assertFailsWith<DocumentNotFoundException> { store.typeOf(ONodeRef(a, 0)) }
        assertFailsWith<DocumentNotFoundException> { store.delete(99) }
        assertEquals(listOf(a), store.deletedDocIds())

        assertFailsWith<DocumentRejectedException> { store.insert("[1,]") }
        val rejected = store.peekNextDocId - 1
        val c = store.insert("""{"k":true}""")
        assertFailsWith<DocumentNotFoundException>("a rejected insert's docId") { store.deleteAll(listOf(c, rejected)) }
        assertEquals(2, store.searchAll(OQuery("k", OBool(true))).size, "a failing deleteAll changes nothing")
        assertEquals(2, store.deleteAll(listOf(c, a, b, c)))
        assertEquals(emptyList(), store.liveDocIds())
    }

    @Test
    fun `query literals follow document rules`() {
        val store = OracleStore()
        store.insert("""{"n":null,"big":1e300}""")
        assertEquals(1, store.searchAll(OQuery("n", ONull)).size)
        assertEquals(1, store.searchAll(OQuery("big", ONum("10e299"))).size)
        val e = assertFailsWith<DocumentRejectedException> { store.search(OQuery("big", ONum("1e2147483647"))) }
        assertEquals(RejectReason.NUMBER_OUT_OF_RANGE, e.reason)
        assertFailsWith<DocumentRejectedException> { store.search(OQuery("s", OStr("\uD800"))) }
        val noHashTerms = OracleStore(OracleOptions(hashTerms = false))
        noHashTerms.insert("""{"a":[1]}""")
        assertTrue(noHashTerms.searchAll(OQuery("a", ONum("1"))).isNotEmpty())
        assertFailsWith<IllegalArgumentException> { noHashTerms.search(OQuery("a", OArr(ONum("1")))) }
    }
}
