package app.oreshkov.delva.testkit.json

import kotlin.random.Random

/** Writes [OValue]s as JSON text. */
object JsonText {
    /**
     * The canonical output form, which the engine's `writeJsonTo` must reproduce byte for byte: no whitespace,
     * members in insertion order, numbers in their original spelling, and strings escaped minimally —
     * `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, `\u00XX` (lowercase hex) for other control characters, everything
     * else as UTF-8.
     */
    fun minified(value: OValue): String = StringBuilder().also { writeMinified(it, value) }.toString()

    fun minifiedBytes(value: OValue): ByteArray = minified(value).toByteArray()

    /**
     * An equivalent spelling for parser tests: random whitespace, random `\uXXXX` escapes (surrogate pairs for
     * supplementary characters), and `\/`. Parsing it yields a value equal to [value] (exact, not just canonical).
     */
    fun randomlyFormatted(value: OValue, random: Random): String =
        StringBuilder().also { writeRandom(it, value, random) }.toString()

    /**
     * The formatted output form (SPEC.md §5.1), written straight from its definition as an oracle for the engine's
     * streaming layout: a non-empty container goes on one line when it passes the element and depth limits, every
     * container inside it does too, and the line — indentation, key, the container and a comma after it — has at most
     * [lineWidth] code points (a tab indentation counts 8); otherwise one element per line. A non-empty container
     * nested more than [maxLevels] levels below [value] is written as `{...}` or `[...]`, like a scalar.
     */
    fun formatted(
        value: OValue,
        indent: String,
        lineWidth: Int,
        maxInlineElements: Int,
        maxInlineDepth: Int,
        maxLevels: Int = Int.MAX_VALUE,
    ): String {
        val layout = Layout(indent, if (indent == "\t") 8 else indent.length, lineWidth, maxInlineElements, maxInlineDepth, maxLevels)
        return StringBuilder().also { layout.write(it, value, 0, 0, false) }.toString()
    }

    private class Layout(
        val indent: String,
        val indentColumns: Int,
        val lineWidth: Int,
        val maxElements: Int,
        val maxDepth: Int,
        val maxLevels: Int,
    ) {
        /** Writes [value], nested [level] levels below the root, which is also its indentation level. */
        fun write(sb: StringBuilder, value: OValue, level: Int, column: Int, commaAfter: Boolean) {
            val size = size(value)
            if (size == 0 || cut(value, level)) {
                sb.append(flat(value, level))
                return
            }
            val flat = flat(value, level)
            if (inlinable(value, level) && column + flat.codePointCount(0, flat.length) + (if (commaAfter) 1 else 0) <= lineWidth) {
                sb.append(flat)
                return
            }
            sb.append(if (value is OObj) '{' else '[')
            for (i in 0 until size) {
                sb.append('\n').append(indent.repeat(level + 1))
                var start = (level + 1) * indentColumns
                val item = if (value is OObj) {
                    val (k, v) = value.members[i]
                    val key = StringBuilder().also { writeMinifiedString(it, k) }.append(": ").toString()
                    sb.append(key)
                    start += key.codePointCount(0, key.length)
                    v
                } else {
                    (value as OArr).items[i]
                }
                write(sb, item, level + 1, start, i < size - 1)
                if (i < size - 1) sb.append(',')
            }
            sb.append('\n').append(indent.repeat(level)).append(if (value is OObj) '}' else ']')
        }

        private fun size(value: OValue): Int = when (value) {
            is OArr -> value.items.size
            is OObj -> value.members.size
            else -> 0
        }

        private fun children(value: OValue): List<OValue> = when (value) {
            is OArr -> value.items
            is OObj -> value.members.map { it.second }
            else -> emptyList()
        }

        /** Whether [value] is a non-empty container written as `{...}` or `[...]`. */
        private fun cut(value: OValue, level: Int): Boolean = level > maxLevels && size(value) > 0

        private fun inlinable(value: OValue, level: Int): Boolean =
            cut(value, level) || size(value) <= maxElements && height(value, level) <= maxDepth &&
                children(value).all { inlinable(it, level + 1) }

        /** Levels of non-empty containers, not cut out, below [value]'s own. */
        private fun height(value: OValue, level: Int): Int {
            if (value !is OArr && value !is OObj || cut(value, level)) return -1
            val children = children(value)
            if (children.isEmpty()) return -1
            return children.maxOf { height(it, level + 1) + 1 }.coerceAtLeast(0)
        }

        private fun flat(value: OValue, level: Int): String = when {
            cut(value, level) -> if (value is OObj) "{...}" else "[...]"
            value is OArr -> value.items.joinToString(", ", "[", "]") { flat(it, level + 1) }
            value is OObj -> value.members.joinToString(", ", "{", "}") { (k, v) ->
                StringBuilder().also { writeMinifiedString(it, k) }.append(": ").append(flat(v, level + 1)).toString()
            }
            else -> minified(value)
        }
    }

