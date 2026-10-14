package app.oreshkov.clikit.testing

import app.oreshkov.clikit.Argument
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.CommandSpec
import app.oreshkov.clikit.Converters
import app.oreshkov.clikit.InputSource
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.inputSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgramRunsTest {

    private val source = Argument.Single("file", Converters.inputSource)

    private val cat = CommandSpec("cat", arguments = listOf(source))

    private val program = Program(
        CommandSpec("demo", subcommands = listOf(cat)),
        "demo 1.0",
        mapOf(
            cat to CommandHandler { context ->
                val text = context.values.required(source).open(context.io).use { it.readBytes().decodeToString() }
                context.io.out(text)
                0
            },
        ),
    )

    @Test
    fun `a program run reads the given bytes for -`() {
        assertEquals("héllo\n", program.record("cat", "-", stdin = "héllo\n".encodeToByteArray()).stdout)
    }

    @Test
    fun `a recorder with standard input reads it for -, and still records what is written`() {
        val recorder = RecordingTerminalIo()
        val io = recorder.withStdin(byteArrayOf(1, 2, 3))
        assertEquals(listOf<Byte>(1, 2, 3), InputSource.Stdin.open(io).use { it.readBytes().toList() })
        io.outLine("done")
        assertEquals("done\n", recorder.stdout)
    }
}
