package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.engine.Clock
import app.oreshkov.delva.internal.engine.CompactionOptions
import app.oreshkov.delva.internal.engine.CompactionPolicy
import app.oreshkov.delva.internal.engine.RateLimiter
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.term.PostingCodec
import app.oreshkov.delva.internal.term.PostingLocator
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.internal.visibility.Visibility
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.nio.file.Path
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Segment merges (postings, fragments, types), hidden docIds, the compaction policy and the rate limiter. */
class CompactionTest {
    private val fs = SimulatedFileSystemIo()
    private var fileNo = 1L
    private val cache = ByteCache(4L shl 20)

    private fun newFile(name: String): Pair<Path, FileIo> {
        val path = Path.of("seg", "$name-${fileNo++}")
        fs.createDirectories(path.parent)
        return path to fs.open(path, OpenMode.CREATE_NEW)
    }

    private fun segment(kind: Int, name: String, write: (FileIo) -> Unit): SegmentHandle {
        val (path, file) = newFile(name)
        file.use {
            write(it)
            it.force(true)
        }
        return SegmentHandle(SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), cache, kind), path, fs, Path.of("tmp"))
    }

    // ---- postings ---------------------------------------------------------------------------------------------------

    /** The entry a run holds for `(term, docId, containerPre)`; in-array entries differ between runs only in `memberPre`. */
    private class Entry(val containerPre: Long, val memberPre: Long, val inArray: Boolean, val kind: Int, val fragmentPre: Long, val offset: Int)

    private fun entryOf(term: Int, docId: Long, containerPre: Long, run: Int): Entry = when ((term + docId + containerPre) % 3) {
        0L -> Entry(containerPre, containerPre + 1 + run % 3, true, PostingLocator.UNRESOLVED, 0, 0)
        1L -> Entry(containerPre, containerPre + 2, false, PostingLocator.HEADER, containerPre, 0)
        else -> Entry(containerPre, containerPre + 3, false, PostingLocator.SUBTREE, containerPre / 2, (containerPre % 50).toInt())
    }

    private fun encode(entries: List<Entry>): ByteArray {
        val out = GrowableBytes(64)
        var previous = 0L
        for ((i, e) in entries.withIndex()) {
            PostingCodec.writeEntry(out, if (i == 0) e.containerPre else e.containerPre - previous, e.containerPre, e.memberPre, e.inArray, e.kind, e.fragmentPre, e.offset, (e.containerPre % 7).toInt(), if (i == 0) -1 else (entries[i - 1].containerPre % 7).toInt())
            previous = e.containerPre
        }
        return out.toByteArray()
    }

    private fun term(t: Int): ByteArray = "term-$t".toByteArray()

    /** Merged entries of every term and document of [segments]: `term → docId → containers (containerPre, memberPre, kind)`. */
    private fun read(segments: List<SegmentReader>, hidden: HiddenDocIds = HiddenDocIds.NONE): Map<String, Map<Long, List<Triple<Long, Long, Int>>>> {
        val terms = sortedSetOf<String>()
        for (s in segments) {
            val c = s.cursor()
            var ok = c.first()
            while (ok) {
                terms += String(c.key.toByteArray())
                ok = c.next()
            }
        }
        val out = TreeMap<String, Map<Long, List<Triple<Long, Long, Int>>>>()
        val merger = RunMerger()
        val runs = DocRuns()
        for (t in terms) {
            val bytes = t.toByteArray()
            val sources = segments.mapNotNull { SegmentTermSource.open(it, bytes, PostingSegments.bloomHash(bytes), 0) }
            val merged = TermSources(sources.toTypedArray())
            val docs = TreeMap<Long, List<Triple<Long, Long, Int>>>()
            while (merged.docId != Long.MAX_VALUE) {
                val docId = merged.docId
                if (hidden.contains(docId)) {
                    merged.skipDocument()
                    continue
                }
                runs.clear()
                merged.takeRuns(runs)
                merger.reset(docId, runs)
                val list = ArrayList<Triple<Long, Long, Int>>()
                while (merger.next()) list += Triple(merger.containerPre, merger.memberPre, merger.locatorKind)
                docs[docId] = list
            }
            if (docs.isNotEmpty()) out[t] = docs
        }
        return out
    }

    private val postingCases: Gen<List<List<Triple<Int, Long, List<Long>>>>> = Gen { random, size ->
        // Segments of runs (term, docId, containerPres); a document's runs of a term overlap across and within segments.
        List(random.nextInt(1, 6)) {
            List(random.nextInt(0, 3 * size + 2)) {
                val containers = (0 until random.nextInt(1, 2 + size * 4)).map { random.nextLong(0, 40L * (size + 1)) }.distinct().sorted()
                Triple(random.nextInt(0, 6), random.nextLong(0, 12), containers)
            }
        }
    }

    @Test
    fun `posting merge equals reading the inputs together, without hidden documents, in any run and spill size`() {
        forAll("posting merge", postingCases, iterations = 200) { segments ->
            val random = Random(segments.hashCode().toLong())
            val inputs = segments.mapIndexed { s, runs ->
                val memtable = PostingMemtable()
                val seq = HashMap<Pair<Int, Long>, Int>()
                for ((t, docId, containers) in runs) {
                    val runSeq = seq.merge(t to docId, 1, Int::plus)!!
                    memtable.add(PostingRun(term(t), docId, runSeq, containers.size, encode(containers.map { entryOf(t, docId, it, s * 7 + runSeq) })))
                }
                segment(SegmentFormat.INDEX_POSTING, "posting") { PostingSegments.write(memtable, it, 256) }
            }.filter { it.reader.stats.entryCount > 0 }
            if (inputs.isEmpty()) return@forAll
            val readers = inputs.map { it.reader }
            val hidden = if (random.nextBoolean()) HiddenDocIds.NONE else HiddenDocIds(DocIdSet.of(random.nextLong(0, 12)), DocIdSet.of(random.nextLong(0, 12), random.nextLong(0, 12)))
            val expected = read(readers, hidden)
            var spills = 0
            val merge = PostingMerge(readers, hidden, MergeControl.ALWAYS, { spills++; newFile("spill").second }, maxRunBytes = listOf(64, 1 shl 20)[random.nextInt(2)], listMemoryBytes = listOf(1024, 1 shl 20)[random.nextInt(2)])
            val (path, file) = newFile("merged")
            val stats = file.use { merge.write(it, listOf(256, 4096)[random.nextInt(2)]).also { _ -> it.force(true) } }
            val merged = SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), cache, SegmentFormat.INDEX_POSTING)
            assertEquals(expected, read(listOf(merged)))
            assertEquals(expected.size.toLong(), stats.entryCount)
            val docIds = expected.values.flatMap { it.keys }
            if (docIds.isNotEmpty()) {
                assertEquals(docIds.min(), stats.minDocId)
                assertEquals(docIds.max(), stats.maxDocId)
                assertEquals(hidden.countInRange(stats.minDocId, stats.maxDocId), stats.hiddenDocIds)
            }
            assertEquals(merge.postingsWritten, expected.values.sumOf { d -> d.values.sumOf { it.size.toLong() } })
            for (t in expected.keys) assertTrue(merged.mayContain(PostingSegments.bloomHash(t.toByteArray())), "bloom filter misses $t")
            merged.close()
            assertTrue(spills <= 1)
        }
    }

    @Test
    fun `a merged document larger than a run is split into consecutive runs, and a list spills to a file`() {
        val memtables = List(3) { PostingMemtable() }
        for ((s, m) in memtables.withIndex()) {
            val containers = (0 until 3000L).filter { it % 3 == s.toLong() || it % 7 == 0L }
            m.add(PostingRun(term(1), 5, 1, containers.size, encode(containers.map { entryOf(1, 5, it, s) })))
            for (d in 10L until 400L) m.add(PostingRun(term(2), d, 1, 1, encode(listOf(entryOf(2, d, d, s)))))
        }
        val inputs = memtables.map { m -> segment(SegmentFormat.INDEX_POSTING, "posting") { PostingSegments.write(m, it, 4096) } }
        val readers = inputs.map { it.reader }
        val expected = read(readers)
        var spilled = 0
        val merge = PostingMerge(readers, HiddenDocIds.NONE, MergeControl.ALWAYS, { spilled++; newFile("spill").second }, maxRunBytes = 1024, listMemoryBytes = 2048)
        val (path, file) = newFile("merged")
        file.use { merge.write(it, 4096) }
        val merged = SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), cache, SegmentFormat.INDEX_POSTING)
        assertEquals(expected, read(listOf(merged)))
        assertEquals(1, spilled, "the lists passed the memory limit and shared one spill file")
        val source = SegmentTermSource.open(merged, term(1), PostingSegments.bloomHash(term(1)), 0)!!
        val runs = DocRuns()
        assertTrue(source.takeRuns(runs) > 3, "document 5 is cut into runs of at most 1 KiB")
        assertTrue(merge.postingsRead > merge.postingsWritten, "duplicates across the inputs were dropped")
    }

    @Test
    fun `a stop request ends a merge`() {
        val m = PostingMemtable()
        for (d in 0L until 50L) m.add(PostingRun(term(1), d, 1, 1, encode(listOf(entryOf(1, d, 1, 0)))))
        val input = segment(SegmentFormat.INDEX_POSTING, "posting") { PostingSegments.write(m, it, 256) }
        var calls = 0
        val control = MergeControl { if (++calls > 10) throw IllegalStateException("stop") }
        val (_, file) = newFile("merged")
        file.use { f -> assertFailsWith<IllegalStateException> { PostingMerge(listOf(input.reader), HiddenDocIds.NONE, control, { error("no spill") }).write(f, 256) } }
    }

    // ---- fragments --------------------------------------------------------------------------------------------------

    @Test
    fun `fragment merge keeps each key once and leaves out hidden documents`() {
        val random = Random(3)
        val all = TreeMap<Triple<Long, Long, Int>, FragmentEntry>(compareBy({ it.first }, { it.second }, { it.third }))
        val inputs = List(4) {
            val m = FragmentIndexMemtable()
            repeat(300) {
                val docId = random.nextLong(1, 40)
                val pre = random.nextLong(0, 30)
                val e = FragmentEntry(docId, pre, (pre % 3).toInt(), 0, docId * 1000 + pre, 64, 16, 48 + pre.toInt() % 5)
                if (m.find(docId, pre, e.kind, 0) == null) m.add(e)
                all[Triple(docId, pre, e.kind)] = e
            }
            segment(SegmentFormat.INDEX_FRAGMENT, "frag") { FragmentSegments.write(m, it, 512) }
        }
        val hidden = HiddenDocIds(DocIdSet.of(3, 4), DocIdSet.of(1, 39, 17))
        val (path, file) = newFile("merged")
        val stats = file.use { FragmentSegments.merge(inputs.map { it.reader }, it, 512, hidden, MergeControl.ALWAYS) }
        val merged = SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), cache, SegmentFormat.INDEX_FRAGMENT)
        val expected = all.values.filter { !hidden.contains(it.docId) }
        assertEquals(expected.map { listOf(it.docId, it.pre, it.location) }, FragmentSegments.entries(merged).asSequence().map { listOf(it.docId, it.pre, it.location) }.toList())
        assertEquals(hidden.countInRange(stats.minDocId, stats.maxDocId), stats.hiddenDocIds)
        for (docId in expected.map { it.docId }.distinct()) assertTrue(merged.mayContain(FragmentSegments.bloomHash(docId)))
    }

    // ---- types ------------------------------------------------------------------------------------------------------

    private val typeHistories: Gen<List<LongArray>> = Gen { random, size ->
        List(random.nextInt(1, 8 * size + 2)) {
            // docId, pre, typeId (0 = remove), flush after it (1)
            longArrayOf(random.nextLong(0, 8), random.nextLong(0, 6), random.nextLong(0, 4), if (random.nextInt(5) == 0) 1 else 0)
        }
    }

    @Test
    fun `type merge of any adjacent run keeps every view, drops tombstones nothing older needs, and hidden documents`() {
        forAll("type merge", typeHistories, iterations = 300) { history ->
            val random = Random(history.size * 31L + history.sumOf { it[0] + it[2] })
            val model = HashMap<Pair<Long, Long>, Int>()
            val segments = ArrayList<SegmentHandle>()
            var memtable = TypeMemtable()
            var lsn = 0L
            fun flush() {
                if (memtable.isEmpty) return
                val m = memtable
                segments += segment(SegmentFormat.INDEX_TYPE, "type") { TypeSegments.write(m, it, 256) }
                memtable = TypeMemtable()
            }
            for (step in history) {
                val key = step[0] to step[1]
                val old = model[key] ?: 0
                val new = step[2].toInt()
                if (old != new) {
                    memtable.assign(step[0], step[1], ++lsn, new, old)
                    if (new == 0) model.remove(key) else model[key] = new
                }
                if (step[3] == 1L) flush()
            }
            flush()
            if (segments.isEmpty()) return@forAll
            val from = random.nextInt(segments.size)
            val to = random.nextInt(from + 1, segments.size + 1)
            val hidden = if (random.nextBoolean()) HiddenDocIds.NONE else HiddenDocIds(DocIdSet.of(random.nextLong(0, 8)), DocIdSet.of(random.nextLong(0, 8)))
            val older = segments.subList(0, from)
            val olderMayHold = { e: TypeEntry ->
                older.any { s ->
                    val info = s.types!!
                    e.docId in s.reader.stats.minDocId..s.reader.stats.maxDocId && (e.space == TypeEntry.FORWARD || (info.has(e.typeId) && e.docId in info.minDocId(e.typeId)..info.maxDocId(e.typeId))) &&
                        s.reader.mayContain(TypeSegments.bloomHash(e.docId))
                }
            }
            val (path, file) = newFile("type-merged")
            val stats = file.use { TypeSegments.merge(segments.subList(from, to).map { it.reader }, olderMayHold, hidden, MergeControl.ALWAYS, it, 256) }
            val replaced = segments.subList(0, from) + listOfNotNull(stats?.let { SegmentHandle(SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), cache, SegmentFormat.INDEX_TYPE), path, fs, Path.of("tmp")) }) + segments.subList(to, segments.size)

            val visibility = Visibility(1000, LongArray(0), hidden.aborted, hidden.deleted)
            for (v in listOf<Visibility?>(visibility, if (hidden === HiddenDocIds.NONE) null else visibility)) {
                val before = TypeIndexView(emptyList(), segments, v, Long.MAX_VALUE)
                val after = TypeIndexView(emptyList(), replaced, v, Long.MAX_VALUE)
                for (docId in 0L until 8) for (pre in 0L until 6) {
                    assertEquals(before.typeIdOf(docId, pre), after.typeIdOf(docId, pre), "type of ($docId, $pre) after merging [$from, $to) of ${segments.size}")
                }
                for (typeId in 1..3) {
                    fun objects(view: TypeIndexView) = view.scan(typeId).let { s -> generateSequence { if (s.valid) (s.docId to s.pre).also { s.next() } else null }.toList() }
                    assertEquals(objects(before), objects(after), "objects of $typeId after merging [$from, $to)")
                }
            }
            for ((key, typeId) in model) {
                if (!hidden.contains(key.first)) assertEquals(typeId, TypeIndexView(emptyList(), replaced, null, Long.MAX_VALUE).typeIdOf(key.first, key.second))
            }
            if (stats != null) {
                val merged = replaced[from]
                val info = merged.types!!
                val entries = TypeSegments.entries(merged.reader, info.baseLsn).asSequence().toList()
                assertTrue(entries.none { hidden.contains(it.docId) }, "hidden documents' entries were dropped")
                if (from == 0) {
                    assertTrue(entries.none { it.space == TypeEntry.INVERTED && it.value == TypeEntry.TOMBSTONE }, "no tombstone survives a merge from the oldest segment")
                    assertTrue(entries.none { it.space == TypeEntry.FORWARD && it.value == 0 }, "no 'none' entry survives a merge from the oldest segment")
                }
                for (e in entries.filter { it.space == TypeEntry.FORWARD }) assertTrue(merged.reader.mayContain(TypeSegments.bloomHash(e.docId)))
            }
        }
    }

    // ---- writer -----------------------------------------------------------------------------------------------------

    @Test
    fun `a filter built from written keys equals one built from hashes, and streamed values read back`() {
        val random = Random(9)
        val keys = (0 until 2000).map { "k${random.nextInt(100_000)}".toByteArray() }.distinctBy { String(it) }.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, b) }
        val values = keys.associate { String(it) to ByteArray(random.nextInt(0, 9000)) { i -> (i * 31).toByte() } }
        fun write(streamed: Boolean): SegmentReader {
            val (path, file) = newFile(if (streamed) "streamed" else "hashed")
            file.use { f ->
                val writer = SegmentWriter(f, SegmentFormat.INDEX_POSTING, 1024, bloomKey = if (streamed) BloomKey.WHOLE_KEY else null)
                for (k in keys) {
                    val v = values.getValue(String(k))
                    if (!streamed) {
                        writer.addBloomHash(PostingSegments.bloomHash(k))
                        writer.add(k, 0, k.size, v, 0, v.size)
                    } else if (v.isEmpty()) {
                        writer.add(k, 0, k.size, v, 0, 0)
                    } else {
                        writer.addStreamed(k, 0, k.size, v.size.toLong()) { out -> var p = 0; while (p < v.size) { val n = minOf(777, v.size - p); out.write(v, p, n); p += n } }
                    }
                }
                writer.finish(SegmentStats(keys.size.toLong(), 0, 0, -1))
            }
            return SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), cache, SegmentFormat.INDEX_POSTING)
        }
        val hashed = write(false)
        val streamed = write(true)
        val c = streamed.cursor()
        var ok = c.first()
        var n = 0
        while (ok) {
            assertContentEquals(values.getValue(String(c.key.toByteArray())), GrowableBytes(16).also { c.copyValue(it) }.toByteArray())
            n++
            ok = c.next()
        }
        assertEquals(keys.size, n)
        repeat(5000) {
            val probe = PostingSegments.bloomHash("p$it".toByteArray())
            assertEquals(hashed.mayContain(probe), streamed.mayContain(probe), "the filters differ")
        }
    }

    // ---- policy -----------------------------------------------------------------------------------------------------

    @Test
    fun `size-tiered picks keep segments logarithmic and bound rewrites`() {
        val options = CompactionOptions(minMergeSegments = 4, maxInputBytes = 1L shl 40)
        val sizes = ArrayList<Long>()
        var written = 0L
        var flushed = 0L
        repeat(4000) {
            sizes += 100
            flushed += 100
            while (true) {
                val pick = CompactionPolicy.pick(sizes.map { CompactionPolicy.Candidate(it, true) }, options) ?: break
                val total = sizes.subList(pick.from, pick.to).sum()
                written += total
                repeat(pick.to - pick.from) { sizes.removeAt(pick.from) }
                sizes.add(pick.from, total)
            }
            assertTrue(sizes.size <= 4 * (Math.log(sizes.sum() / 100.0) / Math.log(2.0) + 2), "${sizes.size} segments: $sizes")
        }
        assertTrue(written <= flushed * 14, "write amplification ${written.toDouble() / flushed}")
        assertEquals(flushed, sizes.sum())
    }

    @Test
    fun `picks skip unavailable and oversized segments and prefer size, then deletes, then tombstones`() {
        val options = CompactionOptions(minMergeSegments = 2, maxInputBytes = 1000, deletedRatio = 0.5, tombstoneRatio = 0.5)
        fun c(bytes: Long, available: Boolean = true, hidden: Double = 0.0, tombstones: Double = 0.0) = CompactionPolicy.Candidate(bytes, available, hidden, tombstones)
        assertEquals("Pick(1, 3, SIZE_TIERED)", CompactionPolicy.pick(listOf(c(5000), c(10), c(10)), options).toString())
        assertNull(CompactionPolicy.pick(listOf(c(10), c(10, available = false)), options))
        assertNull(CompactionPolicy.pick(listOf(c(600), c(600)), options), "together above maxInputBytes")
        assertEquals("Pick(0, 1, DELETED)", CompactionPolicy.pick(listOf(c(5000, hidden = 0.6), c(600)), options).toString(), "a large segment is rewritten alone for deletes")
        assertEquals("Pick(0, 2, TOMBSTONES)", CompactionPolicy.pick(listOf(c(900), c(50, tombstones = 0.7), c(5000)), options).toString())
        assertNull(CompactionPolicy.pick(listOf(c(5000), c(50, tombstones = 0.7)), options), "the older segments don't fit")
    }

    @Test
    fun `the rate limiter lets bursts through and then paces writes`() {
        var now = 0L
        val clock = Clock { now }
        val limiter = RateLimiter(1000, clock) { nanos -> now += nanos }
        limiter.acquire(1000, MergeControl.ALWAYS)
        assertEquals(0L, now, "a one-second burst doesn't wait")
        limiter.acquire(500, MergeControl.ALWAYS)
        assertTrue(now in 490_000_000L..510_000_000L, "waited $now ns")
        now += 10_000_000_000L
        limiter.acquire(1000, MergeControl.ALWAYS)
        assertTrue(now < 10_510_000_001L, "idle time refills at most one second")
        assertFailsWith<IllegalStateException> { limiter.acquire(5000, MergeControl { error("stopping") }) }
    }
}
