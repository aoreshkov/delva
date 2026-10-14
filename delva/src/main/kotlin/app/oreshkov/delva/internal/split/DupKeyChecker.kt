package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.hash.Xxh3
import java.util.Arrays

/**
 * Detects repeated keys in the objects of one document, in memory bounded by [arenaBytes] plus a small
 * inline window per open object.
 *
 * - **Exact:** while an object has at most [EXACT_KEYS] keys and all of them are still in the splitter's pending
 *   buffer, a new key is compared byte for byte with the earlier ones, found by their offsets from the object's
 *   frame start (offsets stay valid when the frame moves).
 * - **Hashed:** afterwards (or before any of its pending members are flushed), keys are XXH3-128 hashes with the
 *   insert's seed, in an open-addressing table per open object. The tables together use at most [arenaBytes]. When
 *   the innermost object's table needs room the arena lacks, the largest other table is spilled (its hashes sorted
 *   and written as a run to [spill]) and dropped, until the room is there; the outer objects get no keys until it
 *   closes, so their tables would only sit in the arena. Only when its own table is the largest is it spilled
 *   instead, and starts over at the same size. A dropped table comes back at the smallest size when its object next
 *   gets a key.
 * - **Spilled objects** are checked when they close, by a k-way merge of their runs. Runs are merged into one
 *   [MERGE_FAN_IN] at a time while the object is still open (tiered: runs made by merging the same number of spills
 *   are merged together), so a merge reads at most [MERGE_FAN_IN] runs, and a run's file is open only while it is
 *   written or merged.
 *
 * A hash collision can only report a false duplicate (probability ≈ n²/2¹²⁹), never miss one. Frames are addressed
 * by depth; only the innermost open object gets new keys. Reusable through [reset]; not thread-safe.
 */
internal class DupKeyChecker(private val arenaBytes: Long, private val spill: SpillFiles) {
    init {
        require(arenaBytes >= MIN_TABLE_SLOTS * 16L) { "key arena of $arenaBytes bytes is too small" }
    }

    private var seed = 0L

    private var keyCount = IntArray(16)
    private var hashed = BooleanArray(16)
    private var keyOffsets = IntArray(16 * EXACT_KEYS)
    private var keyLengths = IntArray(16 * EXACT_KEYS)

    private var tables = arrayOfNulls<LongArray>(16)
    private var tableUsed = IntArray(16)
    private var arenaUsed = 0L
    private var runs = arrayOfNulls<ArrayList<Run>>(16)

    private val hash = LongArray(2)
    private var sortA = LongArray(0)
    private var sortB = LongArray(0)
    private val ioBuffer = ByteArray(IO_BUFFER_BYTES)

    /** Heap of the hash tables and sort buffers kept for reuse. */
    val retainedBytes: Long get() = tables.sumOf { (it?.size ?: 0) * 8L } + 8L * (sortA.size + sortB.size) + IO_BUFFER_BYTES

    /** Hash tables currently in the arena, in bytes; never more than the arena. */
    val arenaUsedBytes: Long get() = arenaUsed

    /** The most runs one merge has read since [reset]. */
    var peakMergeWidth: Int = 0
        private set

    fun reset(seed: Long) {
        abort()
        this.seed = seed
        peakMergeWidth = 0
    }

    fun openObject(depth: Int) {
        ensureDepth(depth)
        keyCount[depth] = 0
        hashed[depth] = false
        tableUsed[depth] = 0
    }

    /**
     * Registers the key `key[offset, offset + length)` of the object at [depth]. [frameStart] is the object's frame
     * start in [pending] and [keyPosition] where the key's bytes will be (exact mode keeps them for later comparisons).
     * Returns false if the object already has this key.
     */
    fun addKey(depth: Int, key: ByteArray, offset: Int, length: Int, pending: ByteArray, frameStart: Int, keyPosition: Int): Boolean {
        if (!hashed[depth]) {
            val n = keyCount[depth]
            val base = depth * EXACT_KEYS
            for (i in 0 until n) {
                val at = frameStart + keyOffsets[base + i]
                if (keyLengths[base + i] == length && Arrays.equals(pending, at, at + length, key, offset, offset + length)) return false
            }
            if (n < EXACT_KEYS) {
                keyOffsets[base + n] = keyPosition - frameStart
                keyLengths[base + n] = length
                keyCount[depth] = n + 1
                return true
            }
            toHashed(depth, pending, frameStart)
        }
        return insert(depth, key, offset, length)
    }

    /** Must be called before pending members of the object at [depth] leave the buffer. */
    fun beforeFlush(depth: Int, pending: ByteArray, frameStart: Int) {
        if (!hashed[depth]) toHashed(depth, pending, frameStart)
    }

