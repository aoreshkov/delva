package app.oreshkov.delva.testkit.json

import app.oreshkov.delva.testkit.property.Gen
import kotlin.random.Random

/** Document shapes that stress different parts of the engine. */
enum class JsonProfile {
    /** A bit of everything. */
    MIXED,

    /** Objects with many members. */
    WIDE_OBJECTS,

    /** Deep nesting of objects and arrays. */
    DEEP_NESTING,

    /** Arrays of similar objects, like records in a feed. */
    ARRAYS_OF_OBJECTS,

    /** A few keys repeated everywhere, with repeated values: hot terms and many hits per document. */
    HOT_KEYS,

    /** `@type` members, which are ordinary data. */
    TYPE_MEMBERS,

    /** A small pool of numeric values in many spellings (`1`, `1.0`, `10e-1`, `-0`). */
    NUMBER_SPELLINGS,

    /** Strings around the 64-byte exact-term edge and around the fragment size. */
    STRING_EDGES,

    /** BMP and supplementary characters, control characters, quotes and backslashes. */
    UNICODE,

    /** Empty objects, arrays, strings and the empty key. */
    EMPTY_CONTAINERS,

    /** Top-level arrays and scalars. */
    TOP_LEVEL_NON_OBJECTS,
}

/**
 * Sized JSON generators. `size` bounds the number of values roughly; [fragmentSize] sets the long-string edge for
 * [JsonProfile.STRING_EDGES], where a string of that length counts as one value per 64 characters.
 */
class JsonGen(val profile: JsonProfile = JsonProfile.MIXED, val fragmentSize: Int = 64 * 1024) {
    fun value(): Gen<OValue> = Gen { random, size -> Builder(random, maxOf(size, 1)).document() }

    /** A document and a randomly formatted JSON spelling of it. */
    fun document(): Gen<Pair<OValue, ByteArray>> = Gen { random, size ->
        val value = Builder(random, maxOf(size, 1)).document()
        value to JsonText.randomlyFormatted(value, random).toByteArray()
    }

