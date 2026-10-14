package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.CorruptionException

/** Bytes of one stored fragment payload: `bytes[offset, offset + length)`. */
internal class PayloadSlice(val bytes: ByteArray, val offset: Int, val length: Int)

/** Finds the fragments of one document by key. */
internal fun interface FragmentLookup {
    /** The payload of fragment `(pre, kind, seq)`, or null if there's none. */
    fun find(pre: Long, kind: Int, seq: Int): PayloadSlice?
}

/**
 * Streams a stored document or value back as JSON events, resolving stubs by direct key lookups: REF → the SUBTREE
 * at the stub's `pre`; SPLIT → its HEADER, then PARTs from `pre + 1`, each starting where the previous one ended;
 * BIGSTR → its STRCHUNKs. Checks that every stub agrees with what it points to (kind, size, count, string bytes).
 *
 * Iterative, with one reader per level of stub nesting, so memory is O(depth) and deep documents don't recurse.
 * Reusable; not thread-safe.
 */
internal class Reassembler(private val lookup: FragmentLookup) {
    private val readers = ArrayList<PayloadReader>()
    private val header = HeaderReader()
    private val chunk = PayloadReader()

    // Stack of open levels: a reader over a SUBTREE or PART, or a split container.
    private var levels = 0
    private var isSplit = BooleanArray(16)
    private var expectKind = IntArray(16)
    private var expectSize = LongArray(16)
    private var expectCount = LongArray(16)
    private var splitPre = LongArray(16)
    private var splitNextPre = LongArray(16)
    private var splitMembers = LongArray(16)
    private var splitParts = LongArray(16)
    private var splitPartCount = LongArray(16)
    private var firstEvent = BooleanArray(16)

    /** Values written by the last call. */
    var valueCount: Long = 0
        private set

    /** Writes the whole document to [out]. */
    fun writeDocument(out: JsonEvents) {
        levels = 0
        valueCount = 0
        val root = lookup.find(0, FragmentKind.SUBTREE, 0)
        if (root != null) {
            pushReader(root, FragmentKind.SUBTREE, 0, expectKind = 0, expectSize = -1)
        } else {
            pushSplit(0, expectKind = 0, expectCount = -1, expectSize = -1, out)
        }
        run(out)
    }

    /**
     * Writes the value (inline, or a stub that is resolved) whose tag byte is at [valueOffset] in the SUBTREE or PART
     * payload [holder], with `pre` [pre].
     */
    fun writeValue(holder: PayloadSlice, valueOffset: Int, pre: Long, out: JsonEvents) {
        levels = 0
        valueCount = 0
        val level = push()
        while (readers.size <= level) readers += PayloadReader()
        readers[level].openValue(holder.bytes, holder.offset, holder.length, holder.offset + valueOffset, pre, "fragment holding pre $pre")
        isSplit[level] = false
        expectKind[level] = 0
        expectSize[level] = -1
        firstEvent[level] = true
        run(out)
    }

    /** Writes the split container at [pre] (its HEADER and PARTs). */
    fun writeSplit(pre: Long, out: JsonEvents) {
        levels = 0
        valueCount = 0
        pushSplit(pre, expectKind = 0, expectCount = -1, expectSize = -1, out)
        run(out)
    }

    private fun run(out: JsonEvents) {
        while (levels > 0) {
            if (out.isDone) return
            val level = levels - 1
            if (isSplit[level]) {
                continueSplit(level, out)
                continue
            }
            val reader = readers[level]
            val event = reader.next()
            if (firstEvent[level] && event != PayloadReader.END) {
                firstEvent[level] = false
                checkSubtreeRoot(level, reader, event)
            }
            if (reader.hasKey) out.key(reader.payload, reader.keyOffset, reader.keyLength)
            val payload = reader.payload
            when (event) {
                PayloadReader.END -> {
                    levels--
                    if (levels > 0 && isSplit[levels - 1]) {
                        splitMembers[levels - 1] += reader.partMemberCount
                        splitNextPre[levels - 1] = reader.nextValuePre
                    }
                    continue
                }
                PayloadReader.NULL -> out.nullValue()
                PayloadReader.FALSE -> out.booleanValue(false)
                PayloadReader.TRUE -> out.booleanValue(true)
                PayloadReader.INT -> out.number(reader.intValue)
                PayloadReader.NUM -> out.number(payload, reader.offset, reader.length)
                PayloadReader.STR -> out.string(payload, reader.offset, reader.length)
                PayloadReader.START_OBJECT -> out.beginObject()
                PayloadReader.END_OBJECT -> out.endObject()
                PayloadReader.START_ARRAY -> out.beginArray()
                PayloadReader.END_ARRAY -> out.endArray()
                PayloadReader.REF -> {
                    val pre = reader.pre
                    val target = lookup.find(pre, FragmentKind.SUBTREE, 0) ?: missing(pre, "SUBTREE")
                    pushReader(target, FragmentKind.SUBTREE, pre, reader.containerKind, reader.size)
                    continue
                }
                PayloadReader.SPLIT -> {
                    pushSplit(reader.pre, reader.containerKind, reader.count, reader.size, out)
                    continue
                }
                PayloadReader.BIGSTR -> writeBigString(reader, out)
            }
            if (event != PayloadReader.END_OBJECT && event != PayloadReader.END_ARRAY) valueCount++
        }
    }

