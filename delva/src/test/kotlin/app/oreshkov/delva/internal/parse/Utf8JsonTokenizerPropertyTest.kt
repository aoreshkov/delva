package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.json.ParseResult
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Differential tests against the independent oracle parser: the same outcome (tokens, or the rejection reason) for
 * generated documents and for mutated ones, at every window size, chunk size and read pattern (window independence).
 */
class Utf8JsonTokenizerPropertyTest {
    // Small limits, so generated and mutated documents hit them.
    private val limits = ParseLimits(maxDepth = 120, maxKeyBytes = 48, maxNumberLength = 40, maxStringBytes = 1200)

    private class Case(val json: ByteArray, val seed: Long) {
        override fun toString(): String = String(json, Charsets.UTF_8).let { if (it.length > 500) it.take(500) + "…" else it }
    }

    private fun documents(profile: JsonProfile): Gen<Case> {
        val docs = JsonGen(profile, fragmentSize = 300).document()
        return Gen { random, size -> Case(docs.generate(random, size).second, random.nextLong()) }
    }

    private fun mutated(profile: JsonProfile): Gen<Case> {
        val docs = documents(profile)
        return Gen { random, size ->
            val case = docs.generate(random, size)
            var bytes = case.json
            repeat(random.nextInt(1, 4)) { bytes = mutate(bytes, random) }
            Case(bytes, case.seed)
        }
    }

    private fun mutate(bytes: ByteArray, random: Random): ByteArray {
        val at = random.nextInt(0, bytes.size + 1)
        return when (random.nextInt(5)) {
            0 -> bytes.copyOf(at) + byteArrayOf(interestingByte(random)) + bytes.copyOfRange(at, bytes.size)
            1 -> if (at < bytes.size) bytes.copyOf(at) + bytes.copyOfRange(at + 1, bytes.size) else bytes
            2 -> if (at < bytes.size) bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() } else bytes
            3 -> if (at < bytes.size) bytes.copyOf().also { it[at] = interestingByte(random) } else bytes
            else -> bytes.copyOf(at)
        }
    }

    private fun interestingByte(random: Random): Byte =
        if (random.nextBoolean()) INTERESTING[random.nextInt(INTERESTING.size)] else random.nextInt(256).toByte()

    /** Tokenizes [case] every way and checks all agree with the oracle and with each other. */
    private fun checkAgainstOracle(case: Case) {
        val expected = when (val oracle = OracleJson.parse(case.json, limits.toOracle())) {
            is ParseResult.Accepted -> Tokenized.Accepted(expectedTokens(oracle.value))
            is ParseResult.Rejected -> {
                // The tokenizer doesn't look for duplicate keys; whatever follows is unspecified for it.
                if (oracle.reason == RejectReason.DUPLICATE_KEY) return
                oracle.reason
            }
        }
        val random = Random(case.seed)
        val results = listOf(
            tokenize(case.json, limits, window = 1, chunk = 4),
            tokenize(case.json, limits, window = 7, chunk = 64),
            tokenize(case.json, limits, window = 64, chunk = 5),
            tokenize(case.json, limits, window = 64 * 1024, chunk = 64 * 1024),
            tokenize(case.json, limits, window = 256, chunk = 100, stream = ShortReadStream(case.json, random)),
        )
        for (result in results) {
            if (result != results[0]) fail("window sizes disagree: ${results[0]} vs $result")
        }
        when (val actual = results[0]) {
            is Tokenized.Accepted -> assertEquals(expected, actual)
            is Tokenized.Rejected -> assertEquals(expected, actual.reason)
        }
    }

    @Test
    fun `generated documents tokenize like the oracle parses them`() {
        for (profile in JsonProfile.entries) {
            forAll("tokens of $profile documents", documents(profile), iterations = 1000, maxSize = 200) { checkAgainstOracle(it) }
        }
    }

    @Test
    fun `mutated documents are accepted or rejected like the oracle does`() {
        for (profile in JsonProfile.entries) {
            forAll("mutated $profile documents", mutated(profile), iterations = 1000, maxSize = 100) { checkAgainstOracle(it) }
        }
    }

    private companion object {
        val INTERESTING = "{}[]\",:\\ \t\n0123456789.eE+-tfnu/".toByteArray() +
            byteArrayOf(0x00, 0x1F, 0x7F, 0x80.toByte(), 0xBF.toByte(), 0xC0.toByte(), 0xE0.toByte(), 0xED.toByte(), 0xEF.toByte(), 0xF4.toByte(), 0xFF.toByte())
    }
}
