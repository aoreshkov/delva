package app.oreshkov.delva.internal.log

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.PayloadSlice

/**
 * A stored fragment as read: its key, its context and a view of its payload. Reused between reads; the views stay
 * valid as long as the bytes they point to: the log store reads each fragment into its own array.
 */
internal class FragmentView {
    var docId: Long = 0
        private set
    var pre: Long = 0
        private set
    var kind: Int = 0
        private set
    var seq: Int = 0
        private set
    val context = FragmentContext()
    var bytes: ByteArray = EMPTY
        private set
    var payloadOffset: Int = 0
        private set
    var payloadLength: Int = 0
        private set

    /** How the payload is stored ([FragmentCodecs] ids); while compressed, [payloadLength] is the stored length. */
    var codecId: Int = FragmentCodecs.NONE
        private set
    var dictId: Int = 0
        private set

    /** Length of the payload itself. */
    var rawLength: Int = 0
        private set

    val compressed: Boolean get() = codecId != FragmentCodecs.NONE

    private val reader = ByteReader(EMPTY)

    fun slice(): PayloadSlice = PayloadSlice(bytes, payloadOffset, payloadLength)

    /** Points the view at a record `bytes[recordOffset, recordOffset + entry.length)` ([FragmentRecord] layout). */
    internal fun set(entry: FragmentEntry, bytes: ByteArray, recordOffset: Int) {
        val source = "fragment (${entry.docId}, ${entry.pre}, ${entry.kind}, ${entry.seq})"
        reader.reset(bytes, recordOffset, entry.payloadOffset, source)
        FragmentRecord.readContext(reader, context, source)
        if (reader.remaining != 0) throw CorruptionException(source, "${reader.remaining} bytes after the context")
        set(entry.docId, entry.pre, entry.kind, entry.seq, bytes, recordOffset + entry.payloadOffset, entry.rawLength)
    }

    /** Points the view at `bytes[payloadOffset, payloadOffset + payloadLength)`; the caller has set [context]. */
    internal fun set(docId: Long, pre: Long, kind: Int, seq: Int, bytes: ByteArray, payloadOffset: Int, payloadLength: Int) {
        this.docId = docId
        this.pre = pre
        this.kind = kind
        this.seq = seq
        this.bytes = bytes
        this.payloadOffset = payloadOffset
        this.payloadLength = payloadLength
        codecId = FragmentCodecs.NONE
        dictId = 0
        rawLength = payloadLength
    }

    internal fun setCodec(codecId: Int, dictId: Int, rawLength: Int) {
        this.codecId = codecId
        this.dictId = dictId
        this.rawLength = rawLength
    }

    /**
     * Inflates a compressed payload into [buffer] (grown as needed) and points the view at it; does nothing for a
     * stored one. Damage throws [CorruptionException].
     */
    internal fun inflate(codecs: FragmentCodecs, buffer: GrowableBytes, source: String) {
        if (!compressed) return
        buffer.clear()
        val at = buffer.reserve(rawLength)
        codecs.inflate(codecId, dictId, bytes, payloadOffset, payloadLength, buffer.array, at, rawLength, source)
        set(docId, pre, kind, seq, buffer.array, at, rawLength)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
