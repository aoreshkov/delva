package app.oreshkov.delva.testkit.property

import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonShrinker
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShrinkTest {
    private fun failureMessage(block: () -> Unit): String =
        (runCatching(block).exceptionOrNull() as? AssertionError)?.message ?: error("property didn't fail")

    @Test
    fun `operation lists shrink to the minimal failing sequence`() {
        // Fails when the list contains 3 followed later by 7.
        val message = failureMessage {
            forAll("ops", Gens.list(Gens.int(0..9), maxLength = 60), iterations = 500, shrinker = Shrinkers.list()) { ops ->
                val i = ops.indexOf(3)
                check(i < 0 || 7 !in ops.subList(i, ops.size)) { "3 before 7" }
            }
        }
        // Shrinking the size alone may already reach the minimal list; then no shrink step is reported.
        val shrunk = message.lines().first { it.contains("value after") || it.contains("value:") }.substringAfter(": ")
        assertEquals("[3, 7]", shrunk, message)
        assertTrue(message.contains("reproduce with: -Ddelva.seed="), message)
    }

    @Test
    fun `a list that fails whatever it holds shrinks to the empty list`() {
        // A constant generator: shrinking the size alone can't reach the empty list.
        val message = failureMessage {
            forAll("any list", Gens.constant(listOf(5)), iterations = 1, shrinker = Shrinkers.list()) { error("fails") }
        }
        assertTrue(message.contains("value after 1 shrink steps: []"), message)
    }

    @Test
    fun `a virtual machine error carries the seed and size of its case`() {
        val reproduce = Regex("""reproduce with: -Ddelva\.seed=-?\d+ -Ddelva\.size=\d+""")
        val thrownByCase = assertFailsWith<StackOverflowError> {
            forAll("overflow", Gens.constant(7), iterations = 1) { throw StackOverflowError() }
        }
        val caseNote = thrownByCase.suppressed.single().message.orEmpty()
        assertTrue(reproduce.containsMatchIn(caseNote), caseNote)

        // The case fails normally and a shrink candidate overflows: the note names the case being shrunk.
        val thrownWhileShrinking = assertFailsWith<StackOverflowError> {
            forAll("overflow", Gens.constant(listOf(1, 2)), iterations = 1, shrinker = Shrinkers.list()) {
                if (it.isEmpty()) throw StackOverflowError() else error("fails")
            }
        }
        val shrinkNote = thrownWhileShrinking.suppressed.single().message.orEmpty()
        assertTrue(reproduce.containsMatchIn(shrinkNote), shrinkNote)
    }

    /** True for a document containing a 'q' anywhere in a string or key; the property the JSON tests break. */
    private fun containsQ(v: OValue): Boolean = when (v) {
        is OStr -> 'q' in v.value
        is OArr -> v.items.any { containsQ(it) }
        is OObj -> v.members.any { 'q' in it.first || containsQ(it.second) }
        else -> false
    }

    /** The fixed points: "q" itself, or an object whose only key is "q" and whose value can't shrink further. */
    private val minimalWithQ = setOf("OStr(value=q)") +
        listOf("ONull", "ONum(text=0)", "OStr(value=)", "OArr(items=[])", "OObj(members=[])").map { "OObj(members=[(q, $it)])" }

    private fun shrunkWithQ(gen: Gen<OValue>, iterations: Int, maxSize: Int): Pair<String, String> {
        val message = failureMessage {
            forAll("json", gen, iterations = iterations, maxSize = maxSize, shrinker = JsonShrinker) { doc ->
                check(!containsQ(doc)) { "has q: ${JsonText.minified(doc)}" }
            }
        }
        return message.lines().first { it.contains("value after") || it.contains("value:") }.substringAfter(": ") to message
    }

    @Test
    fun `JSON trees shrink structurally`() {
        val (shrunk, message) = shrunkWithQ(JsonGen().value(), iterations = 500, maxSize = 80)
        assertTrue(shrunk in minimalWithQ, message)
    }

    @Test
    fun `a nested JSON document shrinks to a fixed point by unwrapping arrays and objects`() {
        // A constant generator: shrinking the size can't help, so only the shrinker's own steps reach the minimum.
        val nested = OArr(
            ONum("1"),
            OObj("a" to OArr(OBool(true), OObj("xqz" to OArr(ONull, ONum("-2.5")), "b" to OStr("no")))),
            OStr("plain"),
        )
        val (shrunk, message) = shrunkWithQ(Gens.constant(nested), iterations = 1, maxSize = 1)
        assertTrue(shrunk in minimalWithQ, message)
    }
}
