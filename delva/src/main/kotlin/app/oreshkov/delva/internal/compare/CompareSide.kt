package app.oreshkov.delva.internal.compare

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.HeaderReader
import app.oreshkov.delva.internal.payload.JsonEvents
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PayloadSlice
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.payload.Reassembler
import app.oreshkov.delva.internal.store.DocumentFragments
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.types.TypeIndexView
import java.util.Arrays

/**
 * One operand of a comparison: the fragments of one document (stored, or a value split for the comparison) and where
 * the compared value is. Builds [ValueTree]s of its fragments, pooling them, and opens [ChildCursor]s over containers.
 * Checks that stubs agree with what they point to, like the [Reassembler].
 *
 * [partCacheBytes] bounds the per-PART indexes a split object's merge keeps decoded. Not thread-safe.
 */
internal class CompareSide(
    val store: FragmentStore,
    val docId: Long,
    /** A stored document (with node references), or a value given as JSON. */
    val stored: Boolean,
    val partCacheBytes: Long,
    private val types: TypeIndexView?,
    val stats: CompareStats,
) {
    private val view = FragmentView()
    private val reader = PayloadReader()
    private val header = HeaderReader()
    private val lookup = DocumentFragments(store).also { it.docId = docId }
    private val reassembler = Reassembler(lookup)
    private val pool = ArrayList<ValueTree>()

    /** Whether objects of this side may have types; false for values given as JSON. */
    val mayHaveTypes: Boolean = types != null && stored && types.mayHaveTypes(docId)

    // The compared value: inline in a holder fragment, or a split container.
    private var rootSplit = false
    private var rootPre = 0L
    private var rootHolderPre = 0L
    private var rootHolderKind = 0
    private var rootValueOffset = 0

    /** `pre` of the compared value. */
    val valuePre: Long get() = rootPre

    /** A canonical hash of the compared value known without reading it (a value split for the comparison). */
    var rootHashKnown: Boolean = false
        private set
    var rootHashLow: Long = 0
        private set
    var rootHashHigh: Long = 0
        private set

    fun setInlineRoot(pre: Long, holderPre: Long, holderKind: Int, valueOffset: Int) {
        rootSplit = false
        rootPre = pre
        rootHolderPre = holderPre
        rootHolderKind = holderKind
        rootValueOffset = valueOffset
    }

    fun setSplitRoot(pre: Long) {
        rootSplit = true
        rootPre = pre
    }

    /** The whole document of a value split for comparison: its root SUBTREE, or its split root. */
    fun setDocumentRoot(hashLow: Long, hashHigh: Long) {
        if (store.find(docId, 0, FragmentKind.SUBTREE, 0, view)) setInlineRoot(0, 0, FragmentKind.SUBTREE, rootOffset()) else setSplitRoot(0)
        rootHashKnown = true
        rootHashLow = hashLow
        rootHashHigh = hashHigh
    }

    /** Offset of the value in the SUBTREE fragment in [view]: after its key table. */
    private fun rootOffset(): Int {
        reader.open(view.bytes, view.payloadOffset, view.payloadLength, FragmentKind.SUBTREE, 0, source(0, FragmentKind.SUBTREE))
        reader.next()
        return reader.valueOffset - view.payloadOffset
    }

    fun root(into: ValueRef) {
        if (rootSplit) {
            val info = splitInfo(rootPre, 0, -1, -1)
            into.setSplit(this, rootPre, info)
            return
        }
        val tree = valueTree(rootHolderPre, rootHolderKind, rootValueOffset, rootPre)
        try {
            into.setNode(this, tree, 0)
        } finally {
            release(tree)
        }
    }

    // ---- trees ----------------------------------------------------------------------------------------------------

    private fun take(): ValueTree {
        stats.treesBuilt.increment()
        return (if (pool.isEmpty()) ValueTree() else pool.removeAt(pool.size - 1)).also { it.refs = 1 }
    }

    fun retain(tree: ValueTree) {
        tree.refs++
    }

    fun release(tree: ValueTree) {
        check(tree.refs > 0)
        if (--tree.refs == 0 && pool.size < MAX_POOLED_TREES && tree.retainedBytes <= MAX_POOLED_TREE_BYTES) pool += tree
    }

    private fun fetch(pre: Long, kind: Int, seq: Int, what: String): FragmentView {
        if (!store.find(docId, pre, kind, seq, view)) throw CorruptionException("document $docId at pre $pre", "missing $what fragment")
        return view
    }

    private fun source(pre: Long, kind: Int) = "fragment ($docId, $pre, $kind)"

    /** The value at [valueOffset] of fragment `(holderPre, holderKind)`, with `pre` [pre]; release it when done. */
    fun valueTree(holderPre: Long, holderKind: Int, valueOffset: Int, pre: Long): ValueTree {
        val v = fetch(holderPre, holderKind, 0, "holder")
        val tree = take()
        try {
            tree.buildValue(reader, v.bytes, v.payloadOffset, v.payloadLength, valueOffset, pre, source(holderPre, holderKind))
        } catch (t: Throwable) {
            release(tree)
            throw t
        }
        return tree
    }

    /** Member [child] of [index], the shallow tree of PART [part] of [info], decoded with the key table [index] keeps. */
    fun memberTree(info: SplitInfo, part: Int, index: ValueTree, child: Int): ValueTree {
        val pre = info.firstPres[part]
        val v = fetch(pre, FragmentKind.PART, 0, "PART of the container at ${info.pre}")
        val tree = take()
        try {
            tree.buildValue(reader, v.bytes, v.payloadOffset, v.payloadLength, index.offset(child), index.pre(child), source(pre, FragmentKind.PART), index.partKeys)
        } catch (t: Throwable) {
            release(tree)
            throw t
        }
        return tree
    }

    /** The SUBTREE fragment a REF stub at [pre] points to; its root must be a container of [kind] with [size] descendants. */
    fun subtreeTree(pre: Long, kind: Int, size: Long): ValueTree {
        val v = fetch(pre, FragmentKind.SUBTREE, 0, "SUBTREE")
        val tree = take()
        try {
            tree.buildFragment(reader, v.bytes, v.payloadOffset, v.payloadLength, FragmentKind.SUBTREE, pre, shallow = false, source(pre, FragmentKind.SUBTREE))
            val root = tree.child(0, 0)
            val actual = when (tree.kind(root)) {
                PayloadReader.START_OBJECT -> PayloadTag.OBJ
                PayloadReader.START_ARRAY -> PayloadTag.ARR
                else -> -1
            }
            if (actual != kind || tree.size(root) != size) {
                throw CorruptionException(source(pre, FragmentKind.SUBTREE), "REF expects kind $kind size $size, found event ${tree.kind(root)} size ${tree.size(root)}")
            }
        } catch (t: Throwable) {
            release(tree)
            throw t
        }
        return tree
    }

    /** PART [part] of the split container [info]; checked against the directory. Release it when done. */
    fun partTree(info: SplitInfo, part: Int, shallow: Boolean): ValueTree {
        val pre = info.firstPres[part]
        val v = fetch(pre, FragmentKind.PART, 0, "PART of the container at ${info.pre}")
        val tree = take()
        try {
            tree.buildFragment(reader, v.bytes, v.payloadOffset, v.payloadLength, FragmentKind.PART, pre, shallow, source(pre, FragmentKind.PART))
            val end = if (part + 1 < info.partCount) info.firstPres[part + 1] else info.pre + info.size + 1
            if (tree.partKind != info.kind || tree.partMemberCount != info.memberCounts[part] || tree.endPre != end) {
                throw CorruptionException(
                    source(pre, FragmentKind.PART),
                    "PART of kind ${tree.partKind} with ${tree.partMemberCount} members ending before pre ${tree.endPre}; directory says kind ${info.kind}, ${info.memberCounts[part]} members, next part at $end",
                )
            }
        } catch (t: Throwable) {
            release(tree)
            throw t
        }
        return tree
    }

    /**
     * The HEADER and part directory of the split container at [pre]. [kind], [count] and [size] are what a SPLIT
     * stub says (0 / -1 when there's no stub).
     */
    fun splitInfo(pre: Long, kind: Int, count: Long, size: Long): SplitInfo {
        val location = "fragment ($docId, $pre, HEADER, 0)"
        var v = fetch(pre, FragmentKind.HEADER, 0, "HEADER")
        header.open(v.bytes, v.payloadOffset, v.payloadLength, 0, location)
        if ((kind != 0 && header.containerKind != kind) || (count >= 0 && header.count != count) || (size >= 0 && header.size != size)) {
            throw CorruptionException(location, "SPLIT stub disagrees with the header")
        }
        if (header.partCount > Int.MAX_VALUE) throw CorruptionException(location, "part count ${header.partCount}")
        val parts = header.partCount.toInt()
        val info = SplitInfo(pre, header.containerKind, header.count, header.size, header.hashLow, header.hashHigh, parts)
        var n = 0
        var members = 0L
        var seq = 0
        while (true) {
            while (header.nextEntry()) {
                if (n == parts) throw CorruptionException(location, "more directory entries than its $parts parts")
                val expectedPre = if (n == 0) pre + 1 else info.firstPres[n - 1] + 1
                if ((n == 0 && header.firstPre != pre + 1) || header.firstPre < expectedPre || header.firstIndex != members) {
                    throw CorruptionException(location, "directory entry $n at pre ${header.firstPre}, index ${header.firstIndex}")
                }
                info.ensure(n + 1)
                info.firstPres[n] = header.firstPre
                info.memberCounts[n] = header.memberCount
                members += header.memberCount
                n++
            }
            if (n == parts) break
            seq++
            v = fetch(pre, FragmentKind.HEADER, seq, "HEADER $seq")
            header.open(v.bytes, v.payloadOffset, v.payloadLength, seq, "fragment ($docId, $pre, HEADER, $seq)")
        }
        if (members != info.count) throw CorruptionException(location, "directory holds $members members, header says ${info.count}")
        return info
    }

    // ---- cursors ----------------------------------------------------------------------------------------------------

    /** Children of the container [value] in canonical order. */
    fun openCursor(value: ValueRef): ChildCursor = when (value.event) {
        PayloadReader.START_OBJECT, PayloadReader.START_ARRAY -> TreeCursor(this, value.tree!!, value.node)
        PayloadReader.REF -> {
            val tree = subtreeTree(value.pre, if (value.jsonType == JsonType.OBJECT) PayloadTag.OBJ else PayloadTag.ARR, value.size)
            try {
                TreeCursor(this, tree, tree.child(0, 0))
            } finally {
                release(tree)
            }
        }
        PayloadReader.SPLIT -> {
            val info = value.split ?: splitInfo(value.pre, if (value.jsonType == JsonType.OBJECT) PayloadTag.OBJ else PayloadTag.ARR, value.count, value.size)
            if (info.kind == PayloadTag.OBJ) SplitObjectCursor(this, info) else SplitArrayCursor(this, info)
        }
        else -> error("not a container: event ${value.event}")
    }

    // ---- strings ----------------------------------------------------------------------------------------------------

    private val chunkView = FragmentView()
    private val chunkReader = PayloadReader()

    /** Sets [into] to the bytes of chunk [seq] of the BIGSTR at [pre]. */
    fun chunk(pre: Long, seq: Int, into: StringStream) {
        if (!store.find(docId, pre, FragmentKind.STRCHUNK, seq, chunkView)) throw CorruptionException("document $docId at pre $pre", "missing STRCHUNK $seq fragment")
        chunkReader.open(chunkView.bytes, chunkView.payloadOffset, chunkView.payloadLength, FragmentKind.STRCHUNK, pre, "fragment ($docId, $pre, STRCHUNK, $seq)")
        if (chunkReader.next() != PayloadReader.STR || chunkReader.length == 0) throw CorruptionException("fragment ($docId, $pre, STRCHUNK, $seq)", "no string")
        into.bytes = chunkReader.payload
        into.position = chunkReader.offset
        into.end = chunkReader.offset + chunkReader.length
        if (chunkReader.next() != PayloadReader.END) throw CorruptionException("fragment ($docId, $pre, STRCHUNK, $seq)", "more than one string")
    }

    // ---- output and types -------------------------------------------------------------------------------------------

    /** Streams [value] as JSON to [out]. */
    fun writeJson(value: ValueRef, out: JsonEvents) {
        val tree = value.tree
        if (tree == null) {
            reassembler.writeSplit(value.pre, out)
        } else {
            reassembler.writeValue(PayloadSlice(tree.bytes, tree.payloadOffset, tree.payloadLength), tree.offset(value.node), value.pre, out)
        }
    }

    /** The typeId of the object at [pre] (0 = none). */
    fun typeId(pre: Long): Int = if (mayHaveTypes) types!!.typeIdOf(docId, pre) else 0

    private companion object {
        const val MAX_POOLED_TREES = 16
        const val MAX_POOLED_TREE_BYTES = 1L shl 20
    }
}

