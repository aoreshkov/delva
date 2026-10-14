# cli-kit

`cli-kit` (`app.oreshkov.delva:cli-kit`, package `app.oreshkov.clikit`) is a separate library that shares this
repository: the machinery of a command-line program. It depends on the Kotlin standard library and nothing else, not
even Delva, and knows no command of any program's: a program declares its own tree and its handlers.

- A command-tree parser whose options and arguments are typed handles (`values[limit]` is an `Int?`), declared once
  for parsing, help and completion, with environment-variable fallbacks.
- Rules between options — not both (`Constraint.Exclusive`), exactly one of (`Constraint.ExactlyOne`), needs
  (`Constraint.Requires`) — and a single reader of standard input, checked while parsing.
- Converters for durations, times (`-30d`, ISO-8601, epoch milliseconds) and `@file` values.
- Help pages, and bash, zsh, fish and PowerShell completion scripts.
- Tables, compact or indented JSON for `--json` output, and a byte-stream payload.
- Terminal I/O that keeps the payload on stdout and everything decorative on stderr, with colour decided once and ANSI
  enabled on a legacy Windows console; prompts, confirmations, secrets, progress, log lines and `--quiet`. Control
  characters in table cells, notices and errors are shown as `\u001B`, never sent to the terminal.
- A `Program` runner that answers `--help`, `--version` and usage errors (exit status 2), hands every other command
  to the handler the program registered for it, turns an expected failure (`CommandFailure`) into its exit status
  (`ExitStatus` names the `sysexits.h` codes), and lets a command that runs until stopped stop cleanly on Ctrl-C or
  SIGTERM.

```kotlin
val limit = Option.Single("--limit", Converters.positiveInt, "-n", help = "Most rows to list", valueName = "n")
val list = CommandSpec("list", "List things", options = listOf(limit))
val root = CommandSpec("tool", subcommands = listOf(list, StandardOptions.completion))

val program = Program(
    root,
    "tool 1.0",
    mapOf(list to CommandHandler { context -> context.output.text("${context.values[limit] ?: 10} rows"); ExitStatus.OK }),
)

fun main(args: Array<String>) {
    program.runAndExit(args)   // parses, runs the handler, exits with its status
}
```

It is a Kotlin Multiplatform module with one target, the JVM (22 or newer); the Windows console call goes through the
FFM API, so a program using it runs with `--enable-native-access=ALL-UNNAMED`.

## cli-kit-testing

The companion `cli-kit-testing` (`app.oreshkov.delva:cli-kit-testing`, a test dependency) runs a program in-process
against a recording terminal — with typed answers to prompts, standard input and interrupts — and pins every help
page and completion script of a command tree with golden files in one call:

```kotlin
@Test
fun `every help page and completion script`() {
    GoldenFiles(Path.of("src", "test", "resources", "golden"), "golden.update").assertSurface(root)
}

@Test
fun `list prints the rows`() {
    val result = program.record("list", "--limit", "3").assertPipeSafe()
    assertEquals("3 rows\n", result.stdout)
}
```
