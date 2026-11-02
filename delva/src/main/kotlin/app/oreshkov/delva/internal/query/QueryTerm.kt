package app.oreshkov.delva.internal.query

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.canon.Nfc
import app.oreshkov.delva.internal.parse.JsonToken
import app.oreshkov.delva.internal.parse.NfcTokenSource
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.internal.term.IndexPolicy
import app.oreshkov.delva.internal.term.TermEncoder
import app.oreshkov.delva.internal.term.TermTag
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A query value in the form recheck compares against: numbers as NumberKeys, strings as UTF-8. */
internal sealed class Literal {
    /** Number of descendants, as a stored container's `size`. */
    abstract val size: Long

    data object Null : Literal() {
        override val size: Long get() = 0
    }

    class Bool(val value: Boolean) : Literal() {
        override val size: Long get() = 0
    }

    class Num(val key: ByteArray) : Literal() {
        override val size: Long get() = 0
    }

    class Str(val utf8: ByteArray) : Literal() {
        override val size: Long get() = 0
    }

    class Arr(val items: List<Literal>) : Literal() {
        override val size: Long = items.sumOf { 1 + it.size }
    }

    /** Members by key; keys are distinct. */
    class Obj(val members: Map<String, Literal>) : Literal() {
        override val size: Long = members.values.sumOf { 1 + it.size }
    }
}

/**
 * The posting-index term of a query and the literal that lossy matches are rechecked against.
 * [lossy] is true for NUMBER_LONG, STRING_LONG, OBJECT and ARRAY terms.
 */
