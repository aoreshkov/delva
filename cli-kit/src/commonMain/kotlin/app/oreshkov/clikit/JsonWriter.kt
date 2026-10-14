package app.oreshkov.clikit

/**
 * Writes one JSON value (RFC 8259) to [out], as it is described: `--json` output without a
 * serialization library.
 *
 * A writer rather than a serializer, deliberately. A program's machine-readable record is a few
 * fields it already holds, and the one field that is large is often JSON already — a stored
 * document, which [rawValue] passes through as it is. A serializer would need the whole record as
 * an object graph first, and the document as a `String` inside it.
 *
 * Commas and colons are inserted; misuse — a [name] outside an object, a value where a name is
 * due, a second top-level value — throws [IllegalStateException], because it is a bug in the
 * program, not something its user did.
 *
 * Output is compact, which is what JSON Lines wants, unless an [indent] is given. Then every member and element goes
 * on a line of its own at [indent] × its depth, a name is followed by `": "`, a closing bracket goes on its own line
 * at its container's depth, an empty container stays `{}` or `[]`, and no newline follows the top-level value (the
 * caller adds one, as [Output.text] does). Either way, only whitespace between tokens differs.
 *
 * Strings are written as UTF-8-ready text: quotes, backslashes and U+0000–U+001F are escaped, and
 * everything else is written as itself, so `jq` shows what was typed. An unpaired surrogate is
 * written as a `\uXXXX` escape, which RFC 8259 allows and which loses nothing, but which I-JSON
 * (RFC 7493) forbids; it can only come from text that was not valid Unicode to begin with.
 */