/** A split container's HEADER summary and part directory. */
internal class SplitInfo(val pre: Long, val kind: Int, val count: Long, val size: Long, val hashLow: Long, val hashHigh: Long, val partCount: Int) {
    // Only as long as the directory entries read so far: [partCount] is what the HEADER claims, not what it holds.
    var firstPres = LongArray(minOf(partCount, 16))
        private set
    var memberCounts = LongArray(minOf(partCount, 16))
        private set

    /** Makes room for the first [n] parts, [n] ≤ [partCount]. */
    fun ensure(n: Int) {
        if (n <= firstPres.size) return
        val size = maxOf(n.toLong(), 2L * firstPres.size).coerceAtMost(partCount.toLong()).toInt()
        firstPres = firstPres.copyOf(size)
        memberCounts = memberCounts.copyOf(size)
    }
}

/** JSON value types (SPEC.md §2). */
internal object JsonType {
    const val NULL = 0
    const val BOOLEAN = 1
    const val NUMBER = 2
    const val STRING = 3
    const val OBJECT = 4
    const val ARRAY = 5
}

/**
 * A value taken from a [CompareSide]: a node of a [ValueTree] (which it holds until [release]) or a split container
 * without a stub (a split root). Reusable.
 */
internal class ValueRef {
    var side: CompareSide? = null
        private set
    var tree: ValueTree? = null
        private set
    var node: Int = 0
        private set
    var split: SplitInfo? = null
        private set

