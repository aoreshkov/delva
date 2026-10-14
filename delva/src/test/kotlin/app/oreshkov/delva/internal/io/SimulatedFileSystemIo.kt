package app.oreshkov.delva.internal.io

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.random.Random

/**
 * An in-memory disk for deterministic crash tests.
 *
 * - **Files** keep their durable bytes and the ordered list of writes and truncations made since the last `force`.
 *   Reads see everything written. A scheduled force failure drops the unsynced changes and throws (fsyncgate).
 * - **Directory entries** (create, rename, delete) are pending until [syncDirectory] of their directory on [Platform.LINUX]
 *   and durable at once on Windows. What an open file allows depends on the [Platform]: see there.
 * - **Processes.** The instance is one process's view of the disk; [process] gives another one over the same disk,
 *   with its own handles and locks, so a writer and its readers can share a disk. A lock held by one process isn't
 *   granted to another. [exit] ends one process: its handles close and its locks go; [exitAfter] ends it at an I/O
 *   call, as a process that dies mid-write.
 * - **Torn reads.** After [tearReads], a read of the range another process wrote last may see each 4 KiB page of it
 *   either before or after that write. A page lands at random on reads and stays landed, as in a page cache: what a
 *   reader of a file that is being appended to can see while page copies are under way. With `landOnNextCall`, the
 *   writing process's next I/O call lands all of its writes, as the return of a `write` call does on a real system:
 *   only the latest write of a process that hasn't done anything since can read torn.
 * - [crashAfter] makes the n-th following I/O call throw and every later call fail, like a power cut. [crash] then
 *   builds the state after reboot: each file keeps a random prefix of its unsynced changes, the last kept write may be
 *   torn at a random byte and get flipped bits, and a random prefix of the pending directory operations survives. In
 *   its `pages` mode a file instead keeps a random subset of the pages its unsynced changes touched, out of order.
 *   [restart] models every process exiting without power loss (nothing is lost). Both invalidate all handles and locks.
 *
 * Deterministic for a given sequence of calls and seeds. Thread-safe (one lock per disk).
 */
internal class SimulatedFileSystemIo private constructor(private val disk: Disk) : FileSystemIo {
    /** [Platform.LINUX], or [Platform.WINDOWS_STRICT] for [isWindows]. */
    constructor(isWindows: Boolean = false) : this(if (isWindows) Platform.WINDOWS_STRICT else Platform.LINUX)

    constructor(platform: Platform) : this(Disk(platform))

    /** What a file that another handle has open allows, and when directory entries are durable. */
    enum class Platform(val isWindows: Boolean) {
        /** Deleting, renaming and replacing an open file all work; directory entries need [syncDirectory]. */
        LINUX(false),

        /**
         * Windows 10 1809 and later on NTFS, with the JDK's `FILE_SHARE_DELETE`: deleting or renaming an open file works
         * (the name goes at once), and replacing an open target fails.
         */
        WINDOWS_POSIX(true),

        /**
         * Legacy delete semantics (FAT32, exFAT, older systems): deleting an open file leaves its name behind as
         * *delete-pending* until the last handle closes. The name is listed and exists, but opening, creating, deleting
         * or renaming it, or replacing it, fails. Renaming an open file works; replacing an open target fails.
         */
        WINDOWS_LEGACY(true),

        /** As if every file were opened with `NOSHARE_DELETE`: deleting or renaming an open file fails, and so does replacing one. */
        WINDOWS_STRICT(true),
    }

    private sealed class Change {
        class Write(val position: Long, val bytes: ByteArray) : Change()
        class Truncate(val size: Long) : Change()
    }

    /** The last write to a file: [before] holds the bytes it overwrote (zeros past the old end); see [Handle.tear]. */
    private class LastWrite(val position: Long, val before: ByteArray, val after: ByteArray, val writer: SimulatedFileSystemIo) {
        /** Pages of the write, from the one holding [position], that other processes see. */
        val landed = BooleanArray(((position + after.size - 1) / PAGE_BYTES - position / PAGE_BYTES + 1).toInt().coerceAtLeast(1))
    }

    private class Inode {
        var durable = ByteArray(0)
        var durableSize = 0L
        var current = ByteArray(0)
        var size = 0L
        val pending = ArrayList<Change>()
        var openHandles = 0
        var lastWrite: LastWrite? = null
    }

