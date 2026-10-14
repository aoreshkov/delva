package app.oreshkov.delva.internal.query

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.QueryLimitException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.path.NormalizedPath
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.HeaderReader
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PayloadSlice
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.store.FragmentStore

/** The JSON type of a [PathNode]. */
internal object NodeType {
    const val NULL: Int = 1
    const val FALSE: Int = 2
    const val TRUE: Int = 3
    const val NUMBER: Int = 4
    const val STRING: Int = 5
    const val OBJECT: Int = 6
    const val ARRAY: Int = 7
}

/**
 * A value of a stored document as the JSONPath evaluator reads it: its `pre`, its type, where it's stored, and how it
 * was reached — its parent, and its key or index there — which gives its Normalized Path.
 *
 * [form] says how the value is stored: [INLINE] at [valueOffset] of [holder]; a [REF] or [BIGSTR] stub there whose
 * content is in fragments of its own; or a [SPLIT] container whose members are in PARTs, as a stub in [holder] or,
 * with no holder, as a split root. Offsets index [holder]'s bytes, which never change. Immutable apart from the
 * resolution of a REF, which is cached.
 */
internal class PathNode(
    val pre: Long,
    val type: Int,
    val form: Int,
    val holder: PayloadSlice?,
    val holderPre: Long,
    val holderKind: Int,
    val valueOffset: Int,
    /** Containers: the member count, or -1 when a REF has to be resolved to know it. BIGSTR: the chunk count. */
    val count: Long,
    /** Containers: the number of descendants. BIGSTR: the string's UTF-8 length. */
    val size: Long,
    /** INT numbers: the value; NUM numbers and strings: where their bytes are in [holder]. */
    val intValue: Long,
    val textOffset: Int,
    val textLength: Int,
    val parent: PathNode?,
    /** Member key, when the parent is an object: `keyBytes[keyOffset, keyOffset + keyLength)`. */
    val keyBytes: ByteArray?,
    val keyOffset: Int,
    val keyLength: Int,
    /** Position in the parent: the array index, or the member's position. */
    val index: Long,
) {
    internal var resolved: PathNode? = null

    val isContainer: Boolean get() = type == NodeType.OBJECT || type == NodeType.ARRAY

    /** The member key as a string; the parent must be an object. */
    fun key(): String = String(keyBytes!!, keyOffset, keyLength, Charsets.UTF_8)

    /** The Normalized Path from the node the evaluation started at, which has no parent. */
    fun normalizedPath(): String {
        val chain = ArrayList<PathNode>()
        var n: PathNode? = this
        while (n?.parent != null) {
            chain += n
            n = n.parent
        }
        val sb = StringBuilder("$")
        for (k in chain.indices.reversed()) {
            val step = chain[k]
            if (step.parent!!.type == NodeType.OBJECT) NormalizedPath.appendName(sb, step.key()) else NormalizedPath.appendIndex(sb, step.index)
        }
        return sb.toString()
    }

    /** Where the value is, as the hit and compare readers take it. */
    fun located(): LocatedNode {
        val kind = when (type) {
            NodeType.OBJECT -> PayloadTag.OBJ
            NodeType.ARRAY -> PayloadTag.ARR
            else -> 0
        }
        val holder = holder ?: return LocatedNode(pre, true, pre, FragmentKind.HEADER, 0, size, kind)
        return LocatedNode(pre, false, holderPre, holderKind, valueOffset - holder.offset, if (kind != 0) size else 0, kind)
    }

    override fun toString(): String = "PathNode(pre=$pre, type=$type, form=$form)"

    companion object {
        const val INLINE: Int = 0
        const val REF: Int = 1
        const val SPLIT: Int = 2
        const val BIGSTR: Int = 3
    }
}

