package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.DifferenceKind
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleNumbers
import java.util.Arrays
import java.util.IdentityHashMap

/** One side of an oracle comparison: a stored node, or JSON text parsed with the store's limits. */
sealed interface OOperand {
    data class Stored(val ref: ONodeRef) : OOperand

    data class Json(val json: String) : OOperand
}

data class OCompareOptions(
    val includeTypes: Boolean = false,
    val ignoreKeys: Set<String> = emptySet(),
    val ignorePaths: Set<String> = emptySet(),
    val maxDifferences: Int = Int.MAX_VALUE,
    val maxValueBytes: Int = Int.MAX_VALUE,
)

data class ODiffValue(val node: ONodeRef?, val json: String, val truncated: Boolean, val type: String?)

data class ODifference(val path: String, val kind: DifferenceKind, val left: ODiffValue?, val right: ODiffValue?)

/**
 * Comparison semantics (SPEC.md §9) as a plain recursion over [OValue]s, independent of the engine's streaming merge:
 *
 * - equality of SPEC.md §2; with `includeTypes`, objects also need the same assigned type (JSON operands have none);
 * - differences depth first: an object's type change before its members, members by unsigned UTF-8 key bytes, array
 *   elements by index, extra elements one difference each;
 * - ignored keys (any depth) and ignored paths (exact, with their subtrees) are left out on both sides;
 * - a value's JSON is its minified text cut to `maxValueBytes` bytes on a character boundary.
 */
internal class OracleComparison(
    private val store: OracleStore,
    private val options: OCompareOptions,
    private val left: Side,
    private val right: Side,
) {
    class Side(val docId: Long?, val rootPre: Long, val root: OValue)

    private val ignoredPaths: Set<List<Any>> = options.ignorePaths.map { path -> JsonPath.parse(path).steps.map { if (it is JsonPath.Step.Member) it.key else (it as JsonPath.Step.Index).index } }.toSet()
    private val sizes = IdentityHashMap<OValue, Long>()
    private val out = ArrayList<ODifference>()

    fun diff(): List<ODifference> {
        if (left.docId != null && left.docId == right.docId && left.rootPre == right.rootPre) return emptyList()
        compare(left.root, left.rootPre, right.root, right.rootPre, ArrayList())
        return out
    }

    private class Stop : RuntimeException(null, null, false, false)

    private fun compare(a: OValue?, aPre: Long, b: OValue?, bPre: Long, steps: MutableList<Any>) {
        try {
            walk(a!!, aPre, b!!, bPre, steps)
        } catch (_: Stop) {
        }
    }

    private fun walk(a: OValue, aPre: Long, b: OValue, bPre: Long, steps: MutableList<Any>) {
        if (steps in ignoredPaths) return
        if (typeOf(a) != typeOf(b)) return report(steps, DifferenceKind.VALUE_TYPE_CHANGED, a, aPre, b, bPre)
        when (a) {
            ONull -> {}
            is OBool -> if (a.value != (b as OBool).value) report(steps, DifferenceKind.CHANGED, a, aPre, b, bPre)
            is ONum -> if (OracleNumbers.canonical(a.text) != OracleNumbers.canonical((b as ONum).text)) report(steps, DifferenceKind.CHANGED, a, aPre, b, bPre)
            is OStr -> if (a.value != (b as OStr).value) report(steps, DifferenceKind.CHANGED, a, aPre, b, bPre)
            is OObj -> {
                if (options.includeTypes && assignedType(left, aPre) != assignedType(right, bPre)) report(steps, DifferenceKind.ASSIGNED_TYPE_CHANGED, a, aPre, b, bPre)
                val am = members(a, aPre, steps)
                val bm = members(b as OObj, bPre, steps)
                val keys = (am.keys + bm.keys).sortedWith { x, y -> Arrays.compareUnsigned(x.toByteArray(), y.toByteArray()) }
                for (key in keys) {
                    steps += key
                    val (av, ap) = am[key] ?: (null to 0L)
                    val (bv, bp) = bm[key] ?: (null to 0L)
                    when {
                        av != null && bv != null -> walk(av, ap, bv, bp, steps)
                        av != null -> report(steps, DifferenceKind.REMOVED, av, ap, null, 0)
                        else -> report(steps, DifferenceKind.ADDED, null, 0, bv, bp)
                    }
                    steps.removeAt(steps.size - 1)
                }
            }
            is OArr -> {
                val bItems = (b as OArr).items
                var ap = aPre + 1
                var bp = bPre + 1
                for (i in 0 until maxOf(a.items.size, bItems.size)) {
                    steps += i.toLong()
                    val av = a.items.getOrNull(i)
                    val bv = bItems.getOrNull(i)
                    if (steps !in ignoredPaths) {
                        when {
                            av != null && bv != null -> walk(av, ap, bv, bp, steps)
                            av != null -> report(steps, DifferenceKind.REMOVED, av, ap, null, 0)
                            else -> report(steps, DifferenceKind.ADDED, null, 0, bv, bp)
                        }
                    }
                    steps.removeAt(steps.size - 1)
                    if (av != null) ap += 1 + size(av)
                    if (bv != null) bp += 1 + size(bv)
                }
            }
        }
    }

    /** Members not ignored, by key, with their `pre`. */
    private fun members(obj: OObj, pre: Long, steps: MutableList<Any>): Map<String, Pair<OValue, Long>> {
        val result = HashMap<String, Pair<OValue, Long>>()
        var p = pre + 1
        for ((key, value) in obj.members) {
            steps += key
            if (key !in options.ignoreKeys && steps !in ignoredPaths) result[key] = value to p
            steps.removeAt(steps.size - 1)
            p += 1 + size(value)
        }
        return result
    }

    private fun size(value: OValue): Long = sizes.getOrPut(value) {
        when (value) {
            is OObj -> value.members.sumOf { 1 + size(it.second) }
            is OArr -> value.items.sumOf { 1 + size(it) }
            else -> 0L
        }
    }

    private fun typeOf(value: OValue): Int = when (value) {
        ONull -> 0
        is OBool -> 1
        is ONum -> 2
        is OStr -> 3
        is OObj -> 4
        is OArr -> 5
    }

    private fun assignedType(side: Side, pre: Long): String? = side.docId?.let { store.typeOf(ONodeRef(it, pre)) }

    private fun report(steps: List<Any>, kind: DifferenceKind, a: OValue?, aPre: Long, b: OValue?, bPre: Long) {
        out += ODifference(JsonPath.normalized(steps), kind, a?.let { value(left, it, aPre) }, b?.let { value(right, it, bPre) })
        if (out.size >= options.maxDifferences) throw Stop()
    }

    private fun value(side: Side, value: OValue, pre: Long): ODiffValue {
        val bytes = JsonText.minifiedBytes(value)
        val truncated = bytes.size > options.maxValueBytes
        var end = minOf(bytes.size, options.maxValueBytes)
        if (truncated) while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        val type = if (options.includeTypes && value is OObj) assignedType(side, pre) else null
        return ODiffValue(side.docId?.let { ONodeRef(it, pre) }, String(bytes, 0, end, Charsets.UTF_8), truncated, type)
    }
}

/** The oracle form of an engine difference, for exact comparison. */
fun app.oreshkov.delva.Difference.toOracle(): ODifference = ODifference(path, kind, left?.toOracle(), right?.toOracle())

fun app.oreshkov.delva.DiffValue.toOracle(): ODiffValue = ODiffValue(node?.let { ONodeRef(it.docId.raw, it.nodePre) }, json, truncated, type)