    /** A directory change; it becomes durable with a sync of any directory in [dirs]. */
    private sealed class DirOp(val dirs: Set<Path>) {
        class Create(val path: Path, val inode: Inode) : DirOp(setOf(path.parent))

        /** Atomic across directories too: a sync of either one makes all of it durable. */
        class Rename(val from: Path, val to: Path, val inode: Inode) : DirOp(setOf(from.parent, to.parent))
        class Delete(val path: Path) : DirOp(setOf(path.parent))
    }

    /** The state every process view shares; its monitor is the one lock. */
    private class Disk(val platform: Platform) {
        val names = HashMap<Path, Inode>()
        val durableNames = HashMap<Path, Inode>()

        /** Delete-pending names ([Platform.WINDOWS_LEGACY]): gone once their inode has no open handle. */
        val ghosts = HashMap<Path, Inode>()
        val directories = HashSet<Path>()
        val pendingDirOps = ArrayList<DirOp>()
        val locks = HashMap<Path, SimulatedFileSystemIo>()
        var epoch = 0
        var crashAt = -1L
        var down = false
        val forceFailures = ArrayList<(Path) -> Boolean>()
        val closeFailures = ArrayList<(Path) -> Boolean>()

        @Volatile
        var beforeForce: ((Path) -> Unit)? = null
        var calls = 0L
        var forces = 0L
        var tearReads: Random? = null
        var landOnNextCall = false

        /** Inodes whose last write may still read torn, with [landOnNextCall]. */
        val unlanded = ArrayList<Inode>()

        /** Open handles of every process; a reboot invalidates them all. */
        val handles = HashSet<SimulatedFileSystemIo.Handle>()
    }

    private val lock: Any get() = disk
    private var exited = false

    /** The disk-wide call number at which this process exits ([exitAfter]); -1 for none. */
    private var exitAt = -1L

    override val isWindows: Boolean get() = disk.platform.isWindows

    val platform: Platform get() = disk.platform

    /** I/O calls made so far, by every process. */
    val calls: Long get() = synchronized(lock) { disk.calls }

    /** Forces that succeeded so far, by every process. */
    val forces: Long get() = synchronized(lock) { disk.forces }

    /** Whether a scheduled crash happened and the disk is down until [crash]. */
    val isDown: Boolean get() = synchronized(lock) { disk.down }

    /** Another process's view of this disk: its own handles and locks, the same files. */
    fun process(): SimulatedFileSystemIo = SimulatedFileSystemIo(disk)

    /** Ends this process without losing anything: its handles close and its locks go; every later call fails. */
    fun exit() = synchronized(lock) {
        for (handle in disk.handles.filter { it.owner === this }) handle.close()
        disk.locks.values.removeAll { it === this }
        exited = true
    }

    /**
     * Makes this process's [n]-th I/O call from now (counted on the whole disk, 1 = the next one) fail and the process
     * [exit] at it: a process that dies without a power loss, so nothing it wrote is lost and other processes go on.
     */
    fun exitAfter(n: Long) = synchronized(lock) {
        require(n >= 1)
        exitAt = disk.calls + n
    }

    /** Makes reads of what another process wrote last page-torn, with [random]; null turns it off. */
    fun tearReads(random: Random?, landOnNextCall: Boolean = false) = synchronized(lock) {
        disk.tearReads = random
        disk.landOnNextCall = landOnNextCall
    }

    /** Makes the [n]-th I/O call from now (1 = the next one) fail and the disk go down. */
    fun crashAfter(n: Long) = synchronized(lock) {
        require(n >= 1)
        disk.crashAt = disk.calls + n
    }

    /** Makes the next `force` of a file matching [path] fail after dropping that file's unsynced changes. */
    fun failNextForce(path: (Path) -> Boolean) = synchronized(lock) { disk.forceFailures += path }

    /** Makes the next `close` of a handle on a file matching [path] throw, after it has closed the handle. */
    fun failNextClose(path: (Path) -> Boolean) = synchronized(lock) { disk.closeFailures += path }

    /** File handles open on the disk, by any process. */
    val openHandles: Int get() = synchronized(lock) { disk.handles.size }

    /** Calls [hook] with the path at the start of every `force`, outside the disk's lock so it may block; null removes it. */
    fun beforeForce(hook: ((Path) -> Unit)?) {
        disk.beforeForce = hook
    }

