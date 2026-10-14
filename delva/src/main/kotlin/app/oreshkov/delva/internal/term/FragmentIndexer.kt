package app.oreshkov.delva.internal.term

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadFormat
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PayloadTag

/**
 * Emits the postings of one fragment, from the fragment and its context alone.
 * The live splitter runs it on every fragment it writes and recovery runs it on every replayed fragment, so both
 * produce identical postings.
 *
 * - Every object member `k: v` gives `(k, v) → (object, v)`; when `v` is an array, each element `e` also gives
 *   `(k, e) → (object, e, in-array)`, one level deep. Container values give terms only with [hashTerms].
 * - A stub gives the term of the value it stands for: REF and SPLIT carry the container kind and hash, BIGSTR the
 *   string's prefix and hash. The fragment a stub points to doesn't repeat that term.
 * - The locator is resolved when the containing object starts in this fragment, or when it's the split
 *   container of this PART (HEADER locator). Otherwise, for elements of an array whose owner object is elsewhere, it's
 *   [PostingLocator.UNRESOLVED].
 * - HEADER and STRCHUNK fragments have no postings.
 * - [policy] leaves out the postings of keys it doesn't index and of string values outside its lengths; the
 *   hashes of containers are folded either way, so container terms don't change.
 *
 * Hashes of inline containers are recomputed while decoding. Reusable; not thread-safe.
 */
internal class FragmentIndexer(private val hashTerms: Boolean, private val policy: IndexPolicy = IndexPolicy.EVERYTHING) {
    private val reader = PayloadReader()
    private val canonicalHash = CanonicalHash()
    private val encoder = TermEncoder()
    private val term = GrowableBytes(256)
    private val numberKey = GrowableBytes(64)
    private val valueHash = LongArray(2)

    // Frame 0 is the fragment body; frames 1… are the containers open in the payload.
    private var frames = 0
    private var fObject = BooleanArray(16)
    private var fPre = LongArray(16)
    private var fOffset = IntArray(16)
    private var fHash = LongArray(16 * CanonicalHash.FRAME_SLOTS)

    // Path hashes (SPEC.md §15) of each open container and of its parent; frame 0 of a PART is its split container.
    private var fPath = IntArray(16)
    private var fParentPath = IntArray(16)

    // How the container itself is posted (set at its start, used at its end).
    private var fTarget = IntArray(16)
    private var fKeyContext = BooleanArray(16)
    private var fKeyOffset = IntArray(16)
    private var fKeyLength = IntArray(16)
    private var fInArray = BooleanArray(16)

    // For arrays: where their elements are posted.
    private var fElementTarget = IntArray(16)
    private var fElementKeyContext = BooleanArray(16)
    private var fElementKeyOffset = IntArray(16)
    private var fElementKeyLength = IntArray(16)

    private lateinit var context: FragmentContext
    private lateinit var sink: PostingSink
    private var fragmentPre = 0L
    private var locatorKind = 0
    private var payloadOffset = 0

    /** Postings emitted by the last [index] call. */
    var postingCount: Int = 0
        private set

    /** Emits the postings of the fragment `(pre, kind, seq)` with the payload in `bytes[offset, offset + length)`. */
    fun index(pre: Long, kind: Int, seq: Int, context: FragmentContext, bytes: ByteArray, offset: Int, length: Int, sink: PostingSink, source: String = "fragment") {
        postingCount = 0
        if (kind == FragmentKind.HEADER || kind == FragmentKind.STRCHUNK) return
        if (kind != FragmentKind.SUBTREE && kind != FragmentKind.PART) throw CorruptionException(source, "unknown fragment kind $kind")
        if (seq != 0) throw CorruptionException(source, "seq $seq for kind $kind")
        this.context = context
        this.sink = sink
        fragmentPre = pre
        locatorKind = PostingLocator.of(kind)
        payloadOffset = offset
        reader.open(bytes, offset, length, kind, pre, source)

        frames = 1
        fObject[0] = false
        fTarget[0] = NONE
        fPath[0] = context.pathHash
        fParentPath[0] = context.parentPathHash
        fElementTarget[0] = NONE
        if (kind == FragmentKind.PART) {
            if (context.parentPre !in 0 until pre) throw CorruptionException(source, "PART at pre $pre with parent ${context.parentPre}")
            if (reader.partKind == PayloadTag.OBJ) {
                fObject[0] = true
            } else {
                setElementOwnerFromContext(0)
            }
        }
        run()
    }

    private fun run() {
        while (true) {
            when (val event = reader.next()) {
                PayloadReader.END -> return
                PayloadReader.START_OBJECT, PayloadReader.START_ARRAY -> startContainer(event == PayloadReader.START_OBJECT)
                PayloadReader.END_OBJECT, PayloadReader.END_ARRAY -> endContainer()
                else -> scalar(event)
            }
        }
    }

