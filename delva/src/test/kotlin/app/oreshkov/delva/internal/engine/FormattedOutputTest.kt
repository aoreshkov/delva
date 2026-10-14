package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ResultTooLargeException
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Formatted output (SPEC.md §5.1) of stored nodes, read through search hits and selected nodes, with fragments small
 * enough that values are stored as REF, SPLIT, PART and BIGSTR stubs: every node's text is the layout of its value.
 */
class FormattedOutputTest {
    private fun options(t: Int) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 512),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 7, log = TestEngines.LOG, backgroundFlush = false,
    )

    private val formats = listOf(
        JsonFormat.PRETTY,
        JsonFormat(indent = "\t", lineWidth = 0),
        JsonFormat(indent = "", lineWidth = 30, maxInlineElements = 3),
        JsonFormat(indent = "    ", lineWidth = 120, maxInlineDepth = 0),
        JsonFormat(lineWidth = 40, maxLevels = 0),
        JsonFormat(indent = "\t", lineWidth = 0, maxLevels = 2),
    )

    private fun expected(value: OValue, format: JsonFormat) =
        JsonText.formatted(value, format.indent, format.lineWidth, format.maxInlineElements, format.maxInlineDepth, format.maxLevels)

    @Test
    fun `hits and selected nodes are written in the layout of their values at every fragment size`() {
        val random = Random(14)
        for (t in listOf(32, 256, 64 * 1024)) {
            TestEngines.open(options(t)).use { store ->
                val values = ArrayList<OValue>()
                for (profile in listOf(JsonProfile.MIXED, JsonProfile.UNICODE, JsonProfile.EMPTY_CONTAINERS)) {
                    repeat(15) {
                        val value = JsonGen(profile, fragmentSize = t).value().generate(random, 40)
                        store.insert(JsonText.minified(value))
                        values += value
                    }
                }
                val all = JsonPathQuery(JsonPath.compile("$"), ResultMode.WHOLE_DOCUMENT, limit = 1000, scan = ScanPolicy.ALLOW_SCAN)
                store.search(all).use { result ->
                    val hits = result.iterator().asSequence().toList()
                    assertEquals(values.size, hits.size)
                    hits.zip(values).forEach { (hit, value) ->
                        for (format in formats) {
                            val text = expected(value, format)
                            assertEquals(text, hit.readJsonAsString(format, Int.MAX_VALUE), "T=$t, $format, ${hit.node}")
                            val bytes = ByteArrayOutputStream()
                            hit.writeJsonTo(bytes, format)
                            assertEquals(text, bytes.toString(Charsets.UTF_8))
                        }
                        assertEquals(JsonText.minified(value), hit.readJsonAsString(Int.MAX_VALUE))
                    }
                }
                val descendants = JsonPath.compile("$..*")
                for (docId in 1L..values.size) {
                    store.select(NodeRef(DocId(docId), 0), descendants).use { result ->
                        for (node in result) {
                            val value = OracleJson.parseOrThrow(node.readJsonAsString(Int.MAX_VALUE))
                            for (format in formats) {
                                assertEquals(expected(value, format), node.readJsonAsString(format, Int.MAX_VALUE), "T=$t, $format, ${node.path}")
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `maxChars counts the formatted text`() {
        TestEngines.open(options(64 * 1024)).use { store ->
            val docId = store.insert("""{"a":[1,2]}""")
            store.select(NodeRef(docId, 0), JsonPath.compile("$")).use { result ->
                val node = result.iterator().next()
                val format = JsonFormat(lineWidth = 0)
                val text = "{\n  \"a\": [\n    1,\n    2\n  ]\n}"
                assertEquals(text, node.readJsonAsString(format, text.length))
                assertFailsWith<ResultTooLargeException> { node.readJsonAsString(format, text.length - 1) }
                assertEquals("""{"a":[1,2]}""", node.readJsonAsString(11))
            }
        }
    }
}
