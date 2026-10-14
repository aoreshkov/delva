package app.oreshkov.delva.internal.hash

import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Xxh3Test {
    private class Vector(val bits: Int, val length: Int, val seed: Long, val low: Long, val high: Long)

    private val vectors: List<Vector> by lazy {
        val text = checkNotNull(javaClass.classLoader.getResource("xxh3/sanity-vectors.txt")).readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val f = line.split(' ')
            when (f[0]) {
                "xxh3_64" -> Vector(64, f[1].toInt(), f[2].toULong(16).toLong(), f[3].toULong(16).toLong(), 0)
                "xxh3_128" -> Vector(128, f[1].toInt(), f[2].toULong(16).toLong(), f[3].toULong(16).toLong(), f[4].toULong(16).toLong())
                else -> error("bad vector line: $line")
            }
        }
    }

    /** The xxHash sanity buffer (see the resource header). */
    private val sanityBuffer: ByteArray by lazy {
        val prime32 = 2654435761L
        val prime64 = -7046029288634856819L // 11400714785074694797
        var byteGen = prime32
        ByteArray(vectors.maxOf { it.length }) {
            val b = (byteGen ushr 56).toByte()
            byteGen *= prime64
            b
        }
    }

    @Test
    fun `reference vectors cover every length class, seeded and unseeded`() {
        val lengths = vectors.map { it.length }.toSet()
        for (range in listOf(0..0, 1..3, 4..8, 9..16, 17..128, 129..240, 241..1024, 1025..4096)) {
            assertTrue(lengths.any { it in range }, "no vector in $range")
        }
        assertTrue(vectors.any { it.bits == 64 && it.seed != 0L } && vectors.any { it.bits == 128 && it.seed != 0L })
        assertTrue(vectors.size > 1500)
    }

    @Test
    fun `one-shot hashes match the reference vectors`() {
        val out = LongArray(3)
        for (v in vectors) {
            if (v.bits == 64) {
                assertEquals(v.low, Xxh3.hash64(sanityBuffer, 0, v.length, v.seed), "xxh3_64 len=${v.length} seed=${v.seed}")
            } else {
                Xxh3.hash128(sanityBuffer, 0, v.length, v.seed, out, 1)
                assertEquals(v.low, out[1], "xxh3_128 low len=${v.length} seed=${v.seed}")
                assertEquals(v.high, out[2], "xxh3_128 high len=${v.length} seed=${v.seed}")
            }
        }
    }

    @Test
    fun `hashing from a non-zero offset gives the same result`() {
        val shifted = ByteArray(sanityBuffer.size + 13) { 0x5A }
        System.arraycopy(sanityBuffer, 0, shifted, 7, sanityBuffer.size)
        val out = LongArray(2)
        for (v in vectors) {
            if (v.bits == 64) {
                assertEquals(v.low, Xxh3.hash64(shifted, 7, v.length, v.seed))
            } else {
                Xxh3.hash128(shifted, 7, v.length, v.seed, out, 0)
                assertEquals(v.low, out[0])
                assertEquals(v.high, out[1])
            }
        }
    }

    @Test
    fun `streaming in fixed chunk sizes matches the reference vectors`() {
        val state = Xxh3State()
        val out = LongArray(2)
        for (v in vectors) {
            for (chunk in intArrayOf(1, 7, 64, 255, 256, 257, 1000)) {
                state.reset(v.seed)
                var p = 0
                while (p < v.length) {
                    val n = minOf(chunk, v.length - p)
                    state.update(sanityBuffer, p, n)
                    p += n
                }
                if (v.bits == 64) {
                    assertEquals(v.low, state.digest64(), "stream64 len=${v.length} seed=${v.seed} chunk=$chunk")
                } else {
                    state.digest128(out, 0)
                    assertEquals(v.low, out[0], "stream128 low len=${v.length} seed=${v.seed} chunk=$chunk")
                    assertEquals(v.high, out[1], "stream128 high len=${v.length} seed=${v.seed} chunk=$chunk")
                }
            }
        }
    }

    private class SplitCase(val input: ByteArray, val seed: Long, val cuts: IntArray) {
        override fun toString() = "SplitCase(length=${input.size}, seed=$seed, cuts=${cuts.contentToString()})"
    }

    private val splitCases = Gen { random, size ->
        val length = when (random.nextInt(4)) {
            0 -> random.nextInt(0, 17)
            1 -> random.nextInt(0, 241)
            2 -> random.nextInt(241, 1500)
            else -> random.nextInt(0, 30 * size + 300)
        }
        val input = random.nextBytes(length)
        val seed = if (random.nextBoolean()) 0L else random.nextLong()
        val cuts = IntArray(random.nextInt(0, 8)) { random.nextInt(0, length + 1) }.sortedArray()
        SplitCase(input, seed, cuts)
    }

    @Test
    fun `any split into updates gives the one-shot digest, and digesting doesn't disturb the state`() {
        val state = Xxh3State()
        val out = LongArray(2)
        val expected128 = LongArray(2)
        forAll("xxh3 streaming split", splitCases, iterations = 1000) { case ->
            val input = case.input
            state.reset(case.seed)
            var previous = 0
            for (cut in case.cuts) {
                state.update(input, previous, cut - previous)
                // A digest in the middle must not affect the final result.
                state.digest64()
                previous = cut
            }
            state.update(input, previous, input.size - previous)

            assertEquals(Xxh3.hash64(input, 0, input.size, case.seed), state.digest64())
            Xxh3.hash128(input, 0, input.size, case.seed, expected128, 0)
            state.digest128(out, 0)
            assertEquals(expected128[0], out[0])
            assertEquals(expected128[1], out[1])
        }
    }

    @Test
    fun `single-byte updates match the one-shot digest`() {
        val input = sanityBuffer.copyOf(1100)
        val state = Xxh3State().reset(42)
        for (b in input) state.update(b.toInt())
        assertEquals(Xxh3.hash64(input, 0, input.size, 42), state.digest64())
    }
}
