# cli-kit

`cli-kit` (`app.oreshkov.clikit:cli-kit`, package `app.oreshkov.clikit`) is a separate library that shares this
repository: the machinery of a command-line program. It depends on the Kotlin standard library and nothing else, not
even Delva, and knows no command of any program's: a program declares its own tree and its handlers.

- A command-tree parser whose options and arguments are typed handles (`values[limit]` is an `Int?`), declared once
  for parsing, help and completion, with environment-variable fallbacks.
- Rules between options — not both (`Constraint.Exclusive`), exactly one of (`Constraint.ExactlyOne`), needs
  (`Constraint.Requires`) — and a single reader of standard input, checked while parsing.
- Converters for durations, times (`-30d`, ISO-8601, epoch milliseconds) and `@file` values.
- Help pages wrapped to the terminal, at most 100 columns, from `help` texts that can hold paragraphs; and bash,
  zsh, fish and PowerShell completion scripts.
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

It is a Kotlin Multiplatform module with one target, the JVM (22 or newer). Its KDoc is on
[javadoc.io](https://javadoc.io/doc/app.oreshkov.clikit/cli-kit). Both modules were published under the group
`app.oreshkov.delva` up to 0.1.0 ([changelog](../CHANGELOG.md)).

## Native access

The kit calls native code only on Windows, through the FFM API: at a console, to turn on escape sequences, and after
a write to stdout failed, to tell a pipe whose reader left from a real failure. A program whose stdio is piped or
redirected makes no native call while its writes succeed. Grant the access in the start script:

```kotlin
application {
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
```

On the module path, name the module instead: `--enable-native-access=app.oreshkov.clikit`. A JAR run with `java -jar`
can carry `Enable-Native-Access: ALL-UNNAMED` in its manifest.

Without the grant, the JDK prints a warning on stderr the first time, and nothing else changes. If the JDK denies the
call, as [JEP 472](https://openjdk.org/jeps/472) plans to make the default, the kit carries on without it. Output at a
Windows console is then monochrome, and a reader that leaves a pipe is reported as a failed write (exit status 74).

## A command that runs until it's stopped

A server, or anything else that runs until Ctrl-C or SIGTERM, keeps `runAndExit`. Its handler registers what stopping
takes with `onInterrupt`, and with an action registered nothing prints `interrupted`. The action runs on a shutdown
hook while the handler is still running, and the process ends once the hook returns, so the action bounds its own
wait. With kotlinx-coroutines:

```kotlin
val serve = CommandHandler { context ->
    runBlocking {
        val server = launch { serveUntilCancelled() }
        context.onInterrupt {
            server.cancel()
            runBlocking { withTimeoutOrNull(5.seconds) { server.join() } }
        }.use { server.join() }   // a server that stops by itself unregisters the action
    }
    ExitStatus.OK
}
```

The program's stdout can carry a protocol, as an MCP server's does on stdio. The kit writes to it only for help, `--version` and
`completion`; usage errors, failures and notices go to stderr. A terminal that wrote nothing to stdout isn't blamed for
another writer's failed write, so a client hanging up doesn't turn exit status 0 into 74.

## cli-kit-testing

The companion `cli-kit-testing` (`app.oreshkov.clikit:cli-kit-testing`, a test dependency) runs a program in-process
against a recording terminal — with typed answers to prompts, standard input and interrupts — and pins every help
page and completion script of a command tree with golden files in one call (its KDoc is on
[javadoc.io](https://javadoc.io/doc/app.oreshkov.clikit/cli-kit-testing)):

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
