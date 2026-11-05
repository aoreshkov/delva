# cli-kit

`cli-kit` (`app.oreshkov.clikit:cli-kit`, package `app.oreshkov.clikit`) is a separate library that shares this
repository: the machinery of a command-line program. It depends on the Kotlin standard library and nothing else, not
even Delva, and knows no command of any program's: a program declares its own tree and its handlers.

- A command-tree parser whose options and arguments are typed handles (`values[limit]` is an `Int?`), declared once
  for parsing, help and completion, with environment-variable fallbacks, values from
  [configuration files](#configuration-files) where XDG and Windows put them, command aliases,
  [former names and deprecations](#renaming-and-deprecating) that keep old command lines working,
  [options that can also be given positionally](#an-option-given-positionally), and values that converters can call
  "not given", such as an unfilled template.
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
  characters in table cells, notices and errors are shown as `\u001B`, never sent to the terminal, and a line break
  in a notice, warning, error or log line starts a continuation marked `│`, so a request dump stays readable.
- A `Program` runner that answers `--help`, [`help <command>`](#a-help-command), `--version`, usage errors (exit
  status 2) and configuration errors (78), hands every other command
  to the handler the program registered for it, turns an expected failure (`CommandFailure`) into its exit status
  (`ExitStatus` names the `sysexits.h` codes), lets a command that runs until stopped stop cleanly on Ctrl-C or
  SIGTERM, and can read [argument files](#argument-files), `@args.txt`, past a Windows launcher.

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

A single-value option given more than once takes its last value, as POSIX reads options in order and as an alias
that puts a default first needs; when the values differ, `warning: --limit was given 2 times; the last one is used`
says so, without repeating them, and `--quiet` silences it.

It is a Kotlin Multiplatform module with one target, the JVM (22 or newer). Its KDoc is on
[javadoc.io](https://javadoc.io/doc/app.oreshkov.clikit/cli-kit).

## Installation

cli-kit is published to Maven Central. A build depends on the root coordinates, and Gradle picks the JVM variant:

```kotlin
dependencies {
    implementation("app.oreshkov.clikit:cli-kit:0.4.0")
    testImplementation("app.oreshkov.clikit:cli-kit-testing:0.4.0")
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

## An option given positionally

A setting can have two spellings, a flag and a position: `serve --forms-dir /srv/forms` or `serve /srv/forms`.
`Argument.Single.spelling` ([experimental](#experimental-api)) declares the positional as another spelling of the
option, not as a second operand. The setting has one value, which the option owns, and either handle reads it:

```kotlin
val formsDir = Option.Single(
    "--forms-dir", Converters.path,
    help = "Directory of the forms", valueName = "path", required = true, env = "FORMS_DIR",
)
@OptIn(ExperimentalCliKitApi::class)
val formsDirArgument = Argument.Single.spelling(formsDir)   // [forms-dir], "Same as --forms-dir"
val serve = CommandSpec("serve", options = listOf(formsDir), arguments = listOf(formsDirArgument))
```

`serve /srv/forms`, `serve --forms-dir /srv/forms` and `FORMS_DIR=/srv/forms serve` each give `values[formsDir]` the
value `/srv/forms`. Either spelling is the command line, so it wins over the option's variable and its configuration
key, and a variable that a launcher sets for every run gives way to a directory typed on one. Both spellings on one
command line is a usage error (`--forms-dir is given twice: as an option and as <forms-dir>`), and so is neither,
when the option is required: `missing required option --forms-dir (or <forms-dir>, or FORMS_DIR)`. A constraint
names the option, never its spelling, and sees a value given either way. Help shows `[forms-dir]` in the usage line,
and the positional slot completes as the option's value does.

Declared as two operands under `Constraint.ExactlyOne(formsDir, formsDirArgument)`, the variable would count as one of
them, and `FORMS_DIR=/a serve /b` would be refused for giving both.

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

val formsDir = Option.Single("--forms-dir", Converters.path.configured(), valueName = "path", required = true, env = "FORMS_DIR")
@OptIn(ExperimentalCliKitApi::class)
val serve = CommandSpec("serve", options = listOf(formsDir), arguments = listOf(Argument.Single.spelling(formsDir)))
```

`serve --forms-dir '${user_config.forms_dir}' /srv/forms` then runs with `/srv/forms`, after printing
`warning: --forms-dir: ignoring '${user_config.forms_dir}': an unfilled template` on stderr (`--quiet` silences it).
An unset value on the command line overrides an earlier one and lets the next source apply: the
[positional spelling](#an-option-given-positionally), then the option's variable. A repeated option or a variadic
argument drops just that value, and an argument keeps its place, so the ones after it don't shift. When a required
option is left unset, the error says what was given: `missing required option --forms-dir (or <forms-dir>, or
FORMS_DIR; given '${user_config.forms_dir}', which counts as not set)`.

## A help command

`StandardOptions.help` is `help [command]...`, which prints the page `--help` prints, as git-like tools do. A tree has
it only if the program lists it, so a program can still have a `help` command of its own:

```kotlin
val root = CommandSpec("tool", subcommands = listOf(store, StandardOptions.completion, StandardOptions.help))
```

`tool help store get` then prints the page of `tool store get --help`, and `tool help` the root's. The words name a
command below the group that lists `help`, so a group can list it too: `tool store help get` prints the same page. A
word that names no command is a usage error, with the nearest name suggested. `Program` answers `help` without a
handler, before reading any environment variable or checking a required option, so it works wherever `--help` does,
and the completion scripts complete its words.

## Renaming and deprecating

A command can have aliases, other names it keeps for good, such as `ls` for `list`. An option or command that is
renamed keeps its old names as former names, so the scripts that type them go on working, and one that is going away
is deprecated, with what to do instead:

```kotlin
val store = Option.Single("--store", Converters.path, valueName = "dir", formerNames = listOf("--data-dir"))
val pretty = Option.Flag("--pretty", help = "Indent the output", deprecated = "pipe --json to a formatter instead")
val list = CommandSpec("list", "List entries", options = listOf(pretty), aliases = listOf("ls"))
val dump = CommandSpec("dump", "Print every entry", deprecated = "use 'tool list' instead")
val root = CommandSpec("tool", options = listOf(store), subcommands = listOf(list, dump))
```

`tool --data-dir /srv ls --pretty` runs `list`, after these lines on stderr:

```text
warning: --data-dir is deprecated; use --store
warning: --pretty is deprecated: pipe --json to a formatter instead
```

Each spelling is warned about once, however often it's typed, and `--quiet` silences the warnings; an option's
variable set for a deprecated option is warned about too. Help lists `list, ls` among the commands, and tags `--pretty`
and `dump` `(deprecated)`, with the message on the line below; `dump`'s own page starts with it. Former names appear on
no help page, and completion and "did you mean" suggestions offer neither former names nor anything deprecated, but the
parser and the completion scripts read them all. `help` reads them too, and warns about none.

A command that declares two subcommands, or two options, that can be typed the same way is refused when it's built,
and so, by `Program`, is a root with any of these: its name is the program's own.

## Configuration files

An option that takes one value can declare a key, and a program that gives `Program` its settings then reads the
option from configuration files ([experimental](#experimental-api)):

```kotlin
val store = Option.Single("--store", Converters.path, valueName = "dir", env = "TOOL_STORE", config = "store")
val list = CommandSpec("list", "List entries", options = listOf(store))
val root = CommandSpec("tool", subcommands = listOf(list))

@OptIn(ExperimentalCliKitApi::class)
val program = Program(
    root,
    "tool 1.0",
    mapOf(list to CommandHandler { context -> context.output.text("${context.values[store]}"); ExitStatus.OK }),
    environment = System::getenv,
    settings = ConfigurationFiles("tool", variable = "TOOL_CONFIG"),
)
```

With `store = /srv/tool` in `~/.config/tool/config`, `tool list` uses `/srv/tool`. The first source that gives a
usable value wins: the command line, then the option's variable, then the files, highest first, then the handler's
default. A value the converter calls "not given" moves on to the next one, and so does an empty one. Flags and
repeated options can't be configured: there's no `--no-x` to undo a flag a file turned on, and no answer users expect
to whether a file's values replace the command line's or add to them.

`ConfigurationFiles` reads these files, highest first. A variable that is unset or empty takes its default, and a
relative path in an XDG variable is ignored, as the XDG Base Directory specification says:

| Layer | Linux, macOS | Windows |
|---|---|---|
| project, if `project = ".tool"` | the nearest `.tool/config` from the working directory up | the same |
| user | the file `$TOOL_CONFIG` names, if `variable = "TOOL_CONFIG"` and it's set; else `$XDG_CONFIG_HOME/tool/config`; else `~/.config/tool/config` | the same, but `%APPDATA%\tool\config` in place of `~/.config/tool/config` |
| system, unless `system = false` | `tool/config` in each directory of `$XDG_CONFIG_DIRS`, by default `/etc/xdg` | `%ProgramData%\tool\config` |

The format is the kit's own, with no dependency:

- UTF-8, at most 1 MiB, with a leading byte-order mark ignored; lines end in LF or CRLF.
- `key = value`, with the whitespace around the key and the value trimmed. The value is taken as written, with no
  quotes or escapes, so `store = C:\work\store` means what it says, where `java.util.Properties` would read
  `C:workstore`.
- A key is lower case, digits, `-` and `.`, and a file may give it once.
- Blank lines are ignored, and so are lines whose first non-blank character is `#` or `;`. A line that starts with `[`
  is an error, kept for sections in a later version.

A file that doesn't exist is skipped, except the one the variable names. A file that can't be read or is malformed is
an error that names the file and line, and so is a value the converter refuses: `error: invalid value for --store
(from /home/ana/.config/tool/config:3): …`, exit status 78 (`ExitStatus.CONFIG`), with no usage line. The files are
read only when a value is wanted from them, so `--help`, `help`, `--version`, `completion` and a command with no
configurable option work whatever they say. A key that no option of the tree declares is warned about, with the
nearest key, and `--quiet` silences the warning. Help tags the option `[config: store]` and lists the command's keys,
and where they're read, in a Configuration section; `GoldenFiles.assertSurface(root, settings = …)` pins those pages.

Anyone who can write a file can set every key, so give no key to an option that takes a secret, a command to run or
code to load, and `readSecret` never reads one. The project file is off unless the program asks for it, since a
repository someone else wrote can carry one. A value is read as if it were typed, so a relative path is relative to
the working directory, not to the file: write absolute paths in a user or system file.

To read another format, or from somewhere else, implement `Settings`.

## Argument files

A Windows `.bat` start script, such as the one Gradle's `application` plugin writes, hands its arguments to `cmd`, which
reads `|`, `&` and `^` in them as its own however they were quoted: a filter such as `@.kind == 'a|b'` never reaches
the program. A program can turn on argument files ([experimental](#experimental-api)), so that `@path` stands for the
arguments in a file, one per line, which no shell or script reads on the way:

```kotlin
val where = Option.Single("--where", Converters.string, valueName = "filter")
val path = Argument.Single("path", Converters.string, "Where to look")
val query = CommandSpec("query", "Find entries", options = listOf(where), arguments = listOf(path))
val root = CommandSpec("tool", subcommands = listOf(query))

@OptIn(ExperimentalCliKitApi::class)
val program = Program(
    root,
    "tool 1.0",
    mapOf(query to CommandHandler { context -> context.output.text("${context.values[where]}"); ExitStatus.OK }),
).withArgumentFiles()
```

With this `args.txt`, `tool @args.txt` runs `tool query --where "@.kind == 'a|b'" $.orders`, and
`tool query @args.txt` would too if the file left out `query`:

```text
query
--where
@.kind == 'a|b'
$.orders
```

- **The file:** UTF-8, at most 1 MiB, with LF or CRLF line ends and a leading byte-order mark ignored. Each line is
  one argument, taken as written: no quotes, escapes or comments. The final line break adds no argument, and any other
  empty line is an empty argument. A relative path is relative to the working directory.
- **Where `@` counts:** only where the parser reads a new option or operand. A value an option takes is never a file,
  so `--where @.kind`, `--where=@.kind` and `--body @request.json`, which `Converters.inlineOrFile` reads, mean what
  they did; neither is anything after `--`. An operand that starts with `@` is written `@@`: `@@request.json` passes
  `@request.json` on. `@` alone is left as it is.
- **As if typed:** the lines are read where `@path` was, commands, options, values and `--` included, and `--no-color`
  and `--width` work from a file too. A line that starts with `@` is literal, since files don't nest.
- **Errors:** a file that can't be read is `error: can't read argument file 'args.txt': no such file`, exit status 66
  (`ExitStatus.NO_INPUT`), with no usage line.
- **Help and completion:** every help page ends with `@file reads arguments from a file, one per line; @@ for a
  literal @.`, and the completion scripts step over a word that starts with `@`.
- **PowerShell** reads an unquoted `@name` as splatting a variable, so write `tool '@args.txt'` there.

The format is not `java`'s, whose argument files split lines on spaces and read quotes and `#` comments: those are the
rules of another quoting layer, and a filter full of quotes would need escaping for it as it does for `cmd`. Nothing is
read without an `@` the user typed, and a secret is still never taken from a value, from a file or not.

## Experimental API

`@ExperimentalCliKitApi` marks what is new and may still change, in signature or behaviour, in a minor release. Using
it needs `@OptIn(ExperimentalCliKitApi::class)`, and the [changelog](CHANGELOG.md) says when a mark is lifted. It marks
the types of [configuration files](#configuration-files), `Settings`, `Setting`, `SettingsException` and
`ConfigurationFiles`, and the constructors of `Program`, `Parser` and `HelpRenderer` that take settings, while an
option's `config` key is stable. It marks `Program.withArgumentFiles` for [argument files](#argument-files),
`Argument.Single.spelling` and `spellingOf` for [an option given positionally](#an-option-given-positionally), and
`Constraint.RequiresValue`, a rule that one operand needs an option to
have one of some values:

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
