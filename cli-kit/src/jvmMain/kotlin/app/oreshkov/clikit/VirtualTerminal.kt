package app.oreshkov.clikit

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * ANSI on a Windows console, through `java.lang.foreign` — **no JNA, and no native library
 * extracted to a temp directory at startup**.
 *
 * A Windows console only interprets escape sequences if the process opts in with
 * `SetConsoleMode(ENABLE_VIRTUAL_TERMINAL_PROCESSING)`. Libraries reach for JNA to make that call;
 * this module does not need to: the FFM API is final from JDK 22, the floor it is built for. A
 * program using it runs with `--enable-native-access=ALL-UNNAMED`, as this module's tests do, or the
 * JDK warns on the first downcall. A JNA dependency would buy nothing the JDK does not already
 * provide.
 *
 * On Windows 11 and Server 2025 the default host is Windows Terminal, whose ConPTY sets
 * `VirtualTerminalLevel` already, so this is the legacy-`conhost` fallback. **Every failure path
 * ends in monochrome output, never in a crash** — which is also why the whole thing is wrapped: a
 * missing symbol, a restricted-access refusal or a closed handle are all "no colour", and none of
 * them is worth failing a command over.
 */
internal object VirtualTerminal {

    private const val STD_OUTPUT_HANDLE = -11
    private const val ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004

    private val onWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.startsWith("windows") == true

    /** Resolved once, lazily: on a POSIX host the lookup never happens at all. */
    private val kernel32: Kernel32? by lazy { if (onWindows) Kernel32.open() else null }

    private var attempted = false
    private var result = false

    /**
     * @return whether escape sequences will be interpreted. Always `true` off Windows, where a
     *   terminal needs no opt-in.
     */
    fun enable(): Boolean {
        if (!onWindows) return true
        if (attempted) return result
        attempted = true
        result = runCatching { kernel32?.enableVirtualTerminal() == true }.getOrDefault(false)
        return result
    }

    /**
     * The three downcalls, bound once.
     *
     * `Arena.global()` rather than a confined arena: the handles outlive any single call and there
     * is nothing here to free — `kernel32` is already loaded into every Windows process.
     */
    private class Kernel32(
        private val getStdHandle: MethodHandle,
        private val getConsoleMode: MethodHandle,
        private val setConsoleMode: MethodHandle,
    ) {
        fun enableVirtualTerminal(): Boolean {
            val handle = getStdHandle.invokeExact(STD_OUTPUT_HANDLE) as MemorySegment
            // INVALID_HANDLE_VALUE is -1; a null or invalid handle means there is no console to
            // configure, which is a perfectly ordinary state under a pipe.
            if (handle.address() == 0L || handle.address() == -1L) return false

            Arena.ofConfined().use { arena ->
                val modeOut = arena.allocate(ValueLayout.JAVA_INT)
                val read = getConsoleMode.invokeExact(handle, modeOut) as Int
                if (read == 0) return false

                val mode = modeOut.get(ValueLayout.JAVA_INT, 0)
                if (mode and ENABLE_VIRTUAL_TERMINAL_PROCESSING != 0) return true

                val written = setConsoleMode.invokeExact(
                    handle,
                    mode or ENABLE_VIRTUAL_TERMINAL_PROCESSING,
                ) as Int
                return written != 0
            }
        }

        companion object {
            fun open(): Kernel32? = runCatching {
                val linker = Linker.nativeLinker()
                val lookup = SymbolLookup.libraryLookup("kernel32", Arena.global())

                fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle =
                    linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)

                Kernel32(
                    getStdHandle = handle(
                        "GetStdHandle",
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    ),
                    getConsoleMode = handle(
                        "GetConsoleMode",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                    setConsoleMode = handle(
                        "SetConsoleMode",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                    ),
                )
            }.getOrNull()
        }
    }
}