    var jsonType: Int = JsonType.NULL
        private set

    /** The [PayloadReader] event of the node; SPLIT for a split root. */
    var event: Int = PayloadReader.END
        private set
    var pre: Long = 0
        private set

    /** Members of a container, -1 when a REF stub doesn't say; chunks of a BIGSTR. */
    var count: Long = -1
        private set

    /** Descendants of a container; bytes of a BIGSTR. */
    var size: Long = 0
        private set
    var hasHash: Boolean = false
        private set
    var hashLow: Long = 0
        private set
    var hashHigh: Long = 0
        private set

    fun setNode(side: CompareSide, tree: ValueTree, node: Int) {
        release()
        side.retain(tree)
        this.side = side
        this.tree = tree
        this.node = node
        split = null
        event = tree.kind(node)
        pre = tree.pre(node)
        count = -1
        size = 0
        hasHash = false
        when (event) {
            PayloadReader.NULL -> jsonType = JsonType.NULL
            PayloadReader.FALSE, PayloadReader.TRUE -> jsonType = JsonType.BOOLEAN
            PayloadReader.INT, PayloadReader.NUM -> jsonType = JsonType.NUMBER
            PayloadReader.STR -> {
                jsonType = JsonType.STRING
                size = tree.textLength(node).toLong()
            }
            PayloadReader.BIGSTR -> {
                jsonType = JsonType.STRING
                size = tree.size(node)
                count = tree.count(node)
                hash(tree.hashLow(node), tree.hashHigh(node))
            }
            PayloadReader.START_OBJECT, PayloadReader.START_ARRAY -> {
                jsonType = if (event == PayloadReader.START_OBJECT) JsonType.OBJECT else JsonType.ARRAY
                count = tree.count(node)
                size = tree.size(node)
            }
            PayloadReader.REF, PayloadReader.SPLIT -> {
                jsonType = if (tree.containerKind(node) == PayloadTag.OBJ) JsonType.OBJECT else JsonType.ARRAY
                size = tree.size(node)
                if (event == PayloadReader.SPLIT) count = tree.count(node)
                hash(tree.hashLow(node), tree.hashHigh(node))
            }
            else -> error("no value at node $node (event $event)")
        }
    }

