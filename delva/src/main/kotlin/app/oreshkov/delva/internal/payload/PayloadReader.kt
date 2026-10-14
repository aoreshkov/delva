package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader

/**
 * Pull decoder for SUBTREE, PART and STRCHUNK payloads ([PayloadFormat]), validating structure as it reads:
 * every read is bounds-checked, counts are bounded by the bytes left, `sortPerm` must be a permutation ordered by
 * key bytes, each container's `size` must equal the `pre` numbers its content uses, and the key table must not repeat
 * a key. Every call of [next] consumes bytes or closes a container, so decoding always terminates. Problems throw
 * [CorruptionException].
 *
 * Content checks that don't affect decoding (UTF-8 of strings, number grammar) are [PayloadValidator]'s job.
 *
 * Views ([offset], [length], [keyOffset], [keyLength]) index into [payload]. Reusable; not thread-safe.
 */
internal class PayloadReader {
    private val reader = ByteReader(EMPTY)
    private var source = "payload"

    var payload: ByteArray = EMPTY
        private set
    private var payloadEnd = 0
    private var kind = FragmentKind.SUBTREE
    private var wholeBody = true
    private var finished = false
    private var nextPre = 0L

    // Key table: offsets from the payload start (keyBase), lengths and ranks; the reader's own arrays or a borrowed KeyTable's.
    var keyCount: Int = 0
        private set
    private var keyBase = 0
    private var keyTableEnd = 0
    private var ownOffsets = IntArray(16)
    private var ownLengths = IntArray(16)
    private var ownRanks = IntArray(16)
    private var keyOffsets = ownOffsets
    private var keyLengths = ownLengths
    private var ranks = ownRanks
    private var ranksValid = false
    private var rankScratch = IntArray(32)

    // Open containers; frame 0 is the virtual frame of the fragment body.
    private var frames = 0
    private var frameObject = BooleanArray(16)
    private var frameRemaining = LongArray(16)
    private var frameTotal = LongArray(16)
    private var frameStartPre = LongArray(16)
    private var frameSize = LongArray(16)
    private var frameMemberIndex = LongArray(16)

    /** Where a frame's `sortPerm` and member keyRefs live in [memberStack], or -1 without a `sortPerm`. */
    private var frameStackBase = IntArray(16)
    private var memberStack = IntArray(64)
    private var memberStackTop = 0
    private var stamps = IntArray(16)
    private var stampGeneration = 0

    // ---- current event ----

    var event: Int = END
        private set

    /** `pre` of the current value; for END_OBJECT / END_ARRAY, of the container that ends. */
    var pre: Long = 0
        private set

    /** Index in [payload] of the current value's tag byte. */
    var valueOffset: Int = 0
        private set

    /** Whether the current value is an object member; then [keyRef], [keyOffset] and [keyLength] describe its key. */
    var hasKey: Boolean = false
        private set
    var keyRef: Int = 0
        private set
    var keyOffset: Int = 0
        private set
    var keyLength: Int = 0
        private set

    /** Position of the current value in its container (member index or array index). */
    var memberIndex: Long = 0
        private set

    /** INT value. */
    var intValue: Long = 0
        private set

    /** NUM text, STR bytes, or a BIGSTR's prefix: `payload[offset, offset + length)`. */
    var offset: Int = 0
        private set
    var length: Int = 0
        private set

    /** START_OBJECT / START_ARRAY / SPLIT: member count. BIGSTR: chunk count. */
    var count: Long = 0
        private set

    /** Containers and REF / SPLIT: number of descendants. BIGSTR: total string bytes. */
    var size: Long = 0
        private set

    /** Stubs: canonical hash of the value stored elsewhere. */
    var hashLow: Long = 0
        private set
    var hashHigh: Long = 0
        private set

    /** REF: payload bytes of the SUBTREE fragment and every fragment reachable from its stubs. */
    var encodedBytes: Long = 0
        private set

    /** REF / SPLIT: [PayloadTag.OBJ] or [PayloadTag.ARR]. */
    var containerKind: Int = 0
        private set

    /** After opening a PART: its container kind and member count; 0 for other kinds. */
    var partKind: Int = 0
        private set
    var partMemberCount: Long = 0
        private set

    /** Open containers, not counting the fragment body. */
    val depth: Int get() = frames - 1

    fun keyOffsetOf(keyRef: Int): Int = keyBase + keyOffsets[keyRef]

    fun keyLengthOf(keyRef: Int): Int = keyLengths[keyRef]

