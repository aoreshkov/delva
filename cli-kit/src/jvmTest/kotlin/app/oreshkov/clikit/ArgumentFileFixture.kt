package app.oreshkov.clikit

/** A program for [ArgumentFilesProcessTest] to run in a child JVM: `show` prints the terminal's width and its words. */
private object ArgumentFileFixture {
    val words = Argument.Variadic("word", Converters.string, "Words to show", required = false)

    val show = CommandSpec("show", "Show the width and the words", arguments = listOf(words))

    val program = Program(
        CommandSpec("fixture", options = listOf(StandardOptions.width), subcommands = listOf(show)),
        "fixture 1",
        mapOf(
            show to CommandHandler { context ->
                context.io.outLine("width=${context.io.width} " + context.values[words].joinToString(" ") { "[$it]" })
                ExitStatus.OK
            },
        ),
    ).withArgumentFiles()
}

/** The fixture's entry point: `java -cp <test classpath> app.oreshkov.clikit.ArgumentFileFixtureKt <args>`. */
fun main(args: Array<String>) {
    ArgumentFileFixture.program.runAndExit(args)
}