    private fun writeMinified(sb: StringBuilder, value: OValue) {
        when (value) {
            ONull -> sb.append("null")
            is OBool -> sb.append(value.value)
            is ONum -> sb.append(value.text)
            is OStr -> writeMinifiedString(sb, value.value)
            is OArr -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeMinified(sb, item)
                }
                sb.append(']')
            }
            is OObj -> {
                sb.append('{')
                value.members.forEachIndexed { index, (k, v) ->
                    if (index > 0) sb.append(',')
                    writeMinifiedString(sb, k)
                    sb.append(':')
                    writeMinified(sb, v)
                }
                sb.append('}')
            }
        }
    }

    fun writeMinifiedString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                FORM_FEED -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u00").append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xF]) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private fun writeRandom(sb: StringBuilder, value: OValue, random: Random) {
        space(sb, random)
        when (value) {
            ONull, is OBool, is ONum -> writeMinified(sb, value)
            is OStr -> writeRandomString(sb, value.value, random)
            is OArr -> {
                sb.append('[')
                space(sb, random)
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeRandom(sb, item, random)
                }
                sb.append(']')
            }
            is OObj -> {
                sb.append('{')
                space(sb, random)
                value.members.forEachIndexed { index, (k, v) ->
                    if (index > 0) sb.append(',')
                    space(sb, random)
                    writeRandomString(sb, k, random)
                    space(sb, random)
                    sb.append(':')
                    writeRandom(sb, v, random)
                }
                sb.append('}')
            }
        }
        space(sb, random)
    }

    private fun writeRandomString(sb: StringBuilder, s: String, random: Random) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val mustEscape = cp == '"'.code || cp == '\\'.code || cp < 0x20
            if (mustEscape || random.nextInt(8) == 0) {
                when {
                    cp == '/'.code && random.nextBoolean() -> sb.append("\\/")
                    cp < 0x10000 -> unicodeEscape(sb, cp, random)
                    else -> {
                        unicodeEscape(sb, Character.highSurrogate(cp).code, random)
                        unicodeEscape(sb, Character.lowSurrogate(cp).code, random)
                    }
                }
            } else {
                sb.appendCodePoint(cp)
            }
        }
        sb.append('"')
    }

    private fun unicodeEscape(sb: StringBuilder, unit: Int, random: Random) {
        val hex = "%04x".format(unit)
        sb.append("\\u").append(if (random.nextBoolean()) hex else hex.uppercase())
    }

    private fun space(sb: StringBuilder, random: Random) {
        if (random.nextInt(4) != 0) return
        repeat(random.nextInt(1, 3)) { sb.append(WHITESPACE[random.nextInt(WHITESPACE.length)]) }
    }

    private const val FORM_FEED: Char = 12.toChar()
    private const val WHITESPACE =" \t\n\r"
    private val HEX = "0123456789abcdef".toCharArray()
}
