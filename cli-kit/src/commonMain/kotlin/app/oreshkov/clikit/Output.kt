package app.oreshkov.clikit

/**
 * The stream contract, in one object so no command has to remember it.
 *
 * **The payload goes to stdout and everything else goes to stderr.** The payload is whatever the
 * command was asked to produce — the JSON under `--json`, the rendered table without it — and
 * everything else is progress, notices and errors. `demo request get --json | jq` has to work with
 * no filtering, and a spinner that shared the payload's stream would be the thing that broke it.
 *
 * Tables are on stdout because a table *is* the answer to `demo session list`, and a command whose
 * only output vanishes from a redirect is a command nobody can script — the pipe-safety the rule
 * exists for is supplied instead by [table] rendering borderless and uncoloured the moment stdout is
 * not a terminal, which is the shape [TableRenderer.renderPlain] was written for.
 *
 * A machine-readable payload is written with [JsonWriter] — a writer, not a serializer, so this
 * module still depends on nothing — and handed over as text to [text].
 *
 * A notice can be the payload's context, such as the cursor of the next page, so it has to stay next
 * to that payload when a script merges the streams (`2>&1`). The JVM's `SystemTerminalIo` delivers buffered
 * stdout before every stderr write, unlike C stdio, so merged lines read in the order they were
 * written, for writes from one thread. A script that parses the output still asks for [machineReadable]
 * output, which carries its context inside the payload.
 *
 * Everything but [text] — tables, properties, notices, warnings, errors, log lines — goes through
 * [TerminalText.escape], so data shown in them can't drive the terminal; [text] is the payload, and
 * is written as it is.
 *
 * A line break in the message of a notice, a warning, an error or a log line starts a continuation line, indented to
 * where the message starts and marked with the border style's vertical line, `│` or `|`:
 *
 * ```
 * 2026-11-01T19:02:13.897Z DEBUG REQUEST: https://example.com/game/startup
 *                                │ METHOD: POST
 * ```
 *
 * Every line of an entry after its first starts with spaces and the marker, and the first one with the stamp, the
 * level, `warning:`, `error:` or the notice's own text, so text from outside the program can't pass for an entry of
 * its own (a notice's continuation starts with the marker at column 0). A stack trace that [log] adds under [verbose]
 * follows as its own lines, each starting with a class name, `Caused by:`, `Suppressed:` or spaces, with every
 * message in it kept on one line. Each entry, continuations and stack trace included, is one write to stderr, so
 * entries written from several threads don't interleave.
 */
