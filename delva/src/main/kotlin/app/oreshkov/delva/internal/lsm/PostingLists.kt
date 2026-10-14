package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.readFully

/**
 * A term's posting list in a segment, the value of the term's entry:
 * ```
 * postingList := docFreq:varint | postingCount:varint | blockCount:varint
 *              | { lastDocIdDelta:varint blockBytes:varint }* (only when blockCount > 1) | block*
 * block       := docGroup*                                   at most 128 groups
 * docGroup    := docIdDelta:varint | occurrences:varint | groupBytes:varint | entries[groupBytes]
 * ```
 * - `docIdDelta` is from the previous group's docId (0 before the first); 0 means another sorted run of the same
 *   document. A document's runs may continue in the next block.
 * - `lastDocIdDelta` is from the previous block's last docId (0 before the first), so a reader seeks with the skip
 *   table and starts decoding at any block.
 * - `entries` are one sorted run in the [app.oreshkov.delva.internal.term.PostingCodec] format.
 *
 * Builds one list from runs in `(docId, runSeq)` order. The body (the blocks) is kept in memory, or, when a [spill]
 * file is given, moved to it whenever more than [memoryBytes] accumulate, so a merged list of any length (compaction)
 * holds at most that much besides its skip table. Reusable through [reset]; not thread-safe.
 */
internal class PostingListWriter(private val memoryBytes: Int = Int.MAX_VALUE, private val spill: (() -> FileIo)? = null) {
    private val body = GrowableBytes(1024)
    private var spillFile: FileIo? = null

    /** Body bytes moved to the spill file, which hold `[0, spilled)` of the body. */
    private var spilled = 0L
    private var blockEnds = LongArray(16)
    private var blockLastDocIds = LongArray(16)
    private var blockCount = 0
    private var groupsInBlock = 0
    private var previousDocId = 0L

    var docFreq: Long = 0
        private set
    var postingCount: Long = 0
        private set

    val bodyBytes: Long get() = spilled + body.size

    /** The first and the last document of the list; valid once a run was added. */
    var firstDocId: Long = 0
        private set
    val lastDocId: Long get() = previousDocId

    fun reset() {
        body.clear()
        spilled = 0
        blockCount = 0
        groupsInBlock = 0
        previousDocId = 0
        docFreq = 0
        postingCount = 0
    }

    fun addRun(docId: Long, occurrences: Int, entries: ByteArray, offset: Int, length: Int) {
        require(docId >= previousDocId && occurrences >= 1)
        if (groupsInBlock == GROUPS_PER_BLOCK) endBlock()
        if (docFreq == 0L) firstDocId = docId
        if (docId != previousDocId || docFreq == 0L) docFreq++
        body.writeVarLong(docId - previousDocId)
        body.writeVarLong(occurrences.toLong())
        body.writeVarLong(length.toLong())
        body.writeBytes(entries, offset, length)
        previousDocId = docId
        postingCount += occurrences
        groupsInBlock++
        if (body.size > memoryBytes && spill != null) {
            val file = spillFile ?: spill.invoke().also { spillFile = it }
            file.write(spilled, body.array, 0, body.size)
            spilled += body.size
            body.clear()
        }
    }

    /** Appends the list to [out]; only for a list that was never spilled. */
    fun finish(out: GrowableBytes) {
        check(spilled == 0L) { "the list was spilled" }
        finishHeader(out)
        out.writeBytes(body.array, 0, body.size)
    }

    /** Ends the list and appends everything before the body to [out]: the list is [out]'s new bytes, then [copyBody]. */
    fun finishHeader(out: GrowableBytes) {
        check(groupsInBlock > 0) { "empty posting list" }
        endBlock()
        out.writeVarLong(docFreq)
        out.writeVarLong(postingCount)
        out.writeVarLong(blockCount.toLong())
        if (blockCount > 1) {
            var last = 0L
            var start = 0L
            for (b in 0 until blockCount) {
                out.writeVarLong(blockLastDocIds[b] - last)
                out.writeVarLong(blockEnds[b] - start)
                last = blockLastDocIds[b]
                start = blockEnds[b]
            }
        }
    }