    private fun checkSubtreeRoot(level: Int, reader: PayloadReader, event: Int) {
        val kind = expectKind[level]
        if (kind == 0) return
        val actual = when (event) {
            PayloadReader.START_OBJECT -> PayloadTag.OBJ
            PayloadReader.START_ARRAY -> PayloadTag.ARR
            else -> -1
        }
        if (actual != kind || reader.size != expectSize[level]) {
            throw CorruptionException("fragment (${reader.pre}, SUBTREE)", "REF expects kind $kind size ${expectSize[level]}, found event $event size ${reader.size}")
        }
    }

    private fun writeBigString(stub: PayloadReader, out: JsonEvents) {
        val pre = stub.pre
        out.beginString()
        var total = 0L
        for (seq in 0 until stub.count) {
            if (seq > Int.MAX_VALUE) throw CorruptionException("fragment ($pre, BIGSTR)", "chunk count ${stub.count}")
            val slice = lookup.find(pre, FragmentKind.STRCHUNK, seq.toInt()) ?: missing(pre, "STRCHUNK $seq")
            chunk.open(slice.bytes, slice.offset, slice.length, FragmentKind.STRCHUNK, pre)
            if (chunk.next() != PayloadReader.STR) throw CorruptionException("fragment ($pre, STRCHUNK, $seq)", "no string")
            if (seq == 0L && (chunk.length < PayloadFormat.BIGSTR_PREFIX_BYTES ||
                    !java.util.Arrays.equals(chunk.payload, chunk.offset, chunk.offset + PayloadFormat.BIGSTR_PREFIX_BYTES, stub.payload, stub.offset, stub.offset + PayloadFormat.BIGSTR_PREFIX_BYTES))
            ) {
                throw CorruptionException("fragment ($pre, STRCHUNK, 0)", "doesn't start with the BIGSTR prefix")
            }
            out.stringPart(chunk.payload, chunk.offset, chunk.length)
            if (out.isDone) return
            total += chunk.length
            if (chunk.next() != PayloadReader.END) throw CorruptionException("fragment ($pre, STRCHUNK, $seq)", "more than one string")
        }
        if (total != stub.size) throw CorruptionException("fragment ($pre, BIGSTR)", "chunks hold $total bytes, stub says ${stub.size}")
        out.endString()
    }

    private fun pushSplit(pre: Long, expectKind: Int, expectCount: Long, expectSize: Long, out: JsonEvents) {
        val slice = lookup.find(pre, FragmentKind.HEADER, 0) ?: missing(pre, "HEADER")
        header.open(slice.bytes, slice.offset, slice.length, 0, "fragment ($pre, HEADER, 0)")
        if ((expectKind != 0 && header.containerKind != expectKind) || (expectCount >= 0 && header.count != expectCount) || (expectSize >= 0 && header.size != expectSize)) {
            throw CorruptionException("fragment ($pre, HEADER, 0)", "SPLIT stub disagrees with the header")
        }
        val level = push()
        isSplit[level] = true
        this.expectKind[level] = header.containerKind
        this.expectCount[level] = header.count
        this.expectSize[level] = header.size
        splitPre[level] = pre
        splitNextPre[level] = pre + 1
        splitMembers[level] = 0
        splitParts[level] = 0
        splitPartCount[level] = header.partCount
        if (header.containerKind == PayloadTag.OBJ) out.beginObject() else out.beginArray()
        valueCount++
    }

    private fun continueSplit(level: Int, out: JsonEvents) {
        val pre = splitPre[level]
        val end = pre + expectSize[level]
        val next = splitNextPre[level]
        if (next <= end) {
            val slice = lookup.find(next, FragmentKind.PART, 0) ?: missing(next, "PART of the container at $pre")
            val reader = pushReader(slice, FragmentKind.PART, next, 0, -1)
            if (reader.partKind != expectKind[level]) throw CorruptionException("fragment ($next, PART)", "PART kind ${reader.partKind} in a container of kind ${expectKind[level]}")
            splitParts[level]++
            return
        }
        if (next != end + 1 || splitMembers[level] != expectCount[level] || splitParts[level] != splitPartCount[level]) {
            throw CorruptionException(
                "fragment ($pre, HEADER, 0)",
                "parts cover pre ${pre + 1}..${next - 1} with ${splitMembers[level]} members in ${splitParts[level]} parts; header says size ${expectSize[level]}, count ${expectCount[level]}, ${splitPartCount[level]} parts",
            )
        }
        levels--
        if (expectKind[level] == PayloadTag.OBJ) out.endObject() else out.endArray()
    }

    private fun pushReader(slice: PayloadSlice, kind: Int, pre: Long, expectKind: Int, expectSize: Long): PayloadReader {
        val level = push()
        while (readers.size <= level) readers += PayloadReader()
        val reader = readers[level]
        reader.open(slice.bytes, slice.offset, slice.length, kind, pre, "fragment ($pre, $kind)")
        isSplit[level] = false
        this.expectKind[level] = expectKind
        this.expectSize[level] = expectSize
        firstEvent[level] = true
        return reader
    }

    private fun push(): Int {
        if (levels == isSplit.size) {
            val n = levels * 2
            isSplit = isSplit.copyOf(n)
            expectKind = expectKind.copyOf(n)
            expectSize = expectSize.copyOf(n)
            expectCount = expectCount.copyOf(n)
            splitPre = splitPre.copyOf(n)
            splitNextPre = splitNextPre.copyOf(n)
            splitMembers = splitMembers.copyOf(n)
            splitParts = splitParts.copyOf(n)
            splitPartCount = splitPartCount.copyOf(n)
            firstEvent = firstEvent.copyOf(n)
        }
        return levels++
    }

    private fun missing(pre: Long, what: String): Nothing = throw CorruptionException("document at pre $pre", "missing $what fragment")
}
