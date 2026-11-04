package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.vector.Vectors
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.toHex
import java.nio.file.Path
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vector segments: the layout, flush and merge without hidden docIds, lookups, and damage their checksums can't see. */
class VectorSegmentsTest {
    private val fs = SimulatedFileSystemIo()
    private var fileNo = 1L
    private val noStop = MergeControl {}

    private fun memtable(vararg vectors: Pair<Long, FloatArray>): VectorMemtable =
        VectorMemtable().apply { for ((docId, values) in vectors) add(docId, values.copyOf()) }

    private fun newPath(): Path = Path.of("seg", "vector-${fileNo++}").also { fs.createDirectories(it.parent) }

    private fun open(path: Path): SegmentReader = SegmentReader.open(fileNo++, fs.open(path, OpenMode.READ), ByteCache(1L shl 20), SegmentFormat.INDEX_VECTOR)

    private fun write(memtable: VectorMemtable, dimensions: Int, hidden: HiddenDocIds = HiddenDocIds.NONE, blockBytes: Int = 256): SegmentReader {
        val path = newPath()
        fs.open(path, OpenMode.CREATE_NEW).use { VectorSegments.write(memtable, dimensions, it, blockBytes, hidden) }
        return open(path)
    }

    /** Every entry of [segment] as `docId → (values, sum of squares)`, read through [VectorSegments.read]. */
    private fun entries(segment: SegmentReader, dimensions: Int): List<Triple<Long, List<Float>, Double>> {
        val out = ArrayList<Triple<Long, List<Float>, Double>>()
        val cursor = segment.cursor()
        val values = FloatArray(dimensions)
        var ok = cursor.first()
        while (ok) {
            val docId = VectorSegments.docIdOf(cursor, segment.source)
            val squares = VectorSegments.read(cursor, values, segment.source)
            out += Triple(docId, values.toList(), squares)
            ok = cursor.next()
        }
        return out
    }

    private fun hidden(vararg docIds: Long) = HiddenDocIds(DocIdSet.EMPTY, DocIdSet.EMPTY.builder().apply { for (d in docIds) add(d) }.build())

    @Test
    fun `the vector segment layout matches the golden file`() {
        val m = memtable(
            1L to floatArrayOf(1f, -0f, Float.MAX_VALUE),
            2L to floatArrayOf(Float.MIN_VALUE, -2.5f, 3f),
            300L to floatArrayOf(0.1f, 0.2f, 0.3f),
        )
        // Document 2 is hidden: left out, and counted in the stats.
        val path = newPath()
        val stats = fs.open(path, OpenMode.CREATE_NEW).use { VectorSegments.write(m, 3, it, 256, hidden(2)) }
        assertEquals(listOf(2L, 2L, 1L, 300L, 1L), listOf(stats.entryCount, stats.itemCount, stats.minDocId, stats.maxDocId, stats.hiddenDocIds))
        Golden.verify("vector-formats.txt", "Vector segment (SegmentFormat FORMAT_VERSION 2, index kind 5): <case> <hex>", listOf("vector-segment ${fs.bytes(path).toHex()}"))
    }