    /** Hands the body to [sink] in pieces, after [finishHeader]. */
    fun copyBody(sink: (ByteArray, Int, Int) -> Unit) {
        if (spilled > 0) {
            val file = spillFile!!
            val buffer = ByteArray(COPY_BYTES)
            var at = 0L
            while (at < spilled) {
                val n = minOf(COPY_BYTES.toLong(), spilled - at).toInt()
                file.readFully(at, buffer, 0, n)
                sink(buffer, 0, n)
                at += n
            }
        }
        sink(body.array, 0, body.size)
    }

    private fun endBlock() {
        if (blockCount == blockEnds.size) {
            blockEnds = blockEnds.copyOf(blockCount * 2)
            blockLastDocIds = blockLastDocIds.copyOf(blockCount * 2)
        }
        blockEnds[blockCount] = bodyBytes
        blockLastDocIds[blockCount] = previousDocId
        blockCount++
        groupsInBlock = 0
    }

    companion object {
        const val GROUPS_PER_BLOCK: Int = 128
        private const val COPY_BYTES = 64 * 1024
    }
}

/**
 * The header of a [PostingListWriter] list, validated: its counts and where each block starts and ends, with its last
 * docId ([Long.MAX_VALUE] for the only block of a list without a skip table). Reusable; not thread-safe.
 */
internal class PostingListLayout {
    var docFreq: Long = 0
        private set
    var postingCount: Long = 0
        private set
    var blockCount: Int = 0
        private set
    var blockStarts = LongArray(4)
        private set
    var blockEnds = LongArray(4)
        private set
    var lastDocIds = LongArray(4)
        private set

    /** Reads the header from [input], leaving it at the first block. */
    fun read(input: ValueInput) {
        docFreq = input.readVarLong()
        postingCount = input.readVarLong()
        blockCount = input.readVarInt((input.remaining / 3).toInt().coerceAtLeast(1))
        if (blockCount < 1 || docFreq < 1 || postingCount < docFreq) input.corrupt("posting list of $blockCount blocks, $docFreq documents, $postingCount postings")
        if (blockStarts.size < blockCount) {
            blockStarts = LongArray(blockCount)
            blockEnds = LongArray(blockCount)
            lastDocIds = LongArray(blockCount)
        }
        if (blockCount == 1) {
            blockStarts[0] = input.position
            blockEnds[0] = input.length
            lastDocIds[0] = Long.MAX_VALUE
        } else {
            var last = 0L
            var length = 0L
            for (b in 0 until blockCount) {
                val delta = input.readVarLong()
                if (delta < 0 || last > Long.MAX_VALUE - delta) input.corrupt("skip table docId delta $delta")
                last += delta
                lastDocIds[b] = last
                val bytes = input.readVarLong()
                if (bytes <= 0 || bytes > input.length) input.corrupt("posting block of $bytes bytes")
                blockEnds[b] = bytes
                length += bytes
            }
            var start = input.position
            if (start + length != input.length) input.corrupt("posting blocks of $length bytes in a list of ${input.length}")
            for (b in 0 until blockCount) {
                val bytes = blockEnds[b]
                blockStarts[b] = start
                start += bytes
                blockEnds[b] = start
            }
        }
    }

    /** The docId block [b]'s first delta starts from. */
    fun baseDocId(b: Int): Long = if (b == 0) 0L else lastDocIds[b - 1]
}

/**
 * Reads a [PostingListWriter] list group by group, validating as it goes: [seek] uses the skip table, [next]
 * moves over a group without reading its entries (O(1) per group). Reusable; not thread-safe.
 */
internal class PostingListCursor {
    private lateinit var input: ValueInput
    private val layout = PostingListLayout()
    private var blockCount = 0
    private var blockEnds = LongArray(0)
    private var lastDocIds = LongArray(0)
    private var block = 0
    private var entriesStart = 0L
    private var previousDocId = 0L

    val docFreq: Long get() = layout.docFreq
    val postingCount: Long get() = layout.postingCount

    /** DocId of the current group, or [Long.MAX_VALUE] when the list is exhausted. */
    var docId: Long = Long.MAX_VALUE
        private set
    var occurrences: Int = 0
        private set
    var groupBytes: Int = 0
        private set

    fun open(input: ValueInput) {
        this.input = input
        layout.read(input)
        blockCount = layout.blockCount
        blockEnds = layout.blockEnds
        lastDocIds = layout.lastDocIds
        startBlock(0)
    }

