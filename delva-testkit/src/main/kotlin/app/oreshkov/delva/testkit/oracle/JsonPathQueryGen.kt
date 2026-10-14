package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import kotlin.random.Random

/**
 * Random RFC 9535 queries for differential tests, built from the keys and scalar values of sample documents so that
 * they select something often: names, wildcards, indexes and slices (negative ones too), descendant segments, and
 * filters with comparisons, existence tests, `!`, `&&`, `||`, parentheses, absolute queries and all five functions.
 * Spellings vary: shorthand or quoted names, either quote, blank space where the grammar allows it.
 */
class JsonPathQueryGen(samples: List<OValue>) {
    private val keys = ArrayList<String>()
    private val scalars = ArrayList<OValue>()

    init {
        fun walk(v: OValue) {
            when (v) {
                is OObj -> v.members.forEach { (k, m) ->
                    keys += k
                    walk(m)
                }
                is OArr -> v.items.forEach { walk(it) }
                else -> scalars += v
            }
        }
        samples.forEach { walk(it) }
        if (keys.isEmpty()) keys += "a"
        if (scalars.isEmpty()) scalars += ONum("1")
    }

    private val samples = samples.toList()

    /** Object members with a scalar value, or an array value's scalar elements: the terms a store indexes. */
    private val members = ArrayList<Pair<String, OValue>>()

    /**
     * For each member of [members], the steps (keys and array indexes, with the array's length for negative indexes)
     * from the root to the parent of the object holding it; empty for the root object, which a filter can't select.
     */
    private val parentSteps = ArrayList<List<Pair<Any, Int>>?>()

    init {
        fun walk(v: OValue, steps: List<Pair<Any, Int>>, parentOfObject: List<Pair<Any, Int>>?) {
            when (v) {
                is OObj -> v.members.forEach { (k, m) ->
                    when (m) {
                        is OObj -> {}
                        is OArr -> m.items.filter { it !is OObj && it !is OArr }.forEach {
                            members += k to it
                            parentSteps += parentOfObject
                        }
                        else -> {
                            members += k to m
                            parentSteps += parentOfObject
                        }
                    }
                    walk(m, steps + (k to 0), steps)
                }
                is OArr -> v.items.forEachIndexed { i, m -> walk(m, steps + (i to v.items.size), steps) }
                else -> {}
            }
        }
        samples.forEach { walk(it, emptyList(), null) }
    }

    /** `$` and [steps] written as names and indexes, a negative index now and then. */
    private fun fixedPrefix(steps: List<Pair<Any, Int>>, random: Random): String = buildString {
        append('$')
        for ((step, length) in steps) {
            when {
                step is String && shorthandable(step) && random.nextBoolean() -> append('.').append(step)
                step is String -> append('[').append(name(step, random)).append(']')
                random.nextInt(3) == 0 -> append('[').append((step as Int) - length).append(']')
                else -> append('[').append(step).append(']')
            }
        }
    }

    /**
     * A query whose filters compare members with values the samples have, the shape a search can look up in the
     * index: `$..[?@.k == v]`, conjunctions and disjunctions of such comparisons, further segments after the filter,
     * an index after the name, and now and then something that defeats the index (`!`, `!=`, a non-filter selector).
     */
    fun filteredQuery(random: Random): String {
        if (members.isEmpty()) return query(random)
        val picked = random.nextInt(members.size)
        fun term(index: Int = random.nextInt(members.size)): String {
            val (k, v) = members[index]
            val value = scalar(v, random)
            val access = if (shorthandable(k) && random.nextBoolean()) "@.$k" else "@[${name(k, random)}]"
            val op = when (random.nextInt(10)) {
                0 -> "!="
                1 -> "<"
                2 -> "<="
                3 -> ">"
                4 -> ">="
                else -> "=="
            }
            return if (random.nextBoolean()) "$access$op$value" else "$value $op $access"
        }
        val filter = when (random.nextInt(8)) {
            0 -> term(picked) + " && " + term()
            1 -> term(picked) + " || " + term()
            2 -> "(" + term(picked) + " || " + term() + ") && " + term()
            3 -> "!(" + term(picked) + ")"
            4 -> term(picked) + " && " + logical(random, 1)
            else -> term(picked)
        }
        // Half the time the path down to the filtered objects is fixed, as the objects of the picked member have it.
        val steps = parentSteps[picked]
        val prefix = if (steps != null && random.nextBoolean()) {
            fixedPrefix(steps, random)
        } else {
            listOf("$..", "$..", "$..", "$..*", "$.*", "$", "$..*..")[random.nextInt(7)]
        }
        val selectors = if (random.nextInt(8) == 0) "[?$filter, 0]" else "[?$filter]"
        val tail = if (random.nextInt(3) == 0) segment(random, depth = 1) else ""
        return prefix + selectors + tail
    }