    private fun startContainer(isObject: Boolean) {
        val parent = frames - 1
        if (frames == fObject.size) grow()
        val f = frames++
        fObject[f] = isObject
        fPre[f] = reader.pre
        fOffset[f] = reader.valueOffset
        setTarget(parent, f)
        if (parent == 0 && locatorKind == PostingLocator.SUBTREE) {
            fPath[f] = context.pathHash
            fParentPath[f] = context.parentPathHash
        } else {
            fParentPath[f] = fPath[parent]
            fPath[f] = if (fObject[parent]) PathHash.member(fPath[parent], reader.payload, reader.keyOffset, reader.keyLength) else PathHash.element(fPath[parent])
        }
        fElementTarget[f] = NONE
        if (!isObject) {
            if (parent == 0 && locatorKind == PostingLocator.SUBTREE) {
                // The root of a SUBTREE: an array member of the context's owner object, if any.
                setElementOwnerFromContext(f)
            } else if (fTarget[f] != NONE && !fInArray[f]) {
                fElementTarget[f] = fTarget[f]
                fElementKeyContext[f] = fKeyContext[f]
                fElementKeyOffset[f] = fKeyOffset[f]
                fElementKeyLength[f] = fKeyLength[f]
            }
        }
        if (hashTerms) {
            if (isObject) canonicalHash.beginObject(fHash, f * CanonicalHash.FRAME_SLOTS) else canonicalHash.beginArray(fHash, f * CanonicalHash.FRAME_SLOTS)
        }
    }

    private fun endContainer() {
        val f = --frames
        if (!hashTerms) return
        val slot = f * CanonicalHash.FRAME_SLOTS
        if (fObject[f]) canonicalHash.endObject(fHash, slot, valueHash, 0) else canonicalHash.endArray(fHash, slot, valueHash, 0)
        if (fTarget[f] != NONE && indexesKey(fKeyContext[f], fKeyOffset[f], fKeyLength[f])) {
            startTerm(fKeyContext[f], fKeyOffset[f], fKeyLength[f])
            encoder.writeContainer(term, if (fObject[f]) TermTag.OBJECT else TermTag.ARRAY, valueHash[0], valueHash[1])
            emit(fTarget[f], fPre[f], fInArray[f])
        }
        fold(f - 1, fKeyOffset[f], fKeyLength[f])
    }

    private fun scalar(event: Int) {
        val parent = frames - 1
        val target = targetOf(parent)
        val fold = hashTerms && parent > 0
        if (target == NONE && !fold) return
        val keyContext: Boolean
        val keyOffset: Int
        val keyLength: Int
        val inArray: Boolean
        if (fObject[parent]) {
            keyContext = false
            keyOffset = reader.keyOffset
            keyLength = reader.keyLength
            inArray = false
        } else {
            keyContext = fElementKeyContext[parent]
            keyOffset = fElementKeyOffset[parent]
            keyLength = fElementKeyLength[parent]
            inArray = true
        }
        val emit = target != NONE && indexesKey(keyContext, keyOffset, keyLength) && indexesValue(event)
        if (emit) startTerm(keyContext, keyOffset, keyLength)
        val payload = reader.payload
        when (event) {
            PayloadReader.NULL -> {
                if (emit) encoder.writeNull(term)
                if (fold) canonicalHash.nullValue(valueHash, 0)
            }
            PayloadReader.FALSE, PayloadReader.TRUE -> {
                val value = event == PayloadReader.TRUE
                if (emit) encoder.writeBoolean(term, value)
                if (fold) canonicalHash.booleanValue(value, valueHash, 0)
            }
            PayloadReader.INT, PayloadReader.NUM -> {
                numberKey.clear()
                val keyBytes = if (event == PayloadReader.INT) {
                    CanonicalNumber.writeKey(reader.intValue, numberKey)
                } else {
                    CanonicalNumber.writeKey(payload, reader.offset, reader.length, numberKey)
                }
                if (keyBytes < 0) throw CorruptionException("fragment@${reader.valueOffset}", "NUM isn't an in-range JSON number")
                if (fold || keyBytes > TermEncoder.MAX_EXACT_VALUE_BYTES) canonicalHash.number(numberKey.array, 0, keyBytes, valueHash, 0)
                if (emit) encoder.writeNumberKey(term, numberKey.array, 0, keyBytes, valueHash[0], valueHash[1])
            }
            PayloadReader.STR -> {
                val long = reader.length > TermEncoder.MAX_EXACT_VALUE_BYTES
                if (fold || long) canonicalHash.string(payload, reader.offset, reader.length, valueHash, 0)
                if (emit) {
                    if (long) {
                        encoder.writeLongString(term, payload, reader.offset, valueHash[0], valueHash[1])
                    } else {
                        encoder.writeString(term, payload, reader.offset, reader.length)
                    }
                }
            }
            PayloadReader.REF, PayloadReader.SPLIT -> {
                valueHash[0] = reader.hashLow
                valueHash[1] = reader.hashHigh
                if (emit) {
                    // Container terms exist only with hash terms.
                    if (hashTerms) {
                        encoder.writeContainer(term, if (reader.containerKind == PayloadTag.OBJ) TermTag.OBJECT else TermTag.ARRAY, valueHash[0], valueHash[1])
                    } else {
                        term.clear()
                    }
                }
            }
            PayloadReader.BIGSTR -> {
                check(reader.length == PayloadFormat.BIGSTR_PREFIX_BYTES)
                valueHash[0] = reader.hashLow
                valueHash[1] = reader.hashHigh
                if (emit) encoder.writeLongString(term, payload, reader.offset, valueHash[0], valueHash[1])
            }
            else -> error("unexpected event $event")
        }
        if (emit && term.size > 0) emit(target, reader.pre, inArray)
        if (fold) fold(parent, reader.keyOffset, reader.keyLength)
    }

