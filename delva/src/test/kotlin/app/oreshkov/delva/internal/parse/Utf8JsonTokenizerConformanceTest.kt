package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.testkit.json.JsonTestSuite
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.json.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tokenizer against JSONTestSuite. Duplicate keys are the splitter's job, so the tokenizer accepts
 * the two duplicate-key files; every other outcome, including the rejection reason, matches the oracle.
 */
class Utf8JsonTokenizerConformanceTest {
    @Test
    fun `every JSONTestSuite parsing file has the expected outcome at every window size`() {
        val cases = JsonTestSuite.cases
        assertEquals(318, cases.size)
        val failures = ArrayList<String>()
        for (window in intArrayOf(1, 3, 7, 64, Utf8JsonTokenizer.DEFAULT_WINDOW_BYTES)) {
            for (case in cases) {
                val actual = tokenize(case.bytes, window = window)
                val ok = when (val expected = case.expected) {
                    JsonTestSuite.Expected.Accept -> actual is Tokenized.Accepted
                    is JsonTestSuite.Expected.Reject -> when (expected.reason) {
                        RejectReason.DUPLICATE_KEY -> actual is Tokenized.Accepted
                        else -> actual is Tokenized.Rejected && (expected.reason == null || actual.reason == expected.reason)
                    }
                }
                if (!ok) failures += "${case.name} (window $window): expected ${case.expected}, got $actual"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `accepted files produce the oracle's tokens and rejected files the oracle's reason`() {
        val failures = ArrayList<String>()
        for (case in JsonTestSuite.cases) {
            val actual = tokenize(case.bytes, window = 5)
            when (val oracle = OracleJson.parse(case.bytes)) {
                is ParseResult.Accepted -> if (actual != Tokenized.Accepted(expectedTokens(oracle.value))) {
                    failures += "${case.name}: tokens differ: $actual"
                }
                is ParseResult.Rejected -> when {
                    oracle.reason == RejectReason.DUPLICATE_KEY -> {}
                    actual !is Tokenized.Rejected -> failures += "${case.name}: oracle rejects (${oracle.reason}), tokenizer accepts"
                    actual.reason != oracle.reason -> failures += "${case.name}: oracle ${oracle.reason}, tokenizer ${actual.reason}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