    /**
     * Loses power: unsynced state is cut at random points (see the class comment). With [pages], each file's unsynced
     * changes are written back out of order instead of as a prefix ([writeBackPages]); [tear] and [flipBits] then don't
     * apply.
     */
    fun crash(random: Random, tear: Boolean = true, flipBits: Boolean = true, pages: Boolean = false) = synchronized(lock) {
        val keptDirOps = if (disk.pendingDirOps.isEmpty()) 0 else random.nextInt(disk.pendingDirOps.size + 1)
        for (i in 0 until keptDirOps) applyDurably(disk.pendingDirOps[i])
        disk.pendingDirOps.clear()
        val inodes = LinkedHashSet<Inode>()
        inodes += disk.names.values
        inodes += disk.durableNames.values
        for (inode in inodes) {
            val kept = if (pages || inode.pending.isEmpty()) 0 else random.nextInt(inode.pending.size + 1)
            if (pages) writeBackPages(inode, random)
            for (i in 0 until kept) {
                val change = inode.pending[i]
                if (i == kept - 1 && change is Change.Write && tear && random.nextInt(3) == 0) {
                    val torn = change.bytes.copyOf(random.nextInt(change.bytes.size + 1))
                    if (flipBits && torn.isNotEmpty() && random.nextInt(2) == 0) {
                        repeat(1 + random.nextInt(3)) {
                            val at = random.nextInt(torn.size)
                            torn[at] = (torn[at].toInt() xor (1 shl random.nextInt(8))).toByte()
                        }
                    }
                    applyTo(inode, Change.Write(change.position, torn), durable = true)
                } else {
                    applyTo(inode, change, durable = true)
                }
            }
            inode.pending.clear()
            inode.current = inode.durable.copyOf(inode.durable.size)
            inode.size = inode.durableSize
        }
        disk.names.clear()
        disk.names.putAll(disk.durableNames)
        reboot()
    }

    /**
     * Makes durable what page-cache writeback could have left of [inode]'s unsynced changes: writeback isn't ordered
     * within a file, so a later write can survive an earlier one that is lost. The size is the file's size after a random
     * number of the changes, as the metadata may be written back at any point. Each 4 KiB page up to it was written back
     * after its own random number of the changes (none: its durable bytes), or, torn, each of its 512-byte sectors was.
     */
    private fun writeBackPages(inode: Inode, random: Random) {
        val changes = inode.pending
        if (changes.isEmpty()) return
        var size = inode.durableSize
        val sizes = LongArray(changes.size + 1)
        sizes[0] = size
        for ((i, change) in changes.withIndex()) {
            size = when (change) {
                is Change.Write -> maxOf(size, change.position + change.bytes.size)
                is Change.Truncate -> minOf(size, change.size)
            }
            sizes[i + 1] = size
        }
        val kept = sizes[random.nextInt(sizes.size)].toInt()
        val bytes = ByteArray(kept)
        for (page in 0 until ((kept + PAGE_BYTES - 1) / PAGE_BYTES).toInt()) {
            val pageStart = page * PAGE_BYTES
            val torn = random.nextInt(4) == 0
            var image = pageAfter(inode, pageStart, random.nextInt(changes.size + 1), sizes)
            for (sector in 0 until (PAGE_BYTES / SECTOR_BYTES).toInt()) {
                val start = sector * SECTOR_BYTES
                if (pageStart + start >= kept) break
                if (torn) image = pageAfter(inode, pageStart, random.nextInt(changes.size + 1), sizes)
                val length = minOf(SECTOR_BYTES.toLong(), kept - pageStart - start).toInt()
                System.arraycopy(image, start, bytes, (pageStart + start).toInt(), length)
            }
        }
        inode.durable = bytes
        inode.durableSize = kept.toLong()
    }

    /** The page of [inode] at [pageStart] after its durable bytes and the first [count] unsynced changes ([sizes] after each). */
    private fun pageAfter(inode: Inode, pageStart: Long, count: Int, sizes: LongArray): ByteArray {
        val page = ByteArray(PAGE_BYTES.toInt())
        val pageEnd = pageStart + PAGE_BYTES
        if (pageStart < inode.durableSize) {
            System.arraycopy(inode.durable, pageStart.toInt(), page, 0, (minOf(pageEnd, inode.durableSize) - pageStart).toInt())
        }
        for (i in 0 until count) {
            when (val change = inode.pending[i]) {
                is Change.Write -> {
                    val from = maxOf(change.position, pageStart)
                    val to = minOf(change.position + change.bytes.size, pageEnd)
                    if (from < to) System.arraycopy(change.bytes, (from - change.position).toInt(), page, (from - pageStart).toInt(), (to - from).toInt())
                }
                is Change.Truncate -> {
                    // Zeros from the new end to the old, as a later write that extends the file again expects.
                    val from = maxOf(change.size, pageStart)
                    val to = minOf(sizes[i], pageEnd)
                    if (from < to) page.fill(0, (from - pageStart).toInt(), (to - pageStart).toInt())
                }
            }
        }
        return page
    }

