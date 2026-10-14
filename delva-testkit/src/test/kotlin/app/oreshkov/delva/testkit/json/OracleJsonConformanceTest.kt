package app.oreshkov.delva.testkit.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The oracle parser against JSONTestSuite: every `y_` accepted, every `n_` rejected, `i_` as decided. */
class OracleJsonConformanceTest {
    @Test
    fun `every JSONTestSuite parsing file has the expected outcome`() {
        val cases = JsonTestSuite.cases
        assertEquals(318, cases.size)
        val failures = ArrayList<String>()
        for (case in cases) {
            val expected = case.expected
            val actual = OracleJson.parse(case.bytes)
            val ok = when (expected) {
                JsonTestSuite.Expected.Accept -> actual is ParseResult.Accepted
                is JsonTestSuite.Expected.Reject -> actual is ParseResult.Rejected && (expected.reason == null || actual.reason == expected.reason)
            }
            if (!ok) failures += "${case.name}: expected $expected, got $actual"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