    /** Members of the samples' roots, as [members] has them: the terms whose containing object is a root. */
    private val rootMembers = members.indices.filter { parentSteps[it] == null }.map { members[it] }

    /**
     * A document filter (a logical expression whose `@` and `$` are the root) that compares members of the samples'
     * roots with their values more often than not, so it accepts some documents and its terms narrow a search: `@.k ==
     * v` or `$['k'] == v`, conjunctions, disjunctions and negations of such comparisons, comparisons of members that
     * aren't at the root, and now and then any logical expression.
     */
    fun documentFilter(random: Random): String {
        val pool = if (rootMembers.isNotEmpty() && random.nextInt(5) != 0) rootMembers else members
        if (pool.isEmpty() || random.nextInt(8) == 0) return logical(random, 1)
        fun term(): String {
            val (k, v) = pool[random.nextInt(pool.size)]
            val value = scalar(v, random)
            val at = if (random.nextInt(4) == 0) "$" else "@"
            val access = if (shorthandable(k) && random.nextBoolean()) "$at.$k" else "$at[${name(k, random)}]"
            val op = when (random.nextInt(10)) {
                0 -> "!="
                1 -> "<"
                2 -> ">="
                else -> "=="
            }
            return if (random.nextBoolean()) "$access$op$value" else "$value $op $access"
        }
        return when (random.nextInt(8)) {
            0 -> term() + " && " + term()
            1 -> term() + " || " + term()
            2 -> "(" + term() + " || " + term() + ") && " + term()
            3 -> "!(" + term() + ")"
            4 -> term() + " && " + logical(random, 1)
            else -> blank(random) + term() + blank(random)
        }
    }

    /**
     * A query that mostly follows the structure of a sample: each segment selects a child of the value reached so far
     * more often than not, so queries select something often; the rest are random segments.
     */
    fun query(random: Random): String = buildString {
        append('$')
        var current: OValue? = samples[random.nextInt(samples.size)]
        repeat(random.nextInt(4) + (if (random.nextInt(4) == 0) 0 else 1)) {
            val node = current
            val children = when (node) {
                is OObj -> node.members.map { (k, v) -> k to v }
                is OArr -> node.items.mapIndexed { i, v -> i to v }
                else -> emptyList()
            }
            if (children.isEmpty() || random.nextInt(4) == 0) {
                append(segment(random, depth = 0))
                current = null
                return@repeat
            }
            val (step, child) = children[random.nextInt(children.size)]
            val blank = blank(random)
            append(blank)
            when {
                step is String && shorthandable(step) && random.nextBoolean() -> append('.').append(step)
                step is String -> append('[').append(name(step, random)).append(']')
                random.nextInt(3) == 0 -> append('[').append((step as Int) - children.size).append(']')
                random.nextInt(4) == 0 -> append("[?").append(blank(random)).append(logical(random, 1)).append(']')
                else -> append('[').append(step).append(']')
            }
            current = child
        }
    }

    private fun blank(random: Random): String = if (random.nextInt(5) == 0) listOf(" ", "\n", "\t ", "  ")[random.nextInt(4)] else ""

    private fun key(random: Random): String = if (random.nextInt(10) == 0) "zz" else keys[random.nextInt(keys.size)]