    /**
     * Loses power keeping every unsynced change of the files [keep] accepts and none of the others, and every pending
     * directory operation: the worst case of independent page-cache writeback.
     */
    fun crashKeeping(keep: (Path) -> Boolean) = synchronized(lock) {
        for (op in disk.pendingDirOps) applyDurably(op)
        disk.pendingDirOps.clear()
        for ((path, inode) in disk.names) {
            if (keep(path)) for (change in inode.pending) applyTo(inode, change, durable = true)
            inode.pending.clear()
            inode.current = inode.durable.copyOf(inode.durable.size)
            inode.size = inode.durableSize
        }
        reboot()
    }

    /** Ends every process without losing anything: handles and locks go away, data stays. */
    fun restart() = synchronized(lock) { reboot() }

    private fun reboot() {
        for (inode in disk.names.values) {
            inode.openHandles = 0
            inode.lastWrite = null
        }
        disk.ghosts.clear()
        disk.handles.clear()
        disk.locks.clear()
        disk.epoch++
        disk.down = false
        disk.crashAt = -1
        disk.forceFailures.clear()
        disk.closeFailures.clear()
    }

    /** Current bytes of [path] (everything written), for assertions and damage. */
    fun bytes(path: Path): ByteArray = synchronized(lock) {
        val inode = disk.names[path.normalize()] ?: throw NoSuchFileException(path.toString())
        inode.current.copyOf(inode.size.toInt())
    }

    /** Flips bit [bit] of byte [position] of [path], durably: damage on the medium. */
    fun flipBit(path: Path, position: Long, bit: Int) = synchronized(lock) {
        val inode = disk.names[path.normalize()] ?: throw NoSuchFileException(path.toString())
        require(position < inode.size && position < inode.durableSize)
        val p = position.toInt()
        inode.current[p] = (inode.current[p].toInt() xor (1 shl bit)).toByte()
        inode.durable[p] = (inode.durable[p].toInt() xor (1 shl bit)).toByte()
    }

    /** Paths of all files, sorted; delete-pending names aren't files. */
    fun files(): List<Path> = synchronized(lock) { disk.names.keys.sortedBy { it.toString() } }

    /** Delete-pending names ([Platform.WINDOWS_LEGACY]), sorted. */
    fun deletePending(): List<Path> = synchronized(lock) { disk.ghosts.keys.sortedBy { it.toString() } }

    // ---- FileSystemIo ------------------------------------------------------------------------------------------------

    override fun open(path: Path, mode: OpenMode): FileIo = synchronized(lock) {
        call()
        val p = path.normalize()
        refuseGhost(p)
        val inode = when (mode) {
            OpenMode.READ, OpenMode.READ_WRITE -> disk.names[p] ?: throw NoSuchFileException(p.toString())
            OpenMode.CREATE_NEW -> {
                if (p in disk.names) throw FileAlreadyExistsException(p.toString())
                if (p.parent != null && p.parent !in disk.directories) throw NoSuchFileException(p.parent.toString())
                Inode().also {
                    disk.names[p] = it
                    dirOp(DirOp.Create(p, it))
                }
            }
        }
        inode.openHandles++
        Handle(p, inode, disk.epoch, mode != OpenMode.READ).also { disk.handles += it }
    }

    override fun exists(path: Path): Boolean = synchronized(lock) {
        call()
        val p = path.normalize()
        p in disk.names || p in disk.ghosts || p in disk.directories
    }

    override fun list(dir: Path): List<Path> = synchronized(lock) {
        call()
        val d = dir.normalize()
        (disk.names.keys.filter { it.parent == d } + disk.ghosts.keys.filter { it.parent == d } + disk.directories.filter { it.parent == d })
            .sortedBy { it.fileName.toString() }
    }

    override fun createDirectories(dir: Path) = synchronized(lock) {
        call()
        var d: Path? = dir.normalize()
        while (d != null) {
            disk.directories.add(d)
            d = d.parent
        }
    }