    /** Moves to the first group with a docId ≥ [fromDocId]. Never moves backwards. */
    fun seek(fromDocId: Long) {
        if (docId >= fromDocId) return
        if (blockCount > 1) {
            var lo = block
            var hi = blockCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (lastDocIds[mid] < fromDocId) lo = mid + 1 else hi = mid
            }
            if (lo == blockCount) {
                docId = Long.MAX_VALUE
                return
            }
            if (lo > block) startBlock(lo)
        }
        while (docId < fromDocId) next()
    }

    /** Moves to the next group. */
    fun next() {
        if (docId == Long.MAX_VALUE) return
        input.position = entriesStart + groupBytes
        if (input.position == blockEnds[block]) {
            if (blockCount > 1 && docId != lastDocIds[block]) input.corrupt("block $block ends at document $docId, skip table says ${lastDocIds[block]}")
            if (block + 1 == blockCount) {
                docId = Long.MAX_VALUE
                return
            }
            startBlock(block + 1)
            return
        }
        readGroup()
    }

    /** The current group's entries. */
    fun readEntries(): ByteArray {
        check(docId != Long.MAX_VALUE)
        input.position = entriesStart
        val bytes = ByteArray(groupBytes)
        input.readBytes(bytes, 0, groupBytes)
        return bytes
    }

    /** Opens [into] on the current group's entries, without reading them. */
    fun openEntries(into: ValueInput) {
        check(docId != Long.MAX_VALUE)
        into.openSlice(input, entriesStart, groupBytes.toLong())
    }

    /** Drops the block or chunk the list holds; the next move fetches it again. */
    fun release() = input.release()

    private fun startBlock(b: Int) {
        block = b
        previousDocId = layout.baseDocId(b)
        input.position = layout.blockStarts[b]
        if (input.position >= blockEnds[b]) input.corrupt("empty posting block $b")
        readGroup()
    }

    private fun readGroup() {
        val delta = input.readVarLong()
        if (delta < 0 || previousDocId > Long.MAX_VALUE - 1 - delta) input.corrupt("docId delta $delta")
        val id = previousDocId + delta
        if (blockCount > 1 && id > lastDocIds[block]) input.corrupt("document $id in block $block ending at ${lastDocIds[block]}")
        occurrences = input.readVarInt()
        groupBytes = input.readVarInt()
        entriesStart = input.position
        if (occurrences < 1 || groupBytes < 3L * occurrences || groupBytes > blockEnds[block] - entriesStart) {
            input.corrupt("group of $occurrences postings in $groupBytes bytes")
        }
        docId = id
        previousDocId = id
    }
}

/**
 * Reads a [PostingListWriter] list backwards, group by group, with the checks of [PostingListCursor]. Groups are
 * delta-encoded forward, so it decodes one block at a time into a buffer, found through the skip table, and hands its
 * groups out last first; [previous] loads the block before when the buffer runs out. Memory is one block's groups (at
 * most 128). Reusable; not thread-safe.
 */
internal class ReversePostingListCursor {
    private lateinit var input: ValueInput
    private val layout = PostingListLayout()

    /** The block in the buffer. */
    private var block = 0
    private var count = 0
    private var at = 0
    private var docIds = LongArray(GROUPS)
    private var starts = LongArray(GROUPS)
    private var lengths = IntArray(GROUPS)
    private var occurrenceCounts = IntArray(GROUPS)

    val docFreq: Long get() = layout.docFreq
    val postingCount: Long get() = layout.postingCount

    /** DocId of the current group, or -1 when the list is exhausted. */
    var docId: Long = -1
        private set
    val occurrences: Int get() = occurrenceCounts[at]
    val groupBytes: Int get() = lengths[at]

    /** Opens the list at its last group. */
    fun open(input: ValueInput) {
        this.input = input
        layout.read(input)
        load(layout.blockCount - 1)
        position(count - 1)
    }

