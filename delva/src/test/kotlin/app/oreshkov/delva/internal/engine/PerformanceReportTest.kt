package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.testkit.generate.BigDocProfile
import app.oreshkov.delva.testkit.generate.GeneratedJson
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The benchmark report: for each load, a child JVM with a 256 MB heap fills a store on a real disk, then searches,
 * streams, reopens and compacts it, and the parent prints one table of everything (ingest MB/s and peak RSS, index and
 * lane size against the raw JSON, search latency by posting-list size, streaming throughput, recovery time and
 * compaction write amplification), with and without compression.
 *
 * Runs under `reportTest`, not `check`: it takes minutes and its numbers are for the record, so it asserts only that
 * every run completes and reads back what was written. `-Ddelva.report.megabytes=50,500,5000` sets the loads (50 and
 * 500 by default), `-Ddelva.report.shapes=one,many` the document shapes.
 */
class PerformanceReportTest {
    @Test
    fun `benchmark report`() {
        val megabytes = (System.getProperty("delva.report.megabytes") ?: "50,500").split(',').map { it.trim().toInt() }
        val shapes = (System.getProperty("delva.report.shapes") ?: "one,many").split(',').map { it.trim() }
        val rows = ArrayList<String>()
        for (mb in megabytes) {
            for (shape in shapes) {
                for (compressed in listOf(true, false)) {
                    val values = runChild(mb, shape, compressed)
                    val raw = values.getValue("bytes").toDouble()
                    rows += "| %d MiB | %s | %s | %.1f | %d | %.1f | %.1f %% | %.1f %% | %.0f / %.0f | %.1f | %d | %.2f | %s |".format(
                        mb, if (shape == "one") "one document" else "${values.getValue("documents")} documents", if (compressed) "deflate" else "stored",
                        raw / (1 shl 20) / (values.getValue("insertMillis") / 1000.0), values.getValue("flushes"),
                        values.getValue("laneBytes") / (1 shl 20).toDouble(), 100.0 * values.getValue("laneBytes") / raw, 100.0 * values.getValue("indexBytes") / raw,
                        values.getValue("searchP50Micros").toDouble(), values.getValue("searchP99Micros").toDouble(),
                        values.getValue("streamedBytes") / (1 shl 20).toDouble() / (values.getValue("streamMillis").coerceAtLeast(1) / 1000.0),
                        values.getValue("reopenMillis"), values.getValue("writeAmplificationPercent") / 100.0,
                        values.getValue("peakRss").let { if (it < 0) "unknown" else "${it shr 20} MiB" },
                    )
                }
            }
        }
        println(
            "\nBenchmark report (256 MB heap, real disk, SyncEachBatch, CATALOG documents)\n\n" +
                "| Load | Shape | Payloads | Insert MiB/s | Flushes | Lane MiB | Lane ÷ raw | Index ÷ raw | Search p50/p99 µs | Stream MiB/s | Reopen ms | Write amp. | Peak RSS |\n" +
                "|---|---|---|---|---|---|---|---|---|---|---|---|---|\n" + rows.joinToString("\n"),
        )
    }

