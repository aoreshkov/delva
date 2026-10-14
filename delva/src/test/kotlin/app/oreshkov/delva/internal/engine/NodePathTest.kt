package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.oracle.JsonPathOracle
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Normalized Paths of stored nodes (SPEC.md §3.2): `pathOf` and `SearchHit.path` name every node as the oracle does,
 * through REF, SPLIT (by PART) and BIGSTR stubs, for key–value and JSONPath hits alike.
 */
class NodePathTest {
    private fun options(t: Int) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 512),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 13, log = TestEngines.LOG, backgroundFlush = false,
    )

    /** The Normalized Path of every node of [document] by `pre`, as the oracle gives them. */
    private fun oraclePaths(document: OValue): Map<Long, String> =
        mapOf(0L to "$") + JsonPathOracle.parse("$..*").select(document).associate { it.pre to it.path }

    @Test
    fun `every node of random documents has the oracle's path`() {
        val gen = Gen { random, size -> JsonGen(JsonProfile.entries[random.nextInt(JsonProfile.entries.size)], fragmentSize = 64).value().generate(random, size) }
        var nodes = 0
        for (t in listOf(32, 256, 64 * 1024)) {
            TestEngines.open(options(t)).use { store ->
                forAll("pathOf T=$t", gen, iterations = 150) { document ->
                    val docId = store.insert(JsonText.minified(document))
                    for ((pre, path) in oraclePaths(document)) {
                        assertEquals(path, store.pathOf(NodeRef(docId, pre)), "pre $pre of ${JsonText.minified(document).take(300)}")
                        nodes++
                    }
                }
            }
        }
        assertTrue(nodes > 8_000, "only $nodes nodes named")
    }

    @Test
    fun `nodes of split containers are found through the directory`() {
        val document = buildString {
            append("{\"items\":[")
            for (i in 0 until 3000) {
                if (i > 0) append(',')
                append("{\"id\":").append(i).append(",\"tag\":\"t").append(i % 7).append("\"}")
            }
            append("],\"by\":{")
            for (i in 0 until 2000) {
                if (i > 0) append(',')
                append("\"k").append(i).append("\":[").append(i).append("]")
            }
            append("}}")
        }
        TestEngines.open(options(256)).use { store ->
            val docId = store.insert(document)
            assertEquals("$['items'][0]", store.pathOf(NodeRef(docId, 2)))
            // Each item is 3 nodes: the object, then its two members.
            assertEquals("$['items'][2999]['tag']", store.pathOf(NodeRef(docId, 2 + 3 * 2999 + 2)))
            val byPre = 2 + 3 * 3000
            assertEquals("$['by']", store.pathOf(NodeRef(docId, byPre.toLong())))
            assertEquals("$['by']['k1999'][0]", store.pathOf(NodeRef(docId, byPre + 2L * 1999 + 2)))

            val hits = store.search(KeyValueQuery("tag", JsonQueryValue.Str("t6"), mode = ResultMode.CONTAINING_ELEMENT, limit = 1000)).use { r ->
                r.iterator().asSequence().map { it.path }.toList()
            }
            assertEquals((0 until 3000).filter { it % 7 == 6 }.map { "$['items'][$it]" }, hits)
        }
    }

    @Test
    fun `hits of both searches carry their paths`() {
        TestEngines.open(options(32)).use { store ->
            val docId = store.insert("""{"a":{"b":[1,{"c":2,"d":"x"},{"c":3}]},"e":{"c":2}}""")
            fun kv(mode: ResultMode) = store.search(KeyValueQuery("c", JsonQueryValue.Json("2"), mode = mode)).use { r -> r.iterator().asSequence().map { it.path }.toList() }
            assertEquals(listOf("$['a']['b'][1]", "$['e']"), kv(ResultMode.CONTAINING_ELEMENT))
            assertEquals(listOf("$"), kv(ResultMode.WHOLE_DOCUMENT))

            fun jp(text: String, mode: ResultMode = ResultMode.CONTAINING_ELEMENT) =
                store.search(JsonPathQuery(JsonPath.compile(text), mode = mode, scan = ScanPolicy.ALLOW_SCAN)).use { r ->
                    r.iterator().asSequence().map { it.path to store.pathOf(it.node) }.toList()
                }
            assertEquals(listOf("$['a']['b'][1]" to "$['a']['b'][1]", "$['e']" to "$['e']"), jp("$..[?@.c == 2]"))
            assertEquals(listOf("$['a']['b'][1]['c']", "$['a']['b'][2]['c']", "$['e']['c']"), jp("$..c").map { it.first })
            assertEquals(listOf("$" to "$"), jp("$[?@.c == 2]", ResultMode.WHOLE_DOCUMENT))
            assertEquals(listOf("$" to "$"), jp("$"))
            assertEquals("$['a']['b'][1]['d']", store.pathOf(NodeRef(docId, 6)))
        }
    }

    @Test
    fun `pathOf fails for a missing document or node and a path for a closed result`() {
        TestEngines.open(options(64)).use { store ->
            val docId = store.insert("""{"a":[1,2]}""")
            assertEquals("$['a'][1]", store.pathOf(NodeRef(docId, 3)))
            assertFailsWith<IllegalArgumentException> { store.pathOf(NodeRef(docId, 4)) }
            assertFailsWith<DocumentNotFoundException> { store.pathOf(NodeRef(DocId(docId.raw + 1), 0)) }
            store.delete(docId)
            assertFailsWith<DocumentNotFoundException> { store.pathOf(NodeRef(docId, 0)) }

            val other = store.insert("""{"a":[{"k":1}]}""")
            val result = store.search(KeyValueQuery("k", JsonQueryValue.Json("1"), mode = ResultMode.CONTAINING_ELEMENT))
            val hit = result.iterator().next()
            result.close()
            assertFailsWith<IllegalStateException> { hit.path }
            assertEquals("$['a'][0]", store.pathOf(hit.node))
            assertEquals(other, hit.node.docId)
        }
    }
}
