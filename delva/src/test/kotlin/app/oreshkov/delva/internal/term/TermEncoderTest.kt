package app.oreshkov.delva.internal.term

import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.canon.TJ
import app.oreshkov.delva.internal.canon.TestJsonGen
import app.oreshkov.delva.internal.canon.describe
import app.oreshkov.delva.internal.canon.toJson
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.Gens
import app.oreshkov.delva.testkit.property.forAll
import app.oreshkov.delva.testkit.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TermEncoderTest {
    private val encoder = TermEncoder()
    private val hasher = CanonicalHash()
    private val frames = LongArray(CanonicalHash.FRAME_SLOTS * 32)

    /** Canonical hash of a value, computed through [CanonicalHash] directly. */
    private fun hashOf(value: TJ, depth: Int = 0, out: LongArray = LongArray(2)): LongArray {
        when (value) {
            TJ.Null -> hasher.nullValue(out, 0)
            is TJ.Bool -> hasher.booleanValue(value.value, out, 0)
            is TJ.Num -> {
                val key = numberKey(value.text)
                hasher.number(key, 0, key.size, out, 0)
            }
            is TJ.Str -> value.value.toByteArray().let { hasher.string(it, 0, it.size, out, 0) }
            is TJ.Arr -> {
                val frame = depth * CanonicalHash.FRAME_SLOTS
                hasher.beginArray(frames, frame)
                for (item in value.items) hasher.arrayElement(frames, frame, hashOf(item, depth + 1, out), 0)
                hasher.endArray(frames, frame, out, 0)
            }
            is TJ.Obj -> {
                val frame = depth * CanonicalHash.FRAME_SLOTS
                hasher.beginObject(frames, frame)
                for ((k, v) in value.members) {
                    val key = k.toByteArray()
                    hasher.objectMember(frames, frame, key, 0, key.size, hashOf(v, depth + 1, out), 0)
                }
                hasher.endObject(frames, frame, out, 0)
            }
        }
        return out
    }

    private fun numberKey(text: String): ByteArray {
        val out = GrowableBytes()
        val t = text.toByteArray()
        check(CanonicalNumber.writeKey(t, 0, t.size, out) > 0)
        return out.toByteArray()
    }

    /** The term for member `key: value`, as the splitter will produce it. */
    private fun term(key: String, value: TJ): ByteArray {
        val out = GrowableBytes()
        val k = key.toByteArray()
        encoder.writeKey(out, k, 0, k.size)
        val start = out.size
        val written = when (value) {
            TJ.Null -> { encoder.writeNull(out); 1 }
            is TJ.Bool -> { encoder.writeBoolean(out, value.value); 1 }
            is TJ.Num -> value.text.toByteArray().let { encoder.writeNumber(out, it, 0, it.size) }
            is TJ.Str -> value.value.toByteArray().let { encoder.writeString(out, it, 0, it.size) }
            is TJ.Arr, is TJ.Obj -> hashOf(value).let {
                encoder.writeContainer(out, if (value is TJ.Arr) TermTag.ARRAY else TermTag.OBJECT, it[0], it[1])
            }
        }
        assertEquals(out.size - start, written, "returned length")
        return out.toByteArray()
    }

    private fun hashBytes(hash: LongArray) = ByteArray(16).also { CanonicalHash.putHash(it, 0, hash[0], hash[1]) }

    @Test
    fun `terms are equal exactly when key and value are semantically equal`() {
        val members: Gen<Pair<String, TJ>> = Gen { random, size ->
            val key = if (random.nextBoolean()) listOf("", "a", "ab", "k")[random.nextInt(4)] else TestJsonGen.string(random, 4)
            key to TestJsonGen.value(random, size, 2)
        }
        forAll("term collisions", Gens.pair(Gens.pair(members, members), Gens.long()), iterations = 2000, maxSize = 20) { (pair, seed) ->
            val (a, b0) = pair
            // Half the time compare against a respelled copy of the same member.
            val b = if (Random(seed).nextBoolean()) a.first to TestJsonGen.respell(a.second, Random(seed)) else b0
            val sameMember = a.first == b.first && a.second.describe() == b.second.describe()
            val sameTerm = term(a.first, a.second).contentEquals(term(b.first, b.second))
            assertEquals(sameMember, sameTerm, "${a.first}: ${a.second.toJson()} vs ${b.first}: ${b.second.toJson()}")
        }
    }

    @Test
    fun `term starts with the length-prefixed key, then the tag`() {
        val t = term("ключ", TJ.Str("v"))
        val reader = ByteReader(t)
        val keyLength = reader.readVarInt()
        val key = ByteArray(keyLength).also { reader.readBytes(it, 0, keyLength) }
        assertEquals("ключ", key.decodeToString())
        assertEquals(TermTag.STRING, reader.readByte())
        assertEquals('v'.code, reader.readByte())
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `numbers switch from NUMBER to NUMBER_LONG above a 64-byte NumberKey`() {
        // 116 significant digits with an even exponent form 58 pairs: key = 1 + 4 + 58 + 1 = 64 bytes.
        val exact = "0." + "7".repeat(116)
        val long = "0." + "7".repeat(117)
        assertEquals(64, numberKey(exact).size)
        assertEquals(65, numberKey(long).size)

        val exactTerm = term("", TJ.Num(exact))
        assertEquals(TermTag.NUMBER, exactTerm[1].toInt())
        assertContentEquals(numberKey(exact), exactTerm.copyOfRange(2, exactTerm.size))

        val longTerm = term("", TJ.Num(long))
        assertEquals(1 + 1 + 16 + 16, longTerm.size)
        assertEquals(TermTag.NUMBER_LONG, longTerm[1].toInt())
        assertContentEquals(numberKey(long).copyOf(16), longTerm.copyOfRange(2, 18))
        assertContentEquals(hashBytes(hashOf(TJ.Num(long))), longTerm.copyOfRange(18, 34))
    }

    @Test
    fun `strings switch from STRING to STRING_LONG above 64 bytes, and streamed long strings match`() {
        val exact = "é".repeat(32) // 64 bytes
        val long = "é".repeat(32) + "x"
        val exactTerm = term("k", TJ.Str(exact))
        assertEquals(TermTag.STRING, exactTerm[2].toInt())
        assertEquals(2 + 1 + 64, exactTerm.size)

        val longTerm = term("k", TJ.Str(long))
        assertEquals(TermTag.STRING_LONG, longTerm[2].toInt())
        assertContentEquals(long.toByteArray().copyOf(16), longTerm.copyOfRange(3, 19))
        assertContentEquals(hashBytes(hashOf(TJ.Str(long))), longTerm.copyOfRange(19, 35))

        forAll("streamed long string term", Gen { random, size -> random.nextBytes(random.nextInt(65, 70 + size * 40)) }) { bytes ->
            val oneShot = GrowableBytes().also { encoder.writeString(it, bytes, 0, bytes.size) }
            hasher.beginString()
            var p = 0
            while (p < bytes.size) {
                val n = minOf(bytes.size - p, 1 + p % 97)
                hasher.stringPart(bytes, p, n)
                p += n
            }
            val hash = LongArray(2).also { hasher.endString(it, 0) }
            val streamed = GrowableBytes().also { encoder.writeLongString(it, bytes, 0, hash[0], hash[1]) }
            assertEquals(oneShot.toByteArray().toHex(), streamed.toByteArray().toHex())
        }
    }

    @Test
    fun `integer terms from Long equal terms from text`() {
        forAll("number term from long", Gens.edgyLong(), iterations = 1000) { value ->
            val fromLong = GrowableBytes().also { encoder.writeNumber(it, value) }
            val fromText = GrowableBytes().also { value.toString().toByteArray().let { t -> encoder.writeNumber(it, t, 0, t.size) } }
            assertEquals(fromText.toByteArray().toHex(), fromLong.toByteArray().toHex())
        }
    }

    @Test
    fun `invalid numbers write nothing`() {
        val out = GrowableBytes()
        out.writeByte(0x42)
        assertEquals(CanonicalNumber.OUT_OF_RANGE, "1e9999999999".toByteArray().let { encoder.writeNumber(out, it, 0, it.size) })
        assertEquals(CanonicalNumber.MALFORMED, "1.".toByteArray().let { encoder.writeNumber(out, it, 0, it.size) })
        assertEquals(1, out.size)
    }

    @Test
    fun `golden terms`() {
        val cases = listOf(
            "k" to TJ.Null, "k" to TJ.Bool(false), "k" to TJ.Bool(true),
            "id" to TJ.Num("7"), "qty" to TJ.Num("1.0"), "n" to TJ.Num("-0.05"), "n" to TJ.Num("0." + "7".repeat(117)),
            "sku" to TJ.Str("A1"), "" to TJ.Str(""), "s" to TJ.Str("x".repeat(65)), "ключ" to TJ.Str("значение"),
            "items" to TJ.Arr(listOf(TJ.Obj(listOf("sku" to TJ.Str("A1"), "qty" to TJ.Num("2"))))),
            "order" to TJ.Obj(listOf("id" to TJ.Num("7"))),
            "tags" to TJ.Arr(emptyList()), "meta" to TJ.Obj(emptyList()),
        )
        Golden.verify(
            "terms.txt",
            header = "Posting-index term bytes (hex) per member. Changing any line breaks stored indexes.",
            actual = cases.map { (k, v) -> "${term(k, v).toHex()} ${TJ.Str(k).toJson()}:${v.toJson()}" },
        )
    }
}