    /** Moves to the last group with a docId ≤ [toDocId]. Never moves forwards. */
    fun seek(toDocId: Long) {
        if (docId <= toDocId) return
        // The first block up to the current one that ends above the bound: every block before it ends at or below the
        // bound, so when this one has no group at or below it, the last group of the block before is the answer. (A
        // document can continue into the next block, so a block ending exactly at the bound isn't the one.)
        val lastDocIds = layout.lastDocIds
        var lo = 0
        var hi = block
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lastDocIds[mid] <= toDocId) lo = mid + 1 else hi = mid
        }
        if (lo != block) load(lo)
        var i = at
        while (i >= 0 && docIds[i] > toDocId) i--
        when {
            i >= 0 -> position(i)
            block == 0 -> docId = -1
            else -> {
                load(block - 1)
                position(count - 1)
            }
        }
    }

    /** Moves to the previous group. */
    fun previous() {
        when {
            docId < 0 -> return
            at > 0 -> position(at - 1)
            block == 0 -> docId = -1
            else -> {
                load(block - 1)
                position(count - 1)
            }
        }
    }

    /** Opens [into] on the current group's entries, without reading them. */
    fun openEntries(into: ValueInput) {
        check(docId >= 0)
        into.openSlice(input, starts[at], lengths[at].toLong())
    }

    /** Drops the block or chunk the list holds; the next move fetches it again. */
    fun release() = input.release()

    private fun position(i: Int) {
        at = i
        docId = docIds[i]
    }

    /** Decodes block [b]'s groups into the buffer, positioned after its last group. */
    private fun load(b: Int) {
        val blockCount = layout.blockCount
        val end = layout.blockEnds[b]
        val last = layout.lastDocIds[b]
        var previousDocId = layout.baseDocId(b)
        input.position = layout.blockStarts[b]
        if (input.position >= end) input.corrupt("empty posting block $b")
        var n = 0
        while (input.position < end) {
            val delta = input.readVarLong()
            if (delta < 0 || previousDocId > Long.MAX_VALUE - 1 - delta) input.corrupt("docId delta $delta")
            val id = previousDocId + delta
            if (blockCount > 1 && id > last) input.corrupt("document $id in block $b ending at $last")
            val occurrences = input.readVarInt()
            val bytes = input.readVarInt()
            val start = input.position
            if (occurrences < 1 || bytes < 3L * occurrences || bytes > end - start) input.corrupt("group of $occurrences postings in $bytes bytes")
            if (n == docIds.size) grow()
            docIds[n] = id
            starts[n] = start
            lengths[n] = bytes
            occurrenceCounts[n] = occurrences
            n++
            previousDocId = id
            input.position = start + bytes
        }
        if (blockCount > 1 && previousDocId != last) input.corrupt("block $b ends at document $previousDocId, skip table says $last")
        block = b
        count = n
        at = n - 1
    }

    private fun grow() {
        val size = docIds.size * 2
        docIds = docIds.copyOf(size)
        starts = starts.copyOf(size)
        lengths = lengths.copyOf(size)
        occurrenceCounts = occurrenceCounts.copyOf(size)
    }

    private companion object {
        const val GROUPS = PostingListWriter.GROUPS_PER_BLOCK
    }
}

/**
 * The sorted runs of one term in one document, gathered from all sources. A run is either a byte range (a
 * memtable run) or a lazily read slice of a segment's posting list ([inputs]), so gathering copies no entries.
 * Reusable; not thread-safe.
 */
internal class DocRuns {
    var count: Int = 0
        private set
    var bytes = arrayOfNulls<ByteArray>(4)
        private set
    var offsets = IntArray(4)
        private set
    var lengths = IntArray(4)
        private set
    var entryCounts = IntArray(4)
        private set

    /** Segment runs: the input of run `i` when `bytes[i]` is null. Inputs are pooled across [clear]s. */
    var inputs = arrayOfNulls<ValueInput>(4)
        private set

    fun clear() {
        for (i in 0 until count) {
            bytes[i] = null
            inputs[i]?.release()
        }
        count = 0
    }

    /** Moves the segment runs back to their first entry, so the runs can be merged again. */
    fun rewind() {
        for (i in 0 until count) if (bytes[i] == null) inputs[i]!!.position = 0
    }

    fun add(entries: ByteArray, offset: Int, length: Int, entryCount: Int) {
        val i = slot()
        bytes[i] = entries
        offsets[i] = offset
        lengths[i] = length
        entryCounts[i] = entryCount
    }

    /** Adds the current group of [list] as a run read from the segment. */
    fun add(list: PostingListCursor) {
        val i = slot()
        bytes[i] = null
        val input = inputs[i] ?: ValueInput().also { inputs[i] = it }
        list.openEntries(input)
        entryCounts[i] = list.occurrences
    }

    /** Adds the current group of [list] as a run read from the segment. */
    fun add(list: ReversePostingListCursor) {
        val i = slot()
        bytes[i] = null
        val input = inputs[i] ?: ValueInput().also { inputs[i] = it }
        list.openEntries(input)
        entryCounts[i] = list.occurrences
    }