    /** Closes the object at [depth]; returns false if its spilled runs reveal a repeated key. */
    fun closeObject(depth: Int): Boolean {
        val depthRuns = runs[depth]
        var unique = true
        if (depthRuns != null && depthRuns.isNotEmpty()) {
            unique = (tableUsed[depth] == 0 || spillTable(depth)) && mergeCheck(depthRuns)
            depthRuns.forEach { it.file.delete() }
            depthRuns.clear()
        }
        releaseTable(depth)
        keyCount[depth] = 0
        hashed[depth] = false
        return unique
    }

    /**
     * Whether an open object with spilled runs has a key twice, found by merging its runs now. A repeat of a spilled
     * key is otherwise found only when the runs merge, and the fragments and postings built before then hold both
     * copies; a split that fails on them asks this before it reports them as corrupt. Call [abort] afterwards.
     */
    fun repeatsSpilledKey(): Boolean {
        for (d in runs.indices) {
            val depthRuns = runs[d]
            if (depthRuns.isNullOrEmpty()) continue
            if (tableUsed[d] > 0 && !spillTable(d) || !mergeCheck(depthRuns)) return true
        }
        return false
    }

    /**
     * Drops every table and deletes every spilled run (after a rejected or failed insert). A run that fails to delete
     * doesn't keep the others: the first failure is thrown once all of them were tried.
     */
    fun abort() {
        var failure: Throwable? = null
        for (d in runs.indices) {
            runs[d]?.let { list ->
                for (spilled in list) {
                    try {
                        spilled.file.delete()
                    } catch (t: Throwable) {
                        val first = failure
                        if (first == null) failure = t else first.addSuppressed(t)
                    }
                }
                list.clear()
            }
            releaseTable(d)
            keyCount[d] = 0
            hashed[d] = false
        }
        failure?.let { throw it }
    }

    private fun toHashed(depth: Int, pending: ByteArray, frameStart: Int) {
        hashed[depth] = true
        val base = depth * EXACT_KEYS
        for (i in 0 until keyCount[depth]) {
            check(insert(depth, pending, frameStart + keyOffsets[base + i], keyLengths[base + i])) { "exact keys repeat" }
        }
        keyCount[depth] = 0
    }

    private fun insert(depth: Int, key: ByteArray, offset: Int, length: Int): Boolean {
        Xxh3.hash128(key, offset, length, seed, hash, 0)
        var low = hash[0]
        val high = hash[1]
        if (low == 0L && high == 0L) low = 1L // (0, 0) marks an empty slot
        var table = tables[depth]
        if (table == null) {
            // With no table of its own, dropping the others always frees enough: the arena holds at least one.
            if (makeRoom(depth, MIN_TABLE_SLOTS * 16L) == DUPLICATE) return false
            table = allocate(depth, MIN_TABLE_SLOTS)
        } else if ((tableUsed[depth] + 1) * 2 > table.size / 2) {
            when (makeRoom(depth, table.size * 8L)) {
                ROOM -> table = grow(depth, table)
                SPILL_OWN -> if (!spillTable(depth)) return false
                else -> return false
            }
        }
        val mask = table.size / 2 - 1
        var slot = (mix(low, high).toInt()) and mask
        while (true) {
            val l = table[2 * slot]
            val h = table[2 * slot + 1]
            if (l == 0L && h == 0L) break
            if (l == low && h == high) return false
            slot = (slot + 1) and mask
        }
        table[2 * slot] = low
        table[2 * slot + 1] = high
        tableUsed[depth]++
        return true
    }

    /**
     * Frees the arena until [bytes] more fit, by dropping the tables of objects other than the one at [depth]: empty
     * ones first, then the largest, spilled first. Returns [ROOM]; [SPILL_OWN] when the table at [depth] is the
     * largest and must be spilled instead; or [DUPLICATE] when merging a spilled object's runs found a repeated key.
     */
    private fun makeRoom(depth: Int, bytes: Long): Int {
        while (arenaUsed + bytes > arenaBytes) {
            var victim = -1
            for (d in tables.indices) {
                val table = tables[d] ?: continue
                if (d == depth) continue
                if (tableUsed[d] == 0) {
                    victim = d
                    break
                }
                if (victim < 0 || table.size > tables[victim]!!.size) victim = d
            }
            if (victim < 0 || tableUsed[victim] > 0 && tables[depth].let { it != null && it.size > tables[victim]!!.size }) return SPILL_OWN
            if (tableUsed[victim] > 0 && !spillTable(victim)) return DUPLICATE
            arenaUsed -= tables[victim]!!.size * 8L
            tables[victim] = null
        }
        return ROOM
    }

