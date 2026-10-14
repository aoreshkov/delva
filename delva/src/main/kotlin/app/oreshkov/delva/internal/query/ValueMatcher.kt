package app.oreshkov.delva.internal.query

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.payload.JsonEvents
import java.util.Arrays

/**
 * Recheck of a lossy match: receives a stored value as [JsonEvents] and compares it with a
 * [Literal] by the equality of SPEC.md §2 — typed, numbers by NumberKey, strings by bytes, object members by key in
 * any order, array elements in order. Reports [isDone] at the first difference, so a producer can stop early.
 *
 * Stored objects have distinct keys, so equal member counts and every stored member matching mean equality.
 * Reusable through [reset]; not thread-safe.
 */
internal class ValueMatcher : JsonEvents {
    private var root: Literal = Literal.Null
    private val stack = ArrayList<Literal>()
    private var counters = IntArray(16)
    private var started = false
    private var failed = false
    private var complete = false
    private var pendingKey: String? = null
    private val numberKey = GrowableBytes(64)

    // String in parts.
    private var stringTarget: ByteArray? = null
    private var stringPosition = 0

    fun reset(literal: Literal) {
        root = literal
        stack.clear()
        started = false
        failed = false
        complete = false
        pendingKey = null
        stringTarget = null
    }

    /** Whether the whole value was received and equals the literal. */
    val matched: Boolean get() = complete && !failed

    override val isDone: Boolean get() = failed || complete

    override fun beginObject() {
        val expected = expected() as? Literal.Obj ?: return fail()
        push(expected)
    }

    override fun endObject() = end()

    override fun beginArray() {
        val expected = expected() as? Literal.Arr ?: return fail()
        push(expected)
    }

    override fun endArray() = end()

    override fun key(utf8: ByteArray, offset: Int, length: Int) {
        pendingKey = String(utf8, offset, length, Charsets.UTF_8)
    }

    override fun string(utf8: ByteArray, offset: Int, length: Int) {
        val expected = expected() as? Literal.Str ?: return fail()
        if (!Arrays.equals(expected.utf8, 0, expected.utf8.size, utf8, offset, offset + length)) return fail()
        scalarDone()
    }

    override fun beginString() {
        val expected = expected() as? Literal.Str ?: return fail()
        stringTarget = expected.utf8
        stringPosition = 0
    }

    override fun stringPart(utf8: ByteArray, offset: Int, length: Int) {
        val target = stringTarget ?: return fail()
        if (length > target.size - stringPosition || !Arrays.equals(target, stringPosition, stringPosition + length, utf8, offset, offset + length)) {
            return fail()
        }
        stringPosition += length
    }

    override fun endString() {
        val target = stringTarget ?: return fail()
        stringTarget = null
        if (stringPosition != target.size) return fail()
        scalarDone()
    }

    override fun number(ascii: ByteArray, offset: Int, length: Int) {
        val expected = expected() as? Literal.Num ?: return fail()
        numberKey.clear()
        val keyLength = CanonicalNumber.writeKey(ascii, offset, length, numberKey)
        if (keyLength < 0) return fail()
        numberDone(expected, keyLength)
    }

    override fun number(value: Long) {
        val expected = expected() as? Literal.Num ?: return fail()
        numberKey.clear()
        numberDone(expected, CanonicalNumber.writeKey(value, numberKey))
    }

    override fun booleanValue(value: Boolean) {
        val expected = expected() as? Literal.Bool ?: return fail()
        if (expected.value != value) return fail()
        scalarDone()
    }

    override fun nullValue() {
        if (expected() !== Literal.Null) return fail()
        scalarDone()
    }

    private fun numberDone(expected: Literal.Num, keyLength: Int) {
        if (!Arrays.equals(expected.key, 0, expected.key.size, numberKey.array, 0, keyLength)) return fail()
        scalarDone()
    }

    /** The literal the next value must equal, counting it in its container; null if there's none. */
    private fun expected(): Literal? {
        if (failed || complete) return null
        if (stack.isEmpty()) {
            if (started) return null
            started = true
            return root
        }
        val top = stack.size - 1
        return when (val container = stack[top]) {
            is Literal.Obj -> {
                val key = pendingKey ?: return null
                pendingKey = null
                counters[top]++
                container.members[key]
            }
            is Literal.Arr -> container.items.getOrNull(counters[top]++)
            else -> null
        }
    }

    private fun push(container: Literal) {
        if (stack.size == counters.size) counters = counters.copyOf(counters.size * 2)
        counters[stack.size] = 0
        stack += container
    }

    private fun end() {
        if (failed) return
        if (stack.isEmpty()) return fail()
        val top = stack.size - 1
        val expectedCount = when (val container = stack[top]) {
            is Literal.Obj -> container.members.size
            is Literal.Arr -> container.items.size
            else -> -1
        }
        if (counters[top] != expectedCount) return fail()
        stack.removeAt(top)
        if (stack.isEmpty()) complete = true
    }

    private fun scalarDone() {
        if (stack.isEmpty()) complete = true
    }

    private fun fail() {
        failed = true
    }
}
