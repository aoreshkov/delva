package app.oreshkov.delva.internal.query

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.lsm.PostingRun
import app.oreshkov.delva.internal.payload.JsonOutput
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.payload.Reassembler
import app.oreshkov.delva.internal.split.OracleNodes
import app.oreshkov.delva.internal.split.Posting
import app.oreshkov.delva.internal.split.SplitOptions
import app.oreshkov.delva.internal.split.split
import app.oreshkov.delva.internal.store.DocumentFragments
import app.oreshkov.delva.internal.store.MemoryFragmentStore
import app.oreshkov.delva.internal.term.FragmentIndexer
import app.oreshkov.delva.internal.term.PostingEntryReader
import app.oreshkov.delva.internal.term.TermSorter
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.property.forAll
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The locator, the fragment store and the run codec over split documents: every node is found by `pre` (floor lookup,
 * parent hops, stub descents) and streams back as the oracle's JSON; encoded runs decode to the live postings.
 */
class NodeLocatorTest {
    private class Stored(val store: MemoryFragmentStore, val value: OValue, val runs: List<PostingRun>, val livePostings: List<Posting>)

    private fun store(value: OValue, options: SplitOptions): Stored {
        val run = split(JsonText.minifiedBytes(value), options)
        val store = MemoryFragmentStore(options.maxRecordBytes)
        for (f in run.fragments.written) store.add(DOC, f.key.pre, f.key.kind, f.key.seq, f.context, f.payload, 0, f.payload.size)
        val runs = ArrayList<PostingRun>()
        // Re-encode the collected runs through the codec by replaying the fragments into a real sorter.
        val sorter = TermSorter(options.sorterBytes) { docId, runSeq, sorted -> PostingRun.fromSortedRun(docId, runSeq, sorted, GrowableBytes(), runs) }
        sorter.reset(DOC)
        val indexer = FragmentIndexer(options.hashTerms)
        for (f in run.fragments.written) indexer.index(f.key.pre, f.key.kind, f.key.seq, f.context, f.payload, 0, f.payload.size, sorter)
        sorter.finish()
        return Stored(store, value, runs, run.runs.runs.flatten())
    }

    @Test
    fun `every node is located and streams back as the oracle's JSON`() {
        for (t in listOf(32, 64, 256, 4096)) {
            val options = SplitOptions(t, 2 * t + 64, sorterBytes = 4 * (maxOf(t / 4, 64) + 128) + 4096)
            for (profile in JsonProfile.entries) {
                forAll("locate T=$t $profile", JsonGen(profile, t).value(), iterations = 15, maxSize = if (t < 256) 120 else 600) { value ->
                    val stored = store(value, options)
                    val nodes = OracleNodes(value)
                    val locator = NodeLocator(stored.store)
                    val lookup = DocumentFragments(stored.store).also { it.docId = DOC }
                    val reassembler = Reassembler(lookup)
                    val view = FragmentView()
                    for (pre in 0 until nodes.values.size.toLong()) {
                        val node = locator.locate(DOC, pre)
                        val expected = nodes[pre]
                        assertEquals(pre, node.pre)
                        assertEquals(nodes.sizes[pre.toInt()], node.size, "size of $pre")
                        assertEquals(if (expected is OObj) PayloadTag.OBJ else if (expected is OArr) PayloadTag.ARR else 0, node.containerKind, "kind of $pre")
                        val bytes = ByteArrayOutputStream()
                        val out = JsonOutput(bytes, 64)
                        if (node.isSplit) {
                            reassembler.writeSplit(node.pre, out)
                        } else {
                            check(stored.store.find(DOC, node.holderPre, node.holderKind, 0, view))
                            reassembler.writeValue(view.slice(), node.valueOffset, node.pre, out)
                        }
                        out.flush()
                        assertEquals(JsonText.minified(expected), bytes.toString(Charsets.UTF_8), "JSON of $pre")
                    }
                }
            }
        }
    }

    @Test
    fun `encoded runs decode to the live postings`() {
        val options = SplitOptions(64, 256, sorterBytes = 4096)
        forAll("posting codec", JsonGen(JsonProfile.HOT_KEYS, 64).value(), iterations = 200, maxSize = 300) { value ->
            val stored = store(value, options)
            val reader = PostingEntryReader()
            val decoded = ArrayList<Pair<Long, Long>>()
            for (run in stored.runs) {
                reader.reset(run.entries, 0, run.entries.size, run.count)
                var previous = -1L
                while (reader.next()) {
                    check(reader.containerPre > previous)
                    previous = reader.containerPre
                    decoded += reader.containerPre to reader.memberPre
                }
            }
            assertEquals(stored.livePostings.map { it.containerPre to it.memberPre }.sortedWith(compareBy({ it.first }, { it.second })), decoded.sortedWith(compareBy({ it.first }, { it.second })))
        }
    }

    private companion object {
        const val DOC = 7L
    }
}