    private fun allocate(depth: Int, slots: Int): LongArray {
        val table = LongArray(slots * 2)
        tables[depth] = table
        arenaUsed += table.size * 8L
        return table
    }

    private fun grow(depth: Int, old: LongArray): LongArray {
        val oldSlots = old.size / 2
        arenaUsed -= old.size * 8L
        val table = allocate(depth, oldSlots * 2)
        val mask = oldSlots * 2 - 1
        for (s in 0 until oldSlots) {
            val l = old[2 * s]
            val h = old[2 * s + 1]
            if (l == 0L && h == 0L) continue
            var slot = mix(l, h).toInt() and mask
            while (table[2 * slot] != 0L || table[2 * slot + 1] != 0L) slot = (slot + 1) and mask
            table[2 * slot] = l
            table[2 * slot + 1] = h
        }
        return table
    }

    /**
     * Sorts the table's hashes (unsigned, high then low), writes them as a run and empties the table. Returns false
     * when merging the object's runs found a repeated key.
     */
    private fun spillTable(depth: Int): Boolean {
        val table = tables[depth]!!
        val n = tableUsed[depth]
        if (sortA.size < 2 * n) {
            sortA = LongArray(2 * n)
            sortB = LongArray(2 * n)
        }
        var k = 0
        for (s in 0 until table.size / 2) {
            val l = table[2 * s]
            val h = table[2 * s + 1]
            if (l == 0L && h == 0L) continue
            sortA[2 * k] = h
            sortA[2 * k + 1] = l
            k++
        }
        check(k == n)
        val sorted = sortPairs(n)
        val run = spill.create()
        try {
            var used = 0
            for (i in 0 until n) {
                if (used == ioBuffer.size) {
                    run.write(ioBuffer, 0, used)
                    used = 0
                }
                BigEndian.putLong(ioBuffer, used, sorted[2 * i])
                BigEndian.putLong(ioBuffer, used + 8, sorted[2 * i + 1])
                used += 16
            }
            run.write(ioBuffer, 0, used)
            run.finishWriting()
        } catch (e: Throwable) {
            run.delete()
            throw e
        }
        val depthRuns = runs[depth] ?: ArrayList<Run>().also { runs[depth] = it }
        depthRuns.add(Run(run, 0))
        table.fill(0L)
        tableUsed[depth] = 0
        // Levels never increase along the list, and a level holds fewer than MERGE_FAN_IN runs between merges.
        while (depthRuns.size >= MERGE_FAN_IN && depthRuns[depthRuns.size - MERGE_FAN_IN].level == depthRuns.last().level) {
            if (!mergeTail(depthRuns, MERGE_FAN_IN)) return false
        }
        return true
    }

