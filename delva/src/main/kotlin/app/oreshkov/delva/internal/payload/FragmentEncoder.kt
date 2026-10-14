package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.internal.parse.JsonToken
import app.oreshkov.delva.internal.parse.JsonTokenSource
import java.util.Arrays

/**
 * The pending encoding: how values wait in the splitter's stack buffer before their fragment is known.
 * Scalars and stubs use their final payload form; containers differ:
 * ```
 * OBJ  count:u32 LE | size:u64 LE | { keyLen:varint key value }*count      keys inline, no sortPerm
 * ARR  count:u32 LE | size:u64 LE | { value }*count
 * ```
 * `count` and `size` are fixed-width slots, patched when the container closes. Pending bytes are the engine's own
 * and aren't validated.
 */
internal object PendingFormat {
    /** Tag byte plus the count and size slots. */
    const val CONTAINER_HEADER_BYTES: Int = 13

    fun count(pending: ByteArray, containerOffset: Int): Int = LittleEndian.getInt(pending, containerOffset + 1)

    fun size(pending: ByteArray, containerOffset: Int): Long = LittleEndian.getLong(pending, containerOffset + 5)

    /** Index after the scalar or stub that starts at [offset]. */
    fun scalarEnd(pending: ByteArray, offset: Int): Int = when (val tag = pending[offset].toInt() and 0xFF) {
        PayloadTag.NULL, PayloadTag.FALSE, PayloadTag.TRUE -> offset + 1
        PayloadTag.INT -> varintEnd(pending, offset + 1)
        PayloadTag.NUM, PayloadTag.STR -> {
            val lengthEnd = varintEnd(pending, offset + 1)
            lengthEnd + varint(pending, offset + 1).toInt()
        }
        PayloadTag.REF -> varintEnd(pending, varintEnd(pending, offset + 2) + 16)
        PayloadTag.SPLIT -> varintEnd(pending, varintEnd(pending, offset + 2)) + 16
        PayloadTag.BIGSTR -> varintEnd(pending, varintEnd(pending, offset + 1)) + 16 + PayloadFormat.BIGSTR_PREFIX_BYTES
        else -> error("not a scalar or stub: tag $tag at $offset")
    }