    @Test
    fun `segments keep every vector bit for bit, without hidden docIds, and merges keep each docId once`() {
        val random = Random(29)
        for (dimensions in listOf(1, 7, VectorField.MAX_DIMENSIONS)) {
            val field = VectorField("$.v", dimensions, VectorMetric.DOT_PRODUCT)
            // Vectors never change, so a docId in several segments has one vector.
            val vectors = HashMap<Long, FloatArray>()
            fun vector(docId: Long) = vectors.getOrPut(docId) { FloatArray(dimensions) { Float.fromBits(random.nextInt()).takeIf { f -> f.isFinite() } ?: -0f } }
            val inputs = List(3) {
                val m = VectorMemtable()
                repeat(random.nextInt(1, if (dimensions > 100) 6 else 60)) {
                    val docId = random.nextLong(0, 80)
                    if (m.vectorOf(docId) == null) m.add(docId, vector(docId).copyOf())
                }
                write(m, dimensions, blockBytes = listOf(256, 4096)[random.nextInt(2)])
            }
            for (input in inputs) {
                for ((docId, values, squares) in entries(input, dimensions)) {
                    assertEquals(vector(docId).toList(), values, "document $docId")
                    assertEquals(Vectors.squares(vector(docId)).toRawBits(), squares.toRawBits(), "sum of squares of document $docId")
                }
            }
            val gone = vectors.keys.filter { random.nextInt(4) == 0 }.sorted().toLongArray()
            val path = newPath()
            val stats = fs.open(path, OpenMode.CREATE_NEW).use { VectorSegments.merge(inputs, field, hidden(*gone), noStop, it, 512) }
            val expected = TreeMap<Long, List<Float>>()
            for (input in inputs) for ((docId, values, _) in entries(input, dimensions)) if (docId !in gone) expected[docId] = values
            val merged = open(path)
            assertEquals(expected.toList(), entries(merged, dimensions).map { it.first to it.second }, "d=$dimensions")
            assertEquals(expected.size.toLong(), stats?.entryCount)

            // Lookups: the memtable first, then segments newest first; docIds outside every range find nothing.
            val handles = inputs.map { SegmentHandle(it, Path.of("unused"), fs, null) }
            val newer = Memtable().also { it.vectors.add(1000, FloatArray(dimensions) { 1f }) }
            val view = VectorIndexView(listOf(newer), handles, dimensions)
            for (docId in vectors.keys) assertEquals(vector(docId).toList(), view.vectorOf(docId)?.values?.toList(), "lookup of $docId")
            assertEquals(List(dimensions) { 1f }, view.vectorOf(1000)?.values?.toList())
            assertNull(view.vectorOf(999))
            for (r in inputs + merged) r.close()
        }
    }

    @Test
    fun `a merge of only hidden vectors writes nothing`() {
        val field = VectorField("$.v", 2, VectorMetric.EUCLIDEAN)
        val input = write(memtable(5L to floatArrayOf(1f, 2f), 6L to floatArrayOf(3f, 4f)), 2)
        val stats = fs.open(newPath(), OpenMode.CREATE_NEW).use { VectorSegments.merge(listOf(input), field, hidden(5, 6), noStop, it, 256) }
        assertNull(stats)
        input.close()
    }

    /** Entries with valid checksums that no flush writes: a merge refuses them rather than copying them on. */
    @Test
    fun `merges refuse vectors that don't fit the field, whatever their checksums say`() {
        val field = VectorField("$.v", 2, VectorMetric.COSINE)
        fun raw(docId: Long, value: ByteArray): SegmentReader {
            val path = newPath()
            fs.open(path, OpenMode.CREATE_NEW).use { file ->
                val writer = SegmentWriter(file, SegmentFormat.INDEX_VECTOR, 256)
                val key = VectorSegments.key(docId)
                writer.add(key, 0, key.size, value, 0, value.size)
                writer.finish(SegmentStats(1, 1, docId, docId))
            }
            return open(path)
        }
        fun value(squares: Double, vararg values: Float) = ByteArray(VectorSegments.valueBytes(values.size)).also { VectorSegments.encode(values, squares, it) }
        val cases = mapOf(
            "with the sum of squares 1.0, but its values give 25.0" to raw(1, value(1.0, 3f, 4f)),
            "with a vector whose numbers aren't all finite" to raw(2, value(Double.NaN, Float.NaN, 1f)),
            "with a zero vector, which has no direction under COSINE" to raw(3, value(0.0, 0f, -0f)),
            "in 12 bytes, not 16" to raw(4, value(1.0, 1f)),
        )
        for ((detail, segment) in cases) {
            val e = assertFailsWith<CorruptionException>(detail) {
                fs.open(newPath(), OpenMode.CREATE_NEW).use { VectorSegments.merge(listOf(segment), field, HiddenDocIds.NONE, noStop, it, 256) }
            }
            assertTrue(e.detail.endsWith(detail), "${e.detail} ends with $detail")
            segment.close()
        }
    }
}
