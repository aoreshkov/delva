package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.bytes.Varint
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.property.Gens
import app.oreshkov.delva.testkit.property.forAll
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalHashTest {
    /** Drives [CanonicalHash] like the splitter will: explicit frames, strings in random parts. */
    private class StreamingHasher(private val random: Random?) {
        private val hash = CanonicalHash()
        private val frames = LongArray(CanonicalHash.FRAME_SLOTS * 64)
        private val numberKey = GrowableBytes()

        fun hash(value: TJ): Pair<Long, Long> {
            val out = LongArray(2)
            hash(value, 0, out)
            return out[0] to out[1]
        }

        private fun hash(value: TJ, depth: Int, out: LongArray) {
            when (value) {
                TJ.Null -> hash.nullValue(out, 0)
                is TJ.Bool -> hash.booleanValue(value.value, out, 0)
                is TJ.Num -> {
                    numberKey.clear()
                    val bytes = value.text.toByteArray()
                    check(CanonicalNumber.writeKey(bytes, 0, bytes.size, numberKey) > 0)
                    hash.number(numberKey.array, 0, numberKey.size, out, 0)
                }
                is TJ.Str -> {
                    val bytes = value.value.toByteArray()
                    if (random == null) {
                        hash.string(bytes, 0, bytes.size, out, 0)
                    } else {
                        hash.beginString()
                        var p = 0
                        while (p < bytes.size) {
                            val n = random.nextInt(1, bytes.size - p + 1)
                            hash.stringPart(bytes, p, n)
                            p += n
                        }
                        hash.endString(out, 0)
                    }
                }
                is TJ.Arr -> {
                    val frame = depth * CanonicalHash.FRAME_SLOTS
                    hash.beginArray(frames, frame)
                    for (item in value.items) {
                        hash(item, depth + 1, out)
                        hash.arrayElement(frames, frame, out, 0)
                    }
                    hash.endArray(frames, frame, out, 0)
                }
                is TJ.Obj -> {
                    val frame = depth * CanonicalHash.FRAME_SLOTS
                    hash.beginObject(frames, frame)
                    for ((key, member) in value.members) {
                        hash(member, depth + 1, out)
                        val keyBytes = key.toByteArray()
                        hash.objectMember(frames, frame, keyBytes, 0, keyBytes.size, out, 0)
                    }
                    hash.endObject(frames, frame, out, 0)
                }
            }
        }
    }

    /** The [CanonicalHash] formulas written directly: whole byte strings, one-shot XXH3, BigInteger sums. */
    private object SpecHasher {
        private val two128 = BigInteger.ONE.shiftLeft(128)

        fun hash(value: TJ): BigInteger = when (value) {
            TJ.Null -> h(byteArrayOf(0x01))
            is TJ.Bool -> h(byteArrayOf(if (value.value) 0x03 else 0x02))
            is TJ.Num -> {
                val key = GrowableBytes().also { val t = value.text.toByteArray(); CanonicalNumber.writeKey(t, 0, t.size, it) }
                h(byteArrayOf(0x10) + key.toByteArray())
            }
            is TJ.Str -> h(byteArrayOf(0x20) + value.value.toByteArray())
            is TJ.Arr -> {
                var a = BigInteger.ZERO
                for (item in value.items) a = h(byteArrayOf('A'.code.toByte()) + b16(a) + b16(hash(item)))
                h(byteArrayOf('a'.code.toByte()) + b16(a) + u64le(value.items.size.toLong()))
            }
            is TJ.Obj -> {
                var m = BigInteger.ZERO
                for ((key, member) in value.members) {
                    val k = key.toByteArray()
                    val varint = ByteArray(10).let { it.copyOf(Varint.write(it, 0, k.size.toLong())) }
                    m = (m + h(byteArrayOf('M'.code.toByte()) + varint + k + b16(hash(member)))).mod(two128)
                }
                h(byteArrayOf('O'.code.toByte()) + b16(m) + u64le(value.members.size.toLong()))
            }
        }

        private fun h(input: ByteArray): BigInteger {
            val out = LongArray(2)
            Xxh3.hash128(input, 0, input.size, 0L, out, 0)
            return toBig(out[0], out[1])
        }

        private fun b16(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            val out = ByteArray(16)
            val n = minOf(raw.size, 16)
            System.arraycopy(raw, raw.size - n, out, 16 - n, n)
            return out
        }

        private fun u64le(v: Long) = ByteArray(8).also { LittleEndian.putLong(it, 0, v) }
    }

    @Test
    fun `streaming state machine matches the specification formulas`() {
        forAll("canonical hash vs spec", Gens.pair(TestJsonGen.value(), Gens.long()), iterations = 500, maxSize = 30) { (value, seed) ->
            val (low, high) = StreamingHasher(Random(seed)).hash(value)
            assertEquals(SpecHasher.hash(value), toBig(low, high), value.toJson())
        }
    }

    @Test
    fun `equal values hash equally regardless of member order, number spelling and string chunking`() {
        forAll("canonical hash invariance", Gens.pair(TestJsonGen.value(), Gens.long()), iterations = 500, maxSize = 30) { (value, seed) ->
            val random = Random(seed)
            val other = TestJsonGen.respell(value, random)
            assertEquals(StreamingHasher(null).hash(value), StreamingHasher(random).hash(other), "${value.toJson()} vs ${other.toJson()}")
        }
    }

    @Test
    fun `hashes differ when values differ`() {
        forAll("canonical hash distinguishes values", Gens.pair(TestJsonGen.value(), TestJsonGen.value()), iterations = 1000, maxSize = 20) { (a, b) ->
            val equalValues = a.describe() == b.describe()
            val equalHashes = StreamingHasher(null).hash(a) == StreamingHasher(null).hash(b)
            assertEquals(equalValues, equalHashes, "${a.toJson()} vs ${b.toJson()}")
        }
    }

    @Test
    fun `array order matters, types are distinct, and nesting is not flattened`() {
        fun h(v: TJ) = StreamingHasher(null).hash(v)
        val one = TJ.Num("1")
        val two = TJ.Num("2")
        assertNotEquals(h(TJ.Arr(listOf(one, two))), h(TJ.Arr(listOf(two, one))))
        assertNotEquals(h(TJ.Arr(listOf(TJ.Arr(listOf(one)), two))), h(TJ.Arr(listOf(one, TJ.Arr(listOf(two))))))
        assertNotEquals(h(TJ.Arr(listOf(one, TJ.Arr(emptyList())))), h(TJ.Arr(listOf(one))))
        val scalars = listOf(TJ.Null, TJ.Bool(false), TJ.Bool(true), TJ.Num("0"), TJ.Str(""), TJ.Str("0"), TJ.Arr(emptyList()), TJ.Obj(emptyList()))
        assertEquals(scalars.size, scalars.map { h(it) }.toSet().size)
        // A member's key and value are bound together.
        assertNotEquals(
            h(TJ.Obj(listOf("a" to one, "b" to two))),
            h(TJ.Obj(listOf("a" to two, "b" to one))),
        )
    }

    @Test
    fun `golden canonical hashes`() {
        val values = listOf(
            TJ.Null, TJ.Bool(false), TJ.Bool(true),
            TJ.Num("0"), TJ.Num("1"), TJ.Num("-1.5e3"), TJ.Num("0." + "7".repeat(120)),
            TJ.Str(""), TJ.Str("a"), TJ.Str("ключ 😀"), TJ.Str("x".repeat(300)),
            TJ.Arr(emptyList()), TJ.Obj(emptyList()),
            TJ.Arr(listOf(TJ.Num("1"), TJ.Str("a"), TJ.Null)),
            TJ.Obj(listOf("id" to TJ.Num("7"), "tags" to TJ.Arr(listOf(TJ.Str("x"), TJ.Str("y"))))),
            TJ.Obj(listOf("order" to TJ.Obj(listOf("id" to TJ.Num("7"), "items" to TJ.Arr(listOf(TJ.Obj(listOf("sku" to TJ.Str("A1"), "qty" to TJ.Num("2"))))))))),
        )
        Golden.verify(
            "canonical-hashes.txt",
            header = "Canonical XXH3-128 value hashes: <high><low> hex, then the value. Changing any line breaks stored data.",
            actual = values.map { v -> StreamingHasher(null).hash(v).let { (lo, hi) -> "%016x%016x %s".format(hi, lo, v.toJson()) } },
        )
    }
}

internal fun toBig(low: Long, high: Long): BigInteger =
    BigInteger(1, ByteArray(16).also {
        app.oreshkov.delva.internal.bytes.BigEndian.putLong(it, 0, high)
        app.oreshkov.delva.internal.bytes.BigEndian.putLong(it, 8, low)
    })
