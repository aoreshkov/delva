package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VerifyIssueKind
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.lsm.SegmentReader
import app.oreshkov.delva.internal.lsm.SegmentWriter
import app.oreshkov.delva.internal.lsm.VectorSegments
import app.oreshkov.delva.internal.vector.Vectors
import java.nio.file.Path
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The vector index through flushes, recovery from segments, compaction, `verify` and secondaries. */
class VectorIndexEngineTest {
    private val field = VectorField("$.v", 3, VectorMetric.EUCLIDEAN)

    private fun options(memtableBytes: Long? = null) = EngineOptions(
        CreationOptions(fragmentBytes = 64).withVectorField(field), StoreOptions(encoderBudgetBytes = 1024),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 13, log = TestEngines.LOG,
        backgroundFlush = false, memtableBytes = memtableBytes, segmentBlockBytes = 256,
        // Only when asked, so the tests see each flush's segment.
        compaction = CompactionOptions(enabled = false, bytesPerSecond = 0, minMergeSegments = 2, deletedRatio = 0.01),
    )

    /** Document [i]'s vector; every third document has none. */
    private fun vector(i: Int): FloatArray? = if (i % 3 == 2) null else floatArrayOf(i.toFloat(), -0.5f * i, if (i % 2 == 0) -0f else 1e-3f)

    private fun doc(i: Int): String = buildString {
        append("""{"all":true,"i":""").append(i).append(""","pad":"""").append("p".repeat(i * 13 % 200)).append('"')
        vector(i)?.let { append(""","v":[""").append(it.joinToString(",")).append(']') }
        append('}')
    }

    private fun vectorFiles(fs: SimulatedFileSystemIo): List<Path> = fs.files().filter { it.fileName.toString().startsWith("vector-") && it.fileName.toString().endsWith(".seg") }

    private fun assertVectors(store: ReadEngine, expected: Map<Long, Int>, label: String) {
        assertEquals(expected.keys, store.documents().keys, label)
        for ((docId, i) in expected) assertEquals(vector(i)?.toList(), store.vectorOf(docId)?.toList(), "$label: document $docId")
        assertEquals(emptyList(), store.verify().issues.map { it.toString() }, label)
    }

    @Test
    fun `vectors are flushed to segments, read back from them after a restart, and compaction drops deleted ones`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        val expected = LinkedHashMap<Long, Int>()
        TestEngines.open(options, fs).use { store ->
            for (round in 0 until 3) {
                for (i in round * 10 until round * 10 + 10) expected[store.insert(doc(i)).raw] = i
                store.flush()
            }
            assertEquals(3, store.indexes.versions.current().vectorSegments.size)
            assertEquals(3, vectorFiles(fs).size)
            assertVectors(store, expected, "after three flushes")
        }
        fs.restart()
        val deleted = expected.keys.filterIndexed { n, _ -> n % 4 == 0 }
        TestEngines.open(options, fs).use { store ->
            assertEquals(0, store.recoveryStats.replayedCommits)
            assertVectors(store, expected, "from segments")
            for (docId in deleted) {
                assertTrue(store.delete(DocId(docId)))
                expected.remove(docId)
            }
            // A replace gives the new document its own vector, or none.
            val replaced = expected.keys.first()
            val by = store.replace(listOf(DocId(replaced)), doc(100)).raw
            expected.remove(replaced)
            expected[by] = 100
            store.flush()
            assertTrue(store.compact() > 0)
            assertTrue(store.indexes.versions.current().vectorSegments.size < 4, "the four vector segments were merged")
            val indexed = store.indexedVectorDocIds()
            assertEquals(expected.filterValues { vector(it) != null }.keys.sorted(), indexed, "only the visible documents' vectors are left")
            assertVectors(store, expected, "after compaction")
        }
        fs.restart()
        TestEngines.open(options, fs).use { store -> assertVectors(store, expected, "after compaction and a restart") }
    }

    @Test
    fun `a small memtable flushes vectors as inserts go`() {
        val fs = SimulatedFileSystemIo()
        val expected = LinkedHashMap<Long, Int>()
        TestEngines.open(options(memtableBytes = 0), fs).use { store ->
            for (i in 0 until 40) expected[store.insert(doc(i)).raw] = i
            assertTrue(store.indexes.versions.current().vectorSegments.isNotEmpty())
            assertVectors(store, expected, "flushed by the memtable budget")
        }
    }

    @Test
    fun `verify reports a changed byte of a vector segment, and vectors its checksums can't tell are wrong`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        val expected = LinkedHashMap<Long, Int>()
        TestEngines.open(options, fs).use { store ->
            for (i in 0 until 12) expected[store.insert(doc(i)).raw] = i
            store.flush()
            val path = vectorFiles(fs).single()
            fs.flipBit(path, 20, 3)
            val issues = store.verify().issues
            assertTrue(issues.isNotEmpty() && issues.all { it.kind == VerifyIssueKind.SEGMENT && it.location.contains("vector-") }, "$issues")
            fs.flipBit(path, 20, 3)
            assertEquals(emptyList(), store.verify().issues.map { it.toString() })
        }
        val path = vectorFiles(fs).single()
        val first = expected.entries.first { it.value == 1 }

