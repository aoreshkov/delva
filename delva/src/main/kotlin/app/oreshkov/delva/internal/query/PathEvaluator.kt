package app.oreshkov.delva.internal.query

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.compare.CompareSettings
import app.oreshkov.delva.internal.compare.CompareSide
import app.oreshkov.delva.internal.compare.CompareStats
import app.oreshkov.delva.internal.compare.Comparison
import app.oreshkov.delva.internal.path.CompareOp
import app.oreshkov.delva.internal.path.FunctionArg
import app.oreshkov.delva.internal.path.FunctionCall
import app.oreshkov.delva.internal.path.LiteralValue
import app.oreshkov.delva.internal.path.LogicalExpr
import app.oreshkov.delva.internal.path.PathFunction
import app.oreshkov.delva.internal.path.PathQuery
import app.oreshkov.delva.internal.path.PathSegment
import app.oreshkov.delva.internal.path.Selector
import app.oreshkov.delva.internal.path.ValueExpr
import java.util.Arrays

/** A lazily produced nodelist; close it to give back the cursors it holds. */
internal abstract class NodeStream : AutoCloseable {
    /** The next node, or null after the last. */
    abstract fun next(): PathNode?

    override fun close() {}
}

/**
 * Evaluates RFC 9535 queries over one stored document ([doc]) as section 2 of the RFC defines them: [nodes] gives a
 * query's nodelist in the RFC's order, duplicates included, and [test] decides a filter expression for one node.
 *
 * Nodelists are streams pulled one node at a time: a segment holds the node it's working on and a cursor over that
 * node's children, and a descendant segment a stack of cursors as deep as the document. Nothing is materialized,
 * except, for a slice with a negative step, the elements it selects from one PART (or from an unsplit array), which
 * it returns in reverse before it reads the PART before.
 *
 * Comparisons follow RFC 9535 section 2.3.5.2.2 with SPEC.md §2's equality: numbers by decimal value (their NumberKeys),
 * strings by code points (their UTF-8 bytes), objects and arrays by the streaming comparison of SPEC.md §9. `$`
 * inside a filter is [root]. Not thread-safe.
 */
internal class PathEvaluator(val doc: StoredDocument, var root: PathNode, private val compareCacheBytes: Long = DEFAULT_COMPARE_CACHE_BYTES) {
    private val leftKey = GrowableBytes(64)
    private val rightKey = GrowableBytes(64)
    private val compareStats = CompareStats()
    private val patterns = PatternCache()

    /** Filter expressions evaluated, for tests and plans. */
    var filterTests: Long = 0
        private set

    /** The nodelist of [query]: from [root] when it's absolute, else from [current]. */
    fun nodes(query: PathQuery, current: PathNode): NodeStream {
        var stream: NodeStream = Single(if (query.absolute) root else current)
        for (segment in query.segments) stream = SegmentStream(stream, segment)
        return stream
    }

    // ---- segments -------------------------------------------------------------------------------------------------

    private class Single(private var node: PathNode?) : NodeStream() {
        override fun next(): PathNode? = node.also { node = null }
    }

    /**
     * One segment applied to each node of [input] in turn: a child segment applies its selectors to the node, a
     * descendant segment to the node and to each of its descendants in document order (RFC 9535 section 2.5.2.2).
     */
    private inner class SegmentStream(private val input: NodeStream, private val segment: PathSegment) : NodeStream() {
        private val stack = ArrayList<StoredDocument.ChildCursor>()
        private var visiting: PathNode? = null
        private var selector = 0
        private var selected: NodeStream? = null

        override fun next(): PathNode? {
            while (true) {
                selected?.let { s ->
                    s.next()?.let { return it }
                    s.close()
                    selected = null
                    selector++
                }
                val node = visiting
                if (node != null && selector < segment.selectors.size) {
                    selected = select(segment.selectors[selector], node)
                    continue
                }
                visiting = nextVisit() ?: return null
                selector = 0
            }
        }

        /** The next node to apply the selectors to. */
        private fun nextVisit(): PathNode? {
            if (!segment.descendant) return input.next()
            // Pre-order: the children of the node just visited come next, then its following siblings.
            visiting?.let { if (it.isContainer) stack += doc.children(it) }
            while (stack.isNotEmpty()) {
                val top = stack[stack.size - 1]
                top.next()?.let { return it }
                top.close()
                stack.removeAt(stack.size - 1)
            }
            return input.next()
        }

        override fun close() {
            selected?.close()
            selected = null
            for (c in stack) c.close()
            stack.clear()
            input.close()
        }
    }

