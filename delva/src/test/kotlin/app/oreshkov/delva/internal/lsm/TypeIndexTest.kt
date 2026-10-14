package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.visibility.Visibility
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import app.oreshkov.delva.testkit.toHex
import java.nio.file.Path
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Type index formats and lookups: the multi-version type memtable, type segments and the dictionary. */
class TypeIndexTest {
    private val fs = SimulatedFileSystemIo()
    private var fileNo = 1L

    private fun flush(memtable: TypeMemtable, blockBytes: Int): SegmentHandle {
        val path = Path.of("seg", "type-${fileNo}")
        fs.createDirectories(path.parent)
        fs.open(path, OpenMode.CREATE_NEW).use { file ->
            TypeSegments.write(memtable, file, blockBytes)
            file.force(true)
        }
        return SegmentHandle(SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), ByteCache(1L shl 20), SegmentFormat.INDEX_TYPE), path, fs, Path.of("tmp"))
    }

    @Test
    fun `the memtable keeps every version, and a snapshot sees the newest at or before its LSN`() {
        val m = Memtable()
        m.types.assign(1, 5, lsn = 10, newTypeId = 1, oldTypeId = 0)
        m.types.assign(1, 5, lsn = 20, newTypeId = 2, oldTypeId = 1)
        m.types.assign(1, 5, lsn = 30, newTypeId = 0, oldTypeId = 2)
        m.types.assign(1, 9, lsn = 25, newTypeId = 2, oldTypeId = 0)
        assertEquals(-1, m.types.forward(1, 5, 9))
        assertEquals(1, m.types.forward(1, 5, 19))
        assertEquals(2, m.types.forward(1, 5, 29))
        assertEquals(0, m.types.forward(1, 5, Long.MAX_VALUE))
        assertEquals(9, m.types.size, "forward entries, PUTs and TOMBSTONEs of every version")
        assertEquals(1, m.types.netCount(2), "one PUT and one TOMBSTONE of 2, plus a PUT at pre 9")
        fun objects(typeId: Int, lsn: Long) = TypeIndexView(listOf(m), emptyList(), null, lsn).scan(typeId).let { s -> generateSequence { if (s.valid) (s.docId to s.pre).also { s.next() } else null }.toList() }
        assertEquals(listOf(1L to 5L), objects(1, 15))
        assertEquals(emptyList(), objects(1, 25))
        assertEquals(listOf(1L to 5L, 1L to 9L), objects(2, 27))
        assertEquals(listOf(1L to 9L), objects(2, 30))
        assertEquals(listOf(1L to 5L, 1L to 9L), TypeIndexView(listOf(m), emptyList(), null, 26).forwardCursor().let { c -> c.open(1); listOf(5L, 9L).filter { c.typeAt(it) == 2 }.map { 1L to it } })
        assertEquals(listOf(Triple(1L, 5L, 0), Triple(1L, 9L, 2)), m.types.newest().asSequence().filter { it.space == TypeEntry.FORWARD }.map { Triple(it.docId, it.pre, it.value) }.toList())
    }

    /** A random history of assignments with flushes; every view must agree with a map of the newest types. */
    private val histories: Gen<List<LongArray>> = Gen { random, size ->
        List(random.nextInt(1, 6 * size + 2)) {
            // docId, pre, typeId (0 = remove), flush after it (1)
            longArrayOf(random.nextLong(0, 6), random.nextLong(0, 8), random.nextLong(0, 4), if (random.nextInt(8) == 0) 1 else 0)
        }
    }

    @Test
    fun `memtables and segments together give the newest types`() {
        forAll("type index vs model", histories, iterations = 300) { history ->
            val model = TreeMap<Pair<Long, Long>, Int>(compareBy({ it.first }, { it.second }))
            val segments = ArrayList<SegmentHandle>()
            var active = Memtable()
            val frozen = ArrayList<Memtable>()
            var lsn = 0L
            val blockBytes = if (history.size % 2 == 0) 256 else 4096
            for ((i, step) in history.withIndex()) {
                val key = step[0] to step[1]
                val old = model[key] ?: 0
                val new = step[2].toInt()
                val view = TypeIndexView(listOf(active) + frozen.asReversed(), segments, null, Long.MAX_VALUE)
                assertEquals(old, view.typeIdOf(step[0], step[1]), "old type before step $i")
                if (new != old) {
                    active.types.assign(step[0], step[1], ++lsn, new, old)
                    if (new == 0) model.remove(key) else model[key] = new
                }
                if (step[3] == 1L && !active.types.isEmpty) {
                    // Freeze, then flush the oldest frozen memtable now and then, as the engine does.
                    frozen += active
                    active = Memtable()
                    if (frozen.size > 1 || i % 3 == 0) segments += flush(frozen.removeAt(0).types, blockBytes)
                }
            }
            val view = TypeIndexView(listOf(active) + frozen.asReversed(), segments, null, Long.MAX_VALUE)
            val cursor = view.forwardCursor()
            for (docId in 0L until 6L) {
                cursor.open(docId)
                for (pre in 0L until 8L) {
                    val expected = model[docId to pre] ?: 0
                    assertEquals(expected, view.typeIdOf(docId, pre), "typeOf ($docId, $pre)")
                    assertEquals(expected, cursor.typeAt(pre), "cursor ($docId, $pre)")
                }
            }
            for (typeId in 1..3) {
                val expected = model.filterValues { it == typeId }.keys.toList()
                val scan = view.scan(typeId)
                val actual = generateSequence { if (scan.valid) (scan.docId to scan.pre).also { scan.next() } else null }.toList()
                assertEquals(expected, actual, "objects of $typeId")
                if (expected.size > 2) {
                    val after = expected[1]
                    val resumed = view.scan(typeId, after.first, after.second)
                    assertEquals(expected[2], resumed.docId to resumed.pre, "after $after")
                    val seek = view.scan(typeId)
                    seek.seek(expected.last().first, expected.last().second)
                    assertEquals(expected.last(), seek.docId to seek.pre)
                }
                // Hidden documents, aborted or deleted, are skipped.
                val hidden = Visibility(Long.MAX_VALUE, LongArray(0), DocIdSet.of(0, 4), DocIdSet.of(2))
                val visible = TypeIndexView(listOf(active) + frozen.asReversed(), segments, hidden, Long.MAX_VALUE).scan(typeId)
                assertEquals(expected.filter { it.first % 2 == 1L }, generateSequence { if (visible.valid) (visible.docId to visible.pre).also { visible.next() } else null }.toList())
            }
            for (s in segments) s.close()
        }
    }

    @Test
    fun `segment statistics are read at open and damage is detected`() {
        val m = TypeMemtable()
        for (d in 0L until 50L) m.assign(d, 1, 100 + d, (d % 3 + 1).toInt(), 0)
        m.assign(7, 1, 500, 0, 2)
        val handle = flush(m, 256)
        val info = handle.types!!
        assertEquals(100L, info.baseLsn)
        assertEquals(500L, info.maxLsn)
        assertEquals(3, info.typeCount)
        assertEquals(listOf(17L, 16L, 16L), (1..3).map { info.puts(it) }, "the TOMBSTONE of 2 replaced its PUT")
        assertEquals(listOf(0L, 1L, 0L), (1..3).map { info.tombstones(it) })
        assertFalse(info.has(4))
        assertEquals(48L, info.maxDocId(1))
        assertEquals(50L, handle.reader.stats.itemCount, "one forward entry per object, the newest version only")
        assertTrue(handle.reader.mayContain(TypeSegments.bloomHash(7)))
        handle.close()

        // A flipped bit anywhere fails the open or a read.
        val bytes = fs.bytes(handle.path)
        var detected = 0
        for (i in 0 until 200) {
            val at = (i * 7919L) % bytes.size
            fs.flipBit(handle.path, at, i % 8)
            try {
                val reader = SegmentReader.open(fileNo++, fs.open(handle.path, OpenMode.READ), ByteCache(0), SegmentFormat.INDEX_TYPE)
                val damaged = SegmentHandle(reader, handle.path, fs, Path.of("tmp"))
                val view = TypeIndexView(emptyList(), listOf(damaged), null, Long.MAX_VALUE)
                for (d in 0L until 50L) view.typeIdOf(d, 1)
                for (t in 1..3) view.scan(t).let { s -> while (s.valid) s.next() }
                reader.close()
            } catch (_: CorruptionException) {
                detected++
            }
            fs.flipBit(handle.path, at, i % 8)
        }
        assertEquals(200, detected)
    }

    @Test
    fun `the dictionary file round-trips and rejects damage`() {
        val names = listOf("Order", "Größe·Δ", "@type", "t".repeat(256))
        val bytes = TypeDictionary.encode(names)
        val decoded = TypeDictionary.decode(bytes, "test")
        assertEquals(names, decoded.names())
        assertEquals(2, decoded.idOf("Größe·Δ"))
        assertEquals("@type", decoded.nameOf(3))
        for (i in bytes.indices) {
            val damaged = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x10).toByte() }
            assertFailsWith<CorruptionException>("byte $i") { TypeDictionary.decode(damaged, "test") }
        }
        assertFailsWith<CorruptionException> { TypeDictionary.decode(TypeDictionary.encode(listOf("a", "a")), "test") }
        assertFailsWith<CorruptionException> { TypeDictionary.decode(TypeDictionary.encode(listOf("a\u0000")), "test") }
        val live = TypeDictionary()
        assertEquals(1, live.add("A"))
        assertFailsWith<CorruptionException> { live.addStored(3, "B", "test") }
        assertFailsWith<IllegalStateException> { live.add("A") }
    }

    @Test
    fun `type segment and dictionary layouts match the golden file`() {
        val m = TypeMemtable()
        m.assign(3, 0, 40, 1, 0)
        m.assign(3, 4, 41, 2, 0)
        m.assign(3, 4, 42, 1, 2)
        m.assign(300, 17, 43, 0, 1)
        val handle = flush(m, 256)
        handle.close()
        val lines = listOf(
            "type-segment ${fs.bytes(handle.path).toHex()}",
            "type-dictionary ${TypeDictionary.encode(listOf("Order", "Größe")).toHex()}",
        )
        Golden.verify("type-formats.txt", "Type segment (SegmentFormat FORMAT_VERSION 2, index kind 3) and type dictionary (TypeDictionary FORMAT_VERSION 1) layouts: <case> <hex>", lines)
    }
}
