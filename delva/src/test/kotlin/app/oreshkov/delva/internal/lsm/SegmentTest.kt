package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.term.PostingCodec
import app.oreshkov.delva.internal.term.PostingLocator
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import app.oreshkov.delva.testkit.toHex
import java.nio.file.Path
import java.util.Arrays
import java.util.TreeMap
import java.util.TreeSet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Formats: segment files, posting lists and docId sets. */
class SegmentTest {
    private val fs = SimulatedFileSystemIo()
    private var fileNo = 1L

    private val unsigned = Comparator<ByteArray> { a, b -> Arrays.compareUnsigned(a, b) }

    private fun writeSegment(entries: TreeMap<ByteArray, ByteArray>, blockBytes: Int, bloom: Boolean = true): Path {
        val path = Path.of("seg", "s-${fileNo}")
        fs.createDirectories(path.parent)
        fs.open(path, OpenMode.CREATE_NEW).use { file ->
            val writer = SegmentWriter(file, SegmentFormat.INDEX_POSTING, blockBytes)
            for ((k, v) in entries) {
                if (bloom) writer.addBloomHash(PostingSegments.bloomHash(k))
                writer.add(k, 0, k.size, v, 0, v.size)
            }
            writer.finish(SegmentStats(entries.size.toLong(), 7, 1, 9))
            file.force(true)
        }
        return path
    }