    private fun name(key: String, random: Random): String {
        val quote = if (random.nextBoolean()) '\'' else '"'
        return quote + key.flatMap {
            when (it) {
                quote, '\\' -> listOf('\\', it)
                '\n' -> listOf('\\', 'n')
                '\r' -> listOf('\\', 'r')
                '\t' -> listOf('\\', 't')
                '\b' -> listOf('\\', 'b')
                '\u000C' -> listOf('\\', 'f')
                else -> if (it < ' ') "\\u%04x".format(it.code).toList() else listOf(it)
            }
        }.joinToString("") + quote
    }

    private fun shorthandable(key: String) = Regex("""[A-Za-z_\x{80}-\x{10FFFF}][A-Za-z0-9_\x{80}-\x{10FFFF}]*""").matches(key)

    private fun segment(random: Random, depth: Int): String {
        val descendant = random.nextInt(5) == 0
        val prefix = blank(random) + if (descendant) ".." else ""
        val k = key(random)
        return when {
            random.nextInt(3) == 0 && shorthandable(k) -> prefix + (if (descendant) "" else ".") + k
            random.nextInt(8) == 0 -> prefix + (if (descendant) "*" else ".*")
            else -> {
                val n = 1 + (if (random.nextInt(4) == 0) random.nextInt(3) else 0)
                prefix + "[" + blank(random) + (1..n).joinToString(blank(random) + "," + blank(random)) { selector(random, depth) } + blank(random) + "]"
            }
        }
    }

    private fun int(random: Random): Long = random.nextInt(-4, 6).toLong()

    private fun selector(random: Random, depth: Int): String = when (random.nextInt(10)) {
        0, 1, 2 -> name(key(random), random)
        3 -> "*"
        4, 5 -> int(random).toString()
        6 -> {
            fun bound() = if (random.nextBoolean()) int(random).toString() else ""
            val step = when (random.nextInt(3)) {
                0 -> ""
                1 -> ":"
                else -> ":" + listOf(1, 2, -1, -2, 3, 0)[random.nextInt(6)]
            }
            bound() + ":" + bound() + step
        }
        else -> "?" + blank(random) + if (depth < 2) logical(random, depth + 1) else "@"
    }

    private fun logical(random: Random, depth: Int): String = when (random.nextInt(9)) {
        0 -> logical(random, depth) + blank(random) + "&&" + blank(random) + basic(random, depth)
        1 -> logical(random, depth) + blank(random) + "||" + blank(random) + basic(random, depth)
        else -> basic(random, depth)
    }.takeIf { it.length < 400 } ?: "@"

    private fun basic(random: Random, depth: Int): String = when (random.nextInt(10)) {
        0 -> "!" + blank(random) + "(" + logical(random, depth) + ")"
        1 -> "(" + blank(random) + logical(random, depth) + blank(random) + ")"
        2 -> (if (random.nextBoolean()) "!" else "") + relative(random, depth, singular = false)
        3 -> "match(" + singular(random) + "," + blank(random) + pattern(random) + ")"
        4 -> "search(" + singular(random) + "," + pattern(random) + ")"
        else -> comparison(random, depth)
    }

    private fun comparison(random: Random, depth: Int): String {
        val op = listOf("==", "!=", "<", "<=", ">", ">=")[random.nextInt(6)]
        val left = comparable(random, depth)
        val right = comparable(random, depth)
        return left + blank(random) + op + blank(random) + right
    }

    private fun comparable(random: Random, depth: Int): String = when (random.nextInt(8)) {
        0, 1, 2 -> literal(random)
        3, 4 -> singular(random)
        5 -> "length(" + singular(random) + ")"
        6 -> "count(" + relative(random, depth, singular = false) + ")"
        else -> "value(" + relative(random, depth, singular = false) + ")"
    }

    private fun literal(random: Random): String = scalar(
        when (random.nextInt(8)) {
            0 -> ONull
            1 -> OBool(true)
            2 -> OBool(false)
            3 -> ONum(random.nextInt(-3, 10).toString())
            else -> scalars[random.nextInt(scalars.size)]
        },
        random,
    )