    private fun runChild(megabytes: Int, shape: String, compressed: Boolean): Map<String, Long> {
        val dir = Files.createTempDirectory("delva-report")
        try {
            val process = ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-Xmx256m",
                "-cp", System.getProperty("java.class.path"),
                PerformanceReportChild::class.java.name, dir.toString(), (megabytes.toLong() shl 20).toString(), shape, compressed.toString(),
            ).redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().readLines()
            assertTrue(process.waitFor(60, TimeUnit.MINUTES), "the child didn't finish")
            assertEquals(0, process.exitValue(), lines.joinToString("\n"))
            val result = lines.last { it.startsWith("RESULT") }
            return result.removePrefix("RESULT ").split(' ').associate { it.substringBefore('=') to it.substringAfter('=').toLong() }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/** The child of [PerformanceReportTest]: `PerformanceReportChild <dir> <bytes> <one|many> <compressed>`. */
object PerformanceReportChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val bytes = args[1].toLong()
        val many = args[2] == "many"
        val options = EngineOptions(compression = if (args[3].toBoolean()) CompressionOptions() else CompressionOptions.DISABLED)
        val one = JsonQueryValue.Num(BigDecimal.ONE)
        var documents = 0L
        var raw = 0L
        val insertMillis: Long
        val flushes: Long
        val streamedBytes: Long
        val streamMillis: Long
        val searchMicros = ArrayList<Long>()
        val writeAmplificationPercent: Long
        Engine.open(dir, options, JdkFileSystemIo()).use { store ->
            var start = System.nanoTime()
            if (many) {
                while (raw < bytes) {
                    val batch = List(1000) { GeneratedJson(documents + it, BigDocProfile.CATALOG, 2048) }
                    store.insertAll(batch.map(::InsertSource))
                    raw += batch.sumOf { it.producedBytes }
                    documents += batch.size
                }
            } else {
                store.insert(GeneratedJson(7, BigDocProfile.CATALOG, bytes))
                documents = 1
                raw = bytes
            }
            insertMillis = (System.nanoTime() - start) / 1_000_000
            flushes = store.indexes.flushes

            // Search latency: the first page of a hot term, over and over, with the caches warm.
            val query = KeyValueQuery("qty", one, ResultMode.CONTAINING_ELEMENT, limit = 100)
            repeat(200) {
                val at = System.nanoTime()
                store.search(query).use { result -> result.nextPage() }
                searchMicros += (System.nanoTime() - at) / 1000
            }

            // Streaming: every hit written out, which reads and reassembles the fragments.
            start = System.nanoTime()
            val sink = CountingSink()
            store.search(KeyValueQuery("qty", one, ResultMode.CONTAINING_ELEMENT, limit = 1000)).use { result ->
                for (hit in result.iterator()) hit.writeJsonTo(sink)
            }
            streamedBytes = sink.bytes
            streamMillis = (System.nanoTime() - start) / 1_000_000

            store.flush()
            while (store.compact() > 0) {
                // Settle the LSM, so write amplification counts the whole merge work of this load.
            }
            val flushed = store.indexes.flushedBytes
            writeAmplificationPercent = if (flushed == 0L) 0 else 100 * (flushed + store.indexes.compactor.outputBytes) / flushed
        }
        val reopenStart = System.nanoTime()
        val reopenMillis: Long
        Engine.open(dir, options, JdkFileSystemIo()).use { store ->
            reopenMillis = (System.nanoTime() - reopenStart) / 1_000_000
            check(store.damagedDocIds.isEmpty()) { "damaged documents after reopen" }
            // What was written is still readable.
            check(store.search(KeyValueQuery("qty", one, ResultMode.CONTAINING_ELEMENT, limit = 1)).use { it.nextPage() != null }) { "no hits after reopen" }
        }
        val laneBytes = sizeOf(dir.resolve("data"))
        val indexBytes = sizeOf(dir.resolve("index"))
        searchMicros.sort()
        println(
            "RESULT bytes=$raw documents=$documents insertMillis=$insertMillis flushes=$flushes laneBytes=$laneBytes indexBytes=$indexBytes " +
                "searchP50Micros=${searchMicros[searchMicros.size / 2]} searchP99Micros=${searchMicros[searchMicros.size * 99 / 100]} " +
                "streamedBytes=$streamedBytes streamMillis=$streamMillis reopenMillis=$reopenMillis " +
                "writeAmplificationPercent=$writeAmplificationPercent peakRss=${peakRss()}",
        )
    }

    private class CountingSink : OutputStream() {
        var bytes = 0L

        override fun write(b: Int) {
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes += len
        }
    }

    /** Bytes written in [dir]; lane files are preallocated with zeros, so their tail of zeros doesn't count. */
    private fun sizeOf(dir: Path): Long = Files.list(dir).use { paths ->
        paths.toList().sumOf { path -> if (path.fileName.toString().endsWith(".frag")) writtenBytes(path) else path.fileSize() }
    }

    /** Where a preallocated file's zero tail begins, found by reading backwards in chunks (the file may be huge). */
    private fun writtenBytes(path: Path): Long {
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(1 shl 20)
            var end = channel.size()
            while (end > 0) {
                val from = maxOf(0, end - buffer.capacity())
                buffer.clear()
                buffer.limit((end - from).toInt())
                var at = from
                while (buffer.hasRemaining()) {
                    val n = channel.read(buffer, at)
                    if (n < 0) break
                    at += n
                }
                val bytes = buffer.array()
                var i = buffer.position()
                while (i > 0 && bytes[i - 1] == 0.toByte()) i--
                if (i > 0) return from + i
                end = from
            }
            return 0
        }
    }

    /** Peak resident set size of this process in bytes, or -1 where it can't be read. */
    private fun peakRss(): Long {
        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.contains("linux") -> Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong() * 1024
                os.contains("windows") -> {
                    val pid = ProcessHandle.current().pid()
                    val process = ProcessBuilder("powershell", "-NoProfile", "-Command", "(Get-Process -Id $pid).PeakWorkingSet64").redirectErrorStream(true).start()
                    process.inputStream.bufferedReader().readText().trim().toLong().also { process.waitFor() }
                }
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}