/**
 * Reads the values of one stored document for the JSONPath evaluator: nodes by `pre`, the children of containers in
 * document order across REF, SPLIT and PART fragments, members by key, elements by index (through a split array's
 * directory), and scalar values, BIGSTR strings chunk by chunk. Every fragment is read by key from [store], so
 * memory is a fragment per open container being walked. Not thread-safe.
 */
internal class StoredDocument(val store: FragmentStore, val docId: Long) {
    private val view = FragmentView()
    private val locator = NodeLocator(store)
    private val header = HeaderReader()
    private val probe = PayloadReader()
    private val cursors = ArrayList<ChildCursor>()

    /** Fragments read, for tests and plans. */
    var fragmentsRead: Long = 0
        private set

    /** The value with `pre` [pre], as the start of an evaluation: without a parent. */
    fun node(pre: Long): PathNode = node(locator.locate(docId, pre))

    /**
     * The value with `pre` [target] reached from the document root, so that its parent chain names it
     * ([PathNode.normalizedPath]): each level enters the one child whose `[pre, pre + size]` holds the target, a split
     * container through its directory. One fragment per level, each decoded at most once. [target] must be a node of
     * the document.
     */
    fun nodeWithPath(target: Long): PathNode {
        var node = node(0L)
        while (node.pre != target) node = childHolding(node, target)
        return node
    }

    private fun childHolding(node: PathNode, target: Long): PathNode {
        if (!node.isContainer || target > node.pre + node.size) throw CorruptionException("document $docId at pre ${node.pre}", "no child holds pre $target")
        children(node).use { c ->
            c.seekPre(target)
            while (true) {
                val child = c.next() ?: throw CorruptionException("document $docId at pre ${node.pre}", "no child holds pre $target")
                if (target <= child.pre + (if (child.isContainer) child.size else 0L)) return child
            }
        }
    }

    fun node(located: LocatedNode): PathNode {
        if (located.isSplit) return splitRoot(located.pre)
        val slice = fragment(located.holderPre, located.holderKind, 0) ?: missing(located.holderPre, "holder")
        probe.openValue(slice.bytes, slice.offset, slice.length, slice.offset + located.valueOffset, located.pre, source(located.holderPre, located.holderKind))
        val event = probe.next()
        if (event == PayloadReader.END || probe.pre != located.pre) throw CorruptionException(source(located.holderPre, located.holderKind), "no value at pre ${located.pre}")
        return fromReader(probe, event, slice, located.holderPre, located.holderKind, null, -1)
    }

    private fun splitRoot(pre: Long): PathNode {
        val slice = fragment(pre, FragmentKind.HEADER, 0) ?: missing(pre, "HEADER")
        header.open(slice.bytes, slice.offset, slice.length, 0, source(pre, FragmentKind.HEADER))
        val type = if (header.containerKind == PayloadTag.OBJ) NodeType.OBJECT else NodeType.ARRAY
        return PathNode(pre, type, PathNode.SPLIT, null, pre, FragmentKind.HEADER, 0, header.count, header.size, 0, 0, 0, null, null, 0, 0, 0)
    }

    fun fragment(pre: Long, kind: Int, seq: Int): PayloadSlice? {
        if (!store.find(docId, pre, kind, seq, view)) return null
        fragmentsRead++
        return view.slice()
    }

    /** A REF container as the INLINE value its SUBTREE fragment holds; other nodes as they are. */
    fun resolve(node: PathNode): PathNode {
        if (node.form != PathNode.REF) return node
        node.resolved?.let { return it }
        val slice = fragment(node.pre, FragmentKind.SUBTREE, 0) ?: missing(node.pre, "SUBTREE")
        probe.open(slice.bytes, slice.offset, slice.length, FragmentKind.SUBTREE, node.pre, source(node.pre, FragmentKind.SUBTREE))
        val event = probe.next()
        val expected = if (node.type == NodeType.OBJECT) PayloadReader.START_OBJECT else PayloadReader.START_ARRAY
        if (event != expected || probe.size != node.size) {
            throw CorruptionException(source(node.pre, FragmentKind.SUBTREE), "REF expects type ${node.type} size ${node.size}, found event $event size ${probe.size}")
        }
        return PathNode(
            node.pre, node.type, PathNode.INLINE, slice, node.pre, FragmentKind.SUBTREE, probe.valueOffset, probe.count, probe.size, 0, 0, 0,
            node.parent, node.keyBytes, node.keyOffset, node.keyLength, node.index,
        ).also { node.resolved = it }
    }