    private inner class Builder(val random: Random, size: Int) {
        private var budget = size

        fun document(): OValue = when (profile) {
            JsonProfile.TOP_LEVEL_NON_OBJECTS -> if (random.nextBoolean()) array(0) else scalar()
            JsonProfile.MIXED -> when (random.nextInt(10)) {
                0 -> array(0)
                1 -> scalar()
                else -> obj(0)
            }
            else -> obj(0)
        }

        private fun value(depth: Int): OValue {
            budget--
            if (budget <= 0) return scalar()
            val containerWeight = when (profile) {
                JsonProfile.DEEP_NESTING -> 8
                JsonProfile.ARRAYS_OF_OBJECTS, JsonProfile.EMPTY_CONTAINERS -> 5
                else -> 3
            }
            val maxDepth = if (profile == JsonProfile.DEEP_NESTING) 200 else 12
            return if (depth < maxDepth && random.nextInt(10) < containerWeight) {
                if (random.nextBoolean()) obj(depth + 1) else array(depth + 1)
            } else {
                scalar()
            }
        }

        private fun obj(depth: Int): OValue {
            val count = when (profile) {
                JsonProfile.WIDE_OBJECTS -> random.nextInt(0, maxOf(budget, 1) + 1)
                JsonProfile.DEEP_NESTING -> random.nextInt(1, 3)
                JsonProfile.EMPTY_CONTAINERS -> if (random.nextInt(3) == 0) 0 else random.nextInt(1, 4)
                else -> random.nextInt(0, minOf(8, maxOf(budget, 1)) + 1)
            }
            val members = LinkedHashMap<String, OValue>()
            repeat(count) {
                if (budget <= 0 && members.isNotEmpty()) return@repeat
                val key = key()
                if (key !in members) members[key] = memberValue(key, depth)
            }
            return OObj(members.toList())
        }

        private fun memberValue(key: String, depth: Int): OValue = when {
            profile == JsonProfile.TYPE_MEMBERS && key == "@type" -> OStr(TYPE_NAMES[random.nextInt(TYPE_NAMES.size)])
            profile == JsonProfile.ARRAYS_OF_OBJECTS && random.nextInt(3) == 0 -> {
                budget--
                OArr(List(random.nextInt(0, 6)) { record(depth + 1) })
            }
            else -> value(depth)
        }

        private fun record(depth: Int): OValue {
            budget--
            val members = linkedMapOf<String, OValue>("sku" to OStr("S" + random.nextInt(20)), "qty" to number())
            if (random.nextBoolean()) members["tags"] = OArr(List(random.nextInt(0, 4)) { OStr(HOT_VALUES[random.nextInt(HOT_VALUES.size)]) })
            if (random.nextInt(4) == 0 && depth < 12) members["attrs"] = obj(depth + 1)
            return OObj(members.toList())
        }

        private fun array(depth: Int): OValue {
            val count = when (profile) {
                JsonProfile.EMPTY_CONTAINERS -> if (random.nextInt(3) == 0) 0 else random.nextInt(1, 4)
                JsonProfile.DEEP_NESTING -> random.nextInt(1, 3)
                else -> random.nextInt(0, minOf(8, maxOf(budget, 1)) + 1)
            }
            return OArr(List(count) { if (budget <= 0) scalar() else value(depth) })
        }

        private fun key(): String = when (profile) {
            JsonProfile.HOT_KEYS, JsonProfile.ARRAYS_OF_OBJECTS -> HOT_KEYS[random.nextInt(HOT_KEYS.size)]
            JsonProfile.TYPE_MEMBERS -> if (random.nextInt(3) == 0) "@type" else HOT_KEYS[random.nextInt(HOT_KEYS.size)]
            JsonProfile.EMPTY_CONTAINERS -> if (random.nextInt(3) == 0) "" else HOT_KEYS[random.nextInt(HOT_KEYS.size)]
            JsonProfile.UNICODE -> string(random.nextInt(0, 6), unicode = true)
            JsonProfile.WIDE_OBJECTS -> "k" + random.nextInt(1_000_000)
            else -> when (random.nextInt(4)) {
                0 -> HOT_KEYS[random.nextInt(HOT_KEYS.size)]
                1 -> ""
                else -> string(random.nextInt(1, 10), unicode = random.nextInt(5) == 0)
            }
        }

        private fun scalar(): OValue = when (random.nextInt(10)) {
            0 -> ONull
            1 -> OBool(random.nextBoolean())
            2, 3, 4 -> number()
            else -> stringValue()
        }

        private fun stringValue(): OValue = OStr(
            when (profile) {
                JsonProfile.HOT_KEYS, JsonProfile.ARRAYS_OF_OBJECTS -> HOT_VALUES[random.nextInt(HOT_VALUES.size)]
                JsonProfile.STRING_EDGES -> when (random.nextInt(3)) {
                    0 -> string(random.nextInt(60, 70), unicode = false)
                    1 -> {
                        // Charged by length, so the size bounds the document's bytes too: counted as one value
                        // each, these strings made T = 64 KiB documents of tens of megabytes.
                        budget -= fragmentSize / CHARS_PER_VALUE
                        string(fragmentSize + random.nextInt(-4, 5), unicode = random.nextBoolean())
                    }
                    else -> string(random.nextInt(0, 20), unicode = false)
                }
                JsonProfile.UNICODE -> string(random.nextInt(0, 30), unicode = true)
                JsonProfile.EMPTY_CONTAINERS -> if (random.nextBoolean()) "" else string(random.nextInt(0, 5), unicode = false)
                else -> string(random.nextInt(0, 16), unicode = random.nextInt(4) == 0)
            },
        )

        private fun number(): OValue {
            if (profile == JsonProfile.NUMBER_SPELLINGS || random.nextInt(4) == 0) {
                val (intPart, exponent) = NUMBER_POOL[random.nextInt(NUMBER_POOL.size)]
                return ONum(spell(intPart, exponent))
            }
            return ONum(
                when (random.nextInt(5)) {
                    0 -> random.nextInt(-10, 11).toString()
                    1 -> random.nextLong().toString()
                    2 -> "${random.nextInt(-1000, 1000)}.${random.nextInt(0, 1000)}"
                    3 -> "${random.nextInt(1, 10)}${if (random.nextBoolean()) "e" else "E"}${random.nextInt(-300, 301)}"
                    else -> "0.${"0".repeat(random.nextInt(0, 5))}${random.nextInt(1, 1000)}"
                },
            )
        }

        /** A spelling of the value `digits × 10^exponent` (digits without sign). */
        private fun spell(digits: Long, exponent: Int): String {
            if (digits == 0L) return listOf("0", "-0", "0.0", "-0.0e5", "0e-7")[random.nextInt(5)]
            val sign = if (digits < 0) "-" else ""
            val magnitude = kotlin.math.abs(digits).toString()
            val shift = random.nextInt(-3, 4)
            val zeros = random.nextInt(0, 3)
            // magnitude × 10^exponent = (magnitude + zeros) × 10^(exponent − zeros), then move the point by `shift`.
            val mantissa = magnitude + "0".repeat(zeros)
            val e = exponent - zeros
            return when {
                shift <= 0 || shift >= mantissa.length -> "$sign$mantissa${exp(e)}"
                else -> {
                    val point = mantissa.length - shift
                    "$sign${mantissa.substring(0, point)}.${mantissa.substring(point)}${exp(e + shift)}"
                }
            }
        }

        private fun exp(e: Int): String = if (e == 0 && random.nextBoolean()) "" else (if (random.nextBoolean()) "e" else "E") + e

        private fun string(length: Int, unicode: Boolean): String {
            val sb = StringBuilder()
            while (sb.length < length) {
                if (!unicode) {
                    sb.append('a' + random.nextInt(26))
                    continue
                }
                when (random.nextInt(10)) {
                    0 -> sb.appendCodePoint(random.nextInt(0, 0x20))
                    1 -> sb.append(if (random.nextBoolean()) '"' else '\\')
                    2, 3 -> sb.appendCodePoint(random.nextInt(0x80, 0x800))
                    4, 5 -> sb.appendCodePoint(random.nextInt(0x800, 0xD800))
                    6 -> sb.appendCodePoint(random.nextInt(0xE000, 0x10000))
                    7 -> sb.appendCodePoint(random.nextInt(0x10000, 0x110000))
                    else -> sb.append('a' + random.nextInt(26))
                }
            }
            return sb.toString()
        }
    }

    companion object {
        val HOT_KEYS = listOf("id", "name", "type", "tags", "value", "items", "k")
        val HOT_VALUES = listOf("a", "b", "x", "hot", "")
        val TYPE_NAMES = listOf("Order", "Item", "type.googleapis.com/pkg.Message")

        /** Values as (significand, power of ten). */
        private val NUMBER_POOL = listOf(0L to 0, 1L to 0, -1L to 0, 15L to -1, 7L to 2, -25L to -3, 1L to 20)

        private const val CHARS_PER_VALUE = 64
    }
}