    override fun atomicRename(from: Path, to: Path) = synchronized(lock) {
        call()
        val f = from.normalize()
        val t = to.normalize()
        refuseGhost(f)
        refuseGhost(t)
        val inode = disk.names[f] ?: throw NoSuchFileException(f.toString())
        if (isWindows && (disk.names[t]?.openHandles ?: 0) > 0) throw AccessDeniedException(f.toString(), t.toString(), "target open")
        if (disk.platform == Platform.WINDOWS_STRICT && inode.openHandles > 0) throw AccessDeniedException(f.toString(), t.toString(), "open")
        disk.names.remove(f)
        disk.names[t] = inode
        dirOp(DirOp.Rename(f, t, inode))
    }

    override fun delete(path: Path) = synchronized(lock) {
        call()
        val p = path.normalize()
        refuseGhost(p)
        val inode = disk.names[p] ?: return@synchronized
        if (inode.openHandles > 0) {
            when (disk.platform) {
                Platform.WINDOWS_STRICT -> throw AccessDeniedException(p.toString(), null, "open")
                Platform.WINDOWS_LEGACY -> disk.ghosts[p] = inode
                else -> {}
            }
        }
        disk.names.remove(p)
        dirOp(DirOp.Delete(p))
    }

    override fun syncDirectory(dir: Path) = synchronized(lock) {
        call()
        if (isWindows) return@synchronized
        val d = dir.normalize()
        val iterator = disk.pendingDirOps.iterator()
        while (iterator.hasNext()) {
            val op = iterator.next()
            if (d in op.dirs) {
                applyDurably(op)
                iterator.remove()
            }
        }
    }

