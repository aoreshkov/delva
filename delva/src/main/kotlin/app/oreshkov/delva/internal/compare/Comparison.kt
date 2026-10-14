package app.oreshkov.delva.internal.compare

import app.oreshkov.delva.DiffValue
import app.oreshkov.delva.DocId
import app.oreshkov.delva.Difference
import app.oreshkov.delva.DifferenceKind
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.payload.JsonEvents
import app.oreshkov.delva.internal.payload.JsonOutput
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.path.NormalizedPath
import app.oreshkov.delva.internal.split.TypePaths
import java.io.OutputStream
import java.util.Arrays

/**
 * Compares two values by a streaming merge-compare with an explicit stack: object members in key order
 * on both sides, array elements by index, so memory is O(depth) frames plus what the cursors hold — a decoded fragment
 * per open level and, for a split object, the part indexes of its merge.
 *
 * Canonical hashes are used where they're free: stubs carry them, a value given as JSON has its root
 * hash from splitting, and a stored inline root is hashed from its one fragment. In [isEqual] unequal hashes answer
 * "different" at once and equal ones are always confirmed by comparing; in a diff, subtrees with equal hashes are
 * skipped unless [CompareSettings.verifySkippedSubtrees]. Neither applies below a path whose content may be ignored,
 * and equal hashes don't skip where objects may carry assigned types that are compared.
 *
 * A diff ([next]) reports differences in canonical order: depth first, members by unsigned key bytes, elements by
 * index. [typeName] names typeIds for [DiffValue.type]. Closing releases what the cursors hold; the caller closes the
 * sides' stores. Not thread-safe.
 */
