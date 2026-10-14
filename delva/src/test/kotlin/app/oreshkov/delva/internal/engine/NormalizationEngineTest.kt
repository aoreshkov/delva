package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CompareOptions
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NFC normalization (SPEC.md §2.1): a store created with it stores, indexes, compares and reads back one spelling
 * of every string and key, and its queries, type names and type paths are normalized the same way.
 */
class NormalizationEngineTest {
    private val composedKey = "café"
    private val decomposedKey = "café"
    private val composedValue = "résumé"
    private val decomposedValue = "résumé"

    private fun options(normalization: Normalization, stringChunkBytes: Int = 4096, documentKey: String? = null) = EngineOptions(
        CreationOptions(fragmentBytes = 1024, normalization = normalization).withDocumentKey(documentKey), StoreOptions(encoderBudgetBytes = 4096),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 3, log = TestEngines.LOG, backgroundFlush = false,
        stringChunkBytes = stringChunkBytes,
    )

    private fun hits(store: Engine, key: String, value: String): Int =
        store.search(KeyValueQuery(key, JsonQueryValue.Str(value), ResultMode.CONTAINING_ELEMENT, limit = 100)).use {
            it.iterator().asSequence().count()
        }

    private fun json(store: Engine, key: String, value: String): String? =
        store.search(KeyValueQuery(key, JsonQueryValue.Str(value), ResultMode.WHOLE_DOCUMENT, limit = 10)).use { result ->
            result.iterator().asSequence().firstOrNull()?.readJsonAsString()
        }

    @Test
    fun `with NFC either spelling finds the document and the normalized one is stored`() {
        TestEngines.open(options(Normalization.NFC)).use { store ->
            store.insert("""{"all":true,"$decomposedKey":"$decomposedValue"}""")
            assertEquals(1, hits(store, composedKey, composedValue), "the composed spelling didn't find it")
            assertEquals(1, hits(store, decomposedKey, decomposedValue), "the decomposed spelling didn't find it")
            assertEquals(1, hits(store, composedKey, decomposedValue), "mixed spellings didn't find it")

            val document = json(store, composedKey, composedValue)!!
            assertContains(document, composedKey)
            assertContains(document, composedValue)
            assertFalse('́' in document, "the combining mark survived into the stored document")
        }
    }

    @Test
    fun `without normalization the two spellings are different strings`() {
        TestEngines.open(options(Normalization.NONE)).use { store ->
            store.insert("""{"all":true,"$decomposedKey":"$decomposedValue"}""")
            assertEquals(1, hits(store, decomposedKey, decomposedValue))
            assertEquals(0, hits(store, composedKey, composedValue), "exact comparison found the other spelling")
            assertContains(json(store, decomposedKey, decomposedValue)!!, decomposedValue)
        }
    }

    @Test
    fun `type names and type paths are normalized with the keys they select`() {
        TestEngines.open(options(Normalization.NFC)).use { store ->
            // The path spells the key one way, the document the other, and the type name is decomposed as well.
            store.insert(
                """{"all":true,"$decomposedKey":{"x":1}}""",
                mapOf("$[\"$composedKey\"]" to decomposedValue),
            )
            val nodes = store.objectsOfType(composedValue)
            assertEquals(1, nodes.size, "the composed type name found nothing")
            assertEquals(1, store.countOfType(decomposedValue), "the decomposed type name found nothing")
            assertEquals(composedValue, store.typeOf(nodes[0]), "the stored type name isn't normalized")
            assertEquals(
                1,
                store.search(KeyValueQuery("x", JsonQueryValue.Num(1), ResultMode.CONTAINING_ELEMENT, type = decomposedValue)).use {
                    it.iterator().asSequence().count()
                },
                "the type filter didn't normalize its name",
            )
        }
    }

