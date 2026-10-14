package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian

/**
 * Lays JSON events out in a formatted [JsonFormat] (SPEC.md §5.1) and writes them through [out], which escapes and
 * buffers as it does for the compact form.
 *
 * A container that has to be written on one line or broken is *pending*: its events are held in [log] while their
 * flat width is summed, until the container closes within its budget (it's then written on one line) or an event
 * would take it over a limit (its opening bracket is then written broken, and the held events are fed through the
 * layout again, where a nested container can become pending in turn). The budget bounds the log, so memory is
 * O(line width + depth). A container that closes exactly at its budget waits for one more event: a comma after it
 * doesn't fit, so it goes on one line only when its parent closes next.
 *
 * Containers nested deeper than [JsonFormat.maxLevels] are cut out before the layout sees them: their events are
 * dropped, and a non-empty one reaches the layout as a single ELIDED event, laid out like a scalar.
 *
 * Reusable through [reset]; not thread-safe.
 */
internal class PrettyJsonOutput(private val out: JsonOutput) : JsonEvents {
    private var format = JsonFormat.PRETTY
    private var indent = SPACE

    // Broken containers: how many are open, and whether each has an element yet (index 1 is the outermost).
    private var depth = 0
    private var hasElement = BooleanArray(16)
    private var afterKey = false
    private var keyWidth = 0

    // The pending container.
    private var pending = false
    private var closedAtBudget = false
    private var hasParent = false
    private var budget = 0
    private var width = 0
    private var openInside = 0
    private var elements = IntArray(16)
    private var pendingAfterKey = false
    private val log = GrowableBytes(256)

    // Events waiting to be laid out again after a pending container broke; read from queueStart.
    private var queue = GrowableBytes(256)
    private var spare = GrowableBytes(256)
    private var queueStart = 0

    // Containers open in the input; the level of the one being cut out (0: none), and whether it's empty so far.
    private var level = 0
    private var elidedAt = 0
    private var elidedEmpty = false

    fun reset(format: JsonFormat) {
        require(!format.isCompact) { "the compact form is written by JsonOutput directly" }
        this.format = format
        indent = if (format.indent == "\t") TAB else ByteArray(format.indent.length) { ' '.code.toByte() }
        depth = 0
        afterKey = false
        pending = false
        closedAtBudget = false
        log.clear()
        queue.clear()
        queueStart = 0
        level = 0
        elidedAt = 0
    }

    override fun beginObject() = accept(BEGIN_OBJECT, NONE, 0, 0, 0)

    override fun endObject() = accept(END_OBJECT, NONE, 0, 0, 0)

    override fun beginArray() = accept(BEGIN_ARRAY, NONE, 0, 0, 0)

    override fun endArray() = accept(END_ARRAY, NONE, 0, 0, 0)

    override fun key(utf8: ByteArray, offset: Int, length: Int) = accept(KEY, utf8, offset, length, 0)

    override fun string(utf8: ByteArray, offset: Int, length: Int) = accept(STRING, utf8, offset, length, 0)

    override fun beginString() = accept(BEGIN_STRING, NONE, 0, 0, 0)

    override fun stringPart(utf8: ByteArray, offset: Int, length: Int) {
        if (length > 0) accept(STRING_PART, utf8, offset, length, 0)
    }

    override fun endString() = accept(END_STRING, NONE, 0, 0, 0)

    override fun number(ascii: ByteArray, offset: Int, length: Int) = accept(NUMBER_TEXT, ascii, offset, length, 0)

    override fun number(value: Long) = accept(NUMBER_LONG, NONE, 0, 0, value)

    override fun booleanValue(value: Boolean) = accept(if (value) TRUE else FALSE, NONE, 0, 0, 0)

    override fun nullValue() = accept(NULL, NONE, 0, 0, 0)

    /** Takes an event of the input: cuts out the containers below [JsonFormat.maxLevels] and lays out the rest. */
    private fun accept(kind: Int, bytes: ByteArray, offset: Int, length: Int, long: Long) {
        if (elidedAt == 0) {
            when (kind) {
                // A container opening at `level` is nested that many levels below the node.
                BEGIN_OBJECT, BEGIN_ARRAY -> if (level++ > format.maxLevels) {
                    elidedAt = level
                    elidedEmpty = true
                    return
                }
                END_OBJECT, END_ARRAY -> level--
            }
            layOut(kind, bytes, offset, length, long)
            return
        }
        when (kind) {
            BEGIN_OBJECT, BEGIN_ARRAY -> level++
            END_OBJECT, END_ARRAY -> if (level-- == elidedAt) {
                elidedAt = 0
                val array = kind == END_ARRAY
                if (elidedEmpty) {
                    layOut(if (array) BEGIN_ARRAY else BEGIN_OBJECT, NONE, 0, 0, 0)
                    layOut(kind, NONE, 0, 0, 0)
                } else {
                    layOut(if (array) ELIDED_ARRAY else ELIDED_OBJECT, NONE, 0, 0, 0)
                }
                return
            }
        }
        elidedEmpty = false
    }

