package app.oreshkov.delva.testkit.json

import kotlin.random.Random

/**
 * Variants of a JSON value for comparison tests: a few random edits at random depths — a scalar changed within
 * its type, a value of another type, members added or removed, arrays grown or cut — plus edits that keep the value
 * equal: members reordered and numbers respelled. With `edits = 0` only the equal-keeping edits apply.
 */
object JsonMutator {
    fun mutate(value: OValue, random: Random, edits: Int): OValue {
        var result = cosmetic(value, random)
        repeat(edits) { result = edit(result, random) }
        return result
    }

    /** The same value with members reordered and numbers respelled here and there. */
    fun cosmetic(value: OValue, random: Random): OValue = when (value) {
        is OObj -> OObj(value.members.map { (k, v) -> k to cosmetic(v, random) }.let { if (random.nextInt(3) == 0) it.shuffled(random) else it })
        is OArr -> OArr(value.items.map { cosmetic(it, random) })
        is ONum -> if (random.nextInt(4) == 0) ONum(respell(value.text, random)) else value
        else -> value
    }

    private fun edit(value: OValue, random: Random): OValue {
        // Descend into a random child now and then, so edits land at every depth.
        if (random.nextInt(3) != 0) {
            when (value) {
                is OObj -> if (value.members.isNotEmpty()) {
                    val i = random.nextInt(value.members.size)
                    return OObj(value.members.mapIndexed { j, m -> if (j == i) m.first to edit(m.second, random) else m })
                }
                is OArr -> if (value.items.isNotEmpty()) {
                    val i = random.nextInt(value.items.size)
                    return OArr(value.items.mapIndexed { j, item -> if (j == i) edit(item, random) else item })
                }
                else -> {}
            }
        }
        return when (value) {
            is OObj -> when (random.nextInt(4)) {
                0 -> if (value.members.isEmpty()) scalar(random) else OObj(value.members.filterIndexed { j, _ -> j != random.nextInt(value.members.size) })
                1 -> {
                    val key = freshKey(value, random)
                    OObj(value.members.toMutableList().apply { add(random.nextInt(size + 1), key to scalar(random)) })
                }
                2 -> OArr(value.members.map { it.second })
                else -> scalar(random)
            }
            is OArr -> when (random.nextInt(4)) {
                0 -> OArr(value.items.take(random.nextInt(value.items.size + 1)))
                1 -> OArr(value.items + List(random.nextInt(1, 4)) { scalar(random) })
                2 -> OObj(value.items.mapIndexed { i, item -> "i$i" to item })
                else -> scalar(random)
            }
            ONull -> if (random.nextBoolean()) OBool(false) else OStr("null")
            is OBool -> if (random.nextBoolean()) OBool(!value.value) else ONull
            is ONum -> when (random.nextInt(3)) {
                0 -> ONum(if (value.text.startsWith("-")) value.text.substring(1) else "-${value.text}")
                1 -> ONum(if (value.text == "7") "8" else "7")
                else -> OStr(value.text)
            }
            is OStr -> when (random.nextInt(3)) {
                0 -> OStr(value.value + "x")
                1 -> OStr(if (value.value.isEmpty()) "é" else value.value.dropLast(1))
                else -> ONum(value.value.length.toString())
            }
        }
    }

    private fun freshKey(obj: OObj, random: Random): String {
        val keys = obj.members.map { it.first }.toSet()
        while (true) {
            val key = listOf("id", "name", "x", "new", "k${random.nextInt(100)}", "é", "a b", "")[random.nextInt(8)]
            if (key !in keys) return key
        }
    }

    private fun scalar(random: Random): OValue = when (random.nextInt(5)) {
        0 -> ONull
        1 -> OBool(random.nextBoolean())
        2 -> ONum(random.nextInt(-5, 100).toString())
        3 -> OStr("s" + random.nextInt(10))
        else -> OObj("v" to ONum("1"))
    }

    /** The same decimal value spelled differently: an exponent, trailing zeros, or both. */
    private fun respell(text: String, random: Random): String {
        if (text.contains('e') || text.contains('E')) return text
        return when (random.nextInt(3)) {
            0 -> if (text.contains('.')) text + "0" else "$text.0"
            1 -> "${text}e0"
            else -> if (text.contains('.')) "${text}00E+0" else "$text.0E+0"
        }
    }
}
