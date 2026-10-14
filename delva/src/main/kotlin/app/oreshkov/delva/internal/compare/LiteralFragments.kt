package app.oreshkov.delva.internal.compare

import app.oreshkov.delva.Normalization
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.log.FragmentRecord
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.FragmentIndexMemtable
import app.oreshkov.delva.internal.parse.JsonTokenSource
import app.oreshkov.delva.internal.parse.NfcTokenSource
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.split.FragmentSink
import app.oreshkov.delva.internal.split.SpillFiles
import app.oreshkov.delva.internal.split.SplitOptions
import app.oreshkov.delva.internal.split.Splitter
import app.oreshkov.delva.internal.split.TypeSink
import app.oreshkov.delva.internal.store.FragmentStore
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * The fragments of a JSON value given to a comparison: split like a document so stored and given
 * values share one comparison path. Records ([FragmentRecord] layout) are kept on the heap up to [memoryBytes]; later
 * ones go to a temporary file in [tmpDir], read back through a small cache, so a huge value costs disk rather than
 * heap. Only the fragment index (one entry per fragment) stays in memory.
 *
 * Written by one splitter, then read; [close] deletes the file. Not thread-safe.
 */
internal class LiteralFragments(
    private val io: FileSystemIo,
    private val tmpDir: Path,
    private val memoryBytes: Long,
) : FragmentSink, FragmentStore, AutoCloseable {
    private val index = FragmentIndexMemtable()
    private val memory = GrowableBytes(4096)
    private val record = GrowableBytes(256)
    private var file: FileIo? = null
    private var fileBytes = 0L
    private val cache = ByteCache(SPILL_CACHE_BYTES, shardCount = 1)

    /** Payload bytes written to the temporary file. */
    val spilledBytes: Long get() = fileBytes

    override fun fragment(docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int) {
        record.clear()
        val payloadOffset = FragmentRecord.write(record, context, payload, offset, length)
        val location: Long
        if (file == null && memory.size.toLong() + record.size <= memoryBytes) {
            location = memory.size.toLong()
            memory.writeBytes(record.array, 0, record.size)
        } else {
            val f = file ?: openFile()
            location = SPILLED or fileBytes
            f.write(fileBytes, record.array, 0, record.size)
            fileBytes += record.size
        }
        index.add(FragmentEntry(docId, pre, kind, seq, location, record.size, payloadOffset, length))
    }

    override fun find(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean {
        val entry = index.find(docId, pre, kind, seq) ?: return false
        read(entry, into)
        return true
    }

    override fun floor(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean {
        val entry = index.floor(docId, pre, kind, seq) ?: return false
        read(entry, into)
        return true
    }

    override fun payloadBytes(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Long {
        if (toPre < fromPre) return 0
        var total = 0L
        for (entry in index.range(docId, fromPre, fromKind, toPre)) total += entry.rawLength
        return total
    }

    private fun read(entry: FragmentEntry, into: FragmentView) {
        if (entry.location and SPILLED == 0L) {
            into.set(entry, memory.array, entry.location.toInt())
            return
        }
        val position = entry.location and SPILLED.inv()
        val bytes = cache.get(position) ?: ByteArray(entry.length).also {
            file!!.readFully(position, it, 0, it.size)
            cache.put(position, it)
        }
        into.set(entry, bytes, 0)
    }

    private fun openFile(): FileIo {
        io.createDirectories(tmpDir)
        while (true) {
            val path = tmpDir.resolve("literal-${COUNTER.incrementAndGet()}.tmp")
            if (io.exists(path)) continue
            return io.open(path, OpenMode.CREATE_NEW).also { file = it }
        }
    }

    override fun close() {
        val f = file ?: return
        file = null
        try {
            f.close()
        } finally {
            try {
                io.delete(f.path)
            } catch (_: IOException) {
                // Removed with the rest of tmp/ when the store opens next.
            }
        }
    }

    private companion object {
        const val SPILLED = 1L shl 62
        const val SPILL_CACHE_BYTES = 4L shl 20
        val COUNTER = AtomicLong()
    }
}

/**
 * Splits JSON values given to comparisons with the store's split options and parse limits (so they're rejected like
 * documents), without producing index terms. Reusable; not thread-safe.
 */
internal class LiteralParser(
    split: SplitOptions,
    limits: ParseLimits,
    windowBytes: Int,
    stringChunkBytes: Int,
    spill: SpillFiles,
    normalization: Normalization = Normalization.NONE,
) {
    private var sink: FragmentSink = FragmentSink { _, _, _, _, _, _, _, _ -> }
    private val tokenizer =
        Utf8JsonTokenizer(InputStream.nullInputStream(), if (normalization == Normalization.NFC) limits.forNfc() else limits, windowBytes, stringChunkBytes)

    /** The JSON operand is normalized like a document, so a stored value and a literal compare in one spelling. */
    private val source: JsonTokenSource =
        if (normalization == Normalization.NFC) NfcTokenSource(tokenizer, limits) else tokenizer
    private val splitter = Splitter(split, { docId, pre, kind, seq, context, payload, offset, length -> sink.fragment(docId, pre, kind, seq, context, payload, offset, length) }, null, TypeSink { _, _, _ -> }, spill)

    val rootHashLow: Long get() = splitter.rootHashLow
    val rootHashHigh: Long get() = splitter.rootHashHigh

    /** Heap kept for reuse. */
    val retainedBytes: Long get() = splitter.retainedBytes

    /** Splits the value read from [input] into [into] as document [LITERAL_DOC_ID]. */
    fun parse(input: InputStream, into: FragmentSink) {
        sink = into
        try {
            tokenizer.reset(input)
            (source as? NfcTokenSource)?.reset()
            splitter.split(LITERAL_DOC_ID, source)
        } finally {
            sink = FragmentSink { _, _, _, _, _, _, _, _ -> }
            tokenizer.reset(InputStream.nullInputStream())
        }
    }

    companion object {
        const val LITERAL_DOC_ID: Long = 0
    }
}