    private fun layOut(kind: Int, bytes: ByteArray, offset: Int, length: Int, long: Long) {
        while (!step(kind, bytes, offset, length, long)) drainQueue()
    }

    /** Lays out the events a broken container gave back, in order, before the event that broke it. */
    private fun drainQueue() {
        while (queueStart < queue.size) {
            val array = queue.array
            val kind = array[queueStart].toInt()
            var p = queueStart + 1
            var offset = 0
            var length = 0
            var long = 0L
            when {
                kind == NUMBER_LONG -> {
                    long = LittleEndian.getLong(array, p)
                    p += 8
                }
                kind.hasBytes() -> {
                    length = LittleEndian.getInt(array, p)
                    offset = p + 4
                    p = offset + length
                }
            }
            if (step(kind, array, offset, length, long)) queueStart = p
        }
        queue.clear()
        queueStart = 0
    }

    /** Takes one event; false when it broke the pending container instead, whose events now lead the queue. */
    private fun step(kind: Int, bytes: ByteArray, offset: Int, length: Int, long: Long): Boolean {
        if (closedAtBudget) {
            if (kind != END_OBJECT && kind != END_ARRAY) return breakPending()
            writeInline()
        }
        if (!pending) {
            direct(kind, bytes, offset, length, long)
            return true
        }
        val startsElement = kind.startsElement() && !pendingAfterKey
        var added = width(kind, bytes, offset, length, long)
        if (startsElement) {
            val count = elements[openInside] + 1
            if (count > format.maxInlineElements) return breakPending()
            // The container at openInside is nested openInside - 1 levels below the pending one.
            if (count == 1 && openInside - 1 > format.maxInlineDepth) return breakPending()
            if (count > 1) added += 2
        }
        val closesPending = openInside == 1 && (kind == END_OBJECT || kind == END_ARRAY)
        val empty = closesPending && elements[1] == 0
        if (width + added > budget && !empty) return breakPending()

        record(kind, bytes, offset, length, long)
        width += added
        if (startsElement) elements[openInside]++
        when (kind) {
            BEGIN_OBJECT, BEGIN_ARRAY -> {
                openInside++
                if (openInside == elements.size) elements = elements.copyOf(elements.size * 2)
                elements[openInside] = 0
                pendingAfterKey = false
            }
            END_OBJECT, END_ARRAY -> openInside--
            KEY -> pendingAfterKey = true
            STRING_PART, END_STRING -> {}
            else -> pendingAfterKey = false
        }
        if (closesPending) {
            if (empty || !hasParent || width < budget) writeInline() else closedAtBudget = true
        }
        return true
    }

    /** Writes an event outside any pending container. */
    private fun direct(kind: Int, bytes: ByteArray, offset: Int, length: Int, long: Long) {
        when (kind) {
            BEGIN_OBJECT, BEGIN_ARRAY -> {
                val column = depth.toLong() * format.indentColumns + if (afterKey) keyWidth else 0
                startElement()
                pending = true
                hasParent = depth > 0
                budget = (format.lineWidth - column).coerceAtLeast(-1).toInt()
                width = 1
                openInside = 1
                elements[1] = 0
                pendingAfterKey = false
                log.clear()
                record(kind, bytes, offset, length, long)
            }
            END_OBJECT, END_ARRAY -> {
                newLine(depth - 1)
                if (kind == END_OBJECT) out.endObject() else out.endArray()
                depth--
            }
            KEY -> {
                startElement()
                out.key(bytes, offset, length)
                out.layout(SPACE)
                keyWidth = JsonOutput.escapedWidth(bytes, offset, length) + 4
                afterKey = true
            }
            STRING_PART -> out.stringPart(bytes, offset, length)
            END_STRING -> out.endString()
            else -> {
                startElement()
                scalar(kind, bytes, offset, length, long)
            }
        }
    }

    /** The separator and line break before an element of a broken container; nothing after a key. */
    private fun startElement() {
        if (afterKey) {
            afterKey = false
            return
        }
        if (depth == 0) return
        if (hasElement[depth]) out.layout(COMMA)
        hasElement[depth] = true
        newLine(depth)
    }

    private fun newLine(level: Int) {
        out.layout(NEWLINE)
        repeat(level) { out.layout(indent) }
    }

