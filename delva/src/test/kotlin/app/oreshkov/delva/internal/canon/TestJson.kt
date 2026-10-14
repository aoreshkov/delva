package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.testkit.property.Gen
import java.math.BigDecimal
import kotlin.random.Random

/** Minimal JSON tree for the canonical-form tests, independent of the testkit's oracle model. */
internal sealed interface TJ {
    data object Null : TJ
    data class Bool(val value: Boolean) : TJ
    data class Num(val text: String) : TJ
    data class Str(val value: String) : TJ
    data class Arr(val items: List<TJ>) : TJ
    data class Obj(val members: List<Pair<String, TJ>>) : TJ
}

/** A canonical description: equal descriptions mean semantically equal values. */
internal fun TJ.describe(): String = when (this) {
    TJ.Null -> "null"
    is TJ.Bool -> value.toString()
    is TJ.Num -> BigDecimal(text).let { if (it.signum() == 0) "N0" else it.stripTrailingZeros().let { s -> "N${s.unscaledValue()}e${-s.scale()}" } }
    is TJ.Str -> "S" + value.length + ":" + value
    is TJ.Arr -> items.joinToString(",", "[", "]") { it.describe() }
    is TJ.Obj -> members.sortedBy { it.first }.joinToString(",", "{", "}") { "${it.first.length}:${it.first}=${it.second.describe()}" }
}

internal fun TJ.toJson(): String = when (this) {
    TJ.Null -> "null"
    is TJ.Bool -> value.toString()
    is TJ.Num -> text
    is TJ.Str -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is TJ.Arr -> items.joinToString(",", "[", "]") { it.toJson() }
    is TJ.Obj -> members.joinToString(",", "{", "}") { TJ.Str(it.first).toJson() + ":" + it.second.toJson() }
}

internal object TestJsonGen {
    private val keyPool = listOf("", "a", "b", "id", "name", "k", "ключ", "\u0000", "a\"b", "😀", "x".repeat(70))

    fun string(random: Random, size: Int): String {
        val length = when (random.nextInt(6)) {
            0 -> random.nextInt(60, 70) // around the 64-byte exact/long edge
            1 -> random.nextInt(200, 600)
            else -> random.nextInt(0, size + 2)
        }
        val sb = StringBuilder()
        while (sb.length < length) {
            when (random.nextInt(8)) {
                0 -> sb.appendCodePoint(random.nextInt(0x80, 0x800))
                1 -> sb.appendCodePoint(random.nextInt(0x10000, 0x110000))
                else -> sb.append('a' + random.nextInt(26))
            }
        }
        return sb.toString()
    }

    fun number(random: Random): String = when (random.nextInt(6)) {
        0 -> random.nextInt(-5, 6).toString()
        1 -> random.nextLong().toString()
        2 -> "${random.nextInt(-999, 1000)}.${random.nextInt(0, 1000)}"
        3 -> "${random.nextInt(1, 10)}e${random.nextInt(-40, 41)}"
        4 -> "-0.0"
        else -> "0." + "7".repeat(random.nextInt(100, 130)) // NumberKey around the 64-byte edge
    }

    fun value(maxDepth: Int = 4): Gen<TJ> = Gen { random, size -> value(random, size, maxDepth) }

    fun value(random: Random, size: Int, depth: Int): TJ = when (random.nextInt(if (depth <= 0) 4 else 6)) {
        0 -> if (random.nextBoolean()) TJ.Null else TJ.Bool(random.nextBoolean())
        1, 2 -> TJ.Num(number(random))
        3 -> TJ.Str(string(random, size))
        4 -> TJ.Arr(List(random.nextInt(0, minOf(size, 6) + 1)) { value(random, size, depth - 1) })
        else -> {
            val keys = LinkedHashSet<String>()
            repeat(random.nextInt(0, minOf(size, 6) + 1)) {
                keys += if (random.nextBoolean()) keyPool[random.nextInt(keyPool.size)] else string(random, 8)
            }
            TJ.Obj(keys.map { it to value(random, size, depth - 1) })
        }
    }

    /** The same value with other number spellings and shuffled object members. */
    fun respell(value: TJ, random: Random): TJ = when (value) {
        is TJ.Num -> TJ.Num(
            BigDecimal(value.text).let {
                when (random.nextInt(4)) {
                    0 -> it.toString()
                    1 -> it.toEngineeringString()
                    2 -> it.setScale(it.scale() + 2).toString()
                    else -> value.text
                }
            },
        )
        is TJ.Arr -> TJ.Arr(value.items.map { respell(it, random) })
        is TJ.Obj -> TJ.Obj(value.members.map { it.first to respell(it.second, random) }.shuffled(random))
        else -> value
    }
}
