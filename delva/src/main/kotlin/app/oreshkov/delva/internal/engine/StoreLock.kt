package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.io.replace
import app.oreshkov.delva.internal.log.LogFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant

/**
 * A store directory's lock, and the record of who holds it.
 *
 * The lock is the `LOCK` file's lock. The holder is recorded apart, in [OWNER_FILE], because a range locked by
 * `FileChannel.tryLock` can't be read by another process on Windows. The record is advisory: it's written after the lock
 * is taken, not synced, and deleted before the lock is released; a crash can leave a stale one, which the next holder
 * replaces, and a reader on the same host drops one whose process is gone. Writing it may fail without failing the open.
 *
 * ```
 * delva-lock 1
 * pid=<decimal>
 * started=<ISO-8601 instant>    (optional)
 * host=<name>                    (optional)
 * ```
 */
internal object StoreLock {
    const val OWNER_FILE: String = "LOCK.owner"
    private const val MAGIC = "delva-lock 1"

    /** A record is a few dozen bytes; anything larger isn't one. */
    private const val MAX_OWNER_BYTES = 1024
    private const val MAX_HOST_CHARS = 255

    /** Enough for the name and a few comment lines; the rest of a larger file is ignored. */
    private const val MAX_HOST_FILE_BYTES = 4096
    private val HOST_NAME_FILES = listOf(Path.of("/proc/sys/kernel/hostname"), Path.of("/etc/hostname"))

    /**
     * Locks [dir] and records [self] as the holder; closing the result deletes the record, then releases the lock.
     * Throws [StoreLockedException], with the holder when its record is readable, when another holder has the lock.
     */
    fun acquire(dir: Path, io: FileSystemIo, self: LockHolder = currentProcess()): AutoCloseable {
        val lock = io.tryLock(dir.resolve(LogFiles.LOCK_FILE)) ?: throw StoreLockedException(dir, holder(dir, io))
        val owner = dir.resolve(OWNER_FILE)
        val recorded = try {
            write(dir, io, self)
            true
        } catch (_: IOException) {
            // Advisory: a lock without a record still locks.
            false
        }
        return AutoCloseable {
            try {
                if (recorded) io.delete(owner)
            } catch (_: IOException) {
                // The next holder replaces it, and a reader on this host drops it once this process is gone.
            } finally {
                lock.close()
            }
        }
    }

    /**
     * The holder [dir]'s record names, or null when it's missing, unreadable or malformed, or names a process on
     * [thisHost] that is gone or is another process with the same pid.
     */
    fun holder(dir: Path, io: FileSystemIo, thisHost: String? = hostName()): LockHolder? {
        val text = try {
            io.open(dir.resolve(OWNER_FILE), OpenMode.READ).use { file ->
                val size = file.size()
                if (size > MAX_OWNER_BYTES) return null
                ByteArray(size.toInt()).also { file.readFully(0, it, 0, it.size) }.toString(Charsets.UTF_8)
            }
        } catch (_: IOException) {
            return null
        }
        val holder = parse(text) ?: return null
        return holder.takeIf { thisHost == null || it.host != thisHost || isRunning(it) }
    }

    /** This process as a [LockHolder]. */
    fun currentProcess(): LockHolder {
        val process = ProcessHandle.current()
        return LockHolder(process.pid(), process.info().startInstant().orElse(null), hostName())
    }

    /**
     * This host's name, or null: the `COMPUTERNAME` or `HOSTNAME` environment variable, else the first of [files] that
     * names one. Shells don't export `HOSTNAME`, so on Linux the name usually comes from the kernel's file. There's no
     * DNS lookup, which can block for seconds.
     */
    fun hostName(env: (String) -> String? = System::getenv, files: List<Path> = HOST_NAME_FILES): String? {
        validHost(env("COMPUTERNAME"))?.let { return it }
        validHost(env("HOSTNAME"))?.let { return it }
        for (file in files) {
            val text = try {
                Files.newInputStream(file).use { it.readNBytes(MAX_HOST_FILE_BYTES) }.toString(Charsets.UTF_8)
            } catch (_: IOException) {
                continue
            }
            // hostname(5): one line, and lines starting with '#' are comments.
            validHost(text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') })?.let { return it }
        }
        return null
    }

    /** [name], trimmed, when a record can hold it. */
    private fun validHost(name: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_HOST_CHARS && it.none(Char::isISOControl) }

    private fun write(dir: Path, io: FileSystemIo, self: LockHolder) {
        val text = buildString {
            append(MAGIC).append('\n')
            append("pid=").append(self.pid).append('\n')
            self.startedAt?.let { append("started=").append(it).append('\n') }
            self.host?.let { append("host=").append(it).append('\n') }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tmp = dir.resolve("$OWNER_FILE.tmp")
        io.delete(tmp)
        io.open(tmp, OpenMode.CREATE_NEW).use { it.write(0, bytes, 0, bytes.size) }
        io.replace(tmp, dir.resolve(OWNER_FILE))
    }

    private fun parse(text: String): LockHolder? {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        if (lines.firstOrNull() != MAGIC) return null
        var pid: Long? = null
        var started: Instant? = null
        var host: String? = null
        for (line in lines.drop(1)) {
            val value = line.substringAfter('=', "")
            when (line.substringBefore('=')) {
                "pid" -> pid = value.toLongOrNull()?.takeIf { it > 0 } ?: return null
                "started" -> started = try {
                    Instant.parse(value)
                } catch (_: DateTimeException) {
                    return null
                }
                "host" -> host = value.takeIf { validHost(it) == it } ?: return null
                // Fields a later version adds are left alone.
                else -> {}
            }
        }
        return LockHolder(pid ?: return null, started, host)
    }

    /** Whether [holder], on this host, is a running process: the pid is alive and, when both are known, started then. */
    private fun isRunning(holder: LockHolder): Boolean {
        val process = ProcessHandle.of(holder.pid).orElse(null) ?: return false
        val started = process.info().startInstant().orElse(null)
        return holder.startedAt == null || started == null || started == holder.startedAt
    }
}