    /** Writes the pending container broken: its opening bracket, then its events through the layout again. */
    private fun breakPending(): Boolean {
        if (log.array[0].toInt() == BEGIN_OBJECT) out.beginObject() else out.beginArray()
        depth++
        if (depth == hasElement.size) hasElement = hasElement.copyOf(hasElement.size * 2)
        hasElement[depth] = false
        pending = false
        closedAtBudget = false
        spare.clear()
        spare.writeBytes(log.array, 1, log.size - 1)
        spare.writeBytes(queue.array, queueStart, queue.size - queueStart)
        val drained = queue
        queue = spare
        spare = drained
        queueStart = 0
        log.clear()
        return false
    }

    /** Writes the pending container on one line. */
    private fun writeInline() {
        val array = log.array
        var p = 0
        var separate = false
        while (p < log.size) {
            val kind = array[p++].toInt()
            var offset = 0
            var length = 0
            var long = 0L
            when {
                kind == NUMBER_LONG -> {
                    long = LittleEndian.getLong(array, p)
                    p += 8
                }
                kind.hasBytes() -> {
                    length = LittleEndian.getInt(array, p)
                    offset = p + 4
                    p = offset + length
                }
            }
            if (separate && kind.startsElement()) out.layout(COMMA_SPACE)
            when (kind) {
                BEGIN_OBJECT -> out.beginObject()
                BEGIN_ARRAY -> out.beginArray()
                END_OBJECT -> out.endObject()
                END_ARRAY -> out.endArray()
                KEY -> {
                    out.key(array, offset, length)
                    out.layout(SPACE)
                }
                STRING_PART -> out.stringPart(array, offset, length)
                END_STRING -> out.endString()
                else -> scalar(kind, array, offset, length, long)
            }
            separate = when (kind) {
                BEGIN_OBJECT, BEGIN_ARRAY, KEY, BEGIN_STRING -> false
                STRING_PART -> separate
                else -> true
            }
        }
        pending = false
        closedAtBudget = false
        log.clear()
    }

    private fun scalar(kind: Int, bytes: ByteArray, offset: Int, length: Int, long: Long) {
        when (kind) {
            STRING -> out.string(bytes, offset, length)
            BEGIN_STRING -> out.beginString()
            NUMBER_TEXT -> out.number(bytes, offset, length)
            NUMBER_LONG -> out.number(long)
            TRUE -> out.booleanValue(true)
            FALSE -> out.booleanValue(false)
            NULL -> out.nullValue()
            ELIDED_OBJECT -> out.elided(array = false)
            ELIDED_ARRAY -> out.elided(array = true)
            else -> throw IllegalStateException("event $kind")
        }
    }

    private fun record(kind: Int, bytes: ByteArray, offset: Int, length: Int, long: Long) {
        log.writeByte(kind)
        if (kind == NUMBER_LONG) {
            log.writeLongLE(long)
        } else if (kind.hasBytes()) {
            log.writeIntLE(length)
            log.writeBytes(bytes, offset, length)
        }
    }

    /** Columns the event's text takes on one line, without the separator before it. */
    private fun width(kind: Int, bytes: ByteArray, offset: Int, length: Int, long: Long): Int = when (kind) {
        KEY -> JsonOutput.escapedWidth(bytes, offset, length) + 4
        STRING -> JsonOutput.escapedWidth(bytes, offset, length) + 2
        STRING_PART -> JsonOutput.escapedWidth(bytes, offset, length)
        NUMBER_TEXT -> length
        NUMBER_LONG -> digits(long)
        TRUE, NULL -> 4
        FALSE, ELIDED_OBJECT, ELIDED_ARRAY -> 5
        else -> 1
    }

    private fun digits(value: Long): Int {
        var count = if (value < 0) 2 else 1
        var v = value / 10
        while (v != 0L) {
            count++
            v /= 10
        }
        return count
    }

    private fun Int.startsElement(): Boolean = this != END_OBJECT && this != END_ARRAY && this != STRING_PART && this != END_STRING

    private fun Int.hasBytes(): Boolean = this == KEY || this == STRING || this == STRING_PART || this == NUMBER_TEXT

    private companion object {
        const val BEGIN_OBJECT = 1
        const val END_OBJECT = 2
        const val BEGIN_ARRAY = 3
        const val END_ARRAY = 4
        const val KEY = 5
        const val STRING = 6
        const val BEGIN_STRING = 7
        const val STRING_PART = 8
        const val END_STRING = 9
        const val NUMBER_TEXT = 10
        const val NUMBER_LONG = 11
        const val TRUE = 12
        const val FALSE = 13
        const val NULL = 14
        const val ELIDED_OBJECT = 15
        const val ELIDED_ARRAY = 16

        val NONE = ByteArray(0)
        val SPACE = byteArrayOf(' '.code.toByte())
        val TAB = byteArrayOf('\t'.code.toByte())
        val COMMA = byteArrayOf(','.code.toByte())
        val COMMA_SPACE = byteArrayOf(','.code.toByte(), ' '.code.toByte())
        val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}
