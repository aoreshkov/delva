package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import java.nio.file.Path

/** Engines on a simulated disk with small log files, for tests. */
internal object TestEngines {
    /** Small files, so tests roll files and preallocate often without allocating much. */
    val LOG = LogOptions(laneFileBytes = 1L shl 20, controlFileBytes = 64L shl 10, preallocateBytes = 16L shl 10, writeBufferBytes = 16 shl 10)

    val DIR: Path = Path.of("db")

    fun open(options: EngineOptions, fs: SimulatedFileSystemIo = SimulatedFileSystemIo(), dir: Path = DIR): Engine = Engine.open(dir, options, fs)

    /**
     * Compression for simulations, by [choice] (0..2): off; deflate without dictionaries at level 1; or dictionaries
     * built from a few KiB and adopted whenever they aren't worse, so dictionary files and MANIFESTs are written often.
     */
    fun compression(choice: Int): CompressionOptions = when (choice) {
        0 -> CompressionOptions.DISABLED
        1 -> CompressionOptions(level = 1, dictionaries = false)
        else -> CompressionOptions(
            sampleBytes = 16 shl 10, sampleChunkBytes = 1024, firstTrainingBytes = 4L shl 10, retrainingBytes = 16L shl 10,
            maxDictionaryBytes = 4 shl 10, minDictionaryGain = 0.0,
        )
    }
}

/** `docId → JSON` of every visible document with a top-level or nested `"all": true`. */
internal fun ReadEngine.documents(): Map<Long, String> =
    search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 10_000)).use { result ->
        result.iterator().asSequence().associate { it.node.docId.raw to it.readJsonAsString(Int.MAX_VALUE) }
    }
