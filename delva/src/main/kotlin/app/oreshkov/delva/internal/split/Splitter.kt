package app.oreshkov.delva.internal.split

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.Varint
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.parse.JsonToken
import app.oreshkov.delva.internal.parse.JsonTokenSource
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentEncoder
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadFormat
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.payload.PayloadWriter
import app.oreshkov.delva.internal.payload.PendingFormat
import app.oreshkov.delva.internal.term.FragmentIndexer
import app.oreshkov.delva.internal.term.PathHash
import app.oreshkov.delva.internal.term.TermSink
import app.oreshkov.delva.internal.term.TermSorter

/**
 * The streaming splitter: turns a document's tokens into bounded fragments, their
 * postings and type assignments, in memory bounded by [SplitOptions] and the document's depth — never by its size.
 *
 * **Pending buffer.** One byte buffer is used as a stack: an open container's pending bytes follow its parent's, in
 * the pending encoding (keys inline, fixed-width count/size slots). Frame `f` owns
 * `[fStart[f], fStart[f + 1])`: its container header, its complete pending members, then its in-progress member
 * (key and open child). Frame state lives in primitive arrays; there are no per-node objects.
 *
 * **Flush rules**, in accounted bytes (pending bytes plus a keyRef/`sortPerm` allowance per object member):
 * - when a member completes and the frame's pending members pass the frame's target (T minus the length of the key
 *   the container itself sits under), they're written as a PART — all of them if that stays within T + T/2, else the
 *   earlier ones, and then the new member alone if it's still above target;
 * - a closing container of at least T/2 becomes a SUBTREE and leaves a REF stub; a container that wrote PARTs gets
 *   HEADER records and leaves a SPLIT stub; smaller containers stay inline;
 * - above the budget B, the frame with the largest pending run of at least T/4 is written as a PART, moving the
 *   bytes above it down;
 * - a string longer than `max(T, 64)` is written as STRCHUNKs of at most T bytes cut on code-point boundaries while
 *   it streams, and leaves a BIGSTR stub (it's never held whole).
 *
 * **Everything else per fragment:** each written fragment goes to the [FragmentSink], then through [FragmentIndexer]
 * into the insert-local [TermSorter] unless there's no term sink (values split for comparison).
 * Canonical hashes run per frame; duplicate keys are detected
 * by [DupKeyChecker]; type paths are matched as values start.
 *
 * Rejections throw [DocumentRejectedException]; the fragments, runs and types delivered so far must then be
 * discarded by the caller. Reusable; not thread-safe.
 */