    /**
     * Opens a whole SUBTREE, STRCHUNK or PART payload in `payload[offset, offset + length)`; [firstPre] is the fragment
     * key's `pre`. Decoding ends with [END] after the last value, and the payload must end there.
     */
    fun open(payload: ByteArray, offset: Int, length: Int, kind: Int, firstPre: Long, source: String = "payload") {
        require(kind == FragmentKind.SUBTREE || kind == FragmentKind.PART || kind == FragmentKind.STRCHUNK) { "kind $kind" }
        start(payload, offset, length, kind, firstPre, source)
        wholeBody = true
        when (kind) {
            FragmentKind.PART -> {
                val containerKind = reader.readByte()
                if (containerKind != PayloadTag.OBJ && containerKind != PayloadTag.ARR) corrupt(reader.position - 1, "PART container kind $containerKind")
                val isObject = containerKind == PayloadTag.OBJ
                val memberCount = reader.readVarLong()
                if (memberCount < 1 || memberCount > reader.remaining / (if (isObject) 2 else 1)) corrupt(reader.position, "PART member count $memberCount")
                val base = if (isObject && memberCount >= 2) readSortPerm(memberCount.toInt()) else -1
                push(isObject, memberCount, startPre = firstPre - 1, size = -1, base)
                partKind = containerKind
                partMemberCount = memberCount
            }
            else -> {
                if (kind == FragmentKind.STRCHUNK && keyCount != 0) corrupt(reader.position, "STRCHUNK with keys")
                push(isObject = false, count = 1, startPre = firstPre - 1, size = -1, stackBase = -1)
            }
        }
    }

    /**
     * Opens the single value at index [valueOffset] of a payload in `payload[offset, offset + length)`: the key
     * table is read from the payload start, then decoding starts at [valueOffset] with `pre` [pre] and ends with [END]
     * after that value. Bytes after it are not checked. With [keys], the key table isn't read: [keys] must have been
     * captured from this payload ([captureKeyTable]), which saves decoding and ordering it again.
     */
    fun openValue(payload: ByteArray, offset: Int, length: Int, valueOffset: Int, pre: Long, source: String = "payload", keys: KeyTable? = null) {
        start(payload, offset, length, FragmentKind.SUBTREE, pre, source, keys)
        if (valueOffset < reader.position || valueOffset >= payloadEnd) corrupt(valueOffset, "value offset outside the payload body")
        reader.seek(valueOffset)
        wholeBody = false
        push(isObject = false, count = 1, startPre = pre - 1, size = -1, stackBase = -1)
    }

    private fun start(payload: ByteArray, offset: Int, length: Int, kind: Int, firstPre: Long, source: String, keys: KeyTable? = null) {
        require(firstPre >= 0)
        this.source = source
        this.payload = payload
        this.kind = kind
        reader.reset(payload, offset, length, source)
        payloadEnd = offset + length
        frames = 0
        memberStackTop = 0
        finished = false
        nextPre = firstPre
        event = END
        partKind = 0
        partMemberCount = 0
        keyBase = offset
        if (keys == null) {
            readKeyTable()
        } else {
            if (keys.end > length) corrupt(offset, "borrowed key table ends after the payload")
            keyOffsets = keys.offsets
            keyLengths = keys.lengths
            ranks = keys.ranks
            keyCount = keys.count
            keyTableEnd = keys.end
            ranksValid = true
            reader.seek(offset + keys.end)
        }
    }

    /** `pre` the next value would get; after [END], one past the last value of the fragment. */
    val nextValuePre: Long get() = nextPre

    /** Index in [payload] of the next byte to decode; after a scalar, a stub or [skipValue], the end of that value. */
    val position: Int get() = reader.position

    private fun readKeyTable() {
        val n = reader.readVarInt(reader.remaining)
        if (ownOffsets.size < n) {
            ownOffsets = IntArray(n)
            ownLengths = IntArray(n)
        }
        keyOffsets = ownOffsets
        keyLengths = ownLengths
        ranks = ownRanks
        for (i in 0 until n) {
            val len = reader.readVarInt(reader.remaining)
            keyOffsets[i] = reader.position - keyBase
            keyLengths[i] = len
            reader.skip(len)
        }
        keyCount = n
        keyTableEnd = reader.position - keyBase
        ranksValid = false
    }