    @Test
    fun `comparisons see one spelling`() {
        TestEngines.open(options(Normalization.NFC)).use { store ->
            val docId = store.insert("""{"all":true,"$decomposedKey":"$decomposedValue"}""")
            val node = store.search(KeyValueQuery(composedKey, JsonQueryValue.Str(composedValue), ResultMode.WHOLE_DOCUMENT)).use {
                it.iterator().next().node
            }
            val composed = CompareOperand.Json("""{"all":true,"$composedKey":"$composedValue"}""")
            assertTrue(store.isEqual(CompareOperand.Stored(node), composed, CompareOptions()), "spellings compared unequal")
            assertTrue(store.diff(CompareOperand.Stored(node), composed, app.oreshkov.delva.DiffOptions()).isEmpty())
            assertEquals(docId, node.docId)
        }
        TestEngines.open(options(Normalization.NONE)).use { store ->
            store.insert("""{"all":true,"$decomposedKey":"$decomposedValue"}""")
            val node = store.search(KeyValueQuery(decomposedKey, JsonQueryValue.Str(decomposedValue), ResultMode.WHOLE_DOCUMENT)).use {
                it.iterator().next().node
            }
            val composed = CompareOperand.Json("""{"all":true,"$composedKey":"$composedValue"}""")
            assertFalse(store.isEqual(CompareOperand.Stored(node), composed, CompareOptions()), "exact comparison ignored the spelling")
        }
    }

    @Test
    fun `keys that differ only in spelling are duplicates`() {
        TestEngines.open(options(Normalization.NFC)).use { store ->
            val e = assertFailsWith<DocumentRejectedException> {
                store.insert("""{"$composedKey":1,"$decomposedKey":2}""")
            }
            assertEquals(RejectReason.DUPLICATE_KEY, e.reason)
        }
        // The same document is two members when spellings are kept apart.
        TestEngines.open(options(Normalization.NONE)).use { store ->
            store.insert("""{"all":true,"$composedKey":1,"$decomposedKey":2}""")
            assertEquals(1, store.documents().size)
        }
    }

    @Test
    fun `a long string normalizes across the parts it arrives in`() {
        // Longer than a fragment and than the string chunks, with marks landing on the boundaries.
        val text = ("éä中ôx").repeat(400)
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        TestEngines.open(options(Normalization.NFC, stringChunkBytes = 64)).use { store ->
            store.insert("""{"all":true,"note":"$text"}""")
            val document = store.documents().values.single()
            assertContains(document, normalized)
            assertFalse('́' in document, "a combining mark survived a part boundary")
            assertEquals(1, hits(store, "note", text), "the decomposed spelling didn't find the long string")
            assertEquals(1, hits(store, "note", normalized), "the normalized spelling didn't find the long string")
        }
    }