        // The first vector's norm one step off, checksums and all.
        rewrite(fs, path) { docId, values, norm -> if (docId == first.key) values to norm.nextUp() else values to norm }
        TestEngines.open(options, fs).use { store ->
            val details = store.verify().issues.filter { it.kind == VerifyIssueKind.VECTOR_INDEX }.map { it.detail }
            val norm = Vectors.norm(vector(first.value)!!)
            assertEquals(listOf("document ${first.key} is indexed with the norm ${norm.nextUp()}, but its values give $norm"), details)
        }

        // Another vector with a norm that fits it: only the COMMIT in the log tells.
        rewrite(fs, path) { docId, values, norm ->
            if (docId == first.key) {
                val other = values.copyOf().also { it[0] += 1f }
                other to Vectors.norm(other)
            } else {
                values to norm
            }
        }
        TestEngines.open(options, fs).use { store ->
            val issues = store.verify().issues.filter { it.kind == VerifyIssueKind.VECTOR_INDEX }
            assertEquals(listOf(first.key), issues.map { it.document?.raw }, "$issues")
            assertTrue(issues.single().detail.startsWith("the vector of document ${first.key} in the index isn't the one its COMMIT at control file"), issues.single().detail)
        }
    }

    /** Rewrites the vector segment at [path] through [change], with the same keys, value lengths and stats, so its size stays. */
    private fun rewrite(fs: SimulatedFileSystemIo, path: Path, change: (Long, FloatArray, Float) -> Pair<FloatArray, Float>) {
        val original = fs.bytes(path)
        val copy = path.resolveSibling("original.tmp")
        fs.delete(copy)
        fs.open(copy, OpenMode.CREATE_NEW).use { it.write(0, original, 0, original.size) }
        fs.delete(path)
        SegmentReader.open(1, fs.open(copy, OpenMode.READ), ByteCache(0), SegmentFormat.INDEX_VECTOR).use { reader ->
            fs.open(path, OpenMode.CREATE_NEW).use { file ->
                val writer = SegmentWriter(file, SegmentFormat.INDEX_VECTOR, 256, inlineLimit = VectorSegments.valueBytes(VectorField.MAX_DIMENSIONS))
                val cursor = reader.cursor()
                val values = FloatArray(field.dimensions)
                val value = ByteArray(VectorSegments.valueBytes(field.dimensions))
                var ok = cursor.first()
                while (ok) {
                    val docId = VectorSegments.docIdOf(cursor, reader.source)
                    val norm = VectorSegments.read(cursor, values, reader.source)
                    val (v, n) = change(docId, values.copyOf(), norm)
                    VectorSegments.encode(v, n, value)
                    writer.add(cursor.key.array, 0, cursor.key.size, value, 0, value.size)
                    ok = cursor.next()
                }
                writer.finish(reader.stats)
                file.force(true)
            }
        }
        fs.delete(copy)
        assertEquals(original.size, fs.bytes(path).size, "the rewritten segment's size")
    }

    @Test
    fun `a reset deletes the vector segments and keeps the vector field`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        TestEngines.open(options, fs).use { store ->
            for (i in 0 until 10) store.insert(doc(i))
            store.flush()
        }
        assertEquals(1, vectorFiles(fs).size)
        assertEquals(options.creation, StoreReset.run(TestEngines.DIR, fs))
        assertEquals(emptyList(), vectorFiles(fs))
        TestEngines.open(options, fs).use { store ->
            assertEquals(emptyList(), store.indexedVectorDocIds())
            val docId = store.insert(doc(0)).raw
            assertEquals(1L, docId)
            assertVectors(store, mapOf(docId to 0), "after the reset")
        }
    }

    @Test
    fun `a secondary reads vectors from segments and the log, and sees new ones after a refresh`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        val expected = LinkedHashMap<Long, Int>()
        TestEngines.open(options, fs).use { writer ->
            for (i in 0 until 8) expected[writer.insert(doc(i)).raw] = i
            writer.flush()
            for (i in 8 until 12) expected[writer.insert(doc(i)).raw] = i
            SecondaryFixtures.secondary(fs, options).use { secondary ->
                assertVectors(secondary, expected, "a secondary from segments and the log")
                for (i in 12 until 20) expected[writer.insert(doc(i)).raw] = i
                assertTrue(secondary.refresh())
                assertVectors(secondary, expected, "after a refresh")
                val gone = expected.keys.first()
                assertTrue(writer.delete(DocId(gone)))
                expected.remove(gone)
                writer.flush()
                writer.compact()
                assertTrue(secondary.refresh())
                assertVectors(secondary, expected, "after the writer flushed and compacted")
            }
        }
    }
}
