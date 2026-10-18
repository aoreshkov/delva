# cli-kit

`cli-kit` (`app.oreshkov.clikit:cli-kit`, package `app.oreshkov.clikit`) is a separate library that shares this
repository: the machinery of a command-line program. It depends on the Kotlin standard library and nothing else, not
even Delva, and knows no command of any program's: a program declares its own tree and its handlers.

- A command-tree parser whose options and arguments are typed handles (`values[limit]` is an `Int?`), declared once
  for parsing, help and completion, with environment-variable fallbacks, and values that converters can call "not
  given", such as an unfilled template.
- Rules between options — not both (`Constraint.Exclusive`), exactly one of (`Constraint.ExactlyOne`), needs
  (`Constraint.Requires`), needs a value (`Constraint.RequiresValue`, [experimental](#experimental-api)) — and a single
  reader of standard input, checked while parsing.
- Converters for durations, times (`-30d`, ISO-8601, epoch milliseconds) and `@file` values.
- Help pages wrapped to the terminal, at most 100 columns, from `help` texts that can hold paragraphs, with an
  Environment section listing the options' variables, those the kit reads for `--no-color` and `--width`, and those a
  command reads for itself (`EnvironmentVariable`);
  and bash, zsh, fish and PowerShell completion scripts.
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
[javadoc.io](https://javadoc.io/doc/app.oreshkov.clikit/cli-kit).

## Installation

cli-kit is published to Maven Central. A build depends on the root coordinates, and Gradle picks the JVM variant:

```kotlin
dependencies {
    implementation("app.oreshkov.clikit:cli-kit:0.2.0")
    testImplementation("app.oreshkov.clikit:cli-kit-testing:0.2.0")
}
```

Their JPMS module names are `app.oreshkov.clikit` and `app.oreshkov.clikit.testing`. Both modules were published
under the group `app.oreshkov.delva` up to 0.1.0 ([changelog](../CHANGELOG.md#010---2026-10-15)).

## Versioning

cli-kit is released on its own, with [its own changelog](CHANGELOG.md), and its versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) independently of Delva's: a cli-kit version says nothing
about a Delva version. cli-kit-testing always has the version of the cli-kit it tests. Until 1.0, a minor version can
change the API incompatibly, and the changelog says what to change when updating; what is marked
[experimental](#experimental-api) may change in any minor version.

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

## Values that mean "not given"

A launcher may pass a value through even when nobody filled it in: an empty string, or a template such as
`${user_config.forms_dir}`. `unsetWhen` makes a converter call such a value unset, and the option, argument or variable
then counts as not given: for `required`, for constraints, for the environment fallback, and in `values`. Nothing is
unset unless a converter says so, since `--prefix ""` can mean something.

```kotlin
val unfilled = Regex("""\$\{[^}]*}""")
fun <T> ValueConverter<T>.configured() = unsetWhen(
    { it.isBlank() || unfilled.matches(it.trim()) },
    notice = { if (it.isBlank()) null else "ignoring '$it': an unfilled template" },
)

val formsDir = Option.Single("--forms-dir", Converters.path.configured(), valueName = "path", env = "FORMS_DIR")
val formsDirArgument = Argument.Single("forms-dir", Converters.path.configured(), required = false)
val serve = CommandSpec(
    "serve",
    options = listOf(formsDir),
    arguments = listOf(formsDirArgument),
    constraints = listOf(Constraint.ExactlyOne(formsDir, formsDirArgument)),
)
```

`serve --forms-dir '${user_config.forms_dir}' /srv/forms` then runs with the argument, after printing
`warning: --forms-dir: ignoring '${user_config.forms_dir}': an unfilled template` on stderr (`--quiet` silences it).
An unset value on the command line overrides an earlier one and lets the option's variable apply. A repeated option or
a variadic argument drops just that value, and an argument keeps its place, so the ones after it don't shift. When a
required option is left unset, the error says what was given: `missing required option --forms-dir (given
'${user_config.forms_dir}', which counts as not set)`.

## Experimental API

`@ExperimentalCliKitApi` marks what is new and may still change, in signature or behaviour, in a minor release. Using
it needs `@OptIn(ExperimentalCliKitApi::class)`, and the [changelog](CHANGELOG.md) says when a mark is lifted. It marks
`Constraint.RequiresValue`, a rule that one operand needs an option to have one of some values:

```kotlin
val transport = Option.Single("--transport", Converters.choice("stdio", "http"), env = "TRANSPORT")
val port = Option.Single("--port", Converters.positiveInt)

@OptIn(ExperimentalCliKitApi::class)
val serve = CommandSpec(
    "serve",
    options = listOf(transport, port),
    constraints = listOf(Constraint.RequiresValue(port, transport, "http")),
)
```

Help notes `--port requires --transport http`, and `serve --port 8080 --transport stdio` is a usage error that says
so: `--port requires --transport http, but --transport is 'stdio'`. The values are written as they would be typed and
converted by the option's converter when the constraint is built, so a misspelt one fails then, not on a user's
command line. An absent option never matches, because the parser can't see the handler's default.

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