    /** A copy of the key table of the open payload, with its ranks, for later [openValue] calls on it. */
    fun captureKeyTable(): KeyTable {
        ensureRanks()
        return KeyTable(keyCount, keyOffsets.copyOf(keyCount), keyLengths.copyOf(keyCount), ranks.copyOf(keyCount), keyTableEnd)
    }

    /** Checks that the key table has no repeated key; decoding checks it lazily, the first time a `sortPerm` needs it. */
    fun checkKeyTable() = ensureRanks()

    /** Position of key [keyRef] in the key table ordered by unsigned key bytes; distinct keys have distinct ranks. */
    fun keyRank(keyRef: Int): Int {
        ensureRanks()
        return ranks[keyRef]
    }

    /** Advances to the next event and returns it. */
    fun next(): Int {
        if (finished) return END
        val f = frames - 1
        if (frameRemaining[f] == 0L) return close(f)
        frameRemaining[f]--
        val index = frameMemberIndex[f]++
        memberIndex = index
        if (frameObject[f]) {
            if (keyCount == 0) corrupt(reader.position, "object member but the key table is empty")
            keyRef = reader.readVarInt(keyCount - 1)
            keyOffset = keyBase + keyOffsets[keyRef]
            keyLength = keyLengths[keyRef]
            hasKey = true
            val base = frameStackBase[f]
            if (base >= 0) memberStack[base + frameTotal[f].toInt() + index.toInt()] = keyRef
        } else {
            hasKey = false
        }
        valueOffset = reader.position
        pre = nextPre
        val tag = reader.readByte()
        if (kind == FragmentKind.STRCHUNK && tag != PayloadTag.STR) corrupt(valueOffset, "STRCHUNK value isn't a string")
        event = when (tag) {
            PayloadTag.NULL -> scalar(NULL)
            PayloadTag.FALSE -> scalar(FALSE)
            PayloadTag.TRUE -> scalar(TRUE)
            PayloadTag.INT -> {
                intValue = reader.readZigZag()
                scalar(INT)
            }
            PayloadTag.NUM -> {
                bytesView()
                if (length == 0) corrupt(valueOffset, "empty number")
                scalar(NUM)
            }
            PayloadTag.STR -> {
                bytesView()
                scalar(STR)
            }
            PayloadTag.OBJ, PayloadTag.ARR -> {
                val isObject = tag == PayloadTag.OBJ
                val c = reader.readVarLong()
                val s = reader.readVarLong()
                if (c < 0 || c > reader.remaining / (if (isObject) 2 else 1)) corrupt(valueOffset, "container count ${java.lang.Long.toUnsignedString(c)}")
                if (s < c) corrupt(valueOffset, "container size ${java.lang.Long.toUnsignedString(s)} below its count $c")
                checkPreRoom(s)
                val base = if (isObject && c >= 2) readSortPerm(c.toInt()) else -1
                push(isObject, c, nextPre, s, base)
                nextPre++
                count = c
                size = s
                if (isObject) START_OBJECT else START_ARRAY
            }
            PayloadTag.REF -> {
                readContainerKind("REF")
                size = nonNegative(reader.readVarLong(), "REF size")
                readHash()
                encodedBytes = nonNegative(reader.readVarLong(), "REF encoded bytes")
                checkPreRoom(size)
                nextPre += size + 1
                REF
            }
            PayloadTag.SPLIT -> {
                readContainerKind("SPLIT")
                count = reader.readVarLong()
                size = reader.readVarLong()
                if (count < 1 || size < count) corrupt(valueOffset, "SPLIT count $count, size $size")
                readHash()
                checkPreRoom(size)
                nextPre += size + 1
                SPLIT
            }
            PayloadTag.BIGSTR -> {
                size = reader.readVarLong()
                count = reader.readVarLong()
                if (size <= PayloadFormat.MAX_EXACT_STRING_BYTES || count < 1 || size < count) {
                    corrupt(valueOffset, "BIGSTR bytes $size, chunks $count")
                }
                readHash()
                offset = reader.position
                length = PayloadFormat.BIGSTR_PREFIX_BYTES
                reader.skip(length)
                scalar(BIGSTR)
            }
            else -> corrupt(valueOffset, "unknown value tag $tag")
        }
        return event
    }

    /** After START_OBJECT or START_ARRAY: decodes (and validates) through the matching end. Otherwise does nothing. */
    fun skipValue() {
        if (event != START_OBJECT && event != START_ARRAY) return
        val open = frames
        while (frames >= open) next()
    }

