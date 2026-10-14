package app.oreshkov.delva.internal.split

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.testkit.json.OracleJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SplitterTest {
    private val small = SplitOptions(fragmentBytes = 32, budgetBytes = 64, sorterBytes = 4096, keyArenaBytes = 1024)

    private fun roundTrip(json: String, options: SplitOptions = small): SplitRun {
        val run = split(json, options)
        assertEquals(json, reassemble(run.fragments))
        val value = OracleJson.parseOrThrow(json)
        checkFragments(run.fragments, value, options)
        checkLocators(run.runs.runs.flatten(), run.fragments, value)
        assertEquals(expectedPostings(value, options.hashTerms), collapse(run.runs.runs.flatten()).mapValues { (_, p) -> p.memberPre to p.inArray })
        return run
    }

    private fun kinds(run: SplitRun) = run.fragments.byKey.keys.map { it.toString() }

    @Test
    fun `a small document is one SUBTREE and one run`() {
        val run = roundTrip("""{"a":1}""", SplitOptions())
        assertEquals(listOf("(0, SUBTREE, 0)"), kinds(run))
        assertEquals(1, run.runs.runs.size)
        assertEquals(1L, run.splitter.postingCount)
    }

    @Test
    fun `scalar roots are one SUBTREE without terms`() {
        for (json in listOf("null", "true", "-1.5e3", "123", "\"x\"")) {
            val run = roundTrip(json, SplitOptions())
            assertEquals(listOf("(0, SUBTREE, 0)"), kinds(run))
            assertEquals(0L, run.splitter.postingCount)
        }
    }

    @Test
    fun `wide containers split into PARTs with a HEADER`() {
        val json = (0 until 40).joinToString(",", "[", "]") { "\"value-$it\"" }
        val run = roundTrip(json)
        val keys = run.fragments.byKey.keys
        assertTrue(keys.first() == FragmentKey(0, FragmentKind.HEADER, 0), "root is split: $keys")
        assertTrue(keys.count { it.kind == FragmentKind.PART } > 5, "$keys")
    }

    @Test
    fun `large children are cut out as SUBTREEs`() {
        val json = """{"a":{"x":"0123456789abcdef","y":"0123456789abcdef"},"b":1,"c":"0123456789abcdef0123456789abcdef"}"""
        val run = roundTrip(json, SplitOptions(64, 4096))
        assertTrue(FragmentKey(1, FragmentKind.SUBTREE, 0) in run.fragments.byKey, kinds(run).toString())
    }

    @Test
    fun `long strings become STRCHUNKs`() {
        val long = "é".repeat(100) + "x".repeat(33)
        val run = roundTrip("""{"k":"$long","n":[1,"$long"]}""")
        assertEquals((0..7).toList(), run.fragments.byKey.keys.filter { it.pre == 1L && it.kind == FragmentKind.STRCHUNK }.map { it.seq })
        assertTrue(run.fragments.byKey.keys.any { it.pre == 4L && it.kind == FragmentKind.STRCHUNK })
        val root = roundTrip("\"$long\"")
        assertTrue(root.fragments.byKey.keys.count { it.kind == FragmentKind.STRCHUNK } > 1)
    }

    @Test
    fun `deep documents under a small budget`() {
        val json = "[".repeat(300) + "{\"k\":\"v\"}" + "]".repeat(300)
        roundTrip(json)
        roundTrip("{\"a\":".repeat(200) + "[1,2,3,4,5,6,7,8,9,10]" + "}".repeat(200))
    }

    @Test
    fun `duplicate keys are rejected in exact, hashed and spilled modes`() {
        fun rejected(json: String, options: SplitOptions = small) {
            val e = assertFailsWith<DocumentRejectedException> { split(json, options) }
            assertEquals(RejectReason.DUPLICATE_KEY, e.reason)
        }
        rejected("""{"a":1,"b":2,"a":3}""")
        val many = (0 until 500).joinToString(",") { "\"k$it\":$it" }
        rejected("{$many,\"k17\":0}", SplitOptions(4096, 1 shl 20))
        val options = SplitOptions(4096, 1 shl 20, keyArenaBytes = 1024)
        val run = newSplitter(options, keep = false)
        run.splitter.split(1, app.oreshkov.delva.internal.parse.Utf8JsonTokenizer(java.io.ByteArrayInputStream("{$many}".toByteArray())), keySeed = 3)
        assertTrue(run.spill.created > 5, "spilled ${run.spill.created}")
        assertEquals(0, run.spill.live)
        rejected("{$many,\"k1\":0}", options)
        rejected("{$many,\"k499\":0}", options)
        val e = assertFailsWith<DocumentRejectedException> { split("{\"x\":{$many,\"k3\":0},\"y\":1}", options) }
        assertEquals(RejectReason.DUPLICATE_KEY, e.reason)
    }

    @Test
    fun `keys and numbers longer than the atom limit are rejected`() {
        val options = SplitOptions(256, 1024)
        // The splitter enforces the limit itself, whatever the token source allows.
        val run = newSplitter(options)
        val tokenizer = app.oreshkov.delva.internal.parse.Utf8JsonTokenizer(java.io.ByteArrayInputStream(("{\"" + "k".repeat(65) + "\":1}").toByteArray()))
        val e = assertFailsWith<DocumentRejectedException> { run.splitter.split(1, tokenizer) }
        assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason)
    }
}