    /** The member count of a container. */
    fun count(node: PathNode): Long = if (node.count >= 0) node.count else resolve(node).count

    /** A cursor over the children of [node], in document order; nothing for a scalar. Close it when done. */
    fun children(node: PathNode): ChildCursor = (if (cursors.isEmpty()) ChildCursor() else cursors.removeAt(cursors.size - 1)).also { it.open(node) }

    /** The member of the object [node] with the UTF-8 key [key], or null. */
    fun member(node: PathNode, key: ByteArray): PathNode? {
        if (node.type != NodeType.OBJECT) return null
        children(node).use { c ->
            while (true) {
                val child = c.next() ?: return null
                if (child.keyLength == key.size && java.util.Arrays.equals(child.keyBytes, child.keyOffset, child.keyOffset + child.keyLength, key, 0, key.size)) return child
            }
        }
    }

    /** Element [index] (not negative) of the array [node], or null past its end. */
    fun element(node: PathNode, index: Long): PathNode? {
        if (node.type != NodeType.ARRAY || index < 0 || index >= count(node)) return null
        children(node).use { c ->
            c.seek(index)
            return c.next()
        }
    }

    // ---- scalars --------------------------------------------------------------------------------------------------

    /** Appends the NumberKey of the number [node] to [out]. */
    fun numberKey(node: PathNode, out: GrowableBytes) {
        if (node.textLength == 0) {
            CanonicalNumber.writeKey(node.intValue, out)
            return
        }
        if (CanonicalNumber.writeKey(node.holder!!.bytes, node.textOffset, node.textLength, out) < 0) {
            throw CorruptionException(source(node.holderPre, node.holderKind), "invalid stored number at pre ${node.pre}")
        }
    }

    /**
     * Calls [chunk] with the UTF-8 bytes of the string [node] in order, as one piece or chunk by chunk, until it returns
     * false.
     */
    inline fun stringChunks(node: PathNode, chunk: (ByteArray, Int, Int) -> Boolean) {
        if (node.form != PathNode.BIGSTR) {
            chunk(node.holder!!.bytes, node.textOffset, node.textLength)
            return
        }
        var total = 0L
        for (seq in 0 until node.count) {
            val reader = openChunk(node, seq)
            total += reader.length
            if (!chunk(reader.payload, reader.offset, reader.length)) return
        }
        if (total != node.size) throw CorruptionException("fragment (${node.pre}, BIGSTR)", "chunks hold $total bytes, stub says ${node.size}")
    }

    private val chunkReader = PayloadReader()

    fun openChunk(node: PathNode, seq: Long): PayloadReader {
        if (seq > Int.MAX_VALUE) throw CorruptionException("fragment (${node.pre}, BIGSTR)", "chunk count ${node.count}")
        val slice = fragment(node.pre, FragmentKind.STRCHUNK, seq.toInt()) ?: missing(node.pre, "STRCHUNK $seq")
        chunkReader.open(slice.bytes, slice.offset, slice.length, FragmentKind.STRCHUNK, node.pre, source(node.pre, FragmentKind.STRCHUNK))
        if (chunkReader.next() != PayloadReader.STR) throw CorruptionException(source(node.pre, FragmentKind.STRCHUNK), "no string")
        return chunkReader
    }

