package app.oreshkov.delva

import java.time.Instant
import java.util.Objects

/**
 * The process that has a store open, as it recorded itself when it opened the store ([StoreLockedException.holder]).
 * The record is advisory: a process that can't write it still holds the store.
 */
public class LockHolder(
    /** The operating system's id of the process. */
    public val pid: Long,
    /** When the process started, when the platform tells. */
    public val startedAt: Instant?,
    /** The host the process runs on: the `COMPUTERNAME` or `HOSTNAME` environment variable, else, on Linux, the kernel's host name. */
    public val host: String?,
) {
    override fun equals(other: Any?): Boolean = other is LockHolder && pid == other.pid && startedAt == other.startedAt && host == other.host

    override fun hashCode(): Int = Objects.hash(pid, startedAt, host)

    /** `process 4711 on build-7, started 2026-09-30T08:15:02Z`, leaving out what isn't known. */
    override fun toString(): String = buildString {
        append("process ").append(pid)
        if (host != null) append(" on ").append(host)
        if (startedAt != null) append(", started ").append(startedAt)
    }
}