    private fun slot(): Int {
        if (count == bytes.size) {
            bytes = bytes.copyOf(count * 2)
            offsets = offsets.copyOf(count * 2)
            lengths = lengths.copyOf(count * 2)
            entryCounts = entryCounts.copyOf(count * 2)
            inputs = inputs.copyOf(count * 2)
        }
        return count++
    }
}

/**
 * The documents of one term in one source (a memtable or a segment), in docId order: ascending, or descending for a
 * source opened descending. Not thread-safe.
 */
internal interface TermSource {
    /** The current document, or when the source is exhausted [Long.MAX_VALUE] (ascending) or -1 (descending). */
    val docId: Long

    /** Moves to the first document at or past [target] in the source's order: ≥ it ascending, ≤ it descending. */
    fun seek(target: Long)

    /** Adds the current document's runs to [into], moves to the next document and returns the runs added. */
    fun takeRuns(into: DocRuns): Int

    /** Moves to the next document; returns how many runs the skipped one had. */
    fun skipDocument(): Int

    /** Drops cached bytes that can be read again; called between documents when many sources are open. */
    fun release() {}
}

/** A term's runs in a posting memtable, from document [from] on in the order [descending] picks. */
internal class MemtableTermSource(memtable: PostingMemtable, term: ByteArray, from: Long, private val descending: Boolean = false) : TermSource {
    private val runs = if (descending) memtable.runsDescending(term, from) else memtable.runs(term, from)
    private var head: PostingRun? = if (runs.hasNext()) runs.next() else null

    override val docId: Long get() = head?.docId ?: if (descending) -1L else Long.MAX_VALUE

    override fun seek(target: Long) {
        if (descending) {
            while (docId > target) skipDocument()
        } else {
            while (docId < target) skipDocument()
        }
    }

    override fun takeRuns(into: DocRuns): Int = advance(into)

    override fun skipDocument(): Int = advance(null)

    private fun advance(into: DocRuns?): Int {
        val first = head ?: return 0
        var n = 0
        var run: PostingRun? = first
        while (run != null && run.docId == first.docId) {
            into?.add(run.entries, 0, run.entries.size, run.count)
            n++
            run = if (runs.hasNext()) runs.next() else null
        }
        head = run
        return n
    }
}

/** A term's posting list in a segment. */
internal class SegmentTermSource private constructor(private val list: PostingListCursor) : TermSource {
    override val docId: Long get() = list.docId

    /** Postings of the term in this segment, from the posting list header. */
    val postingCount: Long get() = list.postingCount

    /** Documents with the term in this segment, from the posting list header. */
    val docFreq: Long get() = list.docFreq

    override fun seek(target: Long) = list.seek(target)

    override fun takeRuns(into: DocRuns): Int = advance(into)

    override fun skipDocument(): Int = advance(null)

    private fun advance(into: DocRuns?): Int {
        val doc = list.docId
        if (doc == Long.MAX_VALUE) return 0
        var n = 0
        while (list.docId == doc) {
            into?.add(list)
            n++
            list.next()
        }
        return n
    }

    override fun release() = list.release()

    companion object {
        /** The posting list of the entry [cursor] is on, from its first document. */
        fun at(cursor: SegmentCursor): SegmentTermSource {
            val input = ValueInput()
            cursor.openValue(input)
            val list = PostingListCursor()
            list.open(input)
            return SegmentTermSource(list)
        }

        /** The term's source in [segment] from [fromDocId] on, or null when the segment doesn't have the term. */
        fun open(segment: SegmentReader, term: ByteArray, termHash: Long, fromDocId: Long): SegmentTermSource? {
            if (segment.stats.maxDocId < fromDocId) return null
            val input = openList(segment, term, termHash) ?: return null
            val list = PostingListCursor()
            list.open(input)
            list.seek(fromDocId)
            return SegmentTermSource(list)
        }

        /** An input on the term's posting list in [segment], or null when the segment doesn't have the term. */
        internal fun openList(segment: SegmentReader, term: ByteArray, termHash: Long): ValueInput? {
            if (!segment.mayContain(termHash)) return null
            val cursor = segment.cursor()
            if (!cursor.seekCeiling(term) || cursor.key.size != term.size || !java.util.Arrays.equals(cursor.key.array, 0, term.size, term, 0, term.size)) return null
            val input = ValueInput()
            cursor.openValue(input)
            return input
        }
    }
}