internal class Comparison(
    private val left: CompareSide,
    private val right: CompareSide,
    private val settings: CompareSettings,
    private val typeName: (Int) -> String?,
) : AutoCloseable {
    private val paths = settings.paths
    private var diffMode = false
    private var started = false
    private var finished = false
    private var unequal = false
    private var pending: Difference? = null
    private var emitted = 0

    private val lv = ValueRef()
    private val rv = ValueRef()
    private val leftString = StringStream()
    private val rightString = StringStream()
    private val leftNumber = GrowableBytes(64)
    private val rightNumber = GrowableBytes(64)
    private val hasher = CanonicalHash()

    private class Frame {
        var isObject = false
        var left: ChildCursor? = null
        var right: ChildCursor? = null
        var pathNode = TypePaths.NONE
        var index = 0L
        var stepIsKey = false
        val stepKey = GrowableBytes(64)
        var stepIndex = 0L
    }

    private val frames = ArrayList<Frame>()
    private var depth = 0

    /** Whether the values are equal. */
    fun isEqual(): Boolean {
        check(!started)
        diffMode = false
        if (sameStoredNode()) return true
        if (!rootHashesMayMatch()) return false.also { left.stats.unequalWithoutReading.increment() }
        while (!finished) step()
        return !unequal
    }

    /** The next difference, or null after the last one or [CompareSettings.maxDifferences] of them. */
    fun next(): Difference? {
        if (!diffMode) {
            check(!started)
            diffMode = true
            if (sameStoredNode()) finished = true
        }
        while (true) {
            if (emitted >= settings.maxDifferences) return null
            pending?.let {
                pending = null
                emitted++
                return it
            }
            if (finished) return null
            step()
        }
    }

    /** Both operands name one stored node, which is equal to itself (immutable content, one snapshot). */
    private fun sameStoredNode(): Boolean = left.stored && right.stored && left.docId == right.docId && left.valuePre == right.valuePre

    /** False when the root hashes are both known and differ, and nothing may be ignored. */
    private fun rootHashesMayMatch(): Boolean {
        val node = paths.root
        if (settings.isIgnored(node) || !settings.ignoresNothingBelow(node)) return true
        val l = LongArray(2)
        val r = LongArray(2)
        try {
            left.root(lv)
            right.root(rv)
            if (!rootHash(left, lv, l) || !rootHash(right, rv, r)) return true
        } finally {
            lv.release()
            rv.release()
        }
        return l[0] == r[0] && l[1] == r[1]
    }

    private fun rootHash(side: CompareSide, value: ValueRef, out: LongArray): Boolean {
        when {
            side.rootHashKnown -> {
                out[0] = side.rootHashLow
                out[1] = side.rootHashHigh
            }
            value.hasHash -> {
                out[0] = value.hashLow
                out[1] = value.hashHigh
            }
            value.jsonType == JsonType.OBJECT || value.jsonType == JsonType.ARRAY -> value.tree!!.hash(value.node, hasher, leftNumber, out, 0)
            else -> return false
        }
        return true
    }

    private fun step() {
        if (!started) {
            started = true
            try {
                left.root(lv)
                right.root(rv)
                compareValues(paths.root)
            } finally {
                lv.release()
                rv.release()
            }
            if (depth == 0) finished = true
            return
        }
        if (depth == 0) {
            finished = true
            return
        }
        val frame = frames[depth - 1]
        if (frame.isObject) stepObject(frame) else stepArray(frame)
        if (depth == 0 && !unequal) finished = true
    }

    private fun stepObject(f: Frame) {
        val l = f.left!!
        val r = f.right!!
        while (l.valid && ignoredMember(f, l)) l.next()
        while (r.valid && ignoredMember(f, r)) r.next()
        if (!l.valid && !r.valid) {
            pop()
            return
        }
        val c = when {
            !l.valid -> 1
            !r.valid -> -1
            else -> Arrays.compareUnsigned(l.keyBytes, l.keyOffset, l.keyOffset + l.keyLength, r.keyBytes, r.keyOffset, r.keyOffset + r.keyLength)
        }
        val cursor = if (c > 0) r else l
        f.stepIsKey = true
        f.stepKey.clear()
        f.stepKey.writeBytes(cursor.keyBytes, cursor.keyOffset, cursor.keyLength)
        try {
            when {
                c < 0 -> {
                    l.value(lv)
                    l.next()
                    difference(DifferenceKind.REMOVED, lv, null)
                }
                c > 0 -> {
                    r.value(rv)
                    r.next()
                    difference(DifferenceKind.ADDED, null, rv)
                }
                else -> {
                    val node = paths.childByKey(f.pathNode, f.stepKey.array, 0, f.stepKey.size)
                    l.value(lv)
                    r.value(rv)
                    l.next()
                    r.next()
                    compareValues(node)
                }
            }
        } finally {
            lv.release()
            rv.release()
        }
    }

    private fun ignoredMember(f: Frame, cursor: ChildCursor): Boolean =
        settings.isIgnoredKey(cursor.keyBytes, cursor.keyOffset, cursor.keyLength) ||
            settings.isIgnored(paths.childByKey(f.pathNode, cursor.keyBytes, cursor.keyOffset, cursor.keyLength))

    private fun stepArray(f: Frame) {
        val l = f.left!!
        val r = f.right!!
        if (!l.valid && !r.valid) {
            pop()
            return
        }
        val index = f.index++
        f.stepIsKey = false
        f.stepIndex = index
        val node = paths.childByIndex(f.pathNode, index)
        try {
            when {
                settings.isIgnored(node) -> {
                    if (l.valid) l.next()
                    if (r.valid) r.next()
                }
                l.valid && r.valid -> {
                    l.value(lv)
                    r.value(rv)
                    l.next()
                    r.next()
                    compareValues(node)
                }
                l.valid -> {
                    l.value(lv)
                    l.next()
                    difference(DifferenceKind.REMOVED, lv, null)
                }
                else -> {
                    r.value(rv)
                    r.next()
                    difference(DifferenceKind.ADDED, null, rv)
                }
            }
        } finally {
            lv.release()
            rv.release()
        }
    }

    /** Compares [lv] and [rv] at [pathNode]; may report a difference and open a frame for their children. */
    private fun compareValues(pathNode: Int) {
        if (settings.isIgnored(pathNode)) return
        if (lv.jsonType != rv.jsonType) return difference(DifferenceKind.VALUE_TYPE_CHANGED, lv, rv)
        when (lv.jsonType) {
            JsonType.NULL -> {}
            JsonType.BOOLEAN -> if (lv.event != rv.event) difference(DifferenceKind.CHANGED, lv, rv)
            JsonType.NUMBER -> if (!numbersEqual()) difference(DifferenceKind.CHANGED, lv, rv)
            JsonType.STRING -> if (!stringsEqual()) difference(DifferenceKind.CHANGED, lv, rv)
            else -> containers(pathNode)
        }
    }

    private fun containers(pathNode: Int) {
        val typesMatter = settings.includeTypes && (left.mayHaveTypes || right.mayHaveTypes)
        val exact = settings.ignoresNothingBelow(pathNode)
        if (typesMatter && lv.jsonType == JsonType.OBJECT && left.typeId(lv.pre) != right.typeId(rv.pre)) {
            difference(DifferenceKind.ASSIGNED_TYPE_CHANGED, lv, rv)
            if (!diffMode) return
        }
        if (lv.hasHash && rv.hasHash) {
            val same = lv.hashLow == rv.hashLow && lv.hashHigh == rv.hashHigh
            if (same && diffMode && !settings.verifySkippedSubtrees && !typesMatter) return left.stats.skippedByHash.increment()
            if (!same && !diffMode && exact) return markUnequal().also { left.stats.unequalWithoutReading.increment() }
        }
        if (!diffMode && exact && (lv.size != rv.size || (lv.count >= 0 && rv.count >= 0 && lv.count != rv.count))) return markUnequal().also { left.stats.unequalWithoutReading.increment() }
        push(pathNode)
    }

    private fun numbersEqual(): Boolean {
        val lt = lv.tree!!
        val rt = rv.tree!!
        if (lv.event == PayloadReader.INT && rv.event == PayloadReader.INT) return lt.intValue(lv.node) == rt.intValue(rv.node)
        val ll = lt.numberKey(lv.node, leftNumber)
        val rl = rt.numberKey(rv.node, rightNumber)
        return Arrays.equals(leftNumber.array, 0, ll, rightNumber.array, 0, rl)
    }

    private fun stringsEqual(): Boolean {
        if (lv.size != rv.size) return false
        if (lv.event == PayloadReader.STR && rv.event == PayloadReader.STR) {
            val lt = lv.tree!!
            val rt = rv.tree!!
            val lo = lt.textOffset(lv.node)
            val ro = rt.textOffset(rv.node)
            return Arrays.equals(lt.bytes, lo, lo + lv.size.toInt(), rt.bytes, ro, ro + rv.size.toInt())
        }
        if (lv.hasHash && rv.hasHash) {
            val same = lv.hashLow == rv.hashLow && lv.hashHigh == rv.hashHigh
            if (!same) return false
            if (diffMode && !settings.verifySkippedSubtrees) return true.also { left.stats.skippedByHash.increment() }
        }
        val l = leftString
        val r = rightString
        l.open(lv)
        r.open(rv)
        while (true) {
            // Chunks are never empty, so a side has bytes left exactly when it's inside a part or can advance.
            val lHas = l.position < l.end || l.advance()
            val rHas = r.position < r.end || r.advance()
            if (!lHas || !rHas) return !lHas && !rHas
            val n = minOf(l.end - l.position, r.end - r.position)
            if (!Arrays.equals(l.bytes, l.position, l.position + n, r.bytes, r.position, r.position + n)) return false
            l.position += n
            r.position += n
        }
    }

    private fun push(pathNode: Int) {
        if (depth == frames.size) frames += Frame()
        val f = frames[depth]
        val l = left.openCursor(lv)
        val r = try {
            right.openCursor(rv)
        } catch (t: Throwable) {
            l.close()
            throw t
        }
        f.isObject = lv.jsonType == JsonType.OBJECT
        f.left = l
        f.right = r
        f.pathNode = pathNode
        f.index = 0
        depth++
    }

    private fun pop() {
        val f = frames[--depth]
        try {
            f.left?.close()
        } finally {
            f.right?.close()
            f.left = null
            f.right = null
        }
    }

    private fun markUnequal() {
        unequal = true
        finished = true
    }

    private fun difference(kind: DifferenceKind, l: ValueRef?, r: ValueRef?) {
        if (!diffMode) return markUnequal()
        check(pending == null) { "two differences in one step" }
        pending = Difference(path(), kind, l?.let { preview(left, it) }, r?.let { preview(right, it) })
    }

    private fun path(): String {
        val sb = StringBuilder("$")
        for (i in 0 until depth) {
            val f = frames[i]
            if (f.stepIsKey) NormalizedPath.appendName(sb, f.stepKey.array, f.stepKey.size) else NormalizedPath.appendIndex(sb, f.stepIndex)
        }
        return sb.toString()
    }

    private fun preview(side: CompareSide, value: ValueRef): DiffValue {
        val max = settings.maxValueBytes
        val bytes = CappedBytes(max)
        val json = JsonOutput(bytes, PREVIEW_BUFFER_BYTES)
        side.writeJson(value, BoundedEvents(json, max.toLong()))
        json.flush()
        val truncated = bytes.total > max
        var end = minOf(bytes.total, max.toLong()).toInt()
        if (truncated) {
            while (end > 0 && bytes.data[end].toInt() and 0xC0 == 0x80) end--
        }
        val type = if (settings.includeTypes && value.jsonType == JsonType.OBJECT) typeName(side.typeId(value.pre)) else null
        val node = if (side.stored) NodeRef(DocId(side.docId), value.pre) else null
        return DiffValue(node, String(bytes.data, 0, end, Charsets.UTF_8), truncated, type)
    }

    override fun close() {
        finished = true
        var failure: Throwable? = null
        while (depth > 0) {
            try {
                pop()
            } catch (t: Throwable) {
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
        }
        lv.release()
        rv.release()
        failure?.let { throw it }
    }

    /** Keeps the first `max + 1` bytes written and counts the rest. */
    private class CappedBytes(max: Int) : OutputStream() {
        private val keep = max.toLong() + 1
        val kept = GrowableBytes(minOf(keep, 256L).toInt())
        var total = 0L

        val data: ByteArray get() = kept.array

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (total < keep) kept.writeBytes(b, off, minOf(len.toLong(), keep - total).toInt())
            total += len
        }
    }

    /** [JsonOutput] that asks its producer to stop once more than [max] bytes are out. */
    private class BoundedEvents(private val out: JsonOutput, private val max: Long) : JsonEvents by out {
        override val isDone: Boolean get() = out.bytesWritten > max
    }

    private companion object {
        const val PREVIEW_BUFFER_BYTES = 1024
    }
}

/** What comparisons did, summed over a store's comparisons; for tests and diagnostics. Thread-safe. */
internal class CompareStats {
    /** Container or string pairs a diff took as equal from their hashes. */
    val skippedByHash = java.util.concurrent.atomic.LongAdder()

    /** `isEqual` calls or subtrees answered "different" from unequal hashes, counts or sizes without reading further. */
    val unequalWithoutReading = java.util.concurrent.atomic.LongAdder()

    /** Fragments decoded into value trees, and PART indexes (re)built for split-object merges. */
    val treesBuilt = java.util.concurrent.atomic.LongAdder()
    val partIndexesBuilt = java.util.concurrent.atomic.LongAdder()
}