    override fun tryLock(path: Path): AutoCloseable? = synchronized(lock) {
        call()
        val p = path.normalize()
        if (p in disk.locks) return@synchronized null
        disk.locks[p] = this
        val lockEpoch = disk.epoch
        AutoCloseable { synchronized(lock) { if (disk.epoch == lockEpoch && disk.locks[p] === this) disk.locks.remove(p) } }
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    private fun call() {
        if (exited) throw IOException("simulated process has exited")
        if (disk.down) throw IOException("simulated disk is down")
        disk.calls++
        if (disk.landOnNextCall && disk.unlanded.isNotEmpty()) {
            disk.unlanded.removeAll { inode ->
                val mine = inode.lastWrite?.writer === this
                if (mine) inode.lastWrite = null
                mine || inode.lastWrite == null
            }
        }
        if (exitAt >= 0 && disk.calls >= exitAt) {
            exitAt = -1
            exit()
            throw IOException("simulated process exit at I/O call ${disk.calls}")
        }
        if (disk.calls == disk.crashAt) {
            disk.down = true
            throw IOException("simulated crash at I/O call ${disk.calls}")
        }
    }

    /** A delete-pending name can't be opened, created, deleted, renamed or replaced. */
    private fun refuseGhost(p: Path) {
        if (p in disk.ghosts) throw AccessDeniedException(p.toString(), null, "delete pending")
    }

    private fun dirOp(op: DirOp) {
        if (isWindows) applyDurably(op) else disk.pendingDirOps += op
    }

    private fun applyDurably(op: DirOp) {
        when (op) {
            is DirOp.Create -> disk.durableNames[op.path] = op.inode
            is DirOp.Rename -> {
                disk.durableNames.remove(op.from)
                disk.durableNames[op.to] = op.inode
            }
            is DirOp.Delete -> disk.durableNames.remove(op.path)
        }
    }

    private fun applyTo(inode: Inode, change: Change, durable: Boolean) {
        var bytes = if (durable) inode.durable else inode.current
        var size = if (durable) inode.durableSize else inode.size
        when (change) {
            is Change.Write -> {
                val end = change.position + change.bytes.size
                if (end > Int.MAX_VALUE) throw IOException("simulated files are limited to 2 GiB")
                if (end > bytes.size) bytes = bytes.copyOf(maxOf(end.toInt(), bytes.size * 2))
                if (change.position > size) bytes.fill(0, size.toInt(), change.position.toInt())
                System.arraycopy(change.bytes, 0, bytes, change.position.toInt(), change.bytes.size)
                size = maxOf(size, end)
            }
            is Change.Truncate -> if (change.size < size) {
                bytes.fill(0, change.size.toInt(), size.toInt())
                size = change.size
            }
        }
        if (durable) {
            inode.durable = bytes
            inode.durableSize = size
        } else {
            inode.current = bytes
            inode.size = size
        }
    }

    /** The bytes [inode]'s range `[position, position + length)` holds now, zeros past its end. */
    private fun currentRange(inode: Inode, position: Long, length: Int): ByteArray {
        val range = ByteArray(length)
        if (position < inode.size) System.arraycopy(inode.current, position.toInt(), range, 0, minOf(length.toLong(), inode.size - position).toInt())
        return range
    }

    private inner class Handle(override val path: Path, private val inode: Inode, private val handleEpoch: Int, private val writable: Boolean) : FileIo {
        val owner: SimulatedFileSystemIo get() = this@SimulatedFileSystemIo

        private var closed = false

        private fun check() {
            call()
            if (handleEpoch != disk.epoch || closed) throw IOException("$path: handle is closed")
        }

        override fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
            check()
            require(position >= 0 && length >= 0)
            if (position >= inode.size) return@synchronized 0
            val n = minOf(length.toLong(), inode.size - position).toInt()
            System.arraycopy(inode.current, position.toInt(), destination, offset, n)
            tear(position, destination, offset, n)
            n
        }

        /**
         * Shows each page of the last write by another process before it until the page has *landed* ([tearReads]):
         * each read lands every page it touches with even odds, and a landed page stays landed, as in a page cache.
         */
        private fun tear(position: Long, destination: ByteArray, offset: Int, n: Int) {
            val random = disk.tearReads ?: return
            val write = inode.lastWrite ?: return
            if (write.writer === owner) return
            val from = maxOf(position, write.position)
            val to = minOf(position + n, write.position + write.after.size)
            if (from >= to) return
            var page = from - from % PAGE_BYTES
            while (page < to) {
                val start = maxOf(page, from)
                val end = minOf(page + PAGE_BYTES, to)
                val index = (page / PAGE_BYTES - write.position / PAGE_BYTES).toInt()
                if (!write.landed[index] && random.nextBoolean()) write.landed[index] = true
                if (!write.landed[index]) {
                    System.arraycopy(write.before, (start - write.position).toInt(), destination, offset + (start - position).toInt(), (end - start).toInt())
                }
                page += PAGE_BYTES
            }
        }

        override fun write(position: Long, source: ByteArray, offset: Int, length: Int) = synchronized(lock) {
            check()
            if (!writable) throw IOException("$path is open for reading")
            val change = Change.Write(position, source.copyOfRange(offset, offset + length))
            // Only while reads tear: a copy of every write is costly for long simulations.
            val before = if (disk.tearReads != null) currentRange(inode, position, length) else null
            inode.pending += change
            applyTo(inode, change, durable = false)
            inode.lastWrite = before?.let { LastWrite(position, it, change.bytes, owner) }
            if (before != null && disk.landOnNextCall) disk.unlanded += inode
        }

        override fun force(metadata: Boolean) {
            disk.beforeForce?.invoke(path)
            forceLocked()
        }

        private fun forceLocked() = synchronized(lock) {
            check()
            val failure = disk.forceFailures.indexOfFirst { it(path) }
            if (failure >= 0) {
                disk.forceFailures.removeAt(failure)
                inode.pending.clear()
                inode.current = inode.durable.copyOf(inode.durable.size)
                inode.size = inode.durableSize
                inode.lastWrite = null
                throw IOException("simulated fsync failure on $path")
            }
            for (change in inode.pending) applyTo(inode, change, durable = true)
            inode.pending.clear()
            disk.forces += 1
        }

        override fun truncate(size: Long) = synchronized(lock) {
            check()
            if (!writable) throw IOException("$path is open for reading")
            val change = Change.Truncate(size)
            inode.pending += change
            applyTo(inode, change, durable = false)
            inode.lastWrite = null
        }

        override fun size(): Long = synchronized(lock) {
            check()
            inode.size
        }

        override fun close() = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            disk.handles -= this
            if (handleEpoch == disk.epoch && --inode.openHandles == 0) disk.ghosts.values.removeAll { it === inode }
            val failure = disk.closeFailures.indexOfFirst { it(path) }
            if (failure >= 0) {
                disk.closeFailures.removeAt(failure)
                throw IOException("simulated close failure on $path")
            }
        }
    }

    private companion object {
        const val PAGE_BYTES = 4096L
        const val SECTOR_BYTES = 512
    }
}
