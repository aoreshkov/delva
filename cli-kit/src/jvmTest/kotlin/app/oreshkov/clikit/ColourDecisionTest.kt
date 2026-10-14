package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The precedence order of [decideColour], asserted rather than assumed — because users expect
 * exactly this order and the only way to be sure of it is to write it down twice.
 *
 * The environment is a function parameter, not `System.getenv`, so these run without mutating the
 * process and without an ordering dependency between tests.
 */
class ColourDecisionTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? =
        { name -> pairs.toMap()[name] }

    /**
     * The platform opt-in, forced to succeed.
     *
     * These tests are about the *order* of the rules, not about Windows. Left to the real
     * downcall, half of them would assert on whether the Gradle test JVM happens to have a console
     * attached — which it does not, so they would pass for entirely the wrong reason. Every test
     * that expects `false` from a rule passes this, so removing the rule makes it fail anywhere.
     */
    private val platformSaysYes: () -> Boolean = { true }

    @Test
    fun `--no-color beats everything, including the force variables`() {
        assertFalse(
            decideColour(
                noColorFlag = true,
                interactive = true,
                environment = env("FORCE_COLOR" to "1", "CLICOLOR_FORCE" to "1"),
                enableVirtualTerminal = platformSaysYes,
            ),
        )
    }

    @Test
    fun `NO_COLOR turns colour off`() {
        assertFalse(
            decideColour(
                noColorFlag = false,
                interactive = true,
                environment = env("NO_COLOR" to "1"),
                enableVirtualTerminal = platformSaysYes,
            ),
        )
    }

    @Test
    fun `an empty NO_COLOR is not a request for monochrome`() {
        // no-color.org: the variable must be present *and* non-empty. An exported-but-empty
        // variable is a shell artefact, not an instruction.
        assertTrue(
            decideColour(
                noColorFlag = false,
                interactive = true,
                environment = env("NO_COLOR" to ""),
                enableVirtualTerminal = platformSaysYes,
            ),
        )
    }

    @Test
    fun `FORCE_COLOR beats a non-interactive stream, which is what CI wants`() {
        assertTrue(
            decideColour(noColorFlag = false, interactive = false, environment = env("FORCE_COLOR" to "1")),
        )
        assertTrue(
            decideColour(noColorFlag = false, interactive = false, environment = env("CLICOLOR_FORCE" to "1")),
        )
    }

    @Test
    fun `FORCE_COLOR does not beat NO_COLOR`() {
        assertFalse(
            decideColour(
                noColorFlag = false,
                interactive = true,
                environment = env("NO_COLOR" to "1", "FORCE_COLOR" to "1"),
                enableVirtualTerminal = platformSaysYes,
            ),
        )
    }

    @Test
    fun `a dumb terminal gets no colour`() {
        assertFalse(
            decideColour(
                noColorFlag = false,
                interactive = true,
                environment = env("TERM" to "dumb"),
                enableVirtualTerminal = platformSaysYes,
            ),
        )
    }

    @Test
    fun `a pipe gets no colour`() {
        // The failure this prevents is escape sequences in a redirected file, which is the single
        // most common way a CLI ruins someone's afternoon.
        assertFalse(
            decideColour(
                noColorFlag = false,
                interactive = false,
                environment = env(),
                enableVirtualTerminal = platformSaysYes,
            ),
        )
    }

    @Test
    fun `an interactive terminal with a clean environment reaches the platform opt-in`() {
        assertTrue(
            decideColour(
                noColorFlag = false,
                interactive = true,
                environment = env("TERM" to "xterm-256color"),
                enableVirtualTerminal = platformSaysYes,
            ),
        )
    }

    @Test
    fun `a platform that refuses the opt-in gets monochrome, not an exception`() {
        // Legacy conhost that will not take ENABLE_VIRTUAL_TERMINAL_PROCESSING. The degradation is
        // plain text, and it is the same path `--no-color` already tests.
        assertFalse(
            decideColour(
                noColorFlag = false,
                interactive = true,
                environment = env("TERM" to "xterm-256color"),
                enableVirtualTerminal = { false },
            ),
        )
    }

    @Test
    fun `the real downcall never throws, whatever this machine is`() {
        // Not an assertion about the answer — that depends on the host — but about the contract:
        // every failure path inside the FFM call ends in `false`.
        VirtualTerminal.enable()
    }
}