/** A term's posting list in a segment, read backwards. */
internal class ReverseSegmentTermSource private constructor(private val list: ReversePostingListCursor) : TermSource {
    override val docId: Long get() = list.docId

    /** Documents with the term in this segment, from the posting list header. */
    val docFreq: Long get() = list.docFreq

    override fun seek(target: Long) = list.seek(target)

    override fun takeRuns(into: DocRuns): Int = advance(into)

    override fun skipDocument(): Int = advance(null)

    private fun advance(into: DocRuns?): Int {
        val doc = list.docId
        if (doc < 0) return 0
        var n = 0
        while (list.docId == doc) {
            into?.add(list)
            n++
            list.previous()
        }
        return n
    }

    override fun release() = list.release()

    companion object {
        /** The term's source in [segment] from [toDocId] down, or null when the segment doesn't have the term. */
        fun open(segment: SegmentReader, term: ByteArray, termHash: Long, toDocId: Long): ReverseSegmentTermSource? {
            if (segment.stats.minDocId > toDocId) return null
            val input = SegmentTermSource.openList(segment, term, termHash) ?: return null
            val list = ReversePostingListCursor()
            list.open(input)
            list.seek(toDocId)
            return ReverseSegmentTermSource(list)
        }
    }
}

/**
 * Merges the [TermSource]s of one term by docId with a small heap: each step exposes the first [docId] in the order
 * of the sources — the smallest, or with [descending] sources the largest. The docId is [exhausted] after the last.
 * Not thread-safe.
 */
internal class TermSources(private val sources: Array<TermSource>, private val descending: Boolean = false) {
    private val heap = IntArray(sources.size)
    private var size = 0

    /** With many sources, each drops its block after moving, so open sources don't pin a block each. */
    private val releasing = sources.size > RELEASE_ABOVE_SOURCES

    /** The docId of an exhausted merge or source. */
    val exhausted: Long = if (descending) -1L else Long.MAX_VALUE

    init {
        for (i in sources.indices) {
            if (sources[i].docId != exhausted) push(i)
            if (releasing) sources[i].release()
        }
    }

    val docId: Long get() = if (size == 0) exhausted else sources[heap[0]].docId

    /** Moves every source to its first document at or past [target] in the merge's order; never moves back. */
    fun seek(target: Long) {
        if (!before(docId, target)) return
        size = 0
        for (i in sources.indices) {
            val source = sources[i]
            if (before(source.docId, target)) {
                source.seek(target)
                if (releasing) source.release()
            }
            if (source.docId != exhausted) push(i)
        }
    }

    /** Adds every source's runs of the current document to [into]; returns the runs added. */
    fun takeRuns(into: DocRuns): Int = advance(into)

    fun skipDocument(): Int = advance(null)

    /** Whether document [a] comes before [b] in the merge's order. */
    private fun before(a: Long, b: Long): Boolean = if (descending) a > b else a < b

    private fun advance(into: DocRuns?): Int {
        val doc = docId
        if (doc == exhausted) return 0
        var n = 0
        while (size > 0 && sources[heap[0]].docId == doc) {
            val i = heap[0]
            n += if (into != null) sources[i].takeRuns(into) else sources[i].skipDocument()
            if (releasing) sources[i].release()
            if (sources[i].docId == exhausted) {
                heap[0] = heap[--size]
            }
            if (size > 0) siftDown(0)
        }
        return n
    }

    private fun push(i: Int) {
        var k = size++
        heap[k] = i
        while (k > 0) {
            val parent = (k - 1) / 2
            if (!before(sources[heap[k]].docId, sources[heap[parent]].docId)) break
            val t = heap[k]
            heap[k] = heap[parent]
            heap[parent] = t
            k = parent
        }
    }

    private fun siftDown(start: Int) {
        var k = start
        while (true) {
            val left = 2 * k + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size && before(sources[heap[right]].docId, sources[heap[left]].docId)) right else left
            if (!before(sources[heap[child]].docId, sources[heap[k]].docId)) return
            val t = heap[k]
            heap[k] = heap[child]
            heap[child] = t
            k = child
        }
    }

    companion object {
        /** Open sources above which sources and runs drop their blocks between reads, as after many flushes. */
        const val RELEASE_ABOVE_SOURCES: Int = 16
    }
}
