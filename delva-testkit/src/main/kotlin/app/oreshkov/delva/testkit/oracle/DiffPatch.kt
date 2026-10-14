package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.DifferenceKind
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson

/**
 * Applies a diff to its left value (`apply(diff(a, b), a) == b`). Needs complete right values (no truncation).
 * Changes and additions apply in order — an array only grows at its end, so indexes stay valid — then removals in
 * reverse order, so removing an array's tail goes from the last element down. Type changes carry no content.
 */
object DiffPatch {
    class Change(val path: String, val kind: DifferenceKind, val rightJson: String?)

    fun apply(left: OValue, changes: List<Change>): OValue {
        var value = left
        for (c in changes) {
            when (c.kind) {
                DifferenceKind.CHANGED, DifferenceKind.VALUE_TYPE_CHANGED -> value = update(value, steps(c.path), 0) { parse(c.rightJson) }
                DifferenceKind.ADDED -> value = update(value, steps(c.path), 0) { old ->
                    check(old == null) { "ADDED at ${c.path} over an existing value" }
                    parse(c.rightJson)
                }
                DifferenceKind.REMOVED, DifferenceKind.ASSIGNED_TYPE_CHANGED -> {}
            }
        }
        for (c in changes.asReversed()) {
            if (c.kind == DifferenceKind.REMOVED) value = update(value, steps(c.path), 0) { old ->
                checkNotNull(old) { "REMOVED at ${c.path} of a missing value" }
                null
            }
        }
        return value
    }

    private fun parse(json: String?): OValue = OracleJson.parseOrThrow(checkNotNull(json) { "difference without a right value" })

    private fun steps(path: String): List<JsonPath.Step> = JsonPath.parse(path).steps

    /** [root] with the value at `steps[from..]` replaced by [f] of the old one (null: absent / remove). */
    private fun update(root: OValue?, steps: List<JsonPath.Step>, from: Int, f: (OValue?) -> OValue?): OValue {
        if (from == steps.size) return checkNotNull(f(root)) { "the root can't be removed" }
        return when (val step = steps[from]) {
            is JsonPath.Step.Member -> {
                val obj = root as? OObj ?: error("member step ${step.key} into $root")
                val members = obj.members.toMutableList()
                val i = members.indexOfFirst { it.first == step.key }
                val old = if (i >= 0) members[i].second else null
                val new = if (from + 1 == steps.size) f(old) else update(checkNotNull(old) { "no member ${step.key}" }, steps, from + 1, f)
                when {
                    new == null -> members.removeAt(i)
                    i >= 0 -> members[i] = step.key to new
                    else -> members += step.key to new
                }
                OObj(members)
            }
            is JsonPath.Step.Index -> {
                val index = Math.toIntExact(step.index)
                val arr = root as? OArr ?: error("index step ${step.index} into $root")
                val items = arr.items.toMutableList()
                val old = items.getOrNull(index)
                val new = if (from + 1 == steps.size) f(old) else update(checkNotNull(old) { "no element ${step.index}" }, steps, from + 1, f)
                when {
                    new == null -> {
                        check(index == items.size - 1) { "removing element ${step.index} that isn't the last of ${items.size}" }
                        items.removeAt(index)
                    }
                    old != null -> items[index] = new
                    else -> {
                        check(index == items.size) { "adding element ${step.index} to an array of ${items.size}" }
                        items += new
                    }
                }
                OArr(items)
            }
        }
    }
}