    /**
     * [v] as a literal: a string quoted by [name], a number in its own spelling. While [template] runs, now and then a
     * placeholder instead, for a value that a [JsonQueryValue] can hold.
     */
    private fun scalar(v: OValue, random: Random): String {
        val text = when (v) {
            is OStr -> name(v.value, random)
            is ONum -> v.text
            else -> JsonText.minified(v)
        }
        val slots = slots ?: return text
        if (random.nextBoolean()) return text
        val value = when (v) {
            is OStr -> JsonQueryValue.Str(v.value)
            is ONum -> v.text.toBigDecimalOrNull()?.let { JsonQueryValue.Num(it) } ?: return text
            is OBool -> JsonQueryValue.Bool(v.value)
            ONull -> JsonQueryValue.Null
            else -> return text
        }
        // Now and then a name used before for the same value, so that one placeholder stands in several places.
        val name = slots.firstOrNull { it.value == value && random.nextInt(3) == 0 }?.name ?: "p${slots.size}"
        slots += Slot(name, value, text)
        return MARK + (slots.size - 1) + MARK
    }

    private class Slot(val name: String, val value: JsonQueryValue, val literal: String)

    /** The placeholders of the [template] being generated, or null outside one. */
    private var slots: MutableList<Slot>? = null

    /**
     * A query or filter with placeholders: what [generate] makes of this generator (such as [filteredQuery] or
     * [documentFilter]), with about half its literals (comparison operands, `match` and `search` patterns) written as
     * `${p0}`, `${p1}`, …, a name now and then repeated for an equal value. [Template.filled] is the same text with the
     * literals in place, which an oracle parses. Not thread-safe, as the generator isn't.
     */
    fun template(random: Random, generate: (Random) -> String): Template {
        val slots = ArrayList<Slot>()
        this.slots = slots
        val marked = try {
            generate(random)
        } finally {
            this.slots = null
        }
        val placeholders = StringBuilder()
        val filled = StringBuilder()
        val parts = marked.split(MARK)
        for ((k, part) in parts.withIndex()) {
            if (k % 2 == 0) {
                placeholders.append(part)
                filled.append(part)
            } else {
                val slot = slots[part.toInt()]
                placeholders.append("\${").append(slot.name).append('}')
                filled.append(slot.literal)
            }
        }
        return Template(placeholders.toString(), slots.associate { it.name to it.value }, filled.toString())
    }

    /** A template's [text], the [values] its placeholders are bound to, and the text with the literals in place. */
    class Template(val text: String, val values: Map<String, JsonQueryValue>, val filled: String) {
        override fun toString(): String = "'$text' with $values"
    }

    /** A singular query: `@` or `$` and names or indexes, without blank space inside brackets. */
    private fun singular(random: Random): String = buildString {
        append(if (random.nextInt(6) == 0) '$' else '@')
        repeat(random.nextInt(3)) {
            val k = key(random)
            when {
                random.nextInt(4) == 0 -> append('[').append(int(random)).append(']')
                shorthandable(k) && random.nextBoolean() -> append('.').append(k)
                else -> append('[').append(name(k, random)).append(']')
            }
        }
    }

    private fun relative(random: Random, depth: Int, singular: Boolean): String {
        if (singular || random.nextBoolean()) return singular(random)
        return "@" + (1..random.nextInt(1, 3)).joinToString("") { segment(random, depth + 1) }
    }

    private fun pattern(random: Random): String {
        val k = scalars.filterIsInstance<OStr>().takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)].value.take(3) } ?: "a"
        val literal = k.map { if (it.isLetterOrDigit()) it.toString() else "." }.joinToString("")
        return scalar(OStr(listOf(literal, "$literal.*", ".*$literal", "[a-m]+", "\\p{L}*", "(a|b)?.", "[^0-9]{1,3}")[random.nextInt(7)]), random)
    }

    private companion object {
        /** Brackets a placeholder's slot number while a template is generated; [name] escapes U+0000, so no query has it. */
        const val MARK = "\u0000"
    }
}