    private fun close(f: Int): Int {
        hasKey = false
        if (f == 0) {
            checkSortPerm(0)
            if (wholeBody && reader.position != payloadEnd) corrupt(reader.position, "${payloadEnd - reader.position} bytes after the last value")
            finished = true
            event = END
            return END
        }
        val used = nextPre - frameStartPre[f] - 1
        if (used != frameSize[f]) corrupt(reader.position, "container at pre ${frameStartPre[f]} has size ${frameSize[f]} but its content uses $used")
        checkSortPerm(f)
        frames--
        pre = frameStartPre[f]
        size = frameSize[f]
        count = frameTotal[f]
        event = if (frameObject[f]) END_OBJECT else END_ARRAY
        return event
    }

    private fun scalar(event: Int): Int {
        checkPreRoom(0)
        nextPre++
        return event
    }

    private fun bytesView() {
        length = reader.readVarInt(reader.remaining)
        offset = reader.position
        reader.skip(length)
    }

    private fun readContainerKind(stub: String) {
        containerKind = reader.readByte()
        if (containerKind != PayloadTag.OBJ && containerKind != PayloadTag.ARR) corrupt(valueOffset, "$stub container kind $containerKind")
    }

    private fun readHash() {
        hashHigh = reader.readLongBE()
        hashLow = reader.readLongBE()
    }

    private fun nonNegative(value: Long, what: String): Long {
        if (value < 0) corrupt(valueOffset, "$what ${java.lang.Long.toUnsignedString(value)}")
        return value
    }

    /** A value at [nextPre] with [descendants] descendants must not overflow `pre`. */
    private fun checkPreRoom(descendants: Long) {
        if (descendants > Long.MAX_VALUE - 1 - nextPre) corrupt(valueOffset, "pre overflow")
    }

    private fun push(isObject: Boolean, count: Long, startPre: Long, size: Long, stackBase: Int) {
        if (frames == frameObject.size) growFrames()
        frameObject[frames] = isObject
        frameRemaining[frames] = count
        frameTotal[frames] = count
        frameStartPre[frames] = startPre
        frameSize[frames] = size
        frameMemberIndex[frames] = 0
        frameStackBase[frames] = stackBase
        frames++
    }

    /** Reads a `sortPerm` of [n] entries, checks it's a permutation, and reserves room for the members' keyRefs. */
    private fun readSortPerm(n: Int): Int {
        val base = memberStackTop
        val needed = base.toLong() + 2L * n
        if (needed > memberStack.size) memberStack = memberStack.copyOf(maxOf(needed, memberStack.size * 2L).toInt())
        if (stamps.size < n) stamps = IntArray(maxOf(n, stamps.size * 2))
        if (++stampGeneration == 0) {
            stamps.fill(0)
            stampGeneration = 1
        }
        for (i in 0 until n) {
            val at = reader.position
            val p = reader.readVarInt(n - 1)
            if (stamps[p] == stampGeneration) corrupt(at, "sortPerm repeats position $p")
            stamps[p] = stampGeneration
            memberStack[base + i] = p
        }
        memberStackTop = base + 2 * n
        return base
    }

    private fun checkSortPerm(f: Int) {
        val base = frameStackBase[f]
        if (base < 0) return
        ensureRanks()
        val n = frameTotal[f].toInt()
        var previous = ranks[memberStack[base + n + memberStack[base]]]
        for (i in 1 until n) {
            val rank = ranks[memberStack[base + n + memberStack[base + i]]]
            if (rank <= previous) corrupt(reader.position, "sortPerm isn't ordered by key bytes")
            previous = rank
        }
        memberStackTop = base
    }

    private fun ensureRanks() {
        if (ranksValid) return
        if (ownRanks.size < keyCount) ownRanks = IntArray(keyCount)
        ranks = ownRanks
        if (rankScratch.size < 2 * keyCount) rankScratch = IntArray(2 * keyCount)
        if (!KeyOrder.rank(payload, keyBase, keyOffsets, keyLengths, keyCount, ranks, rankScratch)) corrupt(0, "key table repeats a key")
        ranksValid = true
    }

    private fun growFrames() {
        val n = frameObject.size * 2
        frameObject = frameObject.copyOf(n)
        frameRemaining = frameRemaining.copyOf(n)
        frameTotal = frameTotal.copyOf(n)
        frameStartPre = frameStartPre.copyOf(n)
        frameSize = frameSize.copyOf(n)
        frameMemberIndex = frameMemberIndex.copyOf(n)
        frameStackBase = frameStackBase.copyOf(n)
    }

