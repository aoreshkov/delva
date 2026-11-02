package app.oreshkov.delva.internal.path

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * What translating an I-Regexp costs: the work a `match` or `search` pays for each pattern a document supplies, once
 * per distinct pattern that the evaluator's cache doesn't hold. [kind] `invalid` takes patterns that aren't I-Regexp,
 * which the function treats as false, so their rejection has to cost no more than a valid pattern's translation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class IRegexpBenchmark {
    @Param("valid", "invalid")
    var kind: String = "valid"

    private lateinit var patterns: Array<String>

    @Setup
    fun setUp() {
        patterns = if (kind == "valid") VALID else INVALID
    }

    /** Translates every pattern of [kind] once. */
    @Benchmark
    fun compile(blackhole: Blackhole) {
        for (pattern in patterns) blackhole.consume(IRegexp.compile(pattern))
    }

    private companion object {
        val VALID = arrayOf(
            "dracula", "[Dd][Rr][Aa][Cc][Uu][Ll][Aa]", "^S[0-9]+$", "(ab|cd)*e", "[A-Z][a-z]+ \\p{Lu}{2,3}",
            "a.c", "[^\\n]{2,3}", "(evo|evolution)_token_[0-9]{1,4}",
        )

        // Rejected by the translator, and (the last two) ranges that only the JDK used to refuse.
        val INVALID = arrayOf(
            "(?i)dracula", "\\d+", "x(?=y)", "a**", "[a-z", "(ab|cd", "a{5,2}", "[z-a]",
        )
    }
}