internal class QueryTerm(val term: ByteArray, val lossy: Boolean, val literal: Literal) {
    companion object {
        /**
         * Builds the term of `key = value`. Invalid values are rejected like document content
         * ([DocumentRejectedException]); an object or array value without [hashTerms] is a [QueryRejectedException],
         * and so is a key or string length that [policy] doesn't index, because the index can't answer it.
         */
        fun of(
            key: String,
            value: JsonQueryValue,
            hashTerms: Boolean,
            maxDepth: Int,
            policy: IndexPolicy = IndexPolicy.EVERYTHING,
            normalization: Normalization = Normalization.NONE,
        ): QueryTerm {
            val nfc = normalization == Normalization.NFC
            val keyBytes = utf8(if (nfc) Nfc.normalize(key, "query key") else key, "query key")
            val literal = when (value) {
                JsonQueryValue.Null -> Literal.Null
                is JsonQueryValue.Bool -> Literal.Bool(value.value)
                is JsonQueryValue.Str -> Literal.Str(utf8(if (nfc) Nfc.normalize(value.value, "query string") else value.value, "query string"))
                is JsonQueryValue.Num -> numberLiteral(value.value.toString().toByteArray(Charsets.US_ASCII))
                is JsonQueryValue.Json -> parseLiteral(utf8(value.literal, "query literal"), maxDepth, normalization)
            }
            if ((literal is Literal.Obj || literal is Literal.Arr) && !hashTerms) {
                throw QueryRejectedException(QueryRejectedException.Reason.NOT_INDEXED, "object and array values aren't indexed by this store")
            }
            if (!policy.indexesKey(keyBytes, 0, keyBytes.size)) {
                throw QueryRejectedException(QueryRejectedException.Reason.NOT_INDEXED, "the key \"$key\" isn't indexed by this store")
            }
            if (literal is Literal.Str && !policy.indexesString(literal.utf8.size.toLong())) {
                throw QueryRejectedException(
                    QueryRejectedException.Reason.NOT_INDEXED,
                    "string values of ${literal.utf8.size} bytes aren't indexed by this store, which indexes strings of ${indexedLengths(policy)}",
                )
            }
            val out = GrowableBytes(keyBytes.size + 40)
            val encoder = TermEncoder()
            encoder.writeKey(out, keyBytes, 0, keyBytes.size)
            val valueStart = out.size
            when (literal) {
                Literal.Null -> encoder.writeNull(out)
                is Literal.Bool -> encoder.writeBoolean(out, literal.value)
                is Literal.Num -> {
                    val hash = LongArray(2)
                    CanonicalHash().number(literal.key, 0, literal.key.size, hash, 0)
                    encoder.writeNumberKey(out, literal.key, 0, literal.key.size, hash[0], hash[1])
                }
                is Literal.Str -> encoder.writeString(out, literal.utf8, 0, literal.utf8.size)
                is Literal.Arr, is Literal.Obj -> {
                    val hash = LiteralHash().hash(literal)
                    encoder.writeContainer(out, if (literal is Literal.Obj) TermTag.OBJECT else TermTag.ARRAY, hash[0], hash[1])
                }
            }
            val tag = out.array[valueStart].toInt() and 0xFF
            val lossy = tag == TermTag.NUMBER_LONG || tag == TermTag.STRING_LONG || tag == TermTag.OBJECT || tag == TermTag.ARRAY
            return QueryTerm(out.toByteArray(), lossy, literal)
        }

        private fun numberLiteral(text: ByteArray): Literal.Num {
            val key = GrowableBytes(32)
            when (CanonicalNumber.writeKey(text, 0, text.size, key)) {
                CanonicalNumber.MALFORMED -> throw DocumentRejectedException(RejectReason.MALFORMED_JSON, -1, detail = "query number ${String(text, Charsets.US_ASCII)}")
                CanonicalNumber.OUT_OF_RANGE -> throw DocumentRejectedException(RejectReason.NUMBER_OUT_OF_RANGE, -1, detail = "query number ${String(text, Charsets.US_ASCII)}")
            }
            return Literal.Num(key.toByteArray())
        }

        /** UTF-8 of a query string; unpaired surrogates are rejected as [RejectReason.INVALID_STRING] (SPEC.md §4.1). */
        private fun utf8(s: String, what: String): ByteArray {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                val buffer: ByteBuffer = encoder.encode(CharBuffer.wrap(s))
                return ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (_: CharacterCodingException) {
                throw DocumentRejectedException(RejectReason.INVALID_STRING, -1, detail = "$what has an unpaired surrogate")
            }
        }

        /** Parses a JSON literal with the document tokenizer and rules (limits, duplicate keys, number range). */
        private fun parseLiteral(json: ByteArray, maxDepth: Int, normalization: Normalization): Literal {
            val limits = ParseLimits(maxDepth = maxDepth, maxKeyBytes = Int.MAX_VALUE, maxNumberLength = Int.MAX_VALUE)
            val nfc = normalization == Normalization.NFC
            val parsed = Utf8JsonTokenizer(ByteArrayInputStream(json), if (nfc) limits.forNfc() else limits)
            val tokenizer = if (nfc) NfcTokenSource(parsed, limits) else parsed
            // Open containers: object members (with the pending key) or array items.
            val objects = ArrayList<LinkedHashMap<String, Literal>?>()
            val arrays = ArrayList<ArrayList<Literal>?>()
            val keys = ArrayList<String?>()
            val string = GrowableBytes(64)
            var result: Literal? = null

            fun add(value: Literal) {
                val top = objects.size - 1
                if (top < 0) {
                    result = value
                    return
                }
                val members = objects[top]
                if (members != null) {
                    val key = keys[top]!!
                    if (members.put(key, value) != null) {
                        throw DocumentRejectedException(RejectReason.DUPLICATE_KEY, tokenizer.byteOffset, detail = "query literal")
                    }
                    keys[top] = null
                } else {
                    arrays[top]!!.add(value)
                }
            }

            while (true) {
                when (tokenizer.next()) {
                    JsonToken.EOF -> return result!!
                    JsonToken.START_OBJECT -> {
                        objects.add(LinkedHashMap())
                        arrays.add(null)
                        keys.add(null)
                    }
                    JsonToken.START_ARRAY -> {
                        objects.add(null)
                        arrays.add(ArrayList())
                        keys.add(null)
                    }
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> {
                        val top = objects.size - 1
                        val value = objects[top]?.let { Literal.Obj(it) } ?: Literal.Arr(arrays[top]!!)
                        objects.removeAt(top)
                        arrays.removeAt(top)
                        keys.removeAt(top)
                        add(value)
                    }
                    JsonToken.KEY -> keys[keys.size - 1] = String(tokenizer.bytes, tokenizer.offset, tokenizer.length, Charsets.UTF_8)
                    JsonToken.STRING -> add(Literal.Str(tokenizer.bytes.copyOfRange(tokenizer.offset, tokenizer.offset + tokenizer.length)))
                    JsonToken.STRING_PART -> {
                        string.writeBytes(tokenizer.bytes, tokenizer.offset, tokenizer.length)
                        if (tokenizer.isLastPart) {
                            add(Literal.Str(string.toByteArray()))
                            string.clear()
                        }
                    }
                    JsonToken.NUMBER -> add(numberLiteral(tokenizer.bytes.copyOfRange(tokenizer.offset, tokenizer.offset + tokenizer.length)))
                    JsonToken.TRUE -> add(Literal.Bool(true))
                    JsonToken.FALSE -> add(Literal.Bool(false))
                    JsonToken.NULL -> add(Literal.Null)
                }
            }
        }
    }
}