    private fun corrupt(at: Int, detail: String): Nothing = throw CorruptionException("$source@$at", detail)

    companion object {
        const val END: Int = 0
        const val NULL: Int = 1
        const val FALSE: Int = 2
        const val TRUE: Int = 3
        const val INT: Int = 4
        const val NUM: Int = 5
        const val STR: Int = 6
        const val START_OBJECT: Int = 7
        const val END_OBJECT: Int = 8
        const val START_ARRAY: Int = 9
        const val END_ARRAY: Int = 10
        const val REF: Int = 11
        const val SPLIT: Int = 12
        const val BIGSTR: Int = 13

        private val EMPTY = ByteArray(0)
    }
}

/** Decoder for HEADER payloads, validating as it reads. Reusable; not thread-safe. */
internal class HeaderReader {
    private val reader = ByteReader(ByteArray(0))
    private var source = "payload"
    private var entriesLeft = 0
    private var seq = 0

    /** `seq 0` summary; zero for continuation records. */
    var containerKind: Int = 0
        private set
    var count: Long = 0
        private set
    var size: Long = 0
        private set
    var hashLow: Long = 0
        private set
    var hashHigh: Long = 0
        private set
    var partCount: Long = 0
        private set

    /** Directory entries in this record. */
    var entryCount: Int = 0
        private set

    // Current entry.
    var firstPre: Long = -1
        private set
    var firstIndex: Long = 0
        private set
    var memberCount: Long = 0
        private set

    fun open(payload: ByteArray, offset: Int, length: Int, seq: Int, source: String = "payload") {
        require(seq >= 0)
        this.source = source
        this.seq = seq
        reader.reset(payload, offset, length, source)
        if (reader.readVarInt(reader.remaining) != 0) corrupt(0, "HEADER with keys")
        containerKind = 0
        count = 0
        size = 0
        hashLow = 0
        hashHigh = 0
        partCount = 0
        if (seq == 0) {
            containerKind = reader.readByte()
            if (containerKind != PayloadTag.OBJ && containerKind != PayloadTag.ARR) corrupt(reader.position - 1, "container kind $containerKind")
            count = reader.readVarLong()
            size = reader.readVarLong()
            if (count < 1 || size < count) corrupt(reader.position, "count $count, size $size")
            hashHigh = reader.readLongBE()
            hashLow = reader.readLongBE()
            partCount = reader.readVarLong()
            if (partCount < 1 || partCount > count) corrupt(reader.position, "part count $partCount")
        }
        val at = reader.position
        entryCount = reader.readVarInt(reader.remaining / 3)
        if (seq == 0 && entryCount > partCount) corrupt(at, "$entryCount entries for $partCount parts")
        entriesLeft = entryCount
        firstPre = -1
        firstIndex = 0
        memberCount = 0
    }

    /** Advances to the next directory entry; false after the last, when the record must also end. */
    fun nextEntry(): Boolean {
        if (entriesLeft == 0) {
            if (reader.remaining != 0) corrupt(reader.position, "${reader.remaining} bytes after the directory")
            return false
        }
        val at = reader.position
        val pre = reader.readVarLong()
        val index = reader.readVarLong()
        val members = reader.readVarLong()
        if (pre < 0 || index < 0 || members < 1) corrupt(at, "directory entry ($pre, $index, $members)")
        if (seq == 0 && (members > count || index > count - members)) corrupt(at, "directory entry beyond the container's $count members")
        if (firstPre >= 0) {
            if (pre <= firstPre) corrupt(at, "directory pre numbers aren't increasing")
            if (index != firstIndex + memberCount) corrupt(at, "directory entries aren't consecutive")
        }
        firstPre = pre
        firstIndex = index
        memberCount = members
        entriesLeft--
        return true
    }

    private fun corrupt(at: Int, detail: String): Nothing = throw CorruptionException("$source@$at", detail)
}

/**
 * A payload's key table as a [PayloadReader] decoded it: offsets from the payload start, lengths, ranks by unsigned key
 * bytes, and where the table ends. Lets readers of the same payload skip decoding and ordering it again
 * ([PayloadReader.openValue]). Immutable: readers and trees borrow its arrays.
 */
internal class KeyTable(val count: Int, val offsets: IntArray, val lengths: IntArray, val ranks: IntArray, val end: Int) {
    val retainedBytes: Long get() = 12L * count + 64
}