    @Test
    fun `a long run of combining marks is rejected only when the store normalizes`() {
        val zalgo = "x" + "\u0316".repeat(31)
        TestEngines.open(options(Normalization.NFC)).use { store ->
            val e = assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true,"note":"$zalgo"}""") }
            assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason)
            store.insert("""{"all":true,"note":"${zalgo.dropLast(1)}"}""")
        }
        TestEngines.open(options(Normalization.NONE)).use { store ->
            store.insert("""{"all":true,"note":"$zalgo"}""")
            assertEquals(1, hits(store, "note", zalgo))
        }
    }

    @Test
    fun `the run of combining marks is counted in the normalized form, so what reads back goes in again`() {
        TestEngines.open(options(Normalization.NFC)).use { store ->
            // U+0344 normalizes to two marks: 16 as written would be stored as 32, which couldn't go in again.
            val lengthened = "\u4E2D" + "\u0344".repeat(16)
            val e = assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true,"note":"$lengthened"}""") }
            assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason)
            // A Tamil O written as two vowel signs is stored as one: 60 as written are 30 stored.
            val shortened = "\u0B95" + "\u0BC6\u0BBE".repeat(30)
            store.insert("""{"all":true,"note":"$shortened"}""")
            store.insert(json(store, "note", shortened)!!)
            assertEquals(2, hits(store, "note", shortened))
        }
    }

    @Test
    fun `query text past the combining run is rejected, and every spelling of stored text finds it`() {
        val stored = "\u4E2D" + "\u0344".repeat(15) // 30 marks once normalized
        val over = stored + "\u0316"
        TestEngines.open(options(Normalization.NFC, documentKey = "$.id")).use { store ->
            store.insert("""{"all":true,"id":"$stored","$stored":{"$stored":"$stored"}}""", mapOf("$[\"$stored\"]" to stored))
            for (spelling in listOf(stored, Normalizer.normalize(stored, Normalizer.Form.NFC))) {
                assertEquals(1, hits(store, spelling, spelling))
                assertTrue(store.get(spelling) != null)
                assertEquals(1, store.countOfType(spelling))
                val path = JsonPath.compile("$[\"$spelling\"][?@ == \"$spelling\"]")
                assertEquals(1, store.search(JsonPathQuery(path, scan = ScanPolicy.ALLOW_SCAN)).use { it.iterator().asSequence().count() })
            }

            fun rejected(what: String, block: () -> Unit) {
                val e = assertFailsWith<DocumentRejectedException>(what) { block() }
                assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason, what)
                assertContains(e.message!!, "$what has more than 30 combining marks in a row")
            }
            rejected("query key") { hits(store, over, "v") }
            rejected("query string") { hits(store, "k", over) }
            rejected("JSONPath name") { store.search(JsonPathQuery(JsonPath.compile("$[\"$over\"]"), scan = ScanPolicy.ALLOW_SCAN)).close() }
            rejected("JSONPath string") {
                store.search(JsonPathQuery(JsonPath.compile("$[?@ == \"$over\"]"), scan = ScanPolicy.ALLOW_SCAN)).close()
            }
            rejected("document key") { store.get(over) }
            rejected("document key") { store.search(DocumentKeyQuery(prefix = over)).close() }
            rejected("type name") { store.countOfType(over) }
            rejected("type name") { store.insert("""{"all":true,"id":"a","a":{}}""", mapOf("$.a" to over)) }
            rejected("type path") { store.insert("""{"all":true,"id":"b","a":{}}""", mapOf("$[\"$over\"]" to "t")) }
            assertEquals(1, store.documents().size)
        }
        assertFailsWith<InvalidJsonPathException> { CreationOptions(normalization = Normalization.NFC).withDocumentKey("$[\"$over\"]") }
        CreationOptions().withDocumentKey("$[\"$over\"]")
    }

    @Test
    fun `the key limit applies to the normalized key, not to the text as written`() {
        // The key limit is max(T/4, 64) = 256 bytes; e + U+0301 is 3 bytes as written and 2 once composed.
        val fits = "é".repeat(128) // 384 bytes as written, 256 normalized
        val over = "é".repeat(129)
        TestEngines.open(options(Normalization.NFC)).use { store ->
            store.insert("""{"all":true,"$fits":1}""")
            assertEquals(1, store.documents().size)
            val normalized = assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true,"$over":1}""") }
            assertEquals(RejectReason.LIMIT_EXCEEDED, normalized.reason)
            assertContains(normalized.message!!, "key longer than 256 bytes after NFC normalization")
            // Text more than four times the limit can't come within it, so the tokenizer rejects it unread.
            val raw = assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true,"${"x".repeat(1025)}":1}""") }
            assertContains(raw.message!!, "key longer than 256 bytes after NFC normalization")
        }
        TestEngines.open(options(Normalization.NONE)).use { store ->
            val e = assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true,"$fits":1}""") }
            assertContains(e.message!!, "key longer than 256 bytes")
        }
    }

    @Test
    fun `normalization is fixed when the store is created`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(TestEngines.DIR, options(Normalization.NFC), fs).use { store ->
            store.insert("""{"all":true,"$decomposedKey":"$decomposedValue"}""")
        }
        fs.restart()
        val e = assertFailsWith<CreationOptionsMismatchException> {
            Engine.open(TestEngines.DIR, options(Normalization.NONE), fs).close()
        }
        assertEquals(Normalization.NFC, e.stored.normalization)
        assertContains(e.message!!, "normalization NFC, not NONE")
        // Reopening with the option it was created with keeps the store readable.
        fs.restart()
        Engine.open(TestEngines.DIR, options(Normalization.NFC), fs).use { store ->
            assertEquals(1, hits(store, composedKey, composedValue))
            assertNull(json(store, composedKey, "nothing"))
        }
    }
}
