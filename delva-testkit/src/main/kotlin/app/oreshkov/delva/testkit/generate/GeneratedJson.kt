package app.oreshkov.delva.testkit.generate

import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import java.io.InputStream
import kotlin.random.Random

/** Shapes of generated big documents. */
enum class BigDocProfile {
    /** `{"meta":{…},"items":[record, record, …]}`: many small records with repeated keys and values. */
    CATALOG,

    /** `{"k0":…,"k1":…,…}`: one object with a huge number of members. */
    WIDE_OBJECT,

    /** `{"chains":[…]}`: object chains nested up to 900 levels, over and over. */
    DEEP,

    /** `{"docs":[{"id":…,"body":"…"}, …]}`: strings of 1–8 MiB with escapes and multi-byte characters. */
    LONG_STRINGS,

    /** Records, deep chains and long strings interleaved. */
    MIXED,
}

/** Structure events of a generated document, in document order. String values arrive in parts. */
interface JsonEventListener {
    fun startObject() {}
    fun endObject() {}
    fun startArray() {}
    fun endArray() {}
    fun key(name: String) {}
    fun stringStart() {}
    fun stringPart(text: String) {}
    fun stringEnd() {}
    fun number(text: String) {}
    fun literal(value: OValue) {}
}

/**
 * A deterministic JSON document of about [targetBytes] (never much less, at most one record or string chunk more),
 * generated while it's read: memory stays bounded by one record or one string chunk, whatever the size.
 * The same `(seed, profile, targetBytes)` always yields the same bytes and the same [listener] events.
 */
