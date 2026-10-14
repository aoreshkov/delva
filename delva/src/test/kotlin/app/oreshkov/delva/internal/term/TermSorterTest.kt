package app.oreshkov.delva.internal.term

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.split.CollectingRuns
import app.oreshkov.delva.internal.split.Posting
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.Gens
import app.oreshkov.delva.testkit.property.forAll
import app.oreshkov.delva.testkit.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TermSorterTest {
    private fun TermSorter.add(term: String, container: Long, member: Long, inArray: Boolean = false, kind: Int = PostingLocator.SUBTREE, fragmentPre: Long = 0, offset: Int = 5) {
        val bytes = term.toByteArray()
        posting(bytes, 0, bytes.size, container, member, inArray, kind, fragmentPre, offset, 0)
    }

    @Test
    fun `runs are sorted by unsigned term bytes then container, and in-array duplicates collapse`() {
        val runs = CollectingRuns()
        val sorter = TermSorter(4096, runs)
        sorter.reset(9)
        sorter.add("b", 3, 4)
        sorter.add("é", 1, 2)
        sorter.add("a", 7, 9, inArray = true, kind = PostingLocator.UNRESOLVED, offset = 0)
        sorter.add("a", 7, 8, inArray = true, kind = PostingLocator.UNRESOLVED, offset = 0)
        sorter.add("a", 2, 3)
        sorter.finish()
        assertEquals(1, runs.runs.size)
        val run = runs.runs[0]
        assertEquals(listOf("a" to 2L, "a" to 7L, "b" to 3L, "é" to 1L), run.map { String(it.term.chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray()) to it.containerPre })
        assertEquals(8L, run[1].memberPre)
        assertEquals(5L, sorter.postingCount)
    }

    @Test
    fun `a full buffer emits a run and continues`() {
        forAll("sorter runs", Gens.list(Gen { r, _ -> Triple(r.nextInt(0, 40), r.nextLong(0, 50), r.nextLong(0, 1000)) }, maxLength = 2000), iterations = 100, maxSize = 2000) { entries ->
            val runs = CollectingRuns()
            val sorter = TermSorter(4096, runs)
            sorter.reset(1)
            for ((term, container, member) in entries) sorter.add("term-$term", container, member, inArray = true, kind = PostingLocator.UNRESOLVED, offset = 0)
            sorter.finish()
            assertEquals(sorter.runCount, runs.runs.size)
            val comparator = compareBy<Posting>({ it.term }, { it.containerPre })
            for (run in runs.runs) {
                assertEquals(run.sortedWith(comparator), run)
                assertEquals(run.size, run.distinctBy { it.term to it.containerPre }.size)
            }
            val expected = entries.groupBy { "term-${it.first}".toByteArray().toHex() to it.second }.mapValues { (_, v) -> v.minOf { it.third } }
            val actual = runs.runs.flatten().groupBy { it.term to it.containerPre }.mapValues { (_, v) -> v.minOf { it.memberPre } }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `duplicates that differ in more than memberPre are corruption`() {
        val sorter = TermSorter(4096) { _, _, _ -> }
        sorter.reset(1)
        sorter.add("a", 1, 2, inArray = true, kind = PostingLocator.UNRESOLVED, offset = 0)
        sorter.add("a", 1, 3, inArray = true, kind = PostingLocator.SUBTREE, offset = 4)
        assertFailsWith<CorruptionException> { sorter.finish() }
        sorter.reset(1)
        sorter.add("a", 1, 2)
        sorter.add("a", 1, 3)
        assertFailsWith<CorruptionException> { sorter.finish() }
    }
}
