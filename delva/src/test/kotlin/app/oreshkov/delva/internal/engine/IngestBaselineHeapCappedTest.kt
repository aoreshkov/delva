package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.testkit.generate.BigDocProfile
import app.oreshkov.delva.testkit.generate.GeneratedJson
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ingest baseline: a child JVM with a 256 MB heap loads 50 MiB and 500 MiB into a store on a
 * real disk with the default options and durability, once as one generated catalog document (large lane) and once as
 * generated catalogs of about 2 KiB each through `insertAll` (group commits), then streams every `qty = 1` hit.
 * Prints MiB/s, search time and the child's peak resident memory; asserts only that everything completes. Runs with
 * `heapCappedTest`; each load gets a fresh JVM so peak RSS belongs to it alone.
 */
class IngestBaselineHeapCappedTest {
    @Test
    fun `ingest baseline for 50 MiB and 500 MiB`() {
        for (megabytes in listOf(50, 500)) {
            for (shape in listOf("one", "many")) {
                val dir = Files.createTempDirectory("delva-baseline")
                try {
                    val process = ProcessBuilder(
                        ProcessHandle.current().info().command().orElse("java"),
                        "-Xmx256m",
                        "-cp", System.getProperty("java.class.path"),
                        IngestBaselineChild::class.java.name, dir.toString(), (megabytes.toLong() shl 20).toString(), shape,
                    ).redirectErrorStream(true).start()
                    val lines = process.inputStream.bufferedReader().readLines()
                    assertTrue(process.waitFor(30, TimeUnit.MINUTES))
                    assertEquals(0, process.exitValue(), lines.joinToString("\n"))
                    val result = lines.last { it.startsWith("RESULT") }
                    val values = result.removePrefix("RESULT ").split(' ').associate { it.substringBefore('=') to it.substringAfter('=').toLong() }
                    val mib = values.getValue("bytes") / (1024.0 * 1024.0)
                    println(
                        "baseline $megabytes MiB as ${if (shape == "one") "one document" else "${values.getValue("documents")} documents"}: " +
                            "insert %.1f MiB/s (%.1f s), %d flushes, streaming %d hits %.1f s, peak RSS %s".format(
                                mib / (values.getValue("insertMillis") / 1000.0), values.getValue("insertMillis") / 1000.0, values.getValue("flushes"),
                                values.getValue("hits"), values.getValue("searchMillis") / 1000.0,
                                values.getValue("peakRss").let { if (it < 0) "unknown" else "${it shr 20} MiB" },
                            ),
                    )
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }
    }
}

/** The child of [IngestBaselineHeapCappedTest]: `IngestBaselineChild <dir> <bytes> <one|many>`. */
object IngestBaselineChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val bytes = args[1].toLong()
        val many = args[2] == "many"
        Engine.open(dir, EngineOptions(), JdkFileSystemIo()).use { store ->
            var documents = 0L
            var start = System.nanoTime()
            if (many) {
                var produced = 0L
                var seed = 0L
                while (produced < bytes) {
                    val batch = List(1000) { GeneratedJson(seed++, BigDocProfile.CATALOG, 2048) }
                    store.insertAll(batch.map(::InsertSource))
                    produced += batch.sumOf { it.producedBytes }
                    documents += batch.size
                }
            } else {
                store.insert(GeneratedJson(7, BigDocProfile.CATALOG, bytes))
                documents = 1
            }
            val insertMillis = (System.nanoTime() - start) / 1_000_000
            start = System.nanoTime()
            var hits = 0L
            val sink = OutputStream.nullOutputStream()
            store.search(KeyValueQuery("qty", JsonQueryValue.Num(BigDecimal.ONE), ResultMode.CONTAINING_ELEMENT, limit = 1000)).use { result ->
                for (hit in result.iterator()) {
                    hits++
                    hit.writeJsonTo(sink)
                }
            }
            val searchMillis = (System.nanoTime() - start) / 1_000_000
            println("RESULT bytes=$bytes documents=$documents insertMillis=$insertMillis flushes=${store.indexes.flushes} hits=$hits searchMillis=$searchMillis peakRss=${peakRss()}")
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