internal class Splitter(
    private val options: SplitOptions,
    private val fragments: FragmentSink,
    terms: TermSink?,
    private val typeSink: TypeSink,
    spill: SpillFiles,
) {
    private val sorter = TermSorter(options.sorterBytes, terms ?: TermSink { _, _, _ -> })
    private val indexing = terms != null
    private val indexer = FragmentIndexer(options.hashTerms, options.indexPolicy)
    private val encoder = FragmentEncoder()
    private val keys = DupKeyChecker(options.keyArenaBytes, spill)
    private val hash = CanonicalHash()
    private val context = FragmentContext()

    private val fragmentBytes = options.fragmentBytes
    private val allowance = options.memberAllowance.toLong()

    private val pending = GrowableBytes(64 * 1024)
    private val out = GrowableBytes(64 * 1024)
    private val numberKey = GrowableBytes(64)
    private val valueHash = LongArray(2)
    private val longSlot = LongArray(1)

    // ---- frames (index = depth of the container, root container = 0) ----
    private var depth = 0
    private var fObject = BooleanArray(16)
    private var fPre = LongArray(16)
    private var fStart = IntArray(16)

    /** Start of the in-progress member (its key for objects), which is also the end of the complete ones. */
    private var fMemberStart = IntArray(16)
    private var fMemberPre = LongArray(16)

    /** Members so far, flushed or not. */
    private var fCount = LongArray(16)
    private var fPendingCount = IntArray(16)

    /** Allowance of the complete pending members, nested ones included. */
    private var fExtra = LongArray(16)
    private var fFirstPre = LongArray(16)
    private var fFirstIndex = LongArray(16)
    private var fSplit = BooleanArray(16)
    private var fParts = LongArray(16)
    private var fHeaderSeq = IntArray(16)
    private var fDirectory = arrayOfNulls<LongArray>(16)
    private var fDirectoryCount = IntArray(16)

    /** Payload bytes of fragments already written for the container's subtree. */
    private var fFlushed = LongArray(16)
    private var fHash = LongArray(16 * CanonicalHash.FRAME_SLOTS)
    private var fTypeNode = IntArray(16)

    /** Length of the key the container is a member value of, or 0. */
    private var fOwnerKeyLength = IntArray(16)

    /** Path hash of the container (SPEC.md §15). */
    private var fPath = IntArray(16)

    // ---- per insert ----
    private var docId = 0L
    private var nextPre = 0L
    private var paths = TypePaths.EMPTY
    private var matched = BooleanArray(0)
    private var valueTypeNode = TypePaths.NONE
    private var rootDone = false

    // The document key: the string at [TypePaths.keyNode], captured while it streams.
    private val keyBuffer = GrowableBytes(64)
    private var capturingKey = false
    private var keyFound = false

    // String in progress.
    private var inString = false
    private var stringPre = 0L
    private var stringBytes = 0L
    private var stringBig = false
    private var stringChunks = 0
    private val stringBuffer = GrowableBytes(256)
    private val stringPrefix = ByteArray(PayloadFormat.BIGSTR_PREFIX_BYTES)

    // ---- results of the last split ----

    /** [FragmentKind.SUBTREE] or [FragmentKind.HEADER]: the kind of the root fragment at pre 0. */
    var rootKind: Int = 0
        private set
    var rootHashLow: Long = 0
        private set
    var rootHashHigh: Long = 0
        private set

    /** Values in the document. */
    var nodeCount: Long = 0
        private set
    var fragmentCount: Long = 0
        private set
    var payloadBytes: Long = 0
        private set
    var typeCount: Int = 0
        private set
    val runCount: Int get() = sorter.runCount
    val postingCount: Long get() = sorter.postingCount

    /** Posting bytes buffered in the term sorter for the next run. */
    val sorterBufferedBytes: Long get() = sorter.bufferedBytes

    /** Heap the splitter keeps for reuse: pending and output buffers, sorter and duplicate-key tables. */
    val retainedBytes: Long get() = pending.capacity.toLong() + out.capacity + stringBuffer.capacity + sorter.retainedBytes + keys.retainedBytes

    /** Largest pending buffer size reached. */
    var peakPendingBytes: Int = 0
        private set

    /** Largest `payload + owner key + RECORD_OVERHEAD_BYTES` of any fragment. */
    var peakRecordBytes: Int = 0
        private set

    /** The document key of the last split (UTF-8), or null when its type paths have no key path. */
    val documentKey: ByteArray? get() = if (keyFound) keyBuffer.toByteArray() else null

    /**
     * Splits the document read from [source] as [docId]. [typePaths] are matched while streaming; [keySeed] seeds the
     * duplicate-key hashes.
     */
    fun split(docId: Long, source: JsonTokenSource, typePaths: TypePaths = TypePaths.EMPTY, keySeed: Long = 0L) {
        reset(docId, typePaths, keySeed)
        var completed = false
        try {
            while (true) {
                when (val token = source.next()) {
                    JsonToken.EOF -> break
                    JsonToken.START_OBJECT -> openContainer(true, source.byteOffset)
                    JsonToken.START_ARRAY -> openContainer(false, source.byteOffset)
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> closeContainer(source.byteOffset)
                    JsonToken.KEY -> key(source)
                    JsonToken.STRING -> string(source, last = true)
                    JsonToken.STRING_PART -> string(source, source.isLastPart)
                    JsonToken.NUMBER -> number(source)
                    JsonToken.TRUE, JsonToken.FALSE -> literal(if (token == JsonToken.TRUE) PayloadTag.TRUE else PayloadTag.FALSE, source.byteOffset)
                    JsonToken.NULL -> literal(PayloadTag.NULL, source.byteOffset)
                    else -> error("unexpected token ${JsonToken.name(token)}")
                }
            }
            finish()
            completed = true
        } catch (_: FragmentEncoder.RepeatedKeyException) {
            throw DocumentRejectedException(RejectReason.DUPLICATE_KEY, source.byteOffset, detail = "found when the object's members were stored")
        } catch (e: CorruptionException) {
            // The postings of a repeated key that its runs haven't revealed yet look inconsistent to the term sorter.
            if (keys.repeatsSpilledKey()) throw DocumentRejectedException(RejectReason.DUPLICATE_KEY, source.byteOffset, detail = "found when the object's members were indexed")
            throw e
        } finally {
            if (!completed) keys.abort()
        }
    }

    private fun reset(docId: Long, typePaths: TypePaths, keySeed: Long) {
        this.docId = docId
        depth = 0
        nextPre = 0
        paths = typePaths
        if (!typePaths.isEmpty) matched = BooleanArray(typePaths.nodeCount)
        rootDone = false
        keyBuffer.clear()
        capturingKey = false
        keyFound = false
        inString = false
        pending.clear()
        stringBuffer.clear()
        sorter.reset(docId)
        keys.reset(keySeed)
        rootKind = 0
        rootHashLow = 0
        rootHashHigh = 0
        nodeCount = 0
        fragmentCount = 0
        payloadBytes = 0
        typeCount = 0
        peakPendingBytes = 0
        peakRecordBytes = 0
    }

    private fun finish() {
        check(rootDone && depth == 0 && !inString) { "token source ended inside the document" }
        if (!paths.isEmpty) {
            for (node in 0 until paths.nodeCount) {
                if (paths.typeOf(node) != null && !matched[node]) {
                    throw DocumentRejectedException(RejectReason.TYPE_PATH_UNMATCHED, -1, jsonPath = paths.pathOf(node), detail = "the path matches no value")
                }
            }
            if (paths.keyNode != TypePaths.NONE && !keyFound) {
                throw DocumentRejectedException(RejectReason.KEY_MISSING, -1, jsonPath = paths.keyPath, detail = "no value at the document key path")
            }
        }
        nodeCount = nextPre
        sorter.finish()
    }

    // ---- values ----------------------------------------------------------------------------------------------------

    /**
     * Assigns the next `pre` to a value that starts now and matches type paths, and starts capturing the document key
     * when [isString] is at its path; returns the `pre`.
     */
    private fun beginValue(isObject: Boolean, byteOffset: Long, isString: Boolean = false): Long {
        check(!rootDone) { "value after the root" }
        val pre = nextPre++
        var node = TypePaths.NONE
        if (depth > 0) {
            val f = depth - 1
            fMemberPre[f] = pre
            if (!fObject[f]) fMemberStart[f] = pending.size
            val parentNode = fTypeNode[f]
            if (parentNode != TypePaths.NONE) {
                node = if (fObject[f]) {
                    val at = fMemberStart[f]
                    val length = PendingFormat.varint(pending.array, at).toInt()
                    paths.childByKey(parentNode, pending.array, PendingFormat.varintEnd(pending.array, at), length)
                } else {
                    paths.childByIndex(parentNode, fCount[f])
                }
            }
        } else {
            node = paths.root
        }
        valueTypeNode = node
        if (node != TypePaths.NONE) {
            val type = paths.typeOf(node)
            if (type != null) {
                if (!isObject) {
                    throw DocumentRejectedException(RejectReason.TYPE_PATH_UNMATCHED, byteOffset, jsonPath = paths.pathOf(node), detail = "the path matches a value that isn't an object")
                }
                matched[node] = true
                typeCount++
                typeSink.assign(docId, pre, type)
            }
            if (node == paths.keyNode) {
                if (!isString) {
                    throw DocumentRejectedException(RejectReason.KEY_MISSING, byteOffset, jsonPath = paths.keyPath, detail = "the value at the document key path isn't a string")
                }
                capturingKey = true
            }
        }
        return pre
    }

    private fun literal(tag: Int, byteOffset: Long) {
        beginValue(false, byteOffset)
        pending.writeByte(tag)
        when (tag) {
            PayloadTag.NULL -> hash.nullValue(valueHash, 0)
            else -> hash.booleanValue(tag == PayloadTag.TRUE, valueHash, 0)
        }
        endScalar()
    }

    private fun number(source: JsonTokenSource) {
        if (source.length > options.maxAtomBytes) {
            throw DocumentRejectedException(RejectReason.LIMIT_EXCEEDED, source.byteOffset, detail = "number longer than ${options.maxAtomBytes} characters")
        }
        beginValue(false, source.byteOffset)
        numberKey.clear()
        val keyLength = if (CanonicalNumber.parseCanonicalLong(source.bytes, source.offset, source.length, longSlot, 0)) {
            PayloadWriter.intValue(pending, longSlot[0])
            CanonicalNumber.writeKey(longSlot[0], numberKey)
        } else {
            PayloadWriter.numValue(pending, source.bytes, source.offset, source.length)
            CanonicalNumber.writeKey(source.bytes, source.offset, source.length, numberKey)
        }
        when (keyLength) {
            CanonicalNumber.MALFORMED -> throw DocumentRejectedException(RejectReason.MALFORMED_JSON, source.byteOffset, detail = "invalid number")
            CanonicalNumber.OUT_OF_RANGE -> throw DocumentRejectedException(RejectReason.NUMBER_OUT_OF_RANGE, source.byteOffset)
        }
        hash.number(numberKey.array, 0, keyLength, valueHash, 0)
        endScalar()
    }

    private fun string(source: JsonTokenSource, last: Boolean) {
        val bytes = source.bytes
        val offset = source.offset
        val length = source.length
        if (!inString) {
            stringPre = beginValue(false, source.byteOffset, isString = true)
            if (capturingKey) captureKey(bytes, offset, length, last, source.byteOffset)
            if (last && length <= options.inlineStringBytes) {
                // Whole short string: the common case.
                PayloadWriter.strValue(pending, bytes, offset, length)
                hash.string(bytes, offset, length, valueHash, 0)
                endScalar()
                return
            }
            inString = true
            stringBytes = 0
            stringBig = false
            stringChunks = 0
            stringBuffer.clear()
            hash.beginString()
        } else if (capturingKey) {
            captureKey(bytes, offset, length, last, source.byteOffset)
        }
        hash.stringPart(bytes, offset, length)
        stringBytes += length
        stringBuffer.writeBytes(bytes, offset, length)
        if (!stringBig && stringBytes > options.inlineStringBytes) {
            stringBig = true
            System.arraycopy(stringBuffer.array, 0, stringPrefix, 0, stringPrefix.size)
        }
        if (stringBig) {
            while (stringBuffer.size > fragmentBytes) writeChunk()
        }
        if (!last) return
        inString = false
        hash.endString(valueHash, 0)
        if (stringBig) {
            while (stringBuffer.size > 0) writeChunk()
            PayloadWriter.bigString(pending, stringBytes, stringChunks.toLong(), valueHash[0], valueHash[1], stringPrefix, 0)
        } else {
            PayloadWriter.strValue(pending, stringBuffer.array, 0, stringBuffer.size)
        }
        stringBuffer.clear()
        endScalar()
    }

    /** Adds a part of the document key; at its [last] part the key is complete. */
    private fun captureKey(bytes: ByteArray, offset: Int, length: Int, last: Boolean, byteOffset: Long) {
        if (keyBuffer.size + length > CreationOptions.MAX_DOCUMENT_KEY_BYTES) {
            throw DocumentRejectedException(
                RejectReason.LIMIT_EXCEEDED, byteOffset, jsonPath = paths.keyPath,
                detail = "document key longer than ${CreationOptions.MAX_DOCUMENT_KEY_BYTES} bytes",
            )
        }
        keyBuffer.writeBytes(bytes, offset, length)
        if (last) {
            capturingKey = false
            keyFound = true
        }
    }

    /** Writes the next STRCHUNK: at most T bytes from the string buffer, ending on a code-point boundary. */
    private fun writeChunk() {
        val buffered = stringBuffer.size
        var cut = minOf(buffered, fragmentBytes)
        if (cut < buffered) {
            while (cut > 0 && stringBuffer.array[cut].toInt() and 0xC0 == 0x80) cut--
        }
        check(cut > 0)
        out.clear()
        PayloadWriter.keyTableCount(out, 0)
        PayloadWriter.strValue(out, stringBuffer.array, 0, cut)
        context.set(if (depth > 0) fPre[depth - 1] else -1, -1, pending.array, 0, 0, 0)
        emit(stringPre, FragmentKind.STRCHUNK, stringChunks++)
        if (depth > 0) fFlushed[depth - 1] += out.size
        stringBuffer.moveRange(cut, 0, buffered - cut)
        stringBuffer.truncate(buffered - cut)
    }

    private fun endScalar() {
        if (depth > 0) {
            completeMember(depth - 1, 0L)
        } else {
            // A scalar root is the whole document: one SUBTREE fragment, no terms.
            out.clear()
            encoder.encodeValue(pending.array, 0, pending.size, out)
            context.setRoot()
            emit(0, FragmentKind.SUBTREE, 0)
            pending.clear()
            finishRoot(FragmentKind.SUBTREE)
        }
    }

    private fun finishRoot(kind: Int) {
        rootDone = true
        rootKind = kind
        rootHashLow = valueHash[0]
        rootHashHigh = valueHash[1]
    }

    // ---- containers ------------------------------------------------------------------------------------------------

    private fun key(source: JsonTokenSource) {
        val f = depth - 1
        val length = source.length
        if (length > options.maxAtomBytes) {
            throw DocumentRejectedException(RejectReason.LIMIT_EXCEEDED, source.byteOffset, detail = "key longer than ${options.maxAtomBytes} bytes")
        }
        val start = pending.size
        val keyPosition = start + Varint.size(length.toLong())
        if (!keys.addKey(f, source.bytes, source.offset, length, pending.array, fStart[f], keyPosition)) {
            throw DocumentRejectedException(RejectReason.DUPLICATE_KEY, source.byteOffset)
        }
        fMemberStart[f] = start
        PayloadWriter.key(pending, source.bytes, source.offset, length)
    }

    private fun openContainer(isObject: Boolean, byteOffset: Long) {
        val pre = beginValue(isObject, byteOffset)
        if (depth == fObject.size) growFrames()
        val f = depth++
        fObject[f] = isObject
        fPre[f] = pre
        fStart[f] = pending.size
        pending.writeByte(if (isObject) PayloadTag.OBJ else PayloadTag.ARR)
        pending.reserve(PendingFormat.CONTAINER_HEADER_BYTES - 1)
        fMemberStart[f] = pending.size
        fCount[f] = 0
        fPendingCount[f] = 0
        fExtra[f] = 0
        fSplit[f] = false
        fParts[f] = 0
        fHeaderSeq[f] = 0
        fDirectoryCount[f] = 0
        fFlushed[f] = 0
        fTypeNode[f] = valueTypeNode
        fOwnerKeyLength[f] = if (f > 0 && fObject[f - 1]) PendingFormat.varint(pending.array, fMemberStart[f - 1]).toInt() else 0
        fPath[f] = when {
            f == 0 -> PathHash.ROOT
            fObject[f - 1] -> fMemberStart[f - 1].let { keyAt -> PathHash.member(fPath[f - 1], pending.array, PendingFormat.varintEnd(pending.array, keyAt), PendingFormat.varint(pending.array, keyAt).toInt()) }
            else -> PathHash.element(fPath[f - 1])
        }
        val slot = f * CanonicalHash.FRAME_SLOTS
        if (isObject) {
            hash.beginObject(fHash, slot)
            keys.openObject(f)
        } else {
            hash.beginArray(fHash, slot)
        }
    }

    private fun closeContainer(byteOffset: Long) {
        val c = --depth
        val size = nextPre - fPre[c] - 1
        val slot = c * CanonicalHash.FRAME_SLOTS
        val kind = if (fObject[c]) PayloadTag.OBJ else PayloadTag.ARR
        if (fObject[c]) hash.endObject(fHash, slot, valueHash, 0) else hash.endArray(fHash, slot, valueHash, 0)
        if (fSplit[c] && fPendingCount[c] > 0) flushPart(c, pending.size, fPendingCount[c])
        if (fObject[c] && !keys.closeObject(c)) {
            throw DocumentRejectedException(RejectReason.DUPLICATE_KEY, byteOffset, detail = "found when the object closed")
        }

        var childExtra = 0L
        if (fSplit[c]) {
            writeHeaders(c, kind, size)
            pending.truncate(fStart[c])
            if (c > 0) PayloadWriter.split(pending, kind, fCount[c], size, valueHash[0], valueHash[1])
        } else {
            pending.putIntLE(fStart[c] + 1, fCount[c].toInt())
            pending.putLongLE(fStart[c] + 5, size)
            val cost = (pending.size - fStart[c]) + fExtra[c]
            if (c == 0 || cost >= fragmentBytes / 2) {
                out.clear()
                encoder.encodeValue(pending.array, fStart[c], pending.size, out)
                setValueContext(c)
                emit(fPre[c], FragmentKind.SUBTREE, 0)
                pending.truncate(fStart[c])
                fFlushed[c] += out.size
                if (c > 0) PayloadWriter.ref(pending, kind, size, valueHash[0], valueHash[1], fFlushed[c])
            } else {
                childExtra = fExtra[c]
            }
        }

        if (c == 0) {
            finishRoot(if (fSplit[0]) FragmentKind.HEADER else FragmentKind.SUBTREE)
        } else {
            fFlushed[c - 1] += fFlushed[c]
            completeMember(c - 1, childExtra)
        }
    }

    /** The value that started at `fMemberStart[f]` is complete in the pending buffer, and its hash is in [valueHash]. */
    private fun completeMember(f: Int, valueExtra: Long) {
        val slot = f * CanonicalHash.FRAME_SLOTS
        val memberStart = fMemberStart[f]
        var memberExtra = valueExtra
        if (fObject[f]) {
            val keyLength = PendingFormat.varint(pending.array, memberStart).toInt()
            hash.objectMember(fHash, slot, pending.array, PendingFormat.varintEnd(pending.array, memberStart), keyLength, valueHash, 0)
            memberExtra += allowance
        } else {
            hash.arrayElement(fHash, slot, valueHash, 0)
        }
        val memberIndex = fCount[f]++
        val memberCost = (pending.size - memberStart) + memberExtra
        val pendingCost = (memberStart - fStart[f] - PendingFormat.CONTAINER_HEADER_BYTES) + fExtra[f]
        val target = maxOf(1L, (fragmentBytes - fOwnerKeyLength[f]).toLong())
        val limit = maxOf(1L, (fragmentBytes + fragmentBytes / 2 - fOwnerKeyLength[f]).toLong())
        when {
            pendingCost + memberCost <= target -> addPending(f, memberIndex, memberExtra)
            pendingCost + memberCost <= limit -> {
                addPending(f, memberIndex, memberExtra)
                flushPart(f, pending.size, fPendingCount[f])
            }
            else -> {
                if (fPendingCount[f] > 0) flushPart(f, memberStart, fPendingCount[f])
                addPending(f, memberIndex, memberExtra)
                if (memberCost > target) flushPart(f, pending.size, 1)
            }
        }
        fMemberStart[f] = pending.size
        if (pending.size > peakPendingBytes) peakPendingBytes = pending.size
        if (pending.size > options.budgetBytes) enforceBudget()
    }

    private fun addPending(f: Int, memberIndex: Long, extra: Long) {
        if (fPendingCount[f] == 0) {
            fFirstPre[f] = fMemberPre[f]
            fFirstIndex[f] = memberIndex
        }
        fPendingCount[f]++
        fExtra[f] += extra
    }

    /** Over budget: flushes the largest pending runs of at least T/4 until back within it. */
    private fun enforceBudget() {
        while (pending.size > options.budgetBytes) {
            var best = -1
            var bestCost = (fragmentBytes / 4).toLong() - 1
            for (g in 0 until depth) {
                if (fPendingCount[g] == 0) continue
                val cost = (fMemberStart[g] - fStart[g] - PendingFormat.CONTAINER_HEADER_BYTES) + fExtra[g]
                if (cost > bestCost) {
                    best = g
                    bestCost = cost
                }
            }
            if (best < 0) return
            flushPart(best, fMemberStart[best], fPendingCount[best])
        }
    }

    /**
     * Writes the first [count] pending members of frame [f], which occupy `[members start, end)`, as a PART, and
     * removes their bytes: everything above [end] moves down.
     */
    private fun flushPart(f: Int, end: Int, count: Int) {
        val membersStart = fStart[f] + PendingFormat.CONTAINER_HEADER_BYTES
        if (fObject[f]) keys.beforeFlush(f, pending.array, fStart[f])
        out.clear()
        encoder.encodePart(if (fObject[f]) PayloadTag.OBJ else PayloadTag.ARR, pending.array, membersStart, end, count, out)
        if (f > 0 && fObject[f - 1]) {
            val keyAt = fMemberStart[f - 1]
            context.set(fPre[f], fPre[f - 1], pending.array, PendingFormat.varintEnd(pending.array, keyAt), PendingFormat.varint(pending.array, keyAt).toInt(), fFirstIndex[f])
        } else {
            context.set(fPre[f], -1, pending.array, 0, 0, fFirstIndex[f])
        }
        setPaths(f)
        emit(fFirstPre[f], FragmentKind.PART, 0)
        fFlushed[f] += out.size
        fSplit[f] = true
        fParts[f]++
        addDirectoryEntry(f, fFirstPre[f], fFirstIndex[f], count.toLong())

        val delta = end - membersStart
        if (delta > 0) {
            pending.moveRange(end, membersStart, pending.size - end)
            pending.truncate(pending.size - delta)
            if (fMemberStart[f] >= end) fMemberStart[f] -= delta
            for (g in f + 1 until depth) {
                fStart[g] -= delta
                fMemberStart[g] -= delta
            }
        }
        fPendingCount[f] = 0
        fExtra[f] = 0
    }

    private fun addDirectoryEntry(f: Int, firstPre: Long, firstIndex: Long, memberCount: Long) {
        val perRecord = options.directoryEntriesPerRecord
        if (fDirectoryCount[f] == perRecord) writeDirectoryRecord(f)
        val directory = fDirectory[f] ?: LongArray(3 * perRecord).also { fDirectory[f] = it }
        val n = fDirectoryCount[f]++
        directory[3 * n] = firstPre
        directory[3 * n + 1] = firstIndex
        directory[3 * n + 2] = memberCount
    }

    /** Writes the buffered directory entries of frame [f] as the next HEADER continuation record. */
    private fun writeDirectoryRecord(f: Int) {
        out.clear()
        PayloadWriter.keyTableCount(out, 0)
        writeDirectoryEntries(f)
        setValueContext(f)
        emit(fPre[f], FragmentKind.HEADER, ++fHeaderSeq[f])
        fFlushed[f] += out.size
    }

    private fun writeDirectoryEntries(f: Int) {
        val directory = fDirectory[f]!!
        val n = fDirectoryCount[f]
        PayloadWriter.dirEntryCount(out, n)
        for (i in 0 until n) PayloadWriter.dirEntry(out, directory[3 * i], directory[3 * i + 1], directory[3 * i + 2])
        fDirectoryCount[f] = 0
    }

    /**
     * HEADER `seq 0` of a split container with [valueHash]. It holds the whole directory when no continuation record
     * was written yet; otherwise it holds none, and the remaining entries follow as the next continuation record, so
     * the records list the parts in order.
     */
    private fun writeHeaders(c: Int, kind: Int, size: Long) {
        out.clear()
        PayloadWriter.keyTableCount(out, 0)
        PayloadWriter.headerSummary(out, kind, fCount[c], size, valueHash[0], valueHash[1], fParts[c])
        val continued = fHeaderSeq[c] > 0
        if (continued) PayloadWriter.dirEntryCount(out, 0) else writeDirectoryEntries(c)
        setValueContext(c)
        emit(fPre[c], FragmentKind.HEADER, 0)
        fFlushed[c] += out.size
        if (continued && fDirectoryCount[c] > 0) writeDirectoryRecord(c)
    }

    /** Context of a fragment holding frame [c]'s container as a value (SUBTREE, HEADER). */
    private fun setValueContext(c: Int) {
        if (c == 0) {
            context.setRoot()
        } else if (fObject[c - 1]) {
            val keyAt = fMemberStart[c - 1]
            context.set(fPre[c - 1], fPre[c - 1], pending.array, PendingFormat.varintEnd(pending.array, keyAt), PendingFormat.varint(pending.array, keyAt).toInt(), 0)
        } else {
            context.set(fPre[c - 1], -1, pending.array, 0, 0, 0)
        }
        setPaths(c)
    }

    /** The path hashes of frame [c]'s container, its parent and its grandparent into [context]. */
    private fun setPaths(c: Int) {
        context.setPaths(fPath[c], if (c > 0) fPath[c - 1] else PathHash.NONE, if (c > 1) fPath[c - 2] else PathHash.NONE)
    }

    /** Hands the fragment in [out] with [context] to the sink, then indexes it. */
    private fun emit(pre: Long, kind: Int, seq: Int) {
        val recordBytes = out.size + context.ownerKeyLength + SplitOptions.RECORD_OVERHEAD_BYTES
        check(recordBytes <= options.maxRecordBytes) { "fragment ($pre, $kind, $seq) of $recordBytes record bytes exceeds ${options.maxRecordBytes}" }
        if (recordBytes > peakRecordBytes) peakRecordBytes = recordBytes
        fragments.fragment(docId, pre, kind, seq, context, out.array, 0, out.size)
        if (indexing) indexer.index(pre, kind, seq, context, out.array, 0, out.size, sorter)
        fragmentCount++
        payloadBytes += out.size
    }

    private fun growFrames() {
        val n = fObject.size * 2
        fObject = fObject.copyOf(n)
        fPre = fPre.copyOf(n)
        fStart = fStart.copyOf(n)
        fMemberStart = fMemberStart.copyOf(n)
        fMemberPre = fMemberPre.copyOf(n)
        fCount = fCount.copyOf(n)
        fPendingCount = fPendingCount.copyOf(n)
        fExtra = fExtra.copyOf(n)
        fFirstPre = fFirstPre.copyOf(n)
        fFirstIndex = fFirstIndex.copyOf(n)
        fSplit = fSplit.copyOf(n)
        fParts = fParts.copyOf(n)
        fHeaderSeq = fHeaderSeq.copyOf(n)
        fDirectory = fDirectory.copyOf(n)
        fDirectoryCount = fDirectoryCount.copyOf(n)
        fFlushed = fFlushed.copyOf(n)
        fHash = fHash.copyOf(n * CanonicalHash.FRAME_SLOTS)
        fTypeNode = fTypeNode.copyOf(n)
        fOwnerKeyLength = fOwnerKeyLength.copyOf(n)
        fPath = fPath.copyOf(n)
    }
}
