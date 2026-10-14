package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The size half of the benchmark report on a real document: how large the index and the lane files are against the
 * raw JSON, by compression setting. That index ÷ raw stays at or below 37 % with hash terms on (and a path hash per posting, SPEC.md §15) is asserted here.
 *
 * Needs a JSON file in `-Ddelva.sample.json=<file>`; skipped without it. `-Ddelva.size=<T>` sets the fragment size.
 */
class SampleSizeReportTest {
    @Test
    fun `index and lane sizes on a real document`() {
        val file = System.getProperty("delva.sample.json")?.let { Path.of(it) } ?: return
        val fragmentBytes = (System.getProperty("delva.size") ?: "65536").toInt()
        val raw = Files.size(file)
        val rows = ArrayList<String>()
        for (setting in listOf("stored", "deflate", "deflate with dictionaries")) {
            val dir = Files.createTempDirectory("delva-sample-size")
            try {
                val compression = when (setting) {
                    "stored" -> CompressionOptions.DISABLED
                    "deflate" -> CompressionOptions(dictionaries = false)
                    else -> CompressionOptions()
                }
                val options = EngineOptions(
                    CreationOptions(fragmentBytes = fragmentBytes), StoreOptions(encoderBudgetBytes = maxOf(8 shl 20, 2 * fragmentBytes)),
                    compression = compression,
                )
                Engine.open(dir, options, JdkFileSystemIo()).use { store ->
                    val start = System.nanoTime()
                    store.insert(file)
                    val insertMillis = (System.nanoTime() - start) / 1_000_000
                    store.flush()
                    while (store.compact() > 0) {
                        // Settle the LSM: the index size to report is the compacted one.
                    }
                    val lane = laneBytes(dir)
                    val index = sizeOf(dir.resolve("index"))
                    rows += "| %s | %.1f | %.1f | %.1f %% | %.1f %% | %d |".format(
                        setting, raw / (1 shl 20).toDouble() / (insertMillis / 1000.0), lane / (1 shl 20).toDouble(),
                        100.0 * lane / raw, 100.0 * index / raw, store.codecs.dictionaryCount,
                    )
                    assertTrue(index <= raw * 37 / 100, "the index is $index bytes, ${"%.1f".format(100.0 * index / raw)} % of the $raw raw bytes")
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
        println(
            "\nSizes on ${file.fileName} (${raw shr 20} MiB, T = $fragmentBytes)\n\n" +
                "| Payloads | Insert MiB/s | Lane MiB | Lane ÷ raw | Index ÷ raw | Dictionaries |\n" +
                "|---|---|---|---|---|---|\n" + rows.joinToString("\n"),
        )
    }

    private fun sizeOf(dir: Path): Long = Files.list(dir).use { paths -> paths.toList().sumOf { it.fileSize() } }

    /** Lane files are preallocated with zeros: only the bytes written count. */
    private fun laneBytes(dir: Path): Long = Files.list(dir.resolve("data")).use { paths ->
        paths.toList().sumOf { path ->
            val bytes = Files.readAllBytes(path)
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte()) end--
            end.toLong()
        }
    }
}
