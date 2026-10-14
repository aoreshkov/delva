package app.oreshkov.clikit

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path

/**
 * What file descriptor 1 is, asked only once a write to it has failed: that failure means the reader went away when
 * stdout is a pipe or a socket, and something worth reporting — a full disk, say — when it is a file.
 *
 * The JVM ignores SIGPIPE, so `delva get 7 | head -c1` shows up as a failed write rather than a signal, and the
 * exception's message can't tell the two apart: the platform words it, in the user's language.
 */
internal object StandardOutput {
    private val onWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.startsWith("windows") == true

    /** Whether stdout is a pipe or a socket; `false` when that can't be told. */
    fun isPipe(): Boolean = runCatching { if (onWindows) windowsPipe() else posixPipe() }.getOrDefault(false)

    /** The file type bits of `stat` on `/dev/fd/1`, which follows the descriptor to whatever it is open on. */
    private fun posixPipe(): Boolean {
        val type = (Files.getAttribute(Path.of("/dev/fd/1"), "unix:mode") as Int) and S_IFMT
        return type == S_IFIFO || type == S_IFSOCK
    }

    private fun windowsPipe(): Boolean {
        val linker = Linker.nativeLinker()
        val lookup = SymbolLookup.libraryLookup("kernel32", Arena.global())
        val getStdHandle = linker.downcallHandle(
            lookup.find("GetStdHandle").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
        val getFileType = linker.downcallHandle(
            lookup.find("GetFileType").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        val handle = getStdHandle.invokeExact(STD_OUTPUT_HANDLE) as MemorySegment
        return getFileType.invokeExact(handle) as Int == FILE_TYPE_PIPE
    }

    private const val S_IFMT = 0xF000
    private const val S_IFIFO = 0x1000
    private const val S_IFSOCK = 0xC000
    private const val STD_OUTPUT_HANDLE = -11

    /** An anonymous or named pipe; Windows has no other kind a reader can leave. */
    private const val FILE_TYPE_PIPE = 3
}