    private fun open(path: Path, cache: ByteCache = ByteCache(1L shl 20)): SegmentReader =
        SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), cache, SegmentFormat.INDEX_POSTING)

    private fun value(cursor: SegmentCursor): ByteArray = GrowableBytes(16).also { cursor.copyValue(it) }.toByteArray()

    private fun key(cursor: SegmentCursor): ByteArray = cursor.key.toByteArray()

    private val segmentCases: Gen<Pair<TreeMap<ByteArray, ByteArray>, Int>> = Gen { random, size ->
        val entries = TreeMap<ByteArray, ByteArray>(unsigned)
        val prefixes = List(4) { ByteArray(random.nextInt(0, 6)) { random.nextInt(256).toByte() } }
        repeat(random.nextInt(0, 4 * size + 2)) {
            val prefix = prefixes[random.nextInt(prefixes.size)]
            val key = prefix + ByteArray(random.nextInt(0, 12)) { random.nextInt(256).toByte() }
            val length = when (random.nextInt(10)) {
                0 -> random.nextInt(300, 3 * SegmentFormat.CHUNK_BYTES + 7)
                else -> random.nextInt(0, 40)
            }
            entries[key] = ByteArray(length) { random.nextInt(256).toByte() }
        }
        entries to listOf(256, 1024, 64 * 1024)[random.nextInt(3)]
    }

    @Test
    fun `segments iterate and seek like a sorted map`() {
        forAll("segment round trip", segmentCases, iterations = 150, maxSize = 200) { (entries, blockBytes) ->
            val reader = open(writeSegment(entries, blockBytes))
            reader.use {
                assertEquals(entries.size.toLong(), reader.stats.entryCount)
                val cursor = reader.cursor()
                var ok = cursor.first()
                for ((k, v) in entries) {
                    assertTrue(ok, "entry missing")
                    assertContentEquals(k, key(cursor))
                    assertContentEquals(v, value(cursor))
                    if (v.size > 0) {
                        val input = ValueInput()
                        cursor.openValue(input)
                        input.position = (v.size - 1).toLong()
                        assertEquals(v.last().toInt() and 0xFF, input.readByte())
                    }
                    assertTrue(reader.mayContain(PostingSegments.bloomHash(k)), "bloom filter false negative")
                    ok = cursor.next()
                }
                assertFalse(ok)

                val random = Random(entries.size + blockBytes)
                repeat(60) {
                    val target = if (entries.isNotEmpty() && random.nextBoolean()) {
                        val k = entries.keys.elementAt(random.nextInt(entries.size))
                        if (random.nextBoolean()) k else k + byteArrayOf(random.nextInt(256).toByte())
                    } else {
                        ByteArray(random.nextInt(0, 10)) { random.nextInt(256).toByte() }
                    }
                    val ceiling = entries.ceilingEntry(target)
                    val c = reader.cursor()
                    assertEquals(ceiling != null, c.seekCeiling(target))
                    if (ceiling != null) {
                        assertContentEquals(ceiling.key, key(c))
                        assertContentEquals(ceiling.value, value(c))
                        val higher = entries.higherKey(ceiling.key)
                        assertEquals(higher != null, c.next())
                        if (higher != null) assertContentEquals(higher, key(c))
                    }
                    val floor = entries.floorEntry(target)
                    val f = reader.cursor()
                    assertEquals(floor != null, f.seekFloor(target))
                    if (floor != null) {
                        assertContentEquals(floor.key, key(f))
                        assertContentEquals(floor.value, value(f))
                        val higher = entries.higherKey(floor.key)
                        assertEquals(higher != null, f.next())
                        if (higher != null) assertContentEquals(higher, key(f))
                    }
                }
            }
        }
    }

    @Test
    fun `value slices read the same bytes after releasing their blocks and chunks`() {
        forAll("value slices", segmentCases, iterations = 80, maxSize = 200) { (entries, blockBytes) ->
            val path = writeSegment(entries, blockBytes)
            for (cache in listOf(ByteCache(0), ByteCache(1L shl 20))) {
                open(path, cache).use { reader ->
                    val random = Random(entries.size.toLong() + blockBytes)
                    val cursor = reader.cursor()
                    var ok = cursor.first()
                    val value = ValueInput()
                    val slice = ValueInput()
                    for ((_, v) in entries) {
                        check(ok)
                        cursor.openValue(value)
                        val start = random.nextInt(v.size + 1)
                        val length = random.nextInt(v.size - start + 1)
                        value.position = random.nextLong(v.size + 1L)
                        if (random.nextBoolean()) value.release()
                        slice.openSlice(value, start.toLong(), length.toLong())
                        val read = ByteArray(length)
                        var i = 0
                        while (i < length) {
                            if (random.nextInt(4) == 0) slice.release()
                            val n = minOf(length - i, random.nextInt(1, 9))
                            slice.readBytes(read, i, n)
                            i += n
                        }
                        assertContentEquals(v.copyOfRange(start, start + length), read)
                        assertFailsWith<CorruptionException> { slice.readByte() }
                        ok = cursor.next()
                    }
                }
            }
        }
    }

    @Test
    fun `large bloom filters are read in checked pages, not kept on the heap`() {
        val entries = TreeMap<ByteArray, ByteArray>(unsigned)
        repeat(20_000) { i -> entries["term-%06d".format(i).toByteArray()] = byteArrayOf(i.toByte()) }
        val path = writeSegment(entries, 4096)
        val bytes = fs.bytes(path)
        val footer = bytes.size - SegmentFormat.FOOTER_BYTES
        val bloomStart = LittleEndian.getLong(bytes, footer + 8) + LittleEndian.getInt(bytes, footer + 16)
        val bloomBlocks = LittleEndian.getInt(bytes, bloomStart.toInt())
        assertTrue(bloomBlocks > 64, "$bloomBlocks bloom blocks")

        val reader = open(path, ByteCache(0))
        reader.use {
            assertTrue(reader.heapBytes < 32L shl 10, "${reader.heapBytes} heap bytes for ${bloomBlocks * 64} bloom bytes")
            for (k in entries.keys) assertTrue(reader.mayContain(PostingSegments.bloomHash(k)), "bloom filter false negative")
            val absent = (0 until 10_000).count { reader.mayContain(PostingSegments.bloomHash("absent-$it".toByteArray())) }
            assertTrue(absent < 300, "$absent false positives in 10 000")

            // Damage in the first page after the segment was opened fails the lookups that read it.
            fs.flipBit(path, bloomStart + 4 + 3, 2)
            val inFirstPage = entries.keys.first { BloomFilter.block(bloomBlocks, PostingSegments.bloomHash(it)) < 64 }
            assertFailsWith<CorruptionException> { reader.mayContain(PostingSegments.bloomHash(inFirstPage)) }
            val inLastPage = entries.keys.first { BloomFilter.block(bloomBlocks, PostingSegments.bloomHash(it)) >= 64 * ((bloomBlocks - 1) / 64) }
            assertTrue(reader.mayContain(PostingSegments.bloomHash(inLastPage)))
        }
    }

    @Test
    fun `fragment segments merge with a memtable into one segment without repeated keys`() {
        val random = Random(17)
        val readers = ArrayList<SegmentReader>()
        fun entry(docId: Long, pre: Long) = FragmentEntry(docId, pre, (pre % 3).toInt(), (pre % 2).toInt(), docId * 1000 + pre, 100, 20, 300 + pre.toInt())
        repeat(4) { s ->
            val memtable = FragmentIndexMemtable()
            repeat(random.nextInt(50, 300)) {
                val e = entry(random.nextLong(1, 6), random.nextLong(0, 400))
                if (memtable.find(e.docId, e.pre, e.kind, e.seq) == null) memtable.add(e)
            }
            val path = Path.of("seg", "frag-$s")
            fs.createDirectories(path.parent)
            fs.open(path, OpenMode.CREATE_NEW).use { FragmentSegments.write(memtable, it, 512) }
            readers += SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), ByteCache(1L shl 20), SegmentFormat.INDEX_FRAGMENT)
        }
        val memtable = FragmentIndexMemtable()
        repeat(200) {
            val e = entry(random.nextLong(1, 6), random.nextLong(0, 400))
            if (memtable.find(e.docId, e.pre, e.kind, e.seq) == null) memtable.add(e)
        }
        val merged = Path.of("seg", "frag-merged")
        val stats = fs.open(merged, OpenMode.CREATE_NEW).use { FragmentSegments.write(memtable, readers.subList(1, readers.size), it, 512) }
        val mergedReader = SegmentReader.open(fileNo++, fs.open(merged, OpenMode.READ), ByteCache(1L shl 20), SegmentFormat.INDEX_FRAGMENT)
        val union = TreeSet<FragmentEntry>().apply {
            addAll(memtable.entries())
            for (r in readers.subList(1, readers.size)) FragmentSegments.entries(r).forEach { add(it) }
        }
        val read = FragmentSegments.entries(mergedReader).asSequence().toList()
        assertEquals(union.size.toLong(), stats.entryCount)
        assertEquals(union.map { listOf(it.docId, it.pre, it.kind.toLong(), it.seq.toLong(), it.location) }, read.map { listOf(it.docId, it.pre, it.kind.toLong(), it.seq.toLong(), it.location) })
        for (r in readers + mergedReader) r.close()
    }

    @Test
    fun `any damaged byte of a segment is a corruption error`() {
        val random = Random(77)
        val entries = TreeMap<ByteArray, ByteArray>(unsigned)
        repeat(300) { i -> entries["key-%05d".format(i * 7).toByteArray()] = ByteArray(if (i % 50 == 0) 9000 else i % 30) { (it * i).toByte() } }
        val path = writeSegment(entries, 512)
        val size = fs.bytes(path).size
        var failures = 0
        repeat(400) {
            val copy = Path.of("seg", "damaged-${it}")
            val bytes = fs.bytes(path)
            val at = random.nextInt(size)
            bytes[at] = (bytes[at].toInt() xor (1 shl random.nextInt(8))).toByte()
            fs.open(copy, OpenMode.CREATE_NEW).use { f -> f.write(0, bytes, 0, bytes.size) }
            try {
                open(copy, ByteCache(0)).use { reader ->
                    val cursor = reader.cursor()
                    var ok = cursor.first()
                    while (ok) {
                        value(cursor)
                        ok = cursor.next()
                    }
                }
                fail("damage at byte $at of $size went unnoticed")
            } catch (_: CorruptionException) {
                failures++
            }
        }
        assertEquals(400, failures)
        assertFailsWith<CorruptionException> { SegmentReader.open(99, fs.open(path, OpenMode.READ), ByteCache(0), SegmentFormat.INDEX_FRAGMENT) }
    }

    private class Group(val docId: Long, val occurrences: Int, val entries: ByteArray)

    private val postingCases: Gen<List<Group>> = Gen { random, size ->
        var docId = random.nextLong(0, 10)
        List(random.nextInt(1, 6 * size + 2)) {
            if (random.nextInt(4) != 0) docId += random.nextLong(1, 1000)
            val occurrences = random.nextInt(1, 5)
            Group(docId, occurrences, ByteArray(3 * occurrences + random.nextInt(0, 10)) { random.nextInt(256).toByte() })
        }
    }

    @Test
    fun `posting lists round trip and seek by docId`() {
        forAll("posting list", postingCases, iterations = 200, maxSize = 200) { groups ->
            val writer = PostingListWriter()
            for (g in groups) writer.addRun(g.docId, g.occurrences, g.entries, 0, g.entries.size)
            val bytes = GrowableBytes(64).also { writer.finish(it) }.toByteArray()
            assertEquals(groups.map { it.docId }.distinct().size.toLong(), writer.docFreq)

            val input = ValueInput()
            input.openInline(bytes, 0, bytes.size, "test")
            val cursor = PostingListCursor()
            cursor.open(input)
            for ((i, g) in groups.withIndex()) {
                assertEquals(g.docId, cursor.docId, "group $i")
                assertEquals(g.occurrences, cursor.occurrences)
                if (i % 2 == 0) {
                    assertContentEquals(g.entries, cursor.readEntries())
                } else {
                    // A lazily read slice of the group, as merges read runs from segments.
                    val slice = ValueInput()
                    cursor.openEntries(slice)
                    val read = ByteArray(slice.length.toInt()) { slice.readByte().toByte().also { slice.release() } }
                    assertContentEquals(g.entries, read)
                }
                cursor.next()
            }
            assertEquals(Long.MAX_VALUE, cursor.docId)

            val random = Random(groups.size.toLong())
            repeat(20) {
                val from = random.nextLong(0, groups.last().docId + 2)
                input.openInline(bytes, 0, bytes.size, "test")
                cursor.open(input)
                cursor.seek(from)
                val expected = groups.firstOrNull { it.docId >= from }
                assertEquals(expected?.docId ?: Long.MAX_VALUE, cursor.docId, "seek $from")
                if (expected != null) assertContentEquals(expected.entries, cursor.readEntries())
            }
        }
    }

    @Test
    fun `damaged posting lists fail with corruption errors`() {
        val random = Random(5)
        val groups = postingCases.generate(random, 300)
        val writer = PostingListWriter()
        for (g in groups) writer.addRun(g.docId, g.occurrences, g.entries, 0, g.entries.size)
        val original = GrowableBytes(64).also { writer.finish(it) }.toByteArray()
        repeat(2000) {
            val bytes = original.copyOf()
            val at = random.nextInt(bytes.size)
            bytes[at] = random.nextInt(256).toByte()
            val input = ValueInput()
            input.openInline(bytes, 0, bytes.size, "test")
            val cursor = PostingListCursor()
            try {
                cursor.open(input)
                var steps = 0
                while (cursor.docId != Long.MAX_VALUE) {
                    cursor.readEntries()
                    cursor.next()
                    check(++steps <= bytes.size) { "no progress" }
                }
            } catch (_: CorruptionException) {
                // expected for most damage
            }
        }
    }

    @Test
    fun `docId sets behave like a sorted set, share unchanged pages and round trip`() {
        forAll("docId set", Gen { random, size -> List(random.nextInt(1, 6)) { List(random.nextInt(0, 20 * size + 1)) { docId(random) } } }, iterations = 100) { batches ->
            val model = TreeSet<Long>()
            var set = DocIdSet.EMPTY
            val history = ArrayList<Pair<DocIdSet, Set<Long>>>()
            for (batch in batches) {
                val builder = set.builder()
                for (d in batch) assertEquals(model.add(d), builder.add(d))
                set = builder.build()
                history += set to TreeSet(model)
            }
            for ((s, m) in history) {
                assertEquals(m.size.toLong(), s.size)
                assertContentEquals(m.toLongArray(), s.toLongArray(), "an older set changed")
            }
            val random = Random(model.size.toLong())
            repeat(50) {
                val probe = if (model.isNotEmpty() && random.nextBoolean()) model.elementAt(random.nextInt(model.size)) + random.nextLong(-1, 2) else docId(random)
                if (probe < 0) return@repeat
                assertEquals(probe in model, set.contains(probe))
                assertEquals(model.ceiling(probe) ?: -1L, set.nextMember(probe))
                val to = probe + listOf(0L, 63, 64, 65_536, 1L shl 30)[random.nextInt(5)]
                assertEquals(model.subSet(probe, true, to, true).size.toLong(), set.countInRange(probe, to), "count in [$probe, $to]")
            }
            val decoded = DocIdSet.decode(set.encode(), "test", DocIdSet.MAX_DOC_ID + 1)
            assertContentEquals(set.toLongArray(), decoded.toLongArray())
        }

        val full = DocIdSet.EMPTY.builder().apply { for (d in 65_536L until 3 * 65_536L) add(d) }.build()
        assertTrue(full.encode().size < 100, "full pages are stored without their words")
        assertEquals(2 * 65_536L, DocIdSet.decode(full.encode(), "test", DocIdSet.MAX_DOC_ID + 1).size)
        val damaged = full.encode().also { it[20] = (it[20] + 1).toByte() }
        assertFailsWith<CorruptionException> { DocIdSet.decode(damaged, "test", DocIdSet.MAX_DOC_ID + 1) }
    }

    @Test
    fun `a docId set holds no member at or past the store's next docId, so a crafted file can't make it allocate tables`() {
        // Each kind of page, with its greatest member right below the bound.
        val sparse = DocIdSet.of(5, 70_000)
        val bits = DocIdSet.EMPTY.builder().apply { for (i in 0 until DocIdSet.SPARSE_MAX + 1) add(65_536L + 3 * i) }.build()
        val full = DocIdSet.EMPTY.builder().apply { for (d in 0 until 65_536L) add(d) }.build()
        for (set in listOf(sparse, bits, full)) {
            val end = set.toLongArray().last() + 1
            assertEquals(set.size, DocIdSet.decode(set.encode(), "test", end).size)
            assertFailsWith<CorruptionException> { DocIdSet.decode(set.encode(), "test", end - 1) }
        }

        // One full page in each of 100 000 tables: 900 KB that would ask for 800 MB of tables, with a valid checksum.
        val pages = 100_000
        val forged = GrowableBytes(24 + 9 * pages + 4)
        val header = DocIdSet.of(1).encode()
        forged.writeBytes(header, 0, 8)
        forged.writeLongLE(65_536L * pages)
        forged.writeLongLE(pages.toLong())
        for (p in 0 until pages) {
            forged.writeLongLE(p.toLong() shl 10)
            forged.writeByte(2)
        }
        forged.writeIntLE(Crc32c.of(forged.array, 0, forged.size))
        val bytes = forged.toByteArray()
        val e = assertFailsWith<CorruptionException> { DocIdSet.decode(bytes, "test", 1L shl 30) }
        assertTrue(e.message!!.contains("past docId"), e.message)
    }

    private fun docId(random: Random): Long = when (random.nextInt(5)) {
        0 -> random.nextLong(0, 100)
        1 -> random.nextLong(0, 1L shl 20)
        2 -> random.nextLong(0, 1L shl 36)
        3 -> random.nextLong(0, 9000)
        else -> 65_536L * random.nextInt(0, 4) + random.nextLong(-2, 3).coerceAtLeast(0)
    }

    @Test
    fun `docId set pages are sparse up to 4 096 members, then bits, then shared when full, and only in that form`() {
        fun pageSet(count: Int, step: Long = 1) = DocIdSet.EMPTY.builder().apply { for (i in 0 until count) add(65_536 + i * step) }.build()
        // Header 24 bytes, page number and kind 9, checksum 4.
        val sparse = pageSet(DocIdSet.SPARSE_MAX, 16)
        assertEquals(24 + 9 + 2 + 2 * DocIdSet.SPARSE_MAX + 4, sparse.encode().size, "a sparse page takes 2 bytes per member")
        val bits = pageSet(DocIdSet.SPARSE_MAX + 1, 15)
        assertEquals(24 + 9 + 8192 + 4, bits.encode().size)
        assertEquals(DocIdSet.SPARSE_MAX + 1L, DocIdSet.decode(bits.encode(), "test", DocIdSet.MAX_DOC_ID + 1).size)
        assertTrue(bits.contains(65_536 + 15L * DocIdSet.SPARSE_MAX) && !bits.contains(65_537))
        // Grown one docId at a time across batches, older sets stay unchanged.
        var set = DocIdSet.EMPTY
        val sets = ArrayList<DocIdSet>()
        for (i in 0 until 65_536L) {
            if (i % 997 == 0L) sets += set
            set = set.builder().apply { add(65_535 - i) }.build()
        }
        assertEquals(24 + 9 + 4, set.encode().size, "a full page is stored without its words")
        for ((k, s) in sets.withIndex()) {
            assertEquals(k * 997L, s.size)
            assertEquals(k * 997L, s.countInRange(0, 1L shl 40))
        }
        // Non-canonical pages are rejected: a bits page that fits a sparse page.
        val forged = ByteArray(24 + 9 + 8192 + 4)
        System.arraycopy(DocIdSet.of(3).encode(), 0, forged, 0, 24)
        forged[32] = 1
        forged[33] = 8
        app.oreshkov.delva.internal.bytes.LittleEndian.putInt(forged, forged.size - 4, app.oreshkov.delva.internal.bytes.Crc32c.of(forged, 0, forged.size - 4))
        assertFailsWith<CorruptionException> { DocIdSet.decode(forged, "test", DocIdSet.MAX_DOC_ID + 1) }
    }

    @Test
    fun `segment, posting list and docId set layouts match the golden file`() {
        val lines = ArrayList<String>()
        val entries = TreeMap<ByteArray, ByteArray>(unsigned)
        for (k in listOf("apple", "apricot", "banana")) entries[k.toByteArray()] = k.reversed().toByteArray()
        entries["long".toByteArray()] = ByteArray(300) { it.toByte() }
        lines += "segment ${fs.bytes(writeSegment(entries, 256)).toHex()}"
        val writer = PostingListWriter()
        writer.addRun(3, 1, byteArrayOf(1, 2, 3), 0, 3)
        writer.addRun(3, 1, byteArrayOf(4, 5, 6), 0, 3)
        writer.addRun(10, 2, byteArrayOf(0, 2, 7, 1, 2, 7), 0, 6)
        lines += "posting-list ${GrowableBytes(32).also { writer.finish(it) }.toByteArray().toHex()}"
        // Posting entries: SUBTREE, HEADER and UNRESOLVED locators; the third repeats the second's path hash, which isn't written.
        val postings = GrowableBytes(32)
        PostingCodec.writeEntry(postings, 5, 5, 6, false, PostingLocator.SUBTREE, 2, 17, 0xBEEF, -1)
        PostingCodec.writeEntry(postings, 4, 9, 11, false, PostingLocator.HEADER, 9, 0, 0x0102, 0xBEEF)
        PostingCodec.writeEntry(postings, 3, 12, 14, true, PostingLocator.UNRESOLVED, 0, 0, 0x0102, 0x0102)
        lines += "posting-entries ${postings.toByteArray().toHex()}"
        // Sparse pages; then a bits page, 8 KiB, of which the line keeps the start, the end and a checksum of the body.
        val set = DocIdSet.of(1, 2, 63, 64, 65_535, 1L shl 26).encode()
        lines += "docid-set ${set.toHex()}"
        val bits = DocIdSet.EMPTY.builder().apply { for (d in 0L..DocIdSet.SPARSE_MAX) add(3 * d) }.build().encode()
        lines += "docid-set-bits ${bits.size} ${bits.toHex(0, 48)} ${bits.toHex(bits.size - 24, 24)} ${"%08x".format(app.oreshkov.delva.internal.bytes.Crc32c.of(bits, 0, bits.size - 4))}"
        val full = DocIdSet.EMPTY.builder().apply { for (d in 0L until 65_536L) add(d) }.build().encode()
        lines += "docid-set-full ${full.toHex()}"
        Golden.verify("index-formats.txt", "Segment (SegmentFormat FORMAT_VERSION 2), posting list, posting entries and docId set (DocIdSet FORMAT_VERSION 1) layouts: <case> <hex>", lines)
    }
}
