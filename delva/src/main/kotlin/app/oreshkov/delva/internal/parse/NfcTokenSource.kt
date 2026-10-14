package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.Nfc

/**
 * A [JsonTokenSource] that hands on NFC-normalized keys and strings, for stores created with normalization on
 * (SPEC.md §2.1). Everything downstream — the splitter, the payload, the canonical hashes and the index terms —
 * then sees one spelling of every string, without knowing that normalization happened.
 *
 * Keys and short strings are normalized whole. A long string arrives in parts, and a part can end in the middle of
 * a combining sequence, so parts are re-cut: bytes are held back until a safe starter shows where normalizing the
 * two halves separately gives what normalizing the whole string would ([Nfc.tailStart]). The re-cut parts don't
 * line up with the ones the tokenizer produced — they can be a little longer or shorter — which the splitter
 * doesn't mind, since it buffers parts and cuts fragments by its own size.
 *
 * Normalization can lengthen a string (U+0958 composes to two code points, not one) or shorten it, so the key and
 * string limits are enforced here, on the normalized form; the tokenizer in front checks only a bound that no text
 * within them can pass ([ParseLimits.maxRawKeyBytes], [ParseLimits.maxRawStringBytes]). So is the run of combining
 * marks ([Nfc.MAX_COMBINING_RUN]), after a check before normalizing that rejects only text whose normalized form would
 * exceed it. Two keys that differ before normalization can become one key, which is a duplicate key like any other
 * and is rejected by the splitter.
 */
internal class NfcTokenSource(private val delegate: JsonTokenSource, private val limits: ParseLimits) : JsonTokenSource {
    /** Normalized form of a whole key or string, when it isn't already normalized. */
    private val scratch = GrowableBytes(256)

    /** Bytes of a parted string that have arrived but can't be normalized until the next part shows the boundary. */
    private val carry = GrowableBytes(1024)

    /** The part handed on. */
    private val part = GrowableBytes(1024)

    /** Normalized bytes of the string in progress, for the limit that the tokenizer checked before normalizing. */
    private var stringBytes = 0L

    /** Length of the run of combining marks that ends the parts of the string in progress so far, as written. */
    private var rawRun = 0

    /** The same, in the normalized parts handed on. */
    private var run = 0

    /** Offset of the string in progress, reported for every one of its parts (the splitter reads it on the first). */
    private var stringOffset = 0L

    override var bytes: ByteArray = delegate.bytes
        private set
    override var offset: Int = 0
        private set
    override var length: Int = 0
        private set
    override var isLastPart: Boolean = false
        private set
    override var byteOffset: Long = 0
        private set

    override val depth: Int get() = delegate.depth

    override fun next(): Int {
        while (true) {
            when (val token = delegate.next()) {
                JsonToken.KEY -> {
                    whole(limits.maxKeyBytes.toLong(), "key")
                    return token
                }
                JsonToken.STRING -> {
                    stringBytes = 0
                    whole(limits.maxStringBytes, "string")
                    return token
                }
                JsonToken.STRING_PART -> if (part()) return JsonToken.STRING_PART
                else -> {
                    bytes = delegate.bytes
                    offset = delegate.offset
                    length = delegate.length
                    isLastPart = false
                    byteOffset = delegate.byteOffset
                    return token
                }
            }
        }
    }

    override fun close() = delegate.close()

    /** Drops what a rejected document left behind, so the next one starts clean. */
    fun reset() {
        carry.clear()
        stringBytes = 0
        rawRun = 0
        run = 0
    }

    /** Normalizes the current whole token, in place when it is already normalized. */
    private fun whole(max: Long, what: String) {
        val source = delegate.bytes
        val from = delegate.offset
        val count = delegate.length
        if (Nfc.isAscii(source, from, count)) {
            bytes = source
            offset = from
            length = count
        } else {
            if (Nfc.combiningRun(source, from, from + count, 0, Nfc.MAX_RAW_COMBINING_RUN) > Nfc.MAX_RAW_COMBINING_RUN) {
                tooManyMarks(delegate.byteOffset)
            }
            scratch.clear()
            Nfc.normalize(source, from, count, scratch)
            if (Nfc.combiningRun(scratch.array, 0, scratch.size, 0, Nfc.MAX_COMBINING_RUN) > Nfc.MAX_COMBINING_RUN) {
                tooManyMarks(delegate.byteOffset)
            }
            bytes = scratch.array
            offset = 0
            length = scratch.size
        }
        isLastPart = false
        byteOffset = delegate.byteOffset
        if (length.toLong() > max) tooLong(what, max)
    }

    /**
     * Takes the current part and returns whether one was handed on: a part that ends inside a combining sequence is
     * held back until the next one arrives.
     */
    private fun part(): Boolean {
        if (carry.size == 0) {
            stringOffset = delegate.byteOffset
            stringBytes = 0
            rawRun = 0
            run = 0
        }
        rawRun = Nfc.combiningRun(delegate.bytes, delegate.offset, delegate.offset + delegate.length, rawRun, Nfc.MAX_RAW_COMBINING_RUN)
        if (rawRun > Nfc.MAX_RAW_COMBINING_RUN) tooManyMarks(stringOffset)
        carry.writeBytes(delegate.bytes, delegate.offset, delegate.length)
        if (delegate.isLastPart) {
            emit(carry.size, last = true)
            carry.clear()
            return true
        }
        // The run is bounded, so a carry with no safe starter past its first code point is a few hundred bytes at most.
        val tail = Nfc.tailStart(carry.array, 0, carry.size)
        if (tail == 0) return false
        emit(tail, last = false)
        val rest = carry.size - tail
        carry.moveRange(tail, 0, rest)
        carry.truncate(rest)
        return true
    }

    /** Normalizes the first [count] carried bytes into the part handed on. */
    private fun emit(count: Int, last: Boolean) {
        part.clear()
        stringBytes += Nfc.normalize(carry.array, 0, count, part)
        run = Nfc.combiningRun(part.array, 0, part.size, run, Nfc.MAX_COMBINING_RUN)
        if (run > Nfc.MAX_COMBINING_RUN) tooManyMarks(stringOffset)
        bytes = part.array
        offset = 0
        length = part.size
        isLastPart = last
        byteOffset = stringOffset
        if (stringBytes > limits.maxStringBytes) tooLong("string", limits.maxStringBytes)
    }

    private fun tooManyMarks(at: Long): Nothing = throw DocumentRejectedException(
        RejectReason.LIMIT_EXCEEDED,
        at,
        detail = "more than ${Nfc.MAX_COMBINING_RUN} combining marks in a row",
    )

    private fun tooLong(what: String, max: Long): Nothing = throw DocumentRejectedException(
        RejectReason.LIMIT_EXCEEDED,
        byteOffset,
        detail = "$what longer than $max bytes after NFC normalization",
    )
}