    /** The string [node] decoded; throws [QueryLimitException] beyond [MAX_DECODED_STRING_BYTES]. */
    fun string(node: PathNode): String {
        if (node.form != PathNode.BIGSTR) return String(node.holder!!.bytes, node.textOffset, node.textLength, Charsets.UTF_8)
        if (node.size > MAX_DECODED_STRING_BYTES) {
            throw QueryLimitException("match() and search() read strings of at most $MAX_DECODED_STRING_BYTES bytes; the string at pre ${node.pre} has ${node.size}")
        }
        val bytes = GrowableBytes(node.size.toInt())
        stringChunks(node) { b, o, l ->
            bytes.writeBytes(b, o, l)
            true
        }
        return String(bytes.array, 0, bytes.size, Charsets.UTF_8)
    }

    // ---- children -------------------------------------------------------------------------------------------------

    /**
     * The children of one container in document order. For a split container it walks the PARTs one after another,
     * each starting where the previous one ended; [seek] jumps to an index through the container's directory.
     */
    inner class ChildCursor internal constructor() : AutoCloseable {
        private val reader = PayloadReader()
        private lateinit var parent: PathNode
        private var slice: PayloadSlice? = null
        private var slicePre = 0L
        private var sliceKind = 0

        // Split containers: the part being read and where the next one starts.
        private var split = false
        private var nextPartPre = 0L
        private var partOpen = false
        private var done = false

        /** Children still to skip before the next one is returned (a seek inside an unsplit container). */
        private var skip = 0L

        internal fun open(node: PathNode) {
            parent = node
            closed = false
            memberBase = 0
            done = false
            skip = 0
            partOpen = false
            dirSeq = -1
            if (!node.isContainer) {
                done = true
                return
            }
            val n = resolve(node)
            split = n.form == PathNode.SPLIT
            if (split) {
                nextPartPre = n.pre + 1
                return
            }
            val holder = n.holder!!
            slice = holder
            slicePre = n.holderPre
            sliceKind = n.holderKind
            reader.openValue(holder.bytes, holder.offset, holder.length, n.valueOffset, n.pre, source(n.holderPre, n.holderKind))
            val event = reader.next()
            if (event != PayloadReader.START_OBJECT && event != PayloadReader.START_ARRAY) throw CorruptionException(source(n.holderPre, n.holderKind), "no container at pre ${n.pre}")
        }

        /** Positions the cursor so that [next] returns child [index]; call before the first [next]. */
        fun seek(index: Long) {
            if (!split || index == 0L) {
                skip = index
                return
            }
            skip = index - startAtPart(index, byPre = false)
        }

        /**
         * Positions a split container's cursor at the PART that holds `pre` [target], so [next] skips the PARTs before
         * it; does nothing for other containers. Call before the first [next].
         */
        fun seekPre(target: Long) {
            if (split) startAtPart(target, byPre = true)
        }

        /**
         * Makes the PART holding [key] — a member index, or a `pre` when [byPre] — the next one read, as the directory
         * names the first pre and index of every PART; returns the index of its first member.
         */
        private fun startAtPart(key: Long, byPre: Boolean): Long {
            val pre = parent.pre
            var seq = 0
            var partPre = -1L
            var partIndex = 0L
            records@ while (true) {
                val slice = fragment(pre, FragmentKind.HEADER, seq) ?: break
                header.open(slice.bytes, slice.offset, slice.length, seq, source(pre, FragmentKind.HEADER))
                while (header.nextEntry()) {
                    if ((if (byPre) header.firstPre else header.firstIndex) > key) break@records
                    partPre = header.firstPre
                    partIndex = header.firstIndex
                }
                seq++
            }
            if (partPre < 0) throw CorruptionException(source(pre, FragmentKind.HEADER), "the directory has no PART for ${if (byPre) "pre" else "index"} $key")
            nextPartPre = partPre
            memberBase = partIndex
            return partIndex
        }

        // One directory record of a split container, kept by startAtPartOf: each PART's first pre, first index and
        // member count.
        private var dirSeq = -1
        private var dirCount = 0
        private var dirPre = LongArray(0)
        private var dirIndex = LongArray(0)
        private var dirMembers = LongArray(0)

        /**
         * For walking a container backwards a PART at a time: makes the PART that holds child [index] of a split
         * container the next one read, and returns the index of its first child. The directory record it was found in
         * is kept, so the PART before it costs at most one more record read. An unsplit container is one piece
         * starting at 0; call it before the first [next] for one of those.
         */
        fun startAtPartOf(index: Long): Long {
            if (!split) return 0
            partOpen = false
            done = false
            skip = 0
            if (dirSeq < 0) {
                // The first lookup scans the records in order, as startAtPart does.
                var seq = 0
                while (true) {
                    if (!loadDirectory(seq)) noPart(index)
                    if (dirCount > 0 && index < dirIndex[dirCount - 1] + dirMembers[dirCount - 1]) break
                    seq++
                }
            }
            while (dirCount == 0 || index < dirIndex[0]) if (dirSeq == 0 || !loadDirectory(dirSeq - 1)) noPart(index)
            while (index >= dirIndex[dirCount - 1] + dirMembers[dirCount - 1]) if (!loadDirectory(dirSeq + 1) || dirCount == 0) noPart(index)
            var lo = 0
            var hi = dirCount - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (dirIndex[mid] <= index) lo = mid else hi = mid - 1
            }
            nextPartPre = dirPre[lo]
            memberBase = dirIndex[lo]
            return memberBase
        }