    // ---- selectors ------------------------------------------------------------------------------------------------

    /** The children of [node] that [selector] selects, in the RFC's order (RFC 9535 section 2.3). */
    private fun select(selector: Selector, node: PathNode): NodeStream = when (selector) {
        is Selector.Name -> Single(if (node.type == NodeType.OBJECT) doc.member(node, selector.utf8) else null)
        Selector.Wildcard -> if (node.isContainer) Children(doc.children(node)) else EMPTY
        is Selector.Index -> {
            if (node.type != NodeType.ARRAY) {
                EMPTY
            } else {
                val index = if (selector.index >= 0) selector.index else doc.count(node) + selector.index
                Single(doc.element(node, index))
            }
        }
        is Selector.Slice -> if (node.type == NodeType.ARRAY) slice(selector, node) else EMPTY
        is Selector.Filter -> if (node.isContainer) Filtered(doc.children(node), selector.expr) else EMPTY
    }

    private class Children(private val cursor: StoredDocument.ChildCursor) : NodeStream() {
        override fun next(): PathNode? = cursor.next()

        override fun close() = cursor.close()
    }

    private inner class Filtered(private val cursor: StoredDocument.ChildCursor, private val expr: LogicalExpr) : NodeStream() {
        override fun next(): PathNode? {
            while (true) {
                val child = cursor.next() ?: return null
                if (test(expr, child)) return child
            }
        }

        override fun close() = cursor.close()
    }

    /** RFC 9535 section 2.3.4.2.2. */
    private fun slice(slice: Selector.Slice, node: PathNode): NodeStream {
        val step = slice.step ?: 1
        if (step == 0L) return EMPTY
        val length = doc.count(node)
        fun normalize(i: Long) = if (i >= 0) i else length + i
        if (step > 0) {
            val lower = minOf(maxOf(normalize(slice.start ?: 0), 0), length)
            val upper = minOf(maxOf(normalize(slice.end ?: length), 0), length)
            if (lower >= upper) return EMPTY
            val cursor = doc.children(node)
            cursor.seek(lower)
            return object : NodeStream() {
                private var i = lower

                override fun next(): PathNode? {
                    if (i >= upper) return null
                    val child = cursor.next() ?: return null
                    val skip = minOf(step - 1, upper - i - 1)
                    i += step
                    cursor.skip(skip)
                    return child
                }

                override fun close() = cursor.close()
            }
        }
        val upper = minOf(maxOf(normalize(slice.start ?: (length - 1)), -1), length - 1)
        val lower = minOf(maxOf(normalize(slice.end ?: (-length - 1)), -1), length - 1)
        if (upper <= lower) return EMPTY
        // Selected elements come back last to first: each PART's are read forward, then handed out in reverse, so
        // only one PART's nodes are alive.
        val stride = -step
        val cursor = doc.children(node)
        return object : NodeStream() {
            /** The highest selected index not read yet. */
            private var high = upper
            private val part = ArrayList<PathNode>()

            override fun next(): PathNode? {
                while (part.isEmpty()) {
                    if (high <= lower) return null
                    val first = cursor.startAtPartOf(high)
                    val lowest = high - (high - maxOf(first, lower + 1)) / stride * stride
                    cursor.skip(lowest - first)
                    var i = lowest
                    while (i <= high) {
                        part += cursor.next() ?: break
                        if (i + stride <= high) cursor.skip(stride - 1)
                        i += stride
                    }
                    high = lowest - stride
                }
                return part.removeAt(part.size - 1)
            }

            override fun close() {
                part.clear()
                cursor.close()
            }
        }
    }

    // ---- filters --------------------------------------------------------------------------------------------------

