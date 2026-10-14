package app.oreshkov.delva.internal.compare

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.KeyTable
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PayloadTag

/**
 * Values of one fragment decoded in a single pass, so a comparison walks them in canonical order without
 * decoding a fragment again: per node its [PayloadReader] event, `pre`, key, and its children in canonical order —
 * object members by unsigned key bytes (the order `sortPerm` gives), array elements by index. Scalars and stubs
 * keep what comparison needs: an INT's value, a NUM's or STR's bytes, a stub's canonical hash.
 *
 * A tree covers either a whole SUBTREE or PART fragment ([buildFragment], node 0 is then a virtual root whose children
 * are the fragment's top-level values) or one value inside a fragment ([buildValue], node 0 is that value). A shallow
 * build leaves containers undecoded, and [detach] drops the fragment bytes keeping only keys: that's the per-PART index
 * of a split object's merge. Memory is proportional to the decoded bytes (≤ one fragment).
 *
 * Reusable through the build methods; not thread-safe.
 */
internal class ValueTree {
    var bytes: ByteArray = EMPTY
        private set
    var payloadOffset: Int = 0
        private set
    var payloadLength: Int = 0
        private set
    var source: String = ""
        private set

    /** Whether the fragment bytes were dropped by [detach]; nodes then only give keys and positions. */
    var detached: Boolean = false
        private set

    // Key table: key i is keyBytes[keyBase + keyOffsets[i], + keyLengths[i]); keyBytes is bytes unless detached. The
    // offset and length arrays are this tree's own, or a borrowed KeyTable's.
    private var keyBytes = EMPTY
    private var ownKeys = EMPTY
    private var keyCount = 0
    private var keyBase = 0
    private var ownKeyOffsets = IntArray(16)
    private var ownKeyLengths = IntArray(16)
    private var keyOffsets = ownKeyOffsets
    private var keyLengths = ownKeyLengths

    /** After a shallow [buildFragment]: the fragment's key table with ranks, for [buildValue] of its members. */
    var partKeys: KeyTable? = null
        private set

    var nodeCount: Int = 0
        private set
    private var kinds = ByteArray(INITIAL_NODES)
    private var containerKinds = ByteArray(INITIAL_NODES)
    private var offsets = IntArray(INITIAL_NODES)
    private var keyRefs = IntArray(INITIAL_NODES)
    private var pres = LongArray(INITIAL_NODES)
    private var sizes = LongArray(INITIAL_NODES)
    private var counts = LongArray(INITIAL_NODES)
    private var firsts = IntArray(INITIAL_NODES)
    private var auxA = LongArray(INITIAL_NODES)
    private var auxB = LongArray(INITIAL_NODES)
    private var children = IntArray(INITIAL_NODES)
    private var childrenUsed = 0

    /** After [buildFragment]: the `pre` after its last value; for a PART, its container kind and member count. */
    var endPre: Long = 0
        private set
    var partKind: Int = 0
        private set
    var partMemberCount: Long = 0
        private set

    /** Holders of this tree; a pool reuses it at zero. */
    var refs: Int = 0

    // Build scratch.
    private var stack = IntArray(16)
    private var stackBase = IntArray(16)
    private var stackObject = BooleanArray(16)
    private var depth = 0
    private var pending = IntArray(64)
    private var pendingTop = 0
    private var sortScratch = LongArray(64)

    /** Heap held: node arrays, and the fragment bytes unless detached (they may be shared with a cache). */
    val retainedBytes: Long
        get() = 54L * kinds.size + 4L * (children.size + pending.size) + 8L * (ownKeyOffsets.size + sortScratch.size) + (partKeys?.retainedBytes ?: 0) +
            (if (detached) ownKeys.size.toLong() else payloadLength.toLong()) + 256

    fun kind(node: Int): Int = kinds[node].toInt()

    /** REF and SPLIT: [PayloadTag.OBJ] or [PayloadTag.ARR]. */
    fun containerKind(node: Int): Int = containerKinds[node].toInt()

    /** Offset of the node's tag byte from the payload start. */
    fun offset(node: Int): Int = offsets[node]

    fun pre(node: Int): Long = pres[node]

    /** Containers and REF / SPLIT: descendants. BIGSTR: string bytes. */
    fun size(node: Int): Long = sizes[node]

    /** Containers and SPLIT: members. BIGSTR: chunks. The virtual root: top-level values. */
    fun count(node: Int): Long = counts[node]