public class Output(
    private val io: TerminalIo,
    /** True when the command should build a machine-readable payload rather than a human one. */
    public val machineReadable: Boolean,
    /** `--quiet`: no notices, warnings or progress on stderr. Errors are not the program's to silence, and still print. */
    public val quiet: Boolean = false,
    /** `--verbose`: [log] writes debug lines, and the stack trace of an error it is given. */
    public val verbose: Boolean = false,
) {
    private val tables = TableRenderer(io.styler, io.borderStyle)

    /** Free-form payload text — an encoded document, a stored node verbatim. */
    public fun text(value: String) {
        io.outLine(value.trimEnd())
    }

    /**
     * A table, or nothing at all when there are no rows.
     *
     * The empty case goes to stderr as a notice rather than stdout as an empty table: a caller
     * counting lines of stdout should get zero when there was nothing, and a header row over no
     * data is a lie about how much of it there was.
     */
    public fun table(columns: List<Column>, rows: List<List<String>>, empty: String) {
        if (rows.isEmpty()) {
            notice(empty)
            return
        }
        val rendered = if (io.interactive) {
            tables.render(columns, rows, io.width)
        } else {
            tables.renderPlain(columns, rows, io.width)
        }
        io.outLine(rendered)
    }

    /**
     * One object's fields, for the one-object commands such as `session show` or `store stats`: a name, aligned, then
     * its value. Not a table, and never fitted to the width: a value cut short — a path, an id — reads as a different
     * value, so a long one runs on and the terminal wraps it. Names are bold at a terminal; a pipe gets plain text.
     */
    public fun properties(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) {
            notice("nothing to show")
            return
        }
        val escaped = pairs.map { (name, value) -> TerminalText.escape(name) to TerminalText.escape(value) }
        val widest = escaped.maxOf { Styler.displayWidth(it.first) }
        val styler = if (io.interactive) io.styler else Styler.Plain
        val text = escaped.joinToString("\n") { (name, value) ->
            styler.bold(name) + " ".repeat(widest - Styler.displayWidth(name) + 2) + value
        }
        io.outLine(text)
    }

    /** Decoration: what happened, how long it took, what was skipped. Never the payload. */
    public fun notice(message: String) {
        if (quiet) return
        io.errEntry("", message, lineStyle = io.styler::dim)
    }

    /**
     * An error the command reports and carries on from — one file of many that could not be read — while its exit
     * status says something failed. Unlike notices and warnings, never silenced by [quiet].
     */
    public fun error(message: String) {
        io.errEntry("error: ", message, labelStyle = io.styler::red)
    }

    /** A warning on stderr, `warning: <message>`; silenced by [quiet]. */
    public fun warn(message: String) {
        if (quiet) return
        io.errEntry("warning: ", message, labelStyle = io.styler::yellow)
    }

    /**
     * One log line on stderr, for a command that runs for a while and reports as it goes, such as a daemon:
     * `2026-09-28T05:52:13.897Z WARN  message`, with [stamp] dim and the level coloured as [error] and [warn] are.
     *
     * A renderer, not a logging framework: a program keeps its own logger and calls this from its terminal sink,
     * usually with [Format.instantMillis] as the stamp. Errors always print; warnings and info lines not under
     * [quiet]; debug lines only with [verbose] and not under [quiet]. An [error] adds its message, and with [verbose]
     * its stack trace.
     *
     * A line break in [message] or in [error]'s message starts a continuation line under the message, as the class
     * KDoc shows. The entry, continuations and stack trace included, is one write, as every entry is.
     */
    public fun log(level: LogLevel, message: String, error: Throwable? = null, stamp: String? = null) {
        val shown = when (level) {
            LogLevel.ERROR -> true
            LogLevel.WARN, LogLevel.INFO -> !quiet
            LogLevel.DEBUG -> verbose && !quiet
        }
        if (!shown) return
        val styler = io.styler
        val label = level.name.padEnd(LogLevel.WIDTH)
        val head = buildString {
            if (stamp != null) append(styler.dim(stamp)).append(' ')
            append(
                when (level) {
                    LogLevel.ERROR -> styler.red(label)
                    LogLevel.WARN -> styler.yellow(label)
                    LogLevel.INFO -> label
                    LogLevel.DEBUG -> styler.dim(label)
                },
            )
            append(' ')
        }
        val column = (stamp?.let { Styler.displayWidth(it) + 1 } ?: 0) + LogLevel.WIDTH + 1
        // Joined before the split, so a line break in the error's message is marked like one in the message.
        val text = if (error == null) message else message + ": " + (error.message ?: error::class.simpleName.orEmpty())
        val entry = buildString {
            append(TerminalText.entry(head, text, column, io.borderStyle.vertical))
            if (error != null && verbose) append('\n').append(TerminalText.stackTrace(error))
            append('\n')
        }
        io.err(entry)
    }

    /** A progress line for a long step, silent under [quiet] and when stderr's reader is not a terminal. */
    public fun progress(label: String, total: Long? = null): Progress = Progress(io, label, total, enabled = !quiet)

    public companion object {
        /** Fixed-width columns: a truncated one reads as a different value, so it is never shrunk. */
        public fun fixed(header: String, align: Align = Align.Left): Column =
            Column(header, align = align, flexible = false)
    }
}

/**
 * [message] on stderr after [label], such as `error: `, in one write: a line break in it starts a continuation line
 * under the message, marked with the border style's vertical line (see [TerminalText.entry]). [labelStyle] styles the
 * label, and [lineStyle] each line of the message on its own.
 */
internal fun TerminalIo.errEntry(
    label: String,
    message: String,
    labelStyle: (String) -> String = { it },
    lineStyle: (String) -> String = { it },
) {
    err(TerminalText.entry(labelStyle(label), message, Styler.displayWidth(label), borderStyle.vertical, lineStyle) + "\n")
}

/** The level of an [Output.log] line: the kit's own, so a program maps its logger's levels onto it. */
public enum class LogLevel {
    ERROR, WARN, INFO, DEBUG;

    internal companion object {
        /** The widest name, so the messages after the levels line up. */
        const val WIDTH = 5
    }
}
