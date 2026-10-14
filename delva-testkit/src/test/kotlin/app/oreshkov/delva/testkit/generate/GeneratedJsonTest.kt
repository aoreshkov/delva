package app.oreshkov.delva.testkit.generate

import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.json.ParseResult
import app.oreshkov.delva.testkit.oracle.ONestedHits
import app.oreshkov.delva.testkit.oracle.OQuery
import app.oreshkov.delva.testkit.oracle.OResultMode
import app.oreshkov.delva.testkit.oracle.OracleStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneratedJsonTest {
    private val queries: List<Pair<String, OValue>> = listOf(
        "qty" to ONum("1"), "tags" to OStr("hot"), "sku" to OStr("S42"), "hot" to OBool(true), "note" to ONull,
        "leaf" to OBool(true), "level" to ONum("3"), "@type" to OStr("Catalog"), "id" to ONum("1"),
    )

    private fun sizeFor(profile: BigDocProfile) = if (profile == BigDocProfile.LONG_STRINGS) 3_000_000L else 400_000L

    @Test
    fun `same seed gives the same bytes, and the size is near the target`() {
        for (profile in BigDocProfile.entries) {
            val target = sizeFor(profile)
            val a = GeneratedJson(7, profile, target).readAllBytes()
            val b = GeneratedJson(7, profile, target).readAllBytes()
            assertContentEquals(a, b, "$profile")
            assertTrue(a.size >= target && a.size < target + (9L shl 20), "$profile size ${a.size}")
            assertTrue(!GeneratedJson(8, profile, target).readAllBytes().contentEquals(a), "$profile seed matters")
        }
    }

    @Test
    fun `streaming expectations agree with the oracle store`() {
        for (profile in BigDocProfile.entries) {
            for (seed in 1L..2L) {
                val bytes = GeneratedJson(seed, profile, sizeFor(profile)).readAllBytes()
                val root = assertIs<ParseResult.Accepted>(OracleJson.parse(bytes), "$profile parses").value
                val store = OracleStore()
                val docId = store.insert(root)

                for ((key, value) in queries) {
                    val expectation = StreamingExpectation(key, value)
                    val stream = GeneratedJson(seed, profile, sizeFor(profile), expectation)
                    assertEquals(bytes.size.toLong(), stream.drain())
                    assertEquals(store.nodes(docId).size.toLong(), expectation.nodeCount, "$profile node count")

                    val all = store.searchAll(OQuery(key, value, OResultMode.CONTAINING_ELEMENT))
                    val outermost = store.searchAll(OQuery(key, value, OResultMode.CONTAINING_ELEMENT, ONestedHits.OUTERMOST_ONLY))
                    val whole = store.searchAll(OQuery(key, value))
                    val context = "$profile seed=$seed $key=$value"
                    assertEquals(all.size.toLong(), expectation.containingHits, "$context containing")
                    assertEquals(outermost.size.toLong(), expectation.outermostHits, "$context outermost")
                    assertEquals(whole.isNotEmpty(), expectation.documentMatches, "$context whole")
                    if (all.size <= 1000) assertEquals(all.map { it.node.pre }, expectation.firstHitContainers.sorted(), context)
                }
            }
        }
    }
}
