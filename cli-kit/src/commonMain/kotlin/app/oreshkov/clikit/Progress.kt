package app.oreshkov.clikit

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A spinner and a counter on stderr, drawn only at a terminal, and never under `--quiet` (see [Output.progress]).
 *
 * Deliberately not a progress-bar engine: a spinner, how far along, and a word on what it is doing. It redraws at
 * most every 100 milliseconds whatever the caller reports, so a callback made a million times costs a million
 * comparisons, not a million writes to the terminal.
 *
 * Ctrl-C while a progress line is up leaves it cleared: a program run with `runAndExit` does that on its way out.
 */
public class Progress(
    private val io: TerminalIo,
    private val label: String,
    private var total: Long? = null,
    /** False draws nothing at all; true still draws only at a terminal. */
    private val enabled: Boolean = true,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private var current = 0L
    private var frame = 0
    private var active = false
    private var lastDraw: TimeMark? = null

    /** The columns the line on screen takes, for erasing it without `ESC [ K`. */
    private var drawnWidth = 0

    /** One more step done. */
    public fun step(detail: String? = null) {
        update(current + 1, total, detail)
    }

    /** [done] of [total] now, for work counted in bytes or records rather than in steps; nothing after an interrupt. */
    public fun update(done: Long, total: Long? = this.total, detail: String? = null) {
        current = done
        this.total = total
        if (!enabled || !io.interactive) return
        withLock(LOCK) {
            val last = lastDraw
            if (!interrupted && (last == null || last.elapsedNow() >= MIN_INTERVAL)) draw(done, total, detail)
        }
    }

    private fun draw(done: Long, total: Long?, detail: String?) {
        lastDraw = clock.markNow()
        active = true
        shown = this
        val spinner = FRAMES[frame++ % FRAMES.size]
        val counter = if (total != null) " $done/$total" else " $done"
        val suffix = detail?.let { " ${TerminalText.escape(it)}" } ?: ""
        val line = "$spinner ${TerminalText.escape(label)}$counter$suffix"
        val width = Styler.displayWidth(line)
        // Carriage return without a newline: the line is overwritten, so a thousand steps leave one
        // line of scrollback rather than a thousand.
        io.err("\r$line${rest(width)}")
        drawnWidth = width
    }

    /**
     * What erases the rest of the line after [width] columns: `ESC [ K`, or, at a terminal that would print that, spaces
     * over what the last line drew beyond them.
     */
    private fun rest(width: Int): String =
        if (io.escapesInterpreted) CLEAR_TO_END else " ".repeat((drawnWidth - width).coerceAtLeast(0))

    /** Always call this — an unfinished progress line leaves the cursor mid-line for the shell. */
    public fun finish(message: String? = null) {
        val shownMessage = withLock(LOCK) {
            erase()
            enabled && io.interactive && !interrupted
        }
        if (shownMessage) message?.let { io.errLine(TerminalText.escape(it)) }
    }

    /** Takes the line off the screen; called holding [LOCK]. */
    private fun erase() {
        if (!active) return
        io.err("\r${rest(0)}" + if (io.escapesInterpreted) "" else "\r")
        drawnWidth = 0
        active = false
        if (shown === this) shown = null
    }

    public companion object {
        private val MIN_INTERVAL = 100.milliseconds
        private const val CLEAR_TO_END = "\u001B[K"

        // ASCII, so this needs no charset probe of its own.
        private val FRAMES = charArrayOf('|', '/', '-', '\\')

        /**
         * Held while a line is drawn or erased, and while [shown] and [interrupted] change: the command's thread draws,
         * and an interrupt clears from the shutdown hook's while that thread still runs.
         */
        private val LOCK = Any()

        /** The progress line on screen, if any, for [clearShown]. */
        private var shown: Progress? = null

        /** Set by [clearShown]: the process is ending, and a handler still running draws no more. */
        private var interrupted = false

        /**
         * Clears the progress line on screen, if any, and keeps every progress line off the screen from then on: what
         * an interrupt does before the process ends.
         */
        public fun clearShown() {
            withLock(LOCK) {
                interrupted = true
                shown?.erase()
            }
        }

        /** Lets progress lines draw again after [clearShown], for a test that interrupts and then goes on. */
        internal fun resetInterrupted() {
            withLock(LOCK) { interrupted = false }
        }
    }
}