class GeneratedJson(
    seed: Long,
    private val profile: BigDocProfile,
    private val targetBytes: Long,
    private val listener: JsonEventListener? = null,
) : InputStream() {
    private val random = Random(seed)
    private var buffer = ByteArray(1 shl 17)
    private var bufferStart = 0
    private var bufferEnd = 0
    private val text = StringBuilder()

    /** Bytes produced so far (read or buffered). */
    var producedBytes: Long = 0
        private set

    private enum class Phase { START, ITEMS, DONE }

    private var phase = Phase.START
    private var itemCount = 0L
    private var closing = ""
    private var longString: LongStringState? = null

    private class LongStringState(var remainingChars: Long)

    override fun read(): Int {
        if (!fill()) return -1
        return buffer[bufferStart++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, bufferEnd - bufferStart)
        System.arraycopy(buffer, bufferStart, b, off, n)
        bufferStart += n
        return n
    }

    override fun available(): Int = bufferEnd - bufferStart

    /** Drains the rest of the stream; useful to run the [listener] without consuming the bytes. */
    fun drain(): Long {
        val chunk = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = read(chunk, 0, chunk.size)
            if (n < 0) return total
            total += n
        }
    }

    private fun fill(): Boolean {
        while (bufferStart == bufferEnd) {
            if (phase == Phase.DONE) return false
            produce()
            flushText()
        }
        return true
    }

    private fun produce() {
        when (phase) {
            Phase.START -> start()
            Phase.ITEMS -> {
                val current = longString
                when {
                    current != null -> continueLongString(current)
                    producedBytes + text.length >= targetBytes -> finish()
                    else -> {
                        if (itemCount++ > 0) raw(",")
                        item()
                    }
                }
            }
            Phase.DONE -> Unit
        }
    }

    private fun start() {
        when (profile) {
            BigDocProfile.CATALOG, BigDocProfile.MIXED -> {
                open("{")
                key("meta")
                emit(OObj("id" to ONum(random.nextInt(1, 1000).toString()), "source" to OStr("feed-A"), "@type" to OStr("Catalog")))
                raw(",")
                key("items")
                open("[")
                closing = "]}"
            }
            BigDocProfile.WIDE_OBJECT -> {
                open("{")
                closing = "}"
            }
            BigDocProfile.DEEP -> {
                open("{")
                key("chains")
                open("[")
                closing = "]}"
            }
            BigDocProfile.LONG_STRINGS -> {
                open("{")
                key("docs")
                open("[")
                closing = "]}"
            }
        }
        phase = Phase.ITEMS
    }

    private fun item() {
        when (profile) {
            BigDocProfile.CATALOG -> emit(record())
            BigDocProfile.WIDE_OBJECT -> {
                key("k$itemCount")
                emit(if (random.nextInt(4) == 0) record() else scalar())
            }
            BigDocProfile.DEEP -> emit(chain(random.nextInt(1, 901)))
            BigDocProfile.LONG_STRINGS -> startLongStringDoc(random.nextLong(1L shl 20, 8L shl 20))
            BigDocProfile.MIXED -> when (random.nextInt(20)) {
                0 -> emit(chain(random.nextInt(1, 300)))
                1 -> startLongStringDoc(random.nextLong(1L shl 16, 1L shl 21))
                else -> emit(record())
            }
        }
    }

    private fun finish() {
        closing.forEach { if (it == '}') close("}") else close("]") }
        phase = Phase.DONE
    }

    private fun record(): OValue {
        val members = ArrayList<Pair<String, OValue>>()
        members += "sku" to OStr("S" + random.nextInt(10_000))
        members += "qty" to ONum(listOf("1", "2", "1.0", "10e-1", random.nextInt(100).toString())[random.nextInt(5)])
        if (random.nextBoolean()) members += "price" to ONum("${random.nextInt(1, 500)}.${random.nextInt(10, 100)}")
        members += "tags" to OArr(List(random.nextInt(0, 4)) { OStr(TAGS[random.nextInt(TAGS.size)]) })
        if (random.nextInt(3) == 0) members += "attrs" to OObj("color" to OStr(COLORS[random.nextInt(COLORS.size)]), "hot" to OBool(random.nextBoolean()), "note" to ONull)
        if (random.nextInt(10) == 0) members += "children" to OArr(OObj("sku" to OStr("S" + random.nextInt(10_000)), "qty" to ONum("1")))
        return OObj(members)
    }

    private fun chain(depth: Int): OValue {
        var value: OValue = OObj("leaf" to OBool(true), "qty" to ONum("1"))
        repeat(depth - 1) { level -> value = OObj("level" to ONum(level.toString()), "child" to value) }
        return value
    }

    private fun scalar(): OValue = when (random.nextInt(4)) {
        0 -> OStr(TAGS[random.nextInt(TAGS.size)])
        1 -> ONum(random.nextInt(-1000, 1000).toString())
        2 -> OBool(random.nextBoolean())
        else -> ONull
    }

    private fun startLongStringDoc(chars: Long) {
        open("{")
        key("id")
        emit(ONum(itemCount.toString()))
        raw(",")
        key("body")
        raw("\"")
        listener?.stringStart()
        longString = LongStringState(chars)
    }

    private fun continueLongString(state: LongStringState) {
        val part = StringBuilder()
        val n = minOf(state.remainingChars, 1L shl 16).toInt()
        while (part.length < n) {
            when (random.nextInt(40)) {
                0 -> part.append('\n')
                1 -> part.append('"')
                2 -> part.append('\\')
                3 -> part.appendCodePoint(0x1F600 + random.nextInt(50))
                4, 5 -> part.appendCodePoint(0x410 + random.nextInt(32))
                else -> part.append('a' + random.nextInt(26))
            }
        }
        state.remainingChars -= part.length
        val escaped = StringBuilder()
        JsonText.writeMinifiedString(escaped, part.toString())
        raw(escaped.substring(1, escaped.length - 1))
        listener?.stringPart(part.toString())
        if (state.remainingChars <= 0) {
            raw("\"")
            listener?.stringEnd()
            close("}")
            longString = null
        }
    }

    // ---- rendering with events ------------------------------------------------------------------------------------

    private fun emit(value: OValue) {
        when (value) {
            ONull, is OBool -> {
                raw(JsonText.minified(value))
                listener?.literal(value)
            }
            is ONum -> {
                raw(value.text)
                listener?.number(value.text)
            }
            is OStr -> {
                JsonText.writeMinifiedString(text, value.value)
                listener?.stringStart()
                listener?.stringPart(value.value)
                listener?.stringEnd()
            }
            is OArr -> {
                open("[")
                value.items.forEachIndexed { i, item ->
                    if (i > 0) raw(",")
                    emit(item)
                }
                close("]")
            }
            is OObj -> {
                open("{")
                value.members.forEachIndexed { i, (k, v) ->
                    if (i > 0) raw(",")
                    key(k)
                    emit(v)
                }
                close("}")
            }
        }
    }

    private fun key(name: String) {
        JsonText.writeMinifiedString(text, name)
        text.append(':')
        listener?.key(name)
    }

    private fun open(bracket: String) {
        raw(bracket)
        if (bracket == "{") listener?.startObject() else listener?.startArray()
    }

    private fun close(bracket: String) {
        raw(bracket)
        if (bracket == "}") listener?.endObject() else listener?.endArray()
    }

    private fun raw(s: String) {
        text.append(s)
    }

    private fun flushText() {
        if (text.isEmpty()) return
        val bytes = text.toString().toByteArray()
        text.setLength(0)
        if (bufferStart == bufferEnd) {
            bufferStart = 0
            bufferEnd = 0
        }
        if (buffer.size - bufferEnd < bytes.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, bufferEnd + bytes.size))
        System.arraycopy(bytes, 0, buffer, bufferEnd, bytes.size)
        bufferEnd += bytes.size
        producedBytes += bytes.size
    }

    private companion object {
        val TAGS = listOf("hot", "new", "sale", "x", "y")
        val COLORS = listOf("red", "green", "blue")
    }
}