    fun hasKey(node: Int): Boolean = keyRefs[node] >= 0

    fun keyBytes(): ByteArray = keyBytes

    fun keyOffset(node: Int): Int = keyBase + keyOffsets[keyRefs[node]]

    fun keyLength(node: Int): Int = keyLengths[keyRefs[node]]

    /** Whether the children of a container node were decoded (not in a shallow build). */
    fun isExpanded(node: Int): Boolean = firsts[node] >= 0

    /** Child [index] of [node] in canonical order. */
    fun child(node: Int, index: Int): Int = children[firsts[node] + index]

    /** INT value. */
    fun intValue(node: Int): Long = auxA[node]

    /** NUM text or STR bytes: `bytes[payloadOffset + textOffset, + textLength)`. */
    fun textOffset(node: Int): Int = payloadOffset + auxA[node].toInt()

    fun textLength(node: Int): Int = auxB[node].toInt()

    /** REF, SPLIT and BIGSTR: the canonical hash of the value stored elsewhere. */
    fun hashLow(node: Int): Long = auxA[node]

    fun hashHigh(node: Int): Long = auxB[node]

    /** Decodes a SUBTREE or PART fragment `bytes[payloadOffset, + payloadLength)` whose first value has `pre` [firstPre]. */
    fun buildFragment(reader: PayloadReader, bytes: ByteArray, payloadOffset: Int, payloadLength: Int, kind: Int, firstPre: Long, shallow: Boolean, source: String) {
        require(kind == FragmentKind.SUBTREE || kind == FragmentKind.PART)
        reader.open(bytes, payloadOffset, payloadLength, kind, firstPre, source)
        start(reader, bytes, payloadOffset, payloadLength, source)
        partKind = reader.partKind
        partMemberCount = reader.partMemberCount
        val root = addNode(PayloadReader.END, 0, -1, firstPre - 1)
        open(root, partKind == PayloadTag.OBJ)
        decode(reader, shallow)
        counts[root] = (childrenUsed - firsts[root]).toLong()
        partKeys = if (shallow) reader.captureKeyTable() else null
    }

    /**
     * Decodes the value at [valueOffset] (from the payload start) with `pre` [pre]; node 0 is that value. [keys], the
     * payload's [partKeys] from a shallow build, saves decoding its key table again.
     */
    fun buildValue(reader: PayloadReader, bytes: ByteArray, payloadOffset: Int, payloadLength: Int, valueOffset: Int, pre: Long, source: String, keys: KeyTable? = null) {
        reader.openValue(bytes, payloadOffset, payloadLength, payloadOffset + valueOffset, pre, source, keys)
        start(reader, bytes, payloadOffset, payloadLength, source, keys)
        partKind = 0
        partMemberCount = 0
        partKeys = null
        decode(reader, shallow = false)
        if (nodeCount == 0) throw CorruptionException(source, "no value at offset $valueOffset")
    }

    /** Keeps the keys and drops the reference to the fragment bytes; offsets and positions stay valid. */
    fun detach() {
        if (detached) return
        var total = 0
        for (i in 0 until keyCount) total += keyLengths[i]
        if (ownKeys.size < total) ownKeys = ByteArray(total)
        if (ownKeyOffsets.size < keyCount) {
            ownKeyOffsets = IntArray(keyCount)
            ownKeyLengths = IntArray(keyCount)
        }
        var p = 0
        for (i in 0 until keyCount) {
            val length = keyLengths[i]
            System.arraycopy(bytes, keyBase + keyOffsets[i], ownKeys, p, length)
            ownKeyOffsets[i] = p
            ownKeyLengths[i] = length
            p += length
        }
        keyOffsets = ownKeyOffsets
        keyLengths = ownKeyLengths
        keyBase = 0
        keyBytes = ownKeys
        bytes = EMPTY
        detached = true
    }

    private fun start(reader: PayloadReader, bytes: ByteArray, payloadOffset: Int, payloadLength: Int, source: String, keys: KeyTable? = null) {
        this.bytes = bytes
        this.payloadOffset = payloadOffset
        this.payloadLength = payloadLength
        this.source = source
        detached = false
        keyBytes = bytes
        keyBase = payloadOffset
        keyCount = reader.keyCount
        if (keys != null) {
            keyOffsets = keys.offsets
            keyLengths = keys.lengths
        } else {
            if (ownKeyOffsets.size < keyCount) {
                ownKeyOffsets = IntArray(keyCount)
                ownKeyLengths = IntArray(keyCount)
            }
            keyOffsets = ownKeyOffsets
            keyLengths = ownKeyLengths
            for (i in 0 until keyCount) {
                keyOffsets[i] = reader.keyOffsetOf(i) - payloadOffset
                keyLengths[i] = reader.keyLengthOf(i)
            }
        }
        nodeCount = 0
        childrenUsed = 0
        depth = 0
        pendingTop = 0
    }