        /** Reads directory record [seq] into the arrays; false when there is none. */
        private fun loadDirectory(seq: Int): Boolean {
            val pre = parent.pre
            val slice = fragment(pre, FragmentKind.HEADER, seq) ?: return false
            header.open(slice.bytes, slice.offset, slice.length, seq, source(pre, FragmentKind.HEADER))
            dirCount = 0
            while (header.nextEntry()) {
                if (dirCount == dirPre.size) {
                    val n = maxOf(16, 2 * dirCount)
                    dirPre = dirPre.copyOf(n)
                    dirIndex = dirIndex.copyOf(n)
                    dirMembers = dirMembers.copyOf(n)
                }
                dirPre[dirCount] = header.firstPre
                dirIndex[dirCount] = header.firstIndex
                dirMembers[dirCount] = header.memberCount
                dirCount++
            }
            dirSeq = seq
            return true
        }

        private fun noPart(index: Long): Nothing = throw CorruptionException(source(parent.pre, FragmentKind.HEADER), "the directory has no PART for index $index")

        /** Index of the first member of the PART being read, for split containers. */
        private var memberBase = 0L
        private var partMembers = 0L

        /** Skips the next [n] children without building them. */
        fun skip(n: Long) {
            skip += n
        }

        /** The next child, or null after the last. */
        fun next(): PathNode? {
            while (true) {
                val child = step() ?: return null
                if (skip > 0) {
                    skip--
                    continue
                }
                return child
            }
        }