/** The string lengths [policy] indexes, as `at least 4 bytes`, `at most 200 bytes` or `4 to 200 bytes`. */
private fun indexedLengths(policy: IndexPolicy): String = when {
    policy.maxStringBytes == Int.MAX_VALUE -> "at least ${policy.minStringBytes} bytes"
    policy.minStringBytes == 0 -> "at most ${policy.maxStringBytes} bytes"
    else -> "${policy.minStringBytes} to ${policy.maxStringBytes} bytes"
}

/** Canonical hash of a [Literal], without recursion: a literal may nest as deep as `maxDepth` allows. */
private class LiteralHash {
    private val hash = CanonicalHash()
    private var frames = LongArray(16 * CanonicalHash.FRAME_SLOTS)

    /** A container being hashed: the children not taken yet, and for an object the key of the one taken last. */
    private class Open(private val items: Iterator<Literal>?, private val members: Iterator<Map.Entry<String, Literal>>?) {
        val isObject: Boolean get() = members != null
        var key: ByteArray = ByteArray(0)
            private set

        fun next(): Literal? = when {
            members != null -> if (members.hasNext()) members.next().let { key = it.key.toByteArray(Charsets.UTF_8); it.value } else null
            items!!.hasNext() -> items.next()
            else -> null
        }
    }

    fun hash(literal: Literal): LongArray {
        val out = LongArray(2)
        val open = ArrayList<Open>()
        var value: Literal? = literal
        while (true) {
            // Hash `value` into `out`, or, when it's a container, open it and go on with its first child.
            val top = open.size - 1
            when (value) {
                null -> {
                    // The innermost container has no children left.
                    val slot = top * CanonicalHash.FRAME_SLOTS
                    if (open[top].isObject) hash.endObject(frames, slot, out, 0) else hash.endArray(frames, slot, out, 0)
                    open.removeAt(top)
                }
                Literal.Null -> hash.nullValue(out, 0)
                is Literal.Bool -> hash.booleanValue(value.value, out, 0)
                is Literal.Num -> hash.number(value.key, 0, value.key.size, out, 0)
                is Literal.Str -> hash.string(value.utf8, 0, value.utf8.size, out, 0)
                is Literal.Arr -> {
                    value = enter(open, Open(value.items.iterator(), null))
                    continue
                }
                is Literal.Obj -> {
                    value = enter(open, Open(null, value.members.entries.iterator()))
                    continue
                }
            }
            // `out` holds a whole value: add it to its container and take the container's next child.
            if (open.isEmpty()) return out
            val container = open.last()
            val slot = (open.size - 1) * CanonicalHash.FRAME_SLOTS
            if (container.isObject) hash.objectMember(frames, slot, container.key, 0, container.key.size, out, 0) else hash.arrayElement(frames, slot, out, 0)
            value = container.next()
        }
    }

    /** Opens [container] and takes its first child. */
    private fun enter(open: MutableList<Open>, container: Open): Literal? {
        val slot = frame(open.size)
        if (container.isObject) hash.beginObject(frames, slot) else hash.beginArray(frames, slot)
        open += container
        return container.next()
    }

    private fun frame(depth: Int): Int {
        val slot = depth * CanonicalHash.FRAME_SLOTS
        if (slot + CanonicalHash.FRAME_SLOTS > frames.size) frames = frames.copyOf(maxOf(frames.size * 2, slot + CanonicalHash.FRAME_SLOTS))
        return slot
    }
}