    fun setSplit(side: CompareSide, pre: Long, info: SplitInfo) {
        release()
        this.side = side
        split = info
        event = PayloadReader.SPLIT
        this.pre = pre
        jsonType = if (info.kind == PayloadTag.OBJ) JsonType.OBJECT else JsonType.ARRAY
        count = info.count
        size = info.size
        hash(info.hashLow, info.hashHigh)
    }

    private fun hash(low: Long, high: Long) {
        hasHash = true
        hashLow = low
        hashHigh = high
    }

    fun release() {
        val t = tree
        if (t != null) side!!.release(t)
        tree = null
        split = null
        side = null
    }
}

/**
 * The children of one container in canonical order: object members by unsigned key bytes, array elements by
 * index. The key is valid until [next]; [value] gives the current child.
 */
internal interface ChildCursor {
    val valid: Boolean
    val keyBytes: ByteArray
    val keyOffset: Int
    val keyLength: Int

    fun value(into: ValueRef)

    fun next()

    fun close()
}

/** The children of an inline container node. */
internal class TreeCursor(private val side: CompareSide, private val tree: ValueTree, private val node: Int) : ChildCursor {
    private val count = tree.count(node).toInt()
    private var position = 0
    private var closed = false

    init {
        side.retain(tree)
    }

    override val valid: Boolean get() = position < count
    override val keyBytes: ByteArray get() = tree.keyBytes()
    override val keyOffset: Int get() = tree.keyOffset(tree.child(node, position))
    override val keyLength: Int get() = tree.keyLength(tree.child(node, position))