        private fun step(): PathNode? {
            if (done) return null
            if (!split) {
                val event = reader.next()
                if (reader.depth == 0 && (event == PayloadReader.END_OBJECT || event == PayloadReader.END_ARRAY)) {
                    done = true
                    return null
                }
                // Skipped children aren't built; a container's content is skipped either way.
                val child = if (skip > 0) null else fromReader(reader, event, slice!!, slicePre, sliceKind, parent, reader.memberIndex)
                if (event == PayloadReader.START_OBJECT || event == PayloadReader.START_ARRAY) reader.skipValue()
                return child ?: SKIPPED
            }
            while (true) {
                if (!partOpen) {
                    if (nextPartPre > parent.pre + parent.size) {
                        done = true
                        return null
                    }
                    val part = fragment(nextPartPre, FragmentKind.PART, 0) ?: missing(nextPartPre, "PART of the container at ${parent.pre}")
                    reader.open(part.bytes, part.offset, part.length, FragmentKind.PART, nextPartPre, source(nextPartPre, FragmentKind.PART))
                    val kind = if (parent.type == NodeType.OBJECT) PayloadTag.OBJ else PayloadTag.ARR
                    if (reader.partKind != kind) throw CorruptionException(source(nextPartPre, FragmentKind.PART), "PART kind ${reader.partKind} in a container of type ${parent.type}")
                    slice = part
                    slicePre = nextPartPre
                    sliceKind = FragmentKind.PART
                    partMembers = reader.partMemberCount
                    partOpen = true
                }
                val event = reader.next()
                if (event == PayloadReader.END) {
                    partOpen = false
                    memberBase += partMembers
                    nextPartPre = reader.nextValuePre
                    continue
                }
                val child = if (skip > 0) null else fromReader(reader, event, slice!!, slicePre, sliceKind, parent, memberBase + reader.memberIndex)
                if (event == PayloadReader.START_OBJECT || event == PayloadReader.START_ARRAY) reader.skipValue()
                return child ?: SKIPPED
            }
        }

        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            slice = null
            if (cursors.size < MAX_POOLED_CURSORS) cursors += this
        }
    }

    /** A value built from [reader]'s current event, which is a value start. */
    private fun fromReader(reader: PayloadReader, event: Int, slice: PayloadSlice, holderPre: Long, holderKind: Int, parent: PathNode?, index: Long): PathNode {
        val keyBytes = if (reader.hasKey) reader.payload else null
        val keyOffset = if (reader.hasKey) reader.keyOffset else 0
        val keyLength = if (reader.hasKey) reader.keyLength else 0
        fun node(type: Int, form: Int = PathNode.INLINE, count: Long = 0, size: Long = 0, intValue: Long = 0, textOffset: Int = 0, textLength: Int = 0) =
            PathNode(reader.pre, type, form, slice, holderPre, holderKind, reader.valueOffset, count, size, intValue, textOffset, textLength, parent, keyBytes, keyOffset, keyLength, index)
        return when (event) {
            PayloadReader.NULL -> node(NodeType.NULL)
            PayloadReader.FALSE -> node(NodeType.FALSE)
            PayloadReader.TRUE -> node(NodeType.TRUE)
            PayloadReader.INT -> node(NodeType.NUMBER, intValue = reader.intValue)
            PayloadReader.NUM -> node(NodeType.NUMBER, textOffset = reader.offset, textLength = reader.length)
            PayloadReader.STR -> node(NodeType.STRING, textOffset = reader.offset, textLength = reader.length)
            PayloadReader.BIGSTR -> node(NodeType.STRING, PathNode.BIGSTR, count = reader.count, size = reader.size)
            PayloadReader.START_OBJECT -> node(NodeType.OBJECT, count = reader.count, size = reader.size)
            PayloadReader.START_ARRAY -> node(NodeType.ARRAY, count = reader.count, size = reader.size)
            PayloadReader.REF -> node(containerType(reader.containerKind), PathNode.REF, count = -1, size = reader.size)
            PayloadReader.SPLIT -> node(containerType(reader.containerKind), PathNode.SPLIT, count = reader.count, size = reader.size)
            else -> throw CorruptionException(source(holderPre, holderKind), "unexpected event $event at pre ${reader.pre}")
        }
    }

    private fun containerType(kind: Int) = if (kind == PayloadTag.OBJ) NodeType.OBJECT else NodeType.ARRAY

    private fun source(pre: Long, kind: Int) = "fragment ($docId, $pre, $kind)"

    private fun missing(pre: Long, what: String): Nothing = throw CorruptionException("document $docId at pre $pre", "missing $what fragment")

    companion object {
        /** The longest string `match` and `search` decode. */
        const val MAX_DECODED_STRING_BYTES: Long = 16L shl 20

        private const val MAX_POOLED_CURSORS = 64

        /** Stands for a child that [ChildCursor] skipped without building it. */
        private val SKIPPED = PathNode(0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0)
    }
}