    fun varint(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        var shift = 0
        var p = offset
        while (true) {
            val b = bytes[p++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun varintEnd(bytes: ByteArray, offset: Int): Int {
        var p = offset
        while (bytes[p].toInt() and 0x80 != 0) p++
        return p + 1
    }
}

/**
 * Encodes a whole document from a token source into one pending value (no splitting). Numbers become INT when their
 * text is a canonical `Long`, NUM otherwise. String parts are joined, so the document must fit in memory: this
 * is the single-fragment path used by tests and small values; the splitter cuts large documents instead.
 */
internal class PendingEncoder {
    private var frameSlot = IntArray(16)
    private var frameCount = IntArray(16)
    private var frameStartPre = LongArray(16)
    private var depth = 0
    private val stringParts = GrowableBytes(256)
    private val longSlot = LongArray(1)

    /** Appends the document's pending value to [out]; returns the number of values (nodes) in it. */
    fun encode(source: JsonTokenSource, out: GrowableBytes): Long {
        depth = 0
        stringParts.clear()
        var nextPre = 0L
        while (true) {
            when (val token = source.next()) {
                JsonToken.EOF -> return nextPre
                JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                    countMember()
                    out.writeByte(if (token == JsonToken.START_OBJECT) PayloadTag.OBJ else PayloadTag.ARR)
                    push(out.reserve(PendingFormat.CONTAINER_HEADER_BYTES - 1), nextPre)
                    nextPre++
                }
                JsonToken.END_OBJECT, JsonToken.END_ARRAY -> {
                    depth--
                    out.putIntLE(frameSlot[depth], frameCount[depth])
                    out.putLongLE(frameSlot[depth] + 4, nextPre - frameStartPre[depth] - 1)
                }
                JsonToken.KEY -> PayloadWriter.key(out, source.bytes, source.offset, source.length)
                JsonToken.STRING -> {
                    countMember()
                    PayloadWriter.strValue(out, source.bytes, source.offset, source.length)
                    nextPre++
                }
                JsonToken.STRING_PART -> {
                    stringParts.writeBytes(source.bytes, source.offset, source.length)
                    if (source.isLastPart) {
                        countMember()
                        PayloadWriter.strValue(out, stringParts.array, 0, stringParts.size)
                        stringParts.clear()
                        nextPre++
                    }
                }
                JsonToken.NUMBER -> {
                    countMember()
                    if (CanonicalNumber.parseCanonicalLong(source.bytes, source.offset, source.length, longSlot, 0)) {
                        PayloadWriter.intValue(out, longSlot[0])
                    } else {
                        PayloadWriter.numValue(out, source.bytes, source.offset, source.length)
                    }
                    nextPre++
                }
                JsonToken.TRUE, JsonToken.FALSE -> {
                    countMember()
                    PayloadWriter.booleanValue(out, token == JsonToken.TRUE)
                    nextPre++
                }
                JsonToken.NULL -> {
                    countMember()
                    PayloadWriter.nullValue(out)
                    nextPre++
                }
                else -> error("unexpected token ${JsonToken.name(token)}")
            }
        }
    }

    private fun countMember() {
        if (depth > 0) {
            check(frameCount[depth - 1] != Int.MAX_VALUE) { "container too large for one pending buffer" }
            frameCount[depth - 1]++
        }
    }

    private fun push(slot: Int, pre: Long) {
        if (depth == frameSlot.size) {
            frameSlot = frameSlot.copyOf(depth * 2)
            frameCount = frameCount.copyOf(depth * 2)
            frameStartPre = frameStartPre.copyOf(depth * 2)
        }
        frameSlot[depth] = slot
        frameCount[depth] = 0
        frameStartPre[depth] = pre
        depth++
    }
}

/**
 * Re-encodes pending bytes into a final fragment payload ([PayloadFormat]): builds the fragment-local key table
 * (deduplicated, in first-use order), replaces inline keys by keyRefs, converts the count/size slots to varints and
 * adds each object's `sortPerm`. Two passes over at most one fragment's bytes; allocation-free once its buffers have
 * grown. Reusable; not thread-safe.
 */
internal class FragmentEncoder {
    // Key table: key i is pending[keyPos[i], keyPos[i] + keyLen[i]).
    private var keyPos = IntArray(64)
    private var keyLen = IntArray(64)
    private var keyCount = 0
    private var table = IntArray(128) // 0 = empty, else key index + 1
    private var ranks = IntArray(64)
    private var rankScratch = IntArray(128)

    /** keyRefs of every object's members, objects in pre-order; each object's run is reserved when it opens. */
    private var memberKeys = IntArray(256)
    private var memberKeysSize = 0

    private var frames = 0
    private var frameObject = BooleanArray(16)
    private var frameRemaining = IntArray(16)
    private var frameKeyBase = IntArray(16)
    private var frameMemberIndex = IntArray(16)

    private val body = GrowableBytes(1024)
    private var permScratch = LongArray(64)

    /** Appends the SUBTREE payload for the single pending value at `pending[from, to)` to [out]. */
    fun encodeValue(pending: ByteArray, from: Int, to: Int, out: GrowableBytes) {
        encode(pending, from, to, isObjectRun = false, memberCount = 1, part = false, out)
    }

    /**
     * Appends the PART payload for [memberCount] consecutive pending members at `pending[from, to)` of a
     * container of kind [containerKind]; object members are `keyLen key value` as in the pending encoding.
     */
    fun encodePart(containerKind: Int, pending: ByteArray, from: Int, to: Int, memberCount: Int, out: GrowableBytes) {
        require(containerKind == PayloadTag.OBJ || containerKind == PayloadTag.ARR)
        require(memberCount >= 1)
        encode(pending, from, to, containerKind == PayloadTag.OBJ, memberCount, part = true, out)
    }

    private fun encode(pending: ByteArray, from: Int, to: Int, isObjectRun: Boolean, memberCount: Int, part: Boolean, out: GrowableBytes) {
        collectKeys(pending, from, to, isObjectRun, memberCount)
        if (ranks.size < keyCount) ranks = IntArray(keyCount)
        if (rankScratch.size < 2 * keyCount) rankScratch = IntArray(2 * keyCount)
        check(KeyOrder.rank(pending, keyPos, keyLen, keyCount, ranks, rankScratch)) { "the key table can't repeat a key" }

        body.clear()
        var keyCursor = 0
        frames = 0
        val topBase = keyCursor
        if (isObjectRun) keyCursor += memberCount
        if (part) {
            PayloadWriter.partHeader(body, if (isObjectRun) PayloadTag.OBJ else PayloadTag.ARR, memberCount)
            if (isObjectRun && memberCount >= 2) writeSortPerm(topBase, memberCount)
        }
        push(isObjectRun, memberCount, topBase)
        var pos = from
        while (frames > 0) {
            val f = frames - 1
            if (frameRemaining[f] == 0) {
                frames--
                continue
            }
            frameRemaining[f]--
            if (frameObject[f]) {
                PayloadWriter.keyRef(body, memberKeys[frameKeyBase[f] + frameMemberIndex[f]])
                pos = PendingFormat.varintEnd(pending, pos).let { it + PendingFormat.varint(pending, pos).toInt() }
            }
            frameMemberIndex[f]++
            val tag = pending[pos].toInt() and 0xFF
            if (tag == PayloadTag.OBJ || tag == PayloadTag.ARR) {
                val count = PendingFormat.count(pending, pos)
                val size = PendingFormat.size(pending, pos)
                pos += PendingFormat.CONTAINER_HEADER_BYTES
                val base = keyCursor
                if (tag == PayloadTag.OBJ) {
                    keyCursor += count
                    PayloadWriter.objectHeader(body, count, size)
                    if (count >= 2) writeSortPerm(base, count)
                } else {
                    PayloadWriter.arrayHeader(body, count, size)
                }
                push(tag == PayloadTag.OBJ, count, base)
            } else {
                val end = PendingFormat.scalarEnd(pending, pos)
                body.writeBytes(pending, pos, end - pos)
                pos = end
            }
        }
        check(pos == to) { "pending range [$from, $to) holds more than the given members (stopped at $pos)" }

        PayloadWriter.keyTableCount(out, keyCount)
        for (k in 0 until keyCount) PayloadWriter.key(out, pending, keyPos[k], keyLen[k])
        out.writeBytes(body.array, 0, body.size)
    }

    /** Pass 1: interns every key and records each object's member keyRefs. */
    private fun collectKeys(pending: ByteArray, from: Int, to: Int, isObjectRun: Boolean, memberCount: Int) {
        keyCount = 0
        table.fill(0)
        memberKeysSize = 0
        frames = 0
        push(isObjectRun, memberCount, reserveKeys(if (isObjectRun) memberCount else 0))
        var pos = from
        while (frames > 0) {
            val f = frames - 1
            if (frameRemaining[f] == 0) {
                frames--
                continue
            }
            frameRemaining[f]--
            if (frameObject[f]) {
                val length = PendingFormat.varint(pending, pos).toInt()
                val start = PendingFormat.varintEnd(pending, pos)
                memberKeys[frameKeyBase[f] + frameMemberIndex[f]] = intern(pending, start, length)
                pos = start + length
            }
            frameMemberIndex[f]++
            val tag = pending[pos].toInt() and 0xFF
            if (tag == PayloadTag.OBJ || tag == PayloadTag.ARR) {
                val count = PendingFormat.count(pending, pos)
                pos += PendingFormat.CONTAINER_HEADER_BYTES
                push(tag == PayloadTag.OBJ, count, reserveKeys(if (tag == PayloadTag.OBJ) count else 0))
            } else {
                pos = PendingFormat.scalarEnd(pending, pos)
            }
        }
        check(pos == to) { "pending range [$from, $to) holds more than the given members (stopped at $pos)" }
    }

    private fun reserveKeys(count: Int): Int {
        val base = memberKeysSize
        val needed = base.toLong() + count
        check(needed <= Int.MAX_VALUE - 8)
        if (needed > memberKeys.size) memberKeys = memberKeys.copyOf(maxOf(needed.toInt(), memberKeys.size * 2))
        memberKeysSize = needed.toInt()
        return base
    }

    private fun intern(bytes: ByteArray, start: Int, length: Int): Int {
        if (2 * (keyCount + 1) > table.size) rehash(bytes, table.size * 2)
        val mask = table.size - 1
        var slot = Xxh3.hash64(bytes, start, length, 0L).toInt() and mask
        while (true) {
            val entry = table[slot]
            if (entry == 0) break
            val k = entry - 1
            if (keyLen[k] == length && Arrays.equals(bytes, keyPos[k], keyPos[k] + length, bytes, start, start + length)) return k
            slot = (slot + 1) and mask
        }
        if (keyCount == keyPos.size) {
            keyPos = keyPos.copyOf(keyCount * 2)
            keyLen = keyLen.copyOf(keyCount * 2)
        }
        keyPos[keyCount] = start
        keyLen[keyCount] = length
        table[slot] = ++keyCount
        return keyCount - 1
    }

    private fun rehash(bytes: ByteArray, newSize: Int) {
        table = IntArray(newSize)
        val mask = newSize - 1
        for (k in 0 until keyCount) {
            var slot = Xxh3.hash64(bytes, keyPos[k], keyLen[k], 0L).toInt() and mask
            while (table[slot] != 0) slot = (slot + 1) and mask
            table[slot] = k + 1
        }
    }

    /** `sortPerm` for the object whose member keyRefs start at `memberKeys[base]`. */
    private fun writeSortPerm(base: Int, count: Int) {
        if (permScratch.size < count) permScratch = LongArray(maxOf(count, permScratch.size * 2))
        for (i in 0 until count) permScratch[i] = (ranks[memberKeys[base + i]].toLong() shl 32) or i.toLong()
        permScratch.sort(0, count)
        // The key table holds each key once, so equal ranks are one key twice: the duplicate-key checker finds a
        // repeat of a spilled key only when its runs merge, which can be after both copies reach one fragment.
        for (i in 1 until count) if (permScratch[i] ushr 32 == permScratch[i - 1] ushr 32) throw RepeatedKeyException()
        for (i in 0 until count) PayloadWriter.sortPermEntry(body, (permScratch[i] and 0xFFFF_FFFFL).toInt())
    }

    /** An object of the encoded value or run has a key twice. */
    internal class RepeatedKeyException : RuntimeException(null, null, false, false)

    private fun push(isObject: Boolean, count: Int, keyBase: Int) {
        if (frames == frameObject.size) {
            val n = frames * 2
            frameObject = frameObject.copyOf(n)
            frameRemaining = frameRemaining.copyOf(n)
            frameKeyBase = frameKeyBase.copyOf(n)
            frameMemberIndex = frameMemberIndex.copyOf(n)
        }
        frameObject[frames] = isObject
        frameRemaining[frames] = count
        frameKeyBase[frames] = keyBase
        frameMemberIndex[frames] = 0
        frames++
    }
}