    /** Whether [expr] is true with [current] as `@`. */
    fun test(expr: LogicalExpr, current: PathNode): Boolean {
        filterTests++
        return when (expr) {
            is LogicalExpr.Or -> expr.items.any { test(it, current) }
            is LogicalExpr.And -> expr.items.all { test(it, current) }
            is LogicalExpr.Not -> !test(expr.expr, current)
            is LogicalExpr.Exists -> nodes(expr.query, current).use { it.next() != null }
            is LogicalExpr.Test -> logical(expr.call, current)
            is LogicalExpr.Comparison -> compare(value(expr.left, current), expr.op, value(expr.right, current))
        }
    }

    /**
     * The value of [expr]: null for Nothing, a [PathNode], or a [LiteralValue] (literals, and the numbers `length`
     * and `count` give).
     */
    private fun value(expr: ValueExpr, current: PathNode): Any? = when (expr) {
        is ValueExpr.Literal -> expr.value
        is ValueExpr.Query -> nodes(expr.query, current).use { it.next() }
        is ValueExpr.Call -> call(expr.call, current)
    }

    private fun call(call: FunctionCall, current: PathNode): Any? = when (call.function) {
        PathFunction.LENGTH -> length(argument(call.args[0], current))
        PathFunction.COUNT -> nodes((call.args[0] as FunctionArg.Nodes).query, current).use { s ->
            var n = 0L
            while (s.next() != null) n++
            number(n)
        }
        PathFunction.VALUE -> nodes((call.args[0] as FunctionArg.Nodes).query, current).use { s ->
            val first = s.next()
            if (first == null || s.next() != null) null else first
        }
        PathFunction.MATCH, PathFunction.SEARCH -> throw IllegalStateException("${call.function} isn't a value")
    }

    private fun argument(arg: FunctionArg, current: PathNode): Any? = when (arg) {
        is FunctionArg.Value -> value(arg.expr, current)
        is FunctionArg.Nodes, is FunctionArg.Logical -> throw IllegalStateException("not a value argument")
    }

    private fun length(v: Any?): Any? = when {
        v is LiteralValue.Str -> number(v.value.codePointCount(0, v.value.length).toLong())
        v !is PathNode -> null
        v.type == NodeType.STRING -> {
            var n = 0L
            doc.stringChunks(v) { b, o, l ->
                for (k in o until o + l) if (b[k].toInt() and 0xC0 != 0x80) n++
                true
            }
            number(n)
        }
        v.isContainer -> number(doc.count(v))
        else -> null
    }

    private fun number(n: Long): LiteralValue.Num {
        val key = GrowableBytes(16)
        CanonicalNumber.writeKey(n, key)
        return LiteralValue.Num(n.toString(), key.toByteArray())
    }

    /** `match` and `search` (RFC 9535 sections 2.4.6 and 2.4.7). */
    private fun logical(call: FunctionCall, current: PathNode): Boolean {
        check(call.function == PathFunction.MATCH || call.function == PathFunction.SEARCH) { "${call.function} isn't logical" }
        val subject = stringOf(argument(call.args[0], current)) ?: return false
        val patternArg = (call.args[1] as FunctionArg.Value).expr
        val pattern = if (patternArg is ValueExpr.Literal) {
            call.literalPattern
        } else {
            val text = stringOf(value(patternArg, current)) ?: return false
            patterns.compile(text)
        } ?: return false
        return if (call.function == PathFunction.MATCH) pattern.matches(subject) else pattern.find(subject)
    }

    private fun stringOf(v: Any?): String? = when {
        v is LiteralValue.Str -> v.value
        v is PathNode && v.type == NodeType.STRING -> doc.string(v)
        else -> null
    }

    // ---- comparisons ----------------------------------------------------------------------------------------------

    private fun compare(a: Any?, op: CompareOp, b: Any?): Boolean = when (op) {
        CompareOp.EQ -> equal(a, b)
        CompareOp.NE -> !equal(a, b)
        CompareOp.LT -> less(a, b)
        CompareOp.LE -> less(a, b) || equal(a, b)
        CompareOp.GT -> less(b, a)
        CompareOp.GE -> less(b, a) || equal(a, b)
    }

