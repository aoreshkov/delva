package app.oreshkov.delva.internal.term

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteInput
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes

/**
 * Posting entries of one sorted run, in `containerPre` order:
 * ```
 * entry      := containerPreDelta:varint                 from the previous entry; the first is absolute
 *             | memberDelta << 2 | sameHash << 1 | inArray :varint   memberPre − containerPre, at least 1
 *             | [pathHash:u16le]                         path hash of the container's parent (SPEC.md §15), absent
 *                                                      when sameHash: then it's the previous entry's in the run
 *             | fragLocator
 * fragLocator := (containerPre − fragmentPre) << 2 | kind :varint | offsetInFragment:varint     kind PART, SUBTREE
 *             | 0 << 2 | HEADER :varint | 0:varint                                            fragmentPre = containerPre
 *             | 3:varint                                                                       UNRESOLVED
 * ```
 * Within a run `containerPre` strictly increases: duplicates were collapsed by the sorter.
 */
internal object PostingCodec {
    /** Appends the entries of `run[from, to)`, which share one term. */
    fun writeEntries(out: GrowableBytes, run: SortedTermRun, from: Int, to: Int) {
        var previous = 0L
        for (i in from until to) {
            val containerPre = run.containerPre(i)
            writeEntry(out, if (i == from) containerPre else containerPre - previous, containerPre, run.memberPre(i), run.inArray(i), run.locatorKind(i), run.fragmentPre(i), run.locatorOffset(i), run.pathHash(i), if (i == from) -1 else run.pathHash(i - 1))
            previous = containerPre
        }
    }

    fun writeEntry(out: GrowableBytes, containerPreDelta: Long, containerPre: Long, memberPre: Long, inArray: Boolean, locatorKind: Int, fragmentPre: Long, locatorOffset: Int, pathHash: Int, previousPathHash: Int) {
        require(containerPreDelta >= 0 && memberPre > containerPre)
        out.writeVarLong(containerPreDelta)
        val sameHash = pathHash == previousPathHash
        out.writeVarLong(((memberPre - containerPre) shl 2) or (if (sameHash) 2L else 0L) or (if (inArray) 1L else 0L))
        if (!sameHash) out.writeShortLE(pathHash)
        when (locatorKind) {
            PostingLocator.UNRESOLVED -> out.writeVarLong(PostingLocator.UNRESOLVED.toLong())
            else -> {
                require(fragmentPre in 0..containerPre && locatorOffset >= 0)
                out.writeVarLong(((containerPre - fragmentPre) shl 2) or locatorKind.toLong())
                out.writeVarLong(locatorOffset.toLong())
            }
        }
    }
}

/**
 * Decodes the entries of one run ([PostingCodec]), validating each: deltas and member offsets in range, a
 * HEADER locator naming the container itself, UNRESOLVED only for in-array postings. The run is a byte range, or a
 * [ByteInput] slice of a segment read lazily; with [releaseAfterEntry] the input lets go of its block or chunk after
 * every entry, so a merge over thousands of runs holds no block per run. Reusable; not thread-safe.
 */
internal class PostingEntryReader {
    private val reader = ByteReader(ByteArray(0))
    private var input: ByteInput? = null
    private var releaseAfterEntry = false
    private var remaining = 0
    private var first = true

    var containerPre: Long = 0
        private set
    var memberPre: Long = 0
        private set
    var inArray: Boolean = false
        private set
    var locatorKind: Int = 0
        private set
    var fragmentPre: Long = 0
        private set
    var locatorOffset: Int = 0
        private set
    var pathHash: Int = 0
        private set

    fun reset(bytes: ByteArray, offset: Int, length: Int, count: Int, source: String = "posting run") {
        require(count >= 0)
        reader.reset(bytes, offset, length, source)
        input = null
        remaining = count
        first = true
        containerPre = 0
    }

    /** Reads the run from [input], which the reader uses until the next reset. */
    fun reset(input: ByteInput, count: Int, releaseAfterEntry: Boolean) {
        require(count >= 0)
        this.input = input
        this.releaseAfterEntry = releaseAfterEntry
        remaining = count
        first = true
        containerPre = 0
    }

    /** Decodes the next entry; false after the last one, when the bytes must also end. */
    fun next(): Boolean {
        val value = input
        if (remaining == 0) {
            val left = value?.remaining ?: reader.remaining.toLong()
            if (left != 0L) corrupt("$left bytes after the last entry")
            return false
        }
        remaining--
        val delta = readVarLong(value)
        if (delta < 0 || (!first && delta == 0L) || delta > Long.MAX_VALUE - containerPre) corrupt("container delta $delta")
        containerPre = if (first) delta else containerPre + delta
        val firstEntry = first
        first = false
        val member = readVarLong(value)
        val memberDelta = member ushr 2
        if (memberDelta < 1 || memberDelta > Long.MAX_VALUE - containerPre) corrupt("member delta $memberDelta")
        memberPre = containerPre + memberDelta
        inArray = member and 1L != 0L
        if (member and 2L == 0L) {
            pathHash = if (value != null) value.readByte() or (value.readByte() shl 8) else reader.readShortLE()
        } else if (firstEntry) {
            corrupt("the first entry repeats a path hash")
        }
        val locator = readVarLong(value)
        locatorKind = (locator and 3L).toInt()
        val fragmentDelta = locator ushr 2
        when (locatorKind) {
            PostingLocator.UNRESOLVED -> {
                if (fragmentDelta != 0L || !inArray) corrupt("UNRESOLVED locator ${java.lang.Long.toUnsignedString(locator)} (in array: $inArray)")
                fragmentPre = 0
                locatorOffset = 0
            }
            else -> {
                if (fragmentDelta > containerPre) corrupt("fragment delta $fragmentDelta beyond container $containerPre")
                fragmentPre = containerPre - fragmentDelta
                locatorOffset = if (value != null) value.readVarInt() else reader.readVarInt()
                if (locatorKind == PostingLocator.HEADER && (fragmentDelta != 0L || locatorOffset != 0)) corrupt("HEADER locator with delta $fragmentDelta, offset $locatorOffset")
            }
        }
        if (releaseAfterEntry && value != null) value.release()
        return true
    }

    private fun readVarLong(value: ByteInput?): Long = if (value != null) value.readVarLong() else reader.readVarLong()

    private fun corrupt(detail: String): Nothing {
        val value = input
        if (value != null) value.corrupt("posting run@${value.position}: $detail")
        throw CorruptionException("posting run@${reader.position}", detail)
    }
}