    /** Merges the last [count] runs of [depthRuns] into one, a level up; false when two entries are equal. */
    private fun mergeTail(depthRuns: ArrayList<Run>, count: Int): Boolean {
        val inputs = depthRuns.subList(depthRuns.size - count, depthRuns.size)
        val level = inputs[0].level + 1
        val out = spill.create()
        try {
            if (!merge(inputs, out)) {
                out.delete()
                return false
            }
            out.finishWriting()
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        inputs.forEach { it.file.delete() }
        inputs.clear()
        depthRuns.add(Run(out, level))
        return true
    }

    /** Bottom-up merge sort of `n` (high, low) pairs in [sortA]; returns the array that holds the result. */
    private fun sortPairs(n: Int): LongArray {
        var src = sortA
        var dst = sortB
        var width = 1
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = minOf(lo + width, n)
                val hi = minOf(lo + 2 * width, n)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) {
                    if (comparePair(src[2 * i], src[2 * i + 1], src[2 * j], src[2 * j + 1]) <= 0) {
                        dst[2 * k] = src[2 * i]; dst[2 * k + 1] = src[2 * i + 1]; i++
                    } else {
                        dst[2 * k] = src[2 * j]; dst[2 * k + 1] = src[2 * j + 1]; j++
                    }
                    k++
                }
                while (i < mid) { dst[2 * k] = src[2 * i]; dst[2 * k + 1] = src[2 * i + 1]; i++; k++ }
                while (j < hi) { dst[2 * k] = src[2 * j]; dst[2 * k + 1] = src[2 * j + 1]; j++; k++ }
                lo = hi
            }
            val t = src
            src = dst
            dst = t
            width *= 2
        }
        return src
    }

    /** Checks a closing object's runs; false when two entries are equal. */
    private fun mergeCheck(depthRuns: ArrayList<Run>): Boolean {
        // The smallest runs are at the end; merge them until one merge can read the rest.
        while (depthRuns.size > MERGE_FAN_IN) {
            if (!mergeTail(depthRuns, minOf(MERGE_FAN_IN, depthRuns.size - MERGE_FAN_IN + 1))) return false
        }
        return merge(depthRuns, null)
    }

    /** K-way merge of sorted runs into [out], if any; false when two entries are equal. */
    private fun merge(inputs: List<Run>, out: SpillRun?): Boolean {
        val k = inputs.size
        peakMergeWidth = maxOf(peakMergeWidth, k)
        val buffers = Array(k) { ByteArray(MERGE_BUFFER_BYTES) }
        val positions = IntArray(k)
        val limits = IntArray(k)
        val highs = LongArray(k)
        val lows = LongArray(k)
        val heap = IntArray(k)
        var heapSize = 0

        fun advance(r: Int): Boolean {
            if (positions[r] == limits[r]) {
                val n = readFully(inputs[r].file, buffers[r])
                if (n == 0) return false
                check(n % 16 == 0) { "spilled run isn't made of 16-byte entries" }
                positions[r] = 0
                limits[r] = n
            }
            highs[r] = BigEndian.getLong(buffers[r], positions[r])
            lows[r] = BigEndian.getLong(buffers[r], positions[r] + 8)
            positions[r] += 16
            return true
        }

        fun less(a: Int, b: Int) = comparePair(highs[a], lows[a], highs[b], lows[b]) < 0

        fun siftDown(start: Int) {
            var i = start
            while (true) {
                val l = 2 * i + 1
                if (l >= heapSize) return
                var m = l
                if (l + 1 < heapSize && less(heap[l + 1], heap[l])) m = l + 1
                if (!less(heap[m], heap[i])) return
                val t = heap[i]; heap[i] = heap[m]; heap[m] = t
                i = m
            }
        }

        for (r in 0 until k) if (advance(r)) heap[heapSize++] = r
        for (i in heapSize / 2 - 1 downTo 0) siftDown(i)
        var first = true
        var lastHigh = 0L
        var lastLow = 0L
        var used = 0
        while (heapSize > 0) {
            val r = heap[0]
            if (!first && highs[r] == lastHigh && lows[r] == lastLow) return false
            first = false
            lastHigh = highs[r]
            lastLow = lows[r]
            if (out != null) {
                if (used == ioBuffer.size) {
                    out.write(ioBuffer, 0, used)
                    used = 0
                }
                BigEndian.putLong(ioBuffer, used, lastHigh)
                BigEndian.putLong(ioBuffer, used + 8, lastLow)
                used += 16
            }
            if (!advance(r)) heap[0] = heap[--heapSize]
            siftDown(0)
        }
        if (out != null && used > 0) out.write(ioBuffer, 0, used)
        return true
    }

    private fun readFully(run: SpillRun, buffer: ByteArray): Int {
        var n = 0
        while (n < buffer.size) {
            val read = run.read(buffer, n, buffer.size - n)
            if (read < 0) break
            n += read
        }
        return n
    }

    private fun releaseTable(depth: Int) {
        val table = tables[depth] ?: return
        arenaUsed -= table.size * 8L
        tableUsed[depth] = 0
        if (table.size > KEEP_TABLE_SLOTS * 2) {
            tables[depth] = null
        } else {
            table.fill(0L)
            arenaUsed += table.size * 8L
        }
    }

    private fun ensureDepth(depth: Int) {
        if (depth < keyCount.size) return
        val n = maxOf(depth + 1, keyCount.size * 2)
        keyCount = keyCount.copyOf(n)
        hashed = hashed.copyOf(n)
        keyOffsets = keyOffsets.copyOf(n * EXACT_KEYS)
        keyLengths = keyLengths.copyOf(n * EXACT_KEYS)
        tables = tables.copyOf(n)
        tableUsed = tableUsed.copyOf(n)
        runs = runs.copyOf(n)
    }

    /** A sorted run; [level] is 0 for a spilled table and one more than its inputs' for a merge. */
    private class Run(val file: SpillRun, val level: Int)

    internal companion object {
        /** The most runs one merge reads, so its buffers take at most this many [MERGE_BUFFER_BYTES]. */
        const val MERGE_FAN_IN = 64
        const val MERGE_BUFFER_BYTES = 4096

        private const val EXACT_KEYS = 16
        private const val MIN_TABLE_SLOTS = 64
        private const val KEEP_TABLE_SLOTS = 256
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val ROOM = 0
        private const val SPILL_OWN = 1
        private const val DUPLICATE = 2

        private fun mix(low: Long, high: Long): Long = low xor (high * -0x61c8864680b583ebL)

        private fun comparePair(aHigh: Long, aLow: Long, bHigh: Long, bLow: Long): Int {
            val c = java.lang.Long.compareUnsigned(aHigh, bHigh)
            return if (c != 0) c else java.lang.Long.compareUnsigned(aLow, bLow)
        }
    }
}