    override fun value(into: ValueRef) = into.setNode(side, tree, tree.child(node, position))

    override fun next() {
        position++
    }

    override fun close() {
        if (closed) return
        closed = true
        side.release(tree)
    }
}

/** The elements of a split array, one PART at a time. */
internal class SplitArrayCursor(private val side: CompareSide, private val info: SplitInfo) : ChildCursor {
    private var part = -1
    private var tree: ValueTree? = null
    private var position = 0

    init {
        load(0)
    }

    override val valid: Boolean get() = tree != null
    override val keyBytes: ByteArray get() = throw UnsupportedOperationException()
    override val keyOffset: Int get() = throw UnsupportedOperationException()
    override val keyLength: Int get() = throw UnsupportedOperationException()

    private fun load(next: Int) {
        tree?.let { side.release(it) }
        tree = null
        part = next
        position = 0
        if (next < info.partCount) tree = side.partTree(info, next, shallow = false)
    }

    override fun value(into: ValueRef) {
        val t = tree!!
        into.setNode(side, t, t.child(0, position))
    }

    override fun next() {
        val t = tree ?: return
        if (++position == t.count(0).toInt()) load(part + 1)
    }

    override fun close() {
        load(info.partCount)
    }
}

/**
 * The members of a split object in key order: a k-way merge over its PARTs, each sorted by its `sortPerm`. The
 * merge keeps each part's position and current key; the per-part indexes (shallow, detached [ValueTree]s) live in an
 * LRU bounded by [CompareSide.partCacheBytes] and are rebuilt from their fragment when needed again. A member's value
 * is decoded from its PART when it's compared. Two parts holding one key are corruption.
 */
internal class SplitObjectCursor(private val side: CompareSide, private val info: SplitInfo) : ChildCursor {
    private val parts = info.partCount
    private val positions = IntArray(parts)
    private val keys = arrayOfNulls<ByteArray>(parts)
    private val keyLengths = IntArray(parts)
    private val heap = IntArray(parts)
    private var heapSize = 0
    private val cache = LinkedHashMap<Int, ValueTree>(16, 0.75f, true)
    private var cachedBytes = 0L
    private var last = ByteArray(64)
    private var lastLength = 0

