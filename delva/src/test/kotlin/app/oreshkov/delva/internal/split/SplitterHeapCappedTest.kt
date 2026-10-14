package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.ChunkedInputStream
import app.oreshkov.delva.internal.LiveHeapSampler
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.term.SortedTermRun
import app.oreshkov.delva.internal.term.TermSink
import app.oreshkov.delva.testkit.generate.BigDocProfile
import app.oreshkov.delva.testkit.generate.GeneratedJson
import app.oreshkov.delva.testkit.generate.StreamingExpectation
import app.oreshkov.delva.testkit.json.ONum
import java.io.InputStream
import java.nio.file.Files
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs under `heapCappedTest` (256 MB heap): multi-gigabyte documents are split with default options into counting
 * sinks. Memory must stay bounded by the options, never by the document: the 256 MB cap is the proof; the heap
 * in use after the latest collection of each pool is also reported (0 when the old generation never needed a collection).
 * `-Ddelva.heapCapped.bytes=<n>` changes the document size (default 2 GiB, the string 1 GiB).
 */
class SplitterHeapCappedTest {
    private val documentBytes = System.getProperty("delva.heapCapped.bytes")?.toLong() ?: (2L shl 30)

    private class CountingFragments : FragmentSink {
        var count = 0L
        var bytes = 0L
        val kinds = LongArray(0x21)

        override fun fragment(docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int) {
            count++
            bytes += length
            kinds[kind]++
        }
    }

    private class CountingTerms(term: String) : TermSink {
        private val wanted = term.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        var postings = 0L
        var matches = 0L

        override fun run(docId: Long, runSeq: Int, run: SortedTermRun) {
            postings += run.size
            for (i in 0 until run.size) {
                val offset = run.termOffset(i)
                if (Arrays.equals(run.bytes, offset, offset + run.termLength(i), wanted, 0, wanted.size)) matches++
            }
        }
    }

    private fun splitCounting(input: InputStream, label: String, expectation: StreamingExpectation?) {
        val options = SplitOptions()
        val fragments = CountingFragments()
        val terms = CountingTerms(OracleTerms.term("qty", ONum("1")))
        val spillDir = Files.createTempDirectory("delva-spill")
        val splitter = Splitter(options, fragments, terms, { _, _, _ -> }, FileSystemSpillFiles(JdkFileSystemIo(), spillDir))
        val started = System.nanoTime()
        val peak = LiveHeapSampler().use { sampler ->
            splitter.split(1, Utf8JsonTokenizer(input, options.parseLimits()), keySeed = 99)
            sampler.peak
        }
        val seconds = (System.nanoTime() - started) / 1e9
        Files.list(spillDir).use { assertEquals(0, it.count(), "spill files left behind") }
        Files.delete(spillDir)
        println(
            "$label: ${splitter.nodeCount} values, ${fragments.count} fragments (PART ${fragments.kinds[FragmentKind.PART]}, SUBTREE ${fragments.kinds[FragmentKind.SUBTREE]}, " +
                "HEADER ${fragments.kinds[FragmentKind.HEADER]}, STRCHUNK ${fragments.kinds[FragmentKind.STRCHUNK]}), ${fragments.bytes} payload bytes, ${terms.postings} postings in " +
                "${splitter.runCount} runs, peak pending ${splitter.peakPendingBytes}, peak record ${splitter.peakRecordBytes}, peak live heap ${peak shr 20} MiB, %.1f s".format(seconds),
        )
        assertTrue(peak < 160L shl 20, "$label: live heap peaked at ${peak shr 20} MiB")
        assertTrue(splitter.peakPendingBytes <= options.budgetBytes + 2 * options.fragmentBytes, "$label: pending peaked at ${splitter.peakPendingBytes}")
        if (expectation != null) {
            assertEquals(expectation.nodeCount, splitter.nodeCount, "$label node count")
            assertEquals(expectation.containingHits, terms.matches, "$label qty = 1 postings")
        }
    }

    private fun generated(profile: BigDocProfile) {
        val expectation = StreamingExpectation("qty", ONum("1"))
        splitCounting(GeneratedJson(7, profile, documentBytes, expectation), "$profile ${documentBytes shr 20} MiB", expectation)
    }

    @Test
    fun `a wide object splits in bounded memory`() = generated(BigDocProfile.WIDE_OBJECT)

    @Test
    fun `a deep, array-heavy document splits in bounded memory`() = generated(BigDocProfile.DEEP)

    @Test
    fun `a catalog splits in bounded memory`() = generated(BigDocProfile.CATALOG)

    @Test
    fun `a single huge string value splits in bounded memory`() {
        val bytes = documentBytes / 2 / PATTERN_BYTES * PATTERN_BYTES
        splitCounting(HugeStringDocument(bytes), "string ${bytes shr 20} MiB", null)
    }

    @Test
    fun `an inner object under an outer one that fills the key arena splits in bounded memory`() {
        // 300K outer keys grow the outer table to the whole 16 MiB arena. Spilling only the inner table would then
        // write a run every 32 keys, and merge its 125K runs with a 4 KiB buffer and an open file each.
        val chunks = sequence {
            yield("{")
            for (i in 0 until 300_000) yield("\"o$i\":0,")
            yield("\"inner\":{")
            for (i in 0 until 4_000_000) yield(if (i == 0) "\"i0\":0" else ",\"i$i\":0")
            yield("}}")
        }
        splitCounting(ChunkedInputStream(chunks), "inner object under an arena-filling outer one", null)
    }

    /** `{"s":"<ascii and é, repeated>"}`, generated while it's read; the string length must be a multiple of [PATTERN_BYTES]. */
    private class HugeStringDocument(private val stringBytes: Long) : InputStream() {
        private val head = "{\"s\":\"".toByteArray()
        private val tail = "\"}".toByteArray()
        private val pattern = PATTERN
        private var position = 0L
        private val total = head.size + stringBytes + tail.size

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= total) return -1
            var n = 0
            while (n < len && position < total) {
                val inString = position - head.size
                b[off + n++] = when {
                    position < head.size -> head[position.toInt()]
                    inString < stringBytes -> pattern[(inString % pattern.size).toInt()]
                    else -> tail[(inString - stringBytes).toInt()]
                }
                position++
            }
            return n
        }
    }


    private companion object {
        val PATTERN = "abcdefghijklmnopqrstuvwxyz é 0123456789 ".toByteArray()
        val PATTERN_BYTES = PATTERN.size.toLong()
    }
}