public class JsonWriter(
    private val out: Appendable,
    /** `null` for compact output; otherwise one level of indentation, such as `"  "` or `"\t"`. */
    public val indent: String? = null,
) {
    init {
        require(indent == null || indent.all { it == ' ' || it == '\t' }) { "an indent is spaces and tabs, not '$indent'" }
    }

    private enum class Scope { Object, Array }

    private class Frame(val scope: Scope) {
        var count = 0
        var nameWritten = false
    }

    private val frames = ArrayDeque<Frame>()
    private var topLevelDone = false

    /** True once the top-level value is complete. */
    public val isComplete: Boolean get() = topLevelDone

    /**
     * How many containers are open: the depth the next member or element is written at. With [indent] and [quote], it
     * gives the column a value starts at, for a caller that asks its own formatter for a narrower line there.
     */
    public val depth: Int get() = frames.size

    /** Opens an object, where a value is due; members follow as [name] and a value. */
    public fun beginObject(): JsonWriter = open(Scope.Object, '{')

    /** Closes the innermost container, which must be an object with no name left waiting for its value. */
    public fun endObject(): JsonWriter = close(Scope.Object, '}')

    /** Opens an array, where a value is due. */
    public fun beginArray(): JsonWriter = open(Scope.Array, '[')

    /** Closes the innermost container, which must be an array. */
    public fun endArray(): JsonWriter = close(Scope.Array, ']')

    /** The name of the next member; only inside an object, and only where a name is due. */
    public fun name(name: String): JsonWriter {
        val frame = frames.lastOrNull()
        check(frame != null && frame.scope == Scope.Object) { "a name outside an object" }
        check(!frame.nameWritten) { "two names in a row" }
        if (frame.count > 0) out.append(',')
        newLine(depth)
        writeString(name)
        out.append(if (indent == null) ":" else ": ")
        frame.nameWritten = true
        return this
    }

    /** A string, or `null` for a JSON `null`. */
    public fun value(value: String?): JsonWriter = if (value == null) nullValue() else scalar { writeString(value) }

    /** A number. */
    public fun value(value: Long): JsonWriter = scalar { out.append(value.toString()) }

    /** A number. */
    public fun value(value: Int): JsonWriter = scalar { out.append(value.toString()) }

    /** `true` or `false`. */
    public fun value(value: Boolean): JsonWriter = scalar { out.append(if (value) "true" else "false") }

    /** A JSON `null`. */
    public fun nullValue(): JsonWriter = scalar { out.append("null") }

    /**
     * JSON text that is already encoded, written as it is. The caller vouches for it: nothing here
     * parses it, which is the point for a value that is large.
     *
     * With [reindent], each line after the first is prefixed with the indentation of the depth the value sits at, so
     * a value an engine indented from column zero lines up inside this one. That is always safe: a newline inside a
     * JSON string is escaped, so every raw newline in valid JSON is layout.
     */
    public fun rawValue(json: String, reindent: Boolean = indent != null): JsonWriter = scalar {
        val shift = indent?.repeat(depth)
        if (reindent && !shift.isNullOrEmpty()) out.append(json.replace("\n", "\n" + shift)) else out.append(json)
    }

    /**
     * A value [write] writes itself, to wherever this writer's output ends up — the way to embed a value too large
     * to hold as a string. The writer adds the separator before it and nothing after; the caller vouches that
     * [write] writes exactly one JSON value, and flushes anything buffered between here and the destination first.
     * Nothing here sees its text, so it is never re-indented: with an [indent], it lines up only if [write] indents
     * it for [depth] itself.
     */
    public fun rawValue(write: () -> Unit): JsonWriter = scalar { write() }

    private inline fun scalar(write: () -> Unit): JsonWriter {
        beforeValue()
        write()
        afterValue()
        return this
    }

    private fun open(scope: Scope, bracket: Char): JsonWriter {
        beforeValue()
        out.append(bracket)
        frames.addLast(Frame(scope))
        return this
    }

    private fun close(scope: Scope, bracket: Char): JsonWriter {
        val frame = frames.lastOrNull()
        check(frame != null && frame.scope == scope) { "no open ${scope.name.lowercase()} to close" }
        check(!frame.nameWritten) { "a name without a value" }
        frames.removeLast()
        if (frame.count > 0) newLine(depth)
        out.append(bracket)
        afterValue()
        return this
    }

    private fun beforeValue() {
        val frame = frames.lastOrNull()
        when {
            frame == null -> check(!topLevelDone) { "a second top-level value" }
            frame.scope == Scope.Object -> check(frame.nameWritten) { "a value where a name is due" }
            else -> {
                if (frame.count > 0) out.append(',')
                newLine(depth)
            }
        }
    }

    /** With an [indent], a line break and [level] indents; compact output has neither. */
    private fun newLine(level: Int) {
        val indent = indent ?: return
        out.append('\n')
        repeat(level) { out.append(indent) }
    }

    private fun afterValue() {
        val frame = frames.lastOrNull()
        if (frame == null) {
            topLevelDone = true
        } else {
            frame.count++
            frame.nameWritten = false
        }
    }

    private fun writeString(value: String) {
        out.append('"')
        var start = 0
        for (i in value.indices) {
            val escape = escapeFor(value, i) ?: continue
            if (start < i) out.append(value, start, i)
            out.append(escape)
            start = i + 1
        }
        if (start < value.length) out.append(value, start, value.length)
        out.append('"')
    }

    public companion object {
        /** [value] as a JSON string literal, quotes included. */
        public fun quote(value: String): String = buildString { JsonWriter(this).value(value) }

        private const val HEX = "0123456789abcdef"

        private fun escapeFor(text: String, index: Int): String? {
            val c = text[index]
            return when {
                c == '"' -> "\\\""
                c == '\\' -> "\\\\"
                c == '\n' -> "\\n"
                c == '\r' -> "\\r"
                c == '\t' -> "\\t"
                c == '\b' -> "\\b"
                c == '\u000C' -> "\\f"
                c < ' ' -> unicodeEscape(c)
                c.isHighSurrogate() && !(index + 1 < text.length && text[index + 1].isLowSurrogate()) -> unicodeEscape(c)
                c.isLowSurrogate() && !(index > 0 && text[index - 1].isHighSurrogate()) -> unicodeEscape(c)
                else -> null
            }
        }

        private fun unicodeEscape(c: Char): String {
            val code = c.code
            return "\\u" + HEX[code shr 12 and 0xF] + HEX[code shr 8 and 0xF] + HEX[code shr 4 and 0xF] + HEX[code and 0xF]
        }
    }
}

/** `beginObject`, [body], `endObject`. */
public inline fun JsonWriter.writeObject(body: JsonWriter.() -> Unit): JsonWriter {
    beginObject()
    body()
    return endObject()
}

/** `beginArray`, [body], `endArray`. */
public inline fun JsonWriter.writeArray(body: JsonWriter.() -> Unit): JsonWriter {
    beginArray()
    body()
    return endArray()
}

/** A member: [name], then its string value (or `null`). */
public fun JsonWriter.field(name: String, value: String?): JsonWriter = name(name).value(value)

/** A member: [name], then its number value. */
public fun JsonWriter.field(name: String, value: Long): JsonWriter = name(name).value(value)

/** A member: [name], then its number value. */
public fun JsonWriter.field(name: String, value: Int): JsonWriter = name(name).value(value)

/** A member: [name], then its boolean value. */
public fun JsonWriter.field(name: String, value: Boolean): JsonWriter = name(name).value(value)