    init {
        try {
            for (p in 0 until parts) {
                loadKey(p)
                push(p)
            }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override val valid: Boolean get() = heapSize > 0
    override val keyBytes: ByteArray get() = keys[heap[0]]!!
    override val keyOffset: Int get() = 0
    override val keyLength: Int get() = keyLengths[heap[0]]

    private fun index(part: Int): ValueTree {
        cache[part]?.let { return it }
        side.stats.partIndexesBuilt.increment()
        val tree = side.partTree(info, part, shallow = true)
        tree.detach()
        val bytes = tree.retainedBytes
        cache[part] = tree
        cachedBytes += bytes
        val iterator = cache.entries.iterator()
        while (cachedBytes > side.partCacheBytes && cache.size > 1) {
            val eldest = iterator.next()
            if (eldest.key == part) continue
            cachedBytes -= eldest.value.retainedBytes
            side.release(eldest.value)
            iterator.remove()
        }
        return tree
    }

    private fun loadKey(part: Int) {
        val tree = index(part)
        val child = tree.child(0, positions[part])
        val length = tree.keyLength(child)
        var key = keys[part]
        if (key == null || key.size < length) {
            key = ByteArray(maxOf(length, 16))
            keys[part] = key
        }
        System.arraycopy(tree.keyBytes(), tree.keyOffset(child), key, 0, length)
        keyLengths[part] = length
    }

    override fun value(into: ValueRef) {
        val part = heap[0]
        val index = index(part)
        val child = index.child(0, positions[part])
        val tree = side.memberTree(info, part, index, child)
        try {
            into.setNode(side, tree, 0)
        } finally {
            side.release(tree)
        }
    }

    override fun next() {
        val part = heap[0]
        lastLength = keyLengths[part]
        if (last.size < lastLength) last = ByteArray(lastLength)
        System.arraycopy(keys[part]!!, 0, last, 0, lastLength)
        if (++positions[part] == info.memberCounts[part].toInt()) {
            heap[0] = heap[--heapSize]
        } else {
            loadKey(part)
        }
        if (heapSize > 0) siftDown(0)
        if (heapSize > 0 && Arrays.equals(keys[heap[0]]!!, 0, keyLengths[heap[0]], last, 0, lastLength)) {
            throw CorruptionException("fragment (${side.docId}, ${info.pre}, HEADER)", "two parts of the split object hold one key")
        }
    }

    override fun close() {
        for (tree in cache.values) side.release(tree)
        cache.clear()
        cachedBytes = 0
        heapSize = 0
    }

    private fun less(a: Int, b: Int): Boolean {
        val c = Arrays.compareUnsigned(keys[a]!!, 0, keyLengths[a], keys[b]!!, 0, keyLengths[b])
        return if (c != 0) c < 0 else a < b
    }

    private fun push(part: Int) {
        var k = heapSize++
        heap[k] = part
        while (k > 0) {
            val parent = (k - 1) / 2
            if (!less(heap[k], heap[parent])) break
            val t = heap[k]
            heap[k] = heap[parent]
            heap[parent] = t
            k = parent
        }
    }

    private fun siftDown(start: Int) {
        var k = start
        while (true) {
            val left = 2 * k + 1
            if (left >= heapSize) return
            val right = left + 1
            val child = if (right < heapSize && less(heap[right], heap[left])) right else left
            if (!less(heap[child], heap[k])) return
            val t = heap[k]
            heap[k] = heap[child]
            heap[child] = t
            k = child
        }
    }
}

/** A string's bytes in parts: an inline STR, or a BIGSTR's STRCHUNKs read one at a time. */
internal class StringStream {
    var bytes: ByteArray = ByteArray(0)
    var position: Int = 0
    var end: Int = 0
    private var side: CompareSide? = null
    private var pre = 0L
    private var chunks = 0L
    private var nextChunk = 0L
    private var expected = 0L
    private var seen = 0L

    fun open(value: ValueRef) {
        val tree = value.tree!!
        side = value.side
        pre = value.pre
        seen = 0
        if (value.event == PayloadReader.STR) {
            bytes = tree.bytes
            position = tree.textOffset(value.node)
            end = position + tree.textLength(value.node)
            chunks = 0
            nextChunk = 0
            expected = (end - position).toLong()
            seen = expected
        } else {
            chunks = value.count
            nextChunk = 0
            expected = value.size
            position = 0
            end = 0
        }
    }

    /** Moves to the next non-empty part; false at the end of the string. */
    fun advance(): Boolean {
        if (nextChunk >= chunks) {
            if (seen != expected) throw CorruptionException("document ${side?.docId} at pre $pre", "chunks hold $seen bytes, stub says $expected")
            return false
        }
        if (nextChunk > Int.MAX_VALUE) throw CorruptionException("document ${side?.docId} at pre $pre", "chunk count $chunks")
        side!!.chunk(pre, nextChunk.toInt(), this)
        nextChunk++
        seen += end - position
        return true
    }
}