    /** Sets how the container in frame [f], whose parent frame is [parent], is posted. */
    private fun setTarget(parent: Int, f: Int) {
        fTarget[f] = targetOf(parent)
        if (fObject[parent]) {
            fKeyContext[f] = false
            fKeyOffset[f] = reader.keyOffset
            fKeyLength[f] = reader.keyLength
            fInArray[f] = false
        } else {
            fKeyContext[f] = fElementKeyContext[parent]
            fKeyOffset[f] = fElementKeyOffset[parent]
            fKeyLength[f] = fElementKeyLength[parent]
            fInArray[f] = true
        }
    }

    /** The containing-object reference for a value whose parent is frame [parent]. */
    private fun targetOf(parent: Int): Int = when {
        fObject[parent] -> if (parent == 0) SPLIT_CONTAINER else parent
        else -> fElementTarget[parent]
    }

    private fun setElementOwnerFromContext(f: Int) {
        if (!context.hasOwner) return
        fElementTarget[f] = CONTEXT_OWNER
        fElementKeyContext[f] = true
        fElementKeyOffset[f] = context.ownerKeyOffset
        fElementKeyLength[f] = context.ownerKeyLength
    }

    /** Whether [policy] indexes the member key of a value, which is in the context or in the payload. */
    private fun indexesKey(keyContext: Boolean, keyOffset: Int, keyLength: Int): Boolean =
        !policy.limitsKeys || policy.indexesKey(if (keyContext) context.ownerKey else reader.payload, keyOffset, keyLength)

    /** Whether [policy] indexes the value the reader is on: only string lengths are limited. */
    private fun indexesValue(event: Int): Boolean = when {
        !policy.limitsStrings -> true
        event == PayloadReader.STR -> policy.indexesString(reader.length.toLong())
        // A BIGSTR stands for a string of `size` bytes stored in STRCHUNKs.
        event == PayloadReader.BIGSTR -> policy.indexesString(reader.size)
        else -> true
    }

    private fun startTerm(keyContext: Boolean, keyOffset: Int, keyLength: Int) {
        term.clear()
        encoder.writeKey(term, if (keyContext) context.ownerKey else reader.payload, keyOffset, keyLength)
    }

    private fun emit(target: Int, memberPre: Long, inArray: Boolean) {
        postingCount++
        when (target) {
            SPLIT_CONTAINER -> sink.posting(term.array, 0, term.size, context.parentPre, memberPre, inArray, PostingLocator.HEADER, context.parentPre, 0, PathHash.posting(context.parentPathHash))
            // The owner is the parent of the fragment's top container, so its parent is the grandparent.
            CONTEXT_OWNER -> sink.posting(term.array, 0, term.size, context.ownerObjectPre, memberPre, inArray, PostingLocator.UNRESOLVED, 0, 0, PathHash.posting(context.grandparentPathHash))
            else -> sink.posting(term.array, 0, term.size, fPre[target], memberPre, inArray, locatorKind, fragmentPre, fOffset[target] - payloadOffset, PathHash.posting(fParentPath[target]))
        }
    }

    /** Adds [valueHash] to the hash of frame [parent] (not the fragment body). */
    private fun fold(parent: Int, keyOffset: Int, keyLength: Int) {
        if (parent == 0) return
        val slot = parent * CanonicalHash.FRAME_SLOTS
        if (fObject[parent]) {
            canonicalHash.objectMember(fHash, slot, reader.payload, keyOffset, keyLength, valueHash, 0)
        } else {
            canonicalHash.arrayElement(fHash, slot, valueHash, 0)
        }
    }

    private fun grow() {
        val n = fObject.size * 2
        fObject = fObject.copyOf(n)
        fPre = fPre.copyOf(n)
        fOffset = fOffset.copyOf(n)
        fHash = fHash.copyOf(n * CanonicalHash.FRAME_SLOTS)
        fPath = fPath.copyOf(n)
        fParentPath = fParentPath.copyOf(n)
        fTarget = fTarget.copyOf(n)
        fKeyContext = fKeyContext.copyOf(n)
        fKeyOffset = fKeyOffset.copyOf(n)
        fKeyLength = fKeyLength.copyOf(n)
        fInArray = fInArray.copyOf(n)
        fElementTarget = fElementTarget.copyOf(n)
        fElementKeyContext = fElementKeyContext.copyOf(n)
        fElementKeyOffset = fElementKeyOffset.copyOf(n)
        fElementKeyLength = fElementKeyLength.copyOf(n)
    }

    private companion object {
        const val NONE = -1

        /** The split container of a PART of an object. */
        const val SPLIT_CONTAINER = -2

        /** The owner object named by the context. */
        const val CONTEXT_OWNER = -3
    }
}
