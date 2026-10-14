package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.term.FragmentIndexer
import app.oreshkov.delva.internal.term.TermSorter
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonShrinker
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.assertJsonEquals
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Metamorphic tests of the splitter: at every fragment size and budget, a split document reassembles to its canonical
 * JSON, its fragments satisfy every structural invariant, its postings equal the oracle's, its locators point where they should,
 * and replaying the stored fragments through [FragmentIndexer] reproduces the live runs exactly.
 */
class SplitterPropertyTest {
    private class Case(val value: OValue, val json: ByteArray, val window: Int, val chunk: Int) {
        override fun toString(): String = "window=$window chunk=$chunk json=${String(json)}"
    }

    private fun cases(profile: JsonProfile, fragmentBytes: Int): Gen<Case> = Gen { random, size ->
        val value = JsonGen(profile, fragmentSize = fragmentBytes).value().generate(random, size)
        val json = JsonText.randomlyFormatted(value, random).toByteArray()
        Case(value, json, WINDOWS[random.nextInt(WINDOWS.size)], CHUNKS[random.nextInt(CHUNKS.size)])
    }

    private val caseShrinker = app.oreshkov.delva.testkit.property.Shrinker<Case> { case ->
        JsonShrinker.candidates(case.value).map { Case(it, JsonText.minifiedBytes(it), case.window, case.chunk) }
    }

    @Test
    fun `split documents satisfy every invariant at every fragment size and budget`() {
        for ((fragmentBytes, maxSize) in SIZES) {
            for (budget in listOf(2 * fragmentBytes, 8 * fragmentBytes, 8 * 1024 * 1024)) {
                val options = SplitOptions(fragmentBytes, budget, sorterBytes = maxOf(SORTER_BYTES, 4 * (maxOf(fragmentBytes / 4, 64) + 128)), keyArenaBytes = 2048)
                for (profile in JsonProfile.entries) {
                    forAll("split T=$fragmentBytes B=$budget $profile", cases(profile, fragmentBytes), iterations = 20, maxSize = maxSize, shrinker = caseShrinker) {
                        checkDocument(it, options)
                    }
                }
            }
        }
    }

    @Test
    fun `without hash terms containers get no terms but stubs still carry hashes`() {
        for ((fragmentBytes, maxSize) in SIZES.take(3)) {
            val options = SplitOptions(fragmentBytes, 4 * fragmentBytes, hashTerms = false, sorterBytes = maxOf(SORTER_BYTES, 4 * (maxOf(fragmentBytes / 4, 64) + 128)))
            for (profile in listOf(JsonProfile.MIXED, JsonProfile.ARRAYS_OF_OBJECTS, JsonProfile.DEEP_NESTING, JsonProfile.STRING_EDGES)) {
                forAll("split without hash terms T=$fragmentBytes $profile", cases(profile, fragmentBytes), iterations = 30, maxSize = maxSize, shrinker = caseShrinker) {
                    checkDocument(it, options)
                }
            }
        }
    }

    private fun checkDocument(case: Case, options: SplitOptions) {
        val run = split(case.json, options, window = case.window, chunk = case.chunk)
        val value = case.value
        assertJsonEquals(JsonText.minified(value), reassemble(run.fragments))
        checkFragments(run.fragments, value, options)
        assertEquals(OracleTerms.hash(value), run.splitter.rootHashLow to run.splitter.rootHashHigh, "root hash")
        assertEquals(OracleNodes(value).values.size.toLong(), run.splitter.nodeCount)

        val live = run.runs.runs.flatten()
        val collapsed = collapse(live)
        val expected = expectedPostings(value, options.hashTerms)
        val actual = collapsed.mapValues { (_, p) -> p.memberPre to p.inArray }
        if (expected != actual) {
            val missing = expected.keys - actual.keys
            val extra = actual.keys - expected.keys
            val different = expected.keys.intersect(actual.keys).filter { expected[it] != actual[it] }
            throw AssertionError("postings differ: missing $missing, extra $extra, different ${different.map { it to (expected[it] to actual[it]) }}")
        }
        checkLocators(live, run.fragments, value)

        // Live == replay: the stored fragments, indexed again in write order, give the same runs.
        val replayRuns = CollectingRuns()
        val sorter = TermSorter(options.sorterBytes, replayRuns)
        sorter.reset(7)
        val indexer = FragmentIndexer(options.hashTerms)
        for (f in run.fragments.written) indexer.index(f.key.pre, f.key.kind, f.key.seq, f.context, f.payload, 0, f.payload.size, sorter)
        sorter.finish()
        assertEquals(run.runs.runs, replayRuns.runs, "replayed runs")
    }

    private companion object {
        /** Fragment size → generated document size. */
        val SIZES = listOf(32 to 80, 64 to 120, 256 to 400, 4096 to 4000, 65536 to 16000)
        val WINDOWS = intArrayOf(7, 64 * 1024)
        val CHUNKS = intArrayOf(16, 64 * 1024)

        /** Small, so documents give several runs. */
        const val SORTER_BYTES = 4096
    }
}