    private fun decode(reader: PayloadReader, shallow: Boolean) {
        while (true) {
            when (val event = reader.next()) {
                PayloadReader.END -> {
                    if (depth > 0) close(reader)
                    endPre = reader.nextValuePre
                    return
                }
                PayloadReader.END_OBJECT, PayloadReader.END_ARRAY -> close(reader)
                else -> {
                    val node = addNode(event, reader.valueOffset - payloadOffset, if (reader.hasKey) reader.keyRef else -1, reader.pre)
                    if (depth > 0) addPending(node)
                    when (event) {
                        PayloadReader.INT -> auxA[node] = reader.intValue
                        PayloadReader.NUM, PayloadReader.STR -> {
                            auxA[node] = (reader.offset - payloadOffset).toLong()
                            auxB[node] = reader.length.toLong()
                        }
                        PayloadReader.START_OBJECT, PayloadReader.START_ARRAY -> {
                            sizes[node] = reader.size
                            counts[node] = reader.count
                            if (shallow) reader.skipValue() else open(node, event == PayloadReader.START_OBJECT)
                        }
                        PayloadReader.REF, PayloadReader.SPLIT, PayloadReader.BIGSTR -> {
                            sizes[node] = reader.size
                            counts[node] = if (event == PayloadReader.REF) 0 else reader.count
                            containerKinds[node] = reader.containerKind.toByte()
                            auxA[node] = reader.hashLow
                            auxB[node] = reader.hashHigh
                        }
                    }
                }
            }
        }
    }

    private fun addNode(event: Int, offset: Int, keyRef: Int, pre: Long): Int {
        if (nodeCount == kinds.size) growNodes()
        val n = nodeCount++
        kinds[n] = event.toByte()
        containerKinds[n] = 0
        offsets[n] = offset
        keyRefs[n] = keyRef
        pres[n] = pre
        sizes[n] = 0
        counts[n] = 0
        firsts[n] = -1
        auxA[n] = 0
        auxB[n] = 0
        return n
    }

    private fun addPending(node: Int) {
        if (pendingTop == pending.size) pending = pending.copyOf(pending.size * 2)
        pending[pendingTop++] = node
    }

    private fun open(node: Int, isObject: Boolean) {
        if (depth == stack.size) {
            stack = stack.copyOf(depth * 2)
            stackBase = stackBase.copyOf(depth * 2)
            stackObject = stackObject.copyOf(depth * 2)
        }
        stack[depth] = node
        stackBase[depth] = pendingTop
        stackObject[depth] = isObject
        depth++
    }

    /** Ends the innermost open container: its children, sorted by key for objects, move to [children]. */
    private fun close(reader: PayloadReader) {
        depth--
        val node = stack[depth]
        val base = stackBase[depth]
        val k = pendingTop - base
        if (stackObject[depth] && k >= 2) {
            if (sortScratch.size < k) sortScratch = LongArray(maxOf(k, sortScratch.size * 2))
            for (i in 0 until k) {
                val child = pending[base + i]
                sortScratch[i] = (reader.keyRank(keyRefs[child]).toLong() shl 32) or child.toLong()
            }
            sortScratch.sort(0, k)
            for (i in 0 until k) pending[base + i] = (sortScratch[i] and 0xFFFF_FFFFL).toInt()
        }
        if (childrenUsed + k > children.size) children = children.copyOf(maxOf(childrenUsed + k, children.size * 2))
        System.arraycopy(pending, base, children, childrenUsed, k)
        firsts[node] = childrenUsed
        childrenUsed += k
        pendingTop = base
    }

    private fun growNodes() {
        val n = kinds.size * 2
        kinds = kinds.copyOf(n)
        containerKinds = containerKinds.copyOf(n)
        offsets = offsets.copyOf(n)
        keyRefs = keyRefs.copyOf(n)
        pres = pres.copyOf(n)
        sizes = sizes.copyOf(n)
        counts = counts.copyOf(n)
        firsts = firsts.copyOf(n)
        auxA = auxA.copyOf(n)
        auxB = auxB.copyOf(n)
    }