    private fun typeOf(v: Any): Int = when (v) {
        is PathNode -> v.type
        LiteralValue.Null -> NodeType.NULL
        is LiteralValue.Bool -> if (v.value) NodeType.TRUE else NodeType.FALSE
        is LiteralValue.Num -> NodeType.NUMBER
        is LiteralValue.Str -> NodeType.STRING
        else -> throw IllegalStateException("not a value: $v")
    }

    private fun equal(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return a == null && b == null
        val type = typeOf(a)
        if (type != typeOf(b)) return false
        return when (type) {
            NodeType.NUMBER -> Arrays.equals(key(a, leftKey), 0, leftKey.size, key(b, rightKey), 0, rightKey.size)
            NodeType.STRING -> compareStrings(a, b) == 0
            NodeType.OBJECT, NodeType.ARRAY -> containersEqual(a as PathNode, b as PathNode)
            else -> true
        }
    }

    private fun less(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return false
        val type = typeOf(a)
        if (type != typeOf(b)) return false
        return when (type) {
            NodeType.NUMBER -> Arrays.compareUnsigned(key(a, leftKey), 0, leftKey.size, key(b, rightKey), 0, rightKey.size) < 0
            NodeType.STRING -> compareStrings(a, b) < 0
            else -> false
        }
    }

    private fun key(v: Any, out: GrowableBytes): ByteArray {
        out.clear()
        if (v is LiteralValue.Num) out.writeBytes(v.key, 0, v.key.size) else doc.numberKey(v as PathNode, out)
        return out.array
    }

    private val leftString = StringCursor()
    private val rightString = StringCursor()

    /** Compares two strings by their UTF-8 bytes, which orders them by code points. */
    private fun compareStrings(a: Any, b: Any): Int {
        leftString.open(a)
        rightString.open(b)
        while (true) {
            val l = leftString.take()
            val r = rightString.take()
            if (l < 0 || r < 0) return if (l < 0 && r < 0) 0 else if (l < 0) -1 else 1
            if (l != r) return l - r
        }
    }

    /** The bytes of a stored or literal string one at a time, chunk by chunk for a BIGSTR. */
    private inner class StringCursor {
        private var bytes = ByteArray(0)
        private var at = 0
        private var end = 0
        private var node: PathNode? = null
        private var nextChunk = 0L

        fun open(v: Any) {
            node = null
            when {
                v is LiteralValue.Str -> set(v.utf8, 0, v.utf8.size)
                (v as PathNode).form == PathNode.BIGSTR -> {
                    node = v
                    nextChunk = 0
                    set(bytes, 0, 0)
                }
                else -> set(v.holder!!.bytes, v.textOffset, v.textOffset + v.textLength)
            }
        }

        private fun set(b: ByteArray, from: Int, to: Int) {
            bytes = b
            at = from
            end = to
        }

        /** The next byte, unsigned, or -1 at the end. */
        fun take(): Int {
            while (at == end) {
                val n = node ?: return -1
                if (nextChunk == n.count) return -1
                val reader = doc.openChunk(n, nextChunk++)
                set(reader.payload, reader.offset, reader.offset + reader.length)
            }
            return bytes[at++].toInt() and 0xFF
        }
    }

    private val settings = CompareSettings(false, emptySet(), emptySet(), maxDifferences = 1, maxValueBytes = 0, verifySkippedSubtrees = true)

    private fun containersEqual(a: PathNode, b: PathNode): Boolean {
        if (a.pre == b.pre) return true
        if (a.size != b.size || doc.count(a) != doc.count(b)) return false
        val left = side(a)
        val right = side(b)
        return Comparison(left, right, settings) { null }.use { it.isEqual() }
    }

    private fun side(node: PathNode): CompareSide {
        val n = doc.resolve(node)
        val side = CompareSide(doc.store, doc.docId, stored = true, compareCacheBytes / 2, types = null, compareStats)
        val located = n.located()
        if (located.isSplit) side.setSplitRoot(n.pre) else side.setInlineRoot(n.pre, located.holderPre, located.holderKind, located.valueOffset)
        return side
    }

    companion object {
        const val DEFAULT_COMPARE_CACHE_BYTES: Long = 4L shl 20

        private val EMPTY = object : NodeStream() {
            override fun next(): PathNode? = null
        }
    }
}