    // ---- canonical hash of an expanded node ----

    private var hashNodes = IntArray(16)
    private var hashPositions = IntArray(16)
    private var hashFrames = LongArray(16 * CanonicalHash.FRAME_SLOTS)

    /** Canonical hash of [node] into `out[outIndex]` (low) and `out[outIndex + 1]` (high); stubs give their stored hash. */
    fun hash(node: Int, hasher: CanonicalHash, keyBuffer: GrowableBytes, out: LongArray, outIndex: Int) {
        if (!isContainer(kind(node))) {
            scalarHash(node, hasher, keyBuffer, out, outIndex)
            return
        }
        val value = LongArray(2)
        var sp = 0
        pushHash(sp++, node, hasher)
        while (true) {
            val top = sp - 1
            val n = hashNodes[top]
            val slot = top * CanonicalHash.FRAME_SLOTS
            val position = hashPositions[top]
            if (position < counts[n]) {
                hashPositions[top]++
                val child = child(n, position)
                if (isContainer(kind(child))) {
                    pushHash(sp++, child, hasher)
                    continue
                }
                scalarHash(child, hasher, keyBuffer, value, 0)
                addMember(n, slot, child, hasher, value)
                continue
            }
            if (kind(n) == PayloadReader.START_OBJECT) hasher.endObject(hashFrames, slot, value, 0) else hasher.endArray(hashFrames, slot, value, 0)
            sp--
            if (sp == 0) {
                out[outIndex] = value[0]
                out[outIndex + 1] = value[1]
                return
            }
            addMember(hashNodes[sp - 1], (sp - 1) * CanonicalHash.FRAME_SLOTS, n, hasher, value)
        }
    }

    private fun pushHash(level: Int, node: Int, hasher: CanonicalHash) {
        check(isExpanded(node)) { "hash of an undecoded container" }
        if (level == hashNodes.size) {
            hashNodes = hashNodes.copyOf(level * 2)
            hashPositions = hashPositions.copyOf(level * 2)
            hashFrames = hashFrames.copyOf(level * 2 * CanonicalHash.FRAME_SLOTS)
        }
        hashNodes[level] = node
        hashPositions[level] = 0
        val slot = level * CanonicalHash.FRAME_SLOTS
        if (kind(node) == PayloadReader.START_OBJECT) hasher.beginObject(hashFrames, slot) else hasher.beginArray(hashFrames, slot)
    }

    private fun addMember(parent: Int, slot: Int, child: Int, hasher: CanonicalHash, value: LongArray) {
        if (kind(parent) == PayloadReader.START_OBJECT) {
            hasher.objectMember(hashFrames, slot, keyBytes, keyOffset(child), keyLength(child), value, 0)
        } else {
            hasher.arrayElement(hashFrames, slot, value, 0)
        }
    }

    private fun scalarHash(node: Int, hasher: CanonicalHash, keyBuffer: GrowableBytes, out: LongArray, outIndex: Int) {
        when (kind(node)) {
            PayloadReader.NULL -> hasher.nullValue(out, outIndex)
            PayloadReader.FALSE -> hasher.booleanValue(false, out, outIndex)
            PayloadReader.TRUE -> hasher.booleanValue(true, out, outIndex)
            PayloadReader.INT, PayloadReader.NUM -> {
                val length = numberKey(node, keyBuffer)
                hasher.number(keyBuffer.array, 0, length, out, outIndex)
            }
            PayloadReader.STR -> hasher.string(bytes, textOffset(node), textLength(node), out, outIndex)
            PayloadReader.REF, PayloadReader.SPLIT, PayloadReader.BIGSTR -> {
                out[outIndex] = hashLow(node)
                out[outIndex + 1] = hashHigh(node)
            }
            else -> error("no scalar hash for event ${kind(node)}")
        }
    }

    /** Writes the NumberKey of an INT or NUM node into [out] from its start; returns its length. */
    fun numberKey(node: Int, out: GrowableBytes): Int {
        out.clear()
        if (kind(node) == PayloadReader.INT) return CanonicalNumber.writeKey(intValue(node), out)
        val length = CanonicalNumber.writeKey(bytes, textOffset(node), textLength(node), out)
        if (length < 0) throw CorruptionException(source, "invalid number at offset ${offset(node)}")
        return length
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val INITIAL_NODES = 64

        fun isContainer(event: Int): Boolean = event == PayloadReader.START_OBJECT || event == PayloadReader.START_ARRAY
    }
}
