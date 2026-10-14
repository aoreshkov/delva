package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.Varint
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.FragmentLookup
import app.oreshkov.delva.internal.payload.HeaderReader
import app.oreshkov.delva.internal.payload.JsonOutput
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PayloadSlice
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.payload.PayloadValidator
import app.oreshkov.delva.internal.payload.Reassembler
import app.oreshkov.delva.internal.term.PostingLocator
import app.oreshkov.delva.internal.term.PostingSink
import app.oreshkov.delva.internal.term.SortedTermRun
import app.oreshkov.delva.internal.term.TermEncoder
import app.oreshkov.delva.internal.term.TermSink
import app.oreshkov.delva.internal.term.TermTag
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.toHex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.TreeMap

internal data class FragmentKey(val pre: Long, val kind: Int, val seq: Int) : Comparable<FragmentKey> {
    override fun compareTo(other: FragmentKey): Int = compareValuesBy(this, other, { it.pre }, { it.kind }, { it.seq })

    override fun toString(): String = "($pre, ${kindName(kind)}, $seq)"
}

internal fun kindName(kind: Int) = when (kind) {
    FragmentKind.PART -> "PART"
    FragmentKind.SUBTREE -> "SUBTREE"
    FragmentKind.HEADER -> "HEADER"
    FragmentKind.STRCHUNK -> "STRCHUNK"
    else -> "kind$kind"
}

internal class StoredFragment(
    val key: FragmentKey,
    val parentPre: Long,
    val ownerObjectPre: Long,
    val ownerKey: String,
    val firstIndex: Long,
    val payload: ByteArray,
    val pathHash: Int = 0,
    val parentPathHash: Int = 0,
    val grandparentPathHash: Int = 0,
) {
    val context: FragmentContext
        get() = FragmentContext().set(parentPre, ownerObjectPre, ownerKey.toByteArray(), 0, ownerKey.toByteArray().size, firstIndex).setPaths(pathHash, parentPathHash, grandparentPathHash)

    /** Record size as the log frames it: header, key, context varints, codec, payload, 8-byte padding. */
    val recordBytes: Int
        get() {
            val keyBytes = ownerKey.toByteArray().size
            val context = Varint.size(parentPre + 1) + Varint.size(ownerObjectPre + 1) + Varint.size(keyBytes.toLong()) + keyBytes + Varint.size(firstIndex) + 12
            val raw = 12 + 21 + context + 7 + payload.size
            return (raw + 7) / 8 * 8
        }
}

/** Collects fragments in write order and by key; also a [FragmentLookup]. */
internal class CollectingFragments : FragmentSink, FragmentLookup {
    val written = ArrayList<StoredFragment>()
    val byKey = TreeMap<FragmentKey, StoredFragment>()
    var count = 0L
        private set
    var bytes = 0L
        private set
    var keep = true

    override fun fragment(docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int) {
        count++
        bytes += length
        if (!keep) return
        val stored = StoredFragment(
            FragmentKey(pre, kind, seq), context.parentPre, context.ownerObjectPre,
            String(context.ownerKey, context.ownerKeyOffset, context.ownerKeyLength, Charsets.UTF_8), context.firstIndex,
            payload.copyOfRange(offset, offset + length), context.pathHash, context.parentPathHash, context.grandparentPathHash,
        )
        check(byKey.put(stored.key, stored) == null) { "fragment ${stored.key} written twice" }
        written += stored
    }

    override fun find(pre: Long, kind: Int, seq: Int): PayloadSlice? =
        byKey[FragmentKey(pre, kind, seq)]?.let { PayloadSlice(it.payload, 0, it.payload.size) }
}

internal data class Posting(
    val term: String,
    val containerPre: Long,
    val memberPre: Long,
    val inArray: Boolean,
    val locatorKind: Int,
    val fragmentPre: Long,
    val locatorOffset: Int,
)

internal class CollectingPostings : PostingSink {
    val postings = ArrayList<Posting>()

    /** The path hash of each posting, in order. */
    val pathHashes = ArrayList<Int>()

    override fun posting(term: ByteArray, termOffset: Int, termLength: Int, containerPre: Long, memberPre: Long, inArray: Boolean, locatorKind: Int, fragmentPre: Long, locatorOffset: Int, pathHash: Int) {
        pathHashes += pathHash
        postings += Posting(term.toHex(termOffset, termLength), containerPre, memberPre, inArray, locatorKind, fragmentPre, locatorOffset)
    }
}

internal class CollectingRuns : TermSink {
    val runs = ArrayList<List<Posting>>()

    override fun run(docId: Long, runSeq: Int, run: SortedTermRun) {
        check(runSeq == runs.size) { "run $runSeq after ${runs.size} runs" }
        runs += List(run.size) { i ->
            Posting(
                run.bytes.toHex(run.termOffset(i), run.termLength(i)), run.containerPre(i), run.memberPre(i), run.inArray(i),
                run.locatorKind(i), run.fragmentPre(i), run.locatorOffset(i),
            )
        }
    }
}

internal class CollectingTypes : TypeSink {
    val assignments = LinkedHashMap<Long, String>()

    override fun assign(docId: Long, nodePre: Long, typeName: String) {
        check(assignments.put(nodePre, typeName) == null) { "type assigned twice to $nodePre" }
    }
}

internal class SplitRun(val fragments: CollectingFragments, val runs: CollectingRuns, val types: CollectingTypes, val splitter: Splitter, val spill: MemorySpillFiles)

internal fun newSplitter(options: SplitOptions, keep: Boolean = true): SplitRun {
    val fragments = CollectingFragments().also { it.keep = keep }
    val runs = CollectingRuns()
    val types = CollectingTypes()
    val spill = MemorySpillFiles()
    return SplitRun(fragments, runs, types, Splitter(options, fragments, runs, types, spill), spill)
}

internal fun split(
    json: ByteArray,
    options: SplitOptions,
    typePaths: Map<String, String> = emptyMap(),
    window: Int = Utf8JsonTokenizer.DEFAULT_WINDOW_BYTES,
    chunk: Int = Utf8JsonTokenizer.DEFAULT_STRING_CHUNK_BYTES,
    seed: Long = 42,
    input: InputStream = ByteArrayInputStream(json),
): SplitRun {
    val run = newSplitter(options)
    val tokenizer = Utf8JsonTokenizer(input, options.parseLimits(ParseLimits(maxDepth = 10_000)), window, chunk)
    run.splitter.split(7, tokenizer, TypePaths.compile(typePaths), seed)
    return run
}

internal fun split(json: String, options: SplitOptions, typePaths: Map<String, String> = emptyMap()) = split(json.toByteArray(), options, typePaths)

internal fun reassemble(fragments: FragmentLookup): String {
    val bytes = ByteArrayOutputStream()
    val out = JsonOutput(bytes, 64)
    Reassembler(fragments).writeDocument(out)
    out.flush()
    return bytes.toString(Charsets.UTF_8)
}

/** Every value of [value] in pre-order, with its parent, member key and position in the parent. */
internal class OracleNodes(root: OValue) {
    val values = ArrayList<OValue>()
    val parents = ArrayList<Long>()
    val keys = ArrayList<String?>()
    val indexes = ArrayList<Long>()
    val sizes = ArrayList<Long>()

    init {
        add(root, -1, null, 0)
    }

    private fun add(value: OValue, parent: Long, key: String?, index: Long): Long {
        val pre = values.size.toLong()
        values += value
        parents += parent
        keys += key
        indexes += index
        sizes += 0
        var size = 0L
        when (value) {
            is OObj -> value.members.forEachIndexed { i, (k, v) -> size += 1 + add(v, pre, k, i.toLong()) }
            is OArr -> value.items.forEachIndexed { i, v -> size += 1 + add(v, pre, null, i.toLong()) }
            else -> {}
        }
        sizes[pre.toInt()] = size
        return size
    }

    operator fun get(pre: Long): OValue = values[pre.toInt()]

    fun parent(pre: Long) = parents[pre.toInt()]

    fun key(pre: Long) = keys[pre.toInt()]
}

/** Canonical hash and index terms of oracle values, computed with the engine's (golden-tested) primitives. */
internal object OracleTerms {
    private val canonicalHash = CanonicalHash()
    private val encoder = TermEncoder()

    private val frames = LongArray(CanonicalHash.FRAME_SLOTS * 16_000)

    fun hash(value: OValue): Pair<Long, Long> {
        val out = LongArray(2)
        hash(value, 0, out)
        return out[0] to out[1]
    }

    private fun hash(value: OValue, depth: Int, out: LongArray) {
        when (value) {
            ONull -> canonicalHash.nullValue(out, 0)
            is OBool -> canonicalHash.booleanValue(value.value, out, 0)
            is ONum -> {
                val key = GrowableBytes()
                val text = value.text.toByteArray()
                check(CanonicalNumber.writeKey(text, 0, text.size, key) > 0)
                canonicalHash.number(key.array, 0, key.size, out, 0)
            }
            is OStr -> value.value.toByteArray().let { canonicalHash.string(it, 0, it.size, out, 0) }
            is OArr -> {
                val slot = depth * CanonicalHash.FRAME_SLOTS
                canonicalHash.beginArray(frames, slot)
                for (item in value.items) {
                    hash(item, depth + 1, out)
                    canonicalHash.arrayElement(frames, slot, out, 0)
                }
                canonicalHash.endArray(frames, slot, out, 0)
            }
            is OObj -> {
                val slot = depth * CanonicalHash.FRAME_SLOTS
                canonicalHash.beginObject(frames, slot)
                for ((k, v) in value.members) {
                    hash(v, depth + 1, out)
                    val key = k.toByteArray()
                    canonicalHash.objectMember(frames, slot, key, 0, key.size, out, 0)
                }
                canonicalHash.endObject(frames, slot, out, 0)
            }
        }
    }

    fun term(key: String, value: OValue): String {
        val out = GrowableBytes()
        val k = key.toByteArray()
        encoder.writeKey(out, k, 0, k.size)
        when (value) {
            ONull -> encoder.writeNull(out)
            is OBool -> encoder.writeBoolean(out, value.value)
            is ONum -> value.text.toByteArray().let { check(encoder.writeNumber(out, it, 0, it.size) > 0) }
            is OStr -> value.value.toByteArray().let { encoder.writeString(out, it, 0, it.size) }
            is OArr, is OObj -> {
                val (low, high) = hash(value)
                encoder.writeContainer(out, if (value is OObj) TermTag.OBJECT else TermTag.ARRAY, low, high)
            }
        }
        return out.array.toHex(0, out.size)
    }
}

/** Collapses postings to one per (term, container) with the smallest member, as readers merge runs. */
internal fun collapse(postings: List<Posting>): Map<Pair<String, Long>, Posting> {
    val result = HashMap<Pair<String, Long>, Posting>()
    for (p in postings) {
        val key = p.term to p.containerPre
        val existing = result[key]
        if (existing == null) {
            result[key] = p
        } else {
            check(existing.copy(memberPre = 0) == p.copy(memberPre = 0) && p.inArray) { "postings differ in more than memberPre: $existing / $p" }
            if (p.memberPre < existing.memberPre) result[key] = p
        }
    }
    return result
}

/**
 * Checks everything about one split document that doesn't depend on the flush policy: record bounds, payload
 * validity, stub ↔ fragment agreement, complete non-overlapping PART coverage, HEADER directories, contexts, stored
 * hashes, and every fragment being reachable.
 */
internal fun checkFragments(fragments: CollectingFragments, root: OValue, options: SplitOptions) {
    val nodes = OracleNodes(root)
    val validator = PayloadValidator()
    val byKey = fragments.byKey
    val referenced = HashSet<FragmentKey>()
    val rootKey = if (FragmentKey(0, FragmentKind.SUBTREE, 0) in byKey) FragmentKey(0, FragmentKind.SUBTREE, 0) else FragmentKey(0, FragmentKind.HEADER, 0)
    check(rootKey in byKey) { "no root fragment" }

    fun fail(fragment: StoredFragment, message: String): Nothing = throw AssertionError("fragment ${fragment.key}: $message")

    fun checkHash(fragment: StoredFragment, pre: Long, low: Long, high: Long) {
        val expected = OracleTerms.hash(nodes[pre])
        if (expected != (low to high)) fail(fragment, "hash of pre $pre is wrong")
    }

    fun checkContext(fragment: StoredFragment, valuePre: Long, isPart: Boolean) {
        // A PART's top-level values sit in its split container, which is the value that has an owner.
        val expectedParent = nodes.parent(valuePre)
        val holder = if (isPart) expectedParent else valuePre
        if (fragment.parentPre != expectedParent) fail(fragment, "parentPre ${fragment.parentPre}, expected $expectedParent")
        val holderParent = nodes.parent(holder)
        val ownerExpected = fragment.key.kind != FragmentKind.STRCHUNK && holderParent >= 0 && nodes[holderParent] is OObj
        if (ownerExpected) {
            if (fragment.ownerObjectPre != holderParent || fragment.ownerKey != nodes.key(holder)) {
                fail(fragment, "owner (${fragment.ownerObjectPre}, ${fragment.ownerKey}), expected ($holderParent, ${nodes.key(holder)})")
            }
        } else if (fragment.ownerObjectPre != -1L) {
            fail(fragment, "unexpected owner ${fragment.ownerObjectPre}")
        }
        val expectedIndex = if (isPart) nodes.indexes[valuePre.toInt()] else 0
        if (fragment.firstIndex != expectedIndex) fail(fragment, "firstIndex ${fragment.firstIndex}, expected $expectedIndex")
    }

    for (fragment in fragments.written) {
        val key = fragment.key
        if (fragment.recordBytes > options.maxRecordBytes) fail(fragment, "record of ${fragment.recordBytes} bytes exceeds ${options.maxRecordBytes}")
        validator.validate(fragment.payload, 0, fragment.payload.size, key.kind, key.pre, key.seq, "fragment $key")
        when (key.kind) {
            FragmentKind.SUBTREE, FragmentKind.PART -> {
                val reader = PayloadReader()
                reader.open(fragment.payload, 0, fragment.payload.size, key.kind, key.pre)
                if (key.kind == FragmentKind.PART) {
                    checkContext(fragment, key.pre, isPart = true)
                    val container = nodes.parent(key.pre)
                    if (container < 0) fail(fragment, "PART of no container")
                    val kind = if (nodes[container] is OObj) PayloadTag.OBJ else PayloadTag.ARR
                    if (reader.partKind != kind) fail(fragment, "PART kind")
                } else {
                    checkContext(fragment, key.pre, isPart = false)
                }
                while (true) {
                    val event = reader.next()
                    if (event == PayloadReader.END) break
                    val pre = reader.pre
                    val node = nodes[pre]
                    when (event) {
                        PayloadReader.REF -> {
                            referenced += FragmentKey(pre, FragmentKind.SUBTREE, 0)
                            if (FragmentKey(pre, FragmentKind.SUBTREE, 0) !in byKey) fail(fragment, "REF at $pre has no SUBTREE")
                            val kind = if (node is OObj) PayloadTag.OBJ else if (node is OArr) PayloadTag.ARR else -1
                            if (reader.containerKind != kind || reader.size != nodes.sizes[pre.toInt()]) fail(fragment, "REF at $pre kind/size")
                            checkHash(fragment, pre, reader.hashLow, reader.hashHigh)
                        }
                        PayloadReader.SPLIT -> {
                            referenced += FragmentKey(pre, FragmentKind.HEADER, 0)
                            val count = when (node) {
                                is OObj -> node.members.size
                                is OArr -> node.items.size
                                else -> fail(fragment, "SPLIT at $pre isn't a container")
                            }
                            val kind = if (node is OObj) PayloadTag.OBJ else PayloadTag.ARR
                            if (reader.containerKind != kind || reader.count != count.toLong() || reader.size != nodes.sizes[pre.toInt()]) fail(fragment, "SPLIT at $pre kind/count/size")
                            checkHash(fragment, pre, reader.hashLow, reader.hashHigh)
                        }
                        PayloadReader.BIGSTR -> {
                            val bytes = (node as? OStr)?.value?.toByteArray() ?: fail(fragment, "BIGSTR at $pre isn't a string")
                            if (reader.size != bytes.size.toLong()) fail(fragment, "BIGSTR size")
                            if (bytes.size <= options.inlineStringBytes) fail(fragment, "BIGSTR for a short string")
                            if (!fragment.payload.copyOfRange(reader.offset, reader.offset + 16).contentEquals(bytes.copyOf(16))) fail(fragment, "BIGSTR prefix")
                            for (seq in 0 until reader.count.toInt()) referenced += FragmentKey(pre, FragmentKind.STRCHUNK, seq)
                            if (FragmentKey(pre, FragmentKind.STRCHUNK, reader.count.toInt()) in byKey) fail(fragment, "extra STRCHUNK")
                            checkHash(fragment, pre, reader.hashLow, reader.hashHigh)
                        }
                        PayloadReader.STR -> if (reader.length > options.inlineStringBytes) fail(fragment, "inline string of ${reader.length} bytes")
                    }
                }
            }
            FragmentKind.HEADER -> checkContext(fragment, key.pre, isPart = false)
            FragmentKind.STRCHUNK -> {
                checkContext(fragment, key.pre, isPart = false)
                val reader = PayloadReader()
                reader.open(fragment.payload, 0, fragment.payload.size, key.kind, key.pre)
                reader.next()
                if (reader.length > options.fragmentBytes) fail(fragment, "STRCHUNK of ${reader.length} bytes")
            }
            else -> fail(fragment, "unknown kind")
        }
    }

    // Split containers: directory records in seq order list exactly the PARTs, which cover (pre, pre + size].
    for (fragment in fragments.written.filter { it.key.kind == FragmentKind.HEADER && it.key.seq == 0 }) {
        val pre = fragment.key.pre
        val header = HeaderReader()
        header.open(fragment.payload, 0, fragment.payload.size, 0)
        val node = nodes[pre]
        val count = (node as? OObj)?.members?.size ?: (node as? OArr)?.items?.size ?: fail(fragment, "HEADER of a scalar")
        if (header.count != count.toLong() || header.size != nodes.sizes[pre.toInt()]) fail(fragment, "HEADER count/size")
        checkHash(fragment, pre, header.hashLow, header.hashHigh)
        val entries = ArrayList<Triple<Long, Long, Long>>()
        var seq = 0
        while (true) {
            val record = byKey[FragmentKey(pre, FragmentKind.HEADER, seq)] ?: break
            referenced += record.key
            header.open(record.payload, 0, record.payload.size, seq)
            while (header.nextEntry()) entries += Triple(header.firstPre, header.firstIndex, header.memberCount)
            seq++
        }
        var nextPre = pre + 1
        var nextIndex = 0L
        for ((firstPre, firstIndex, memberCount) in entries) {
            val part = byKey[FragmentKey(firstPre, FragmentKind.PART, 0)] ?: fail(fragment, "directory names missing PART $firstPre")
            referenced += part.key
            if (firstPre != nextPre || firstIndex != nextIndex) fail(fragment, "directory entry ($firstPre, $firstIndex) expected ($nextPre, $nextIndex)")
            val reader = PayloadReader()
            reader.open(part.payload, 0, part.payload.size, FragmentKind.PART, firstPre)
            while (reader.next() != PayloadReader.END) continue
            if (reader.partMemberCount != memberCount) fail(fragment, "directory member count")
            nextPre = reader.nextValuePre
            nextIndex += memberCount
        }
        if (nextPre != pre + nodes.sizes[pre.toInt()] + 1 || nextIndex != count.toLong()) fail(fragment, "PARTs don't cover the container")
        val parts = header.partCount
        header.open(fragment.payload, 0, fragment.payload.size, 0)
        if (header.partCount != entries.size.toLong()) fail(fragment, "partCount $parts, directory has ${entries.size}")
    }

    referenced += rootKey
    val unreferenced = byKey.keys - referenced
    check(unreferenced.isEmpty()) { "fragments nothing points to: $unreferenced" }
    if (rootKey.kind == FragmentKind.HEADER) check(nodes[0] is OObj || nodes[0] is OArr)
}

/** Expected postings from the oracle, collapsed: (term, container) → (member, inArray). */
internal fun expectedPostings(value: OValue, hashTerms: Boolean): Map<Pair<String, Long>, Pair<Long, Boolean>> {
    val oracle = app.oreshkov.delva.testkit.oracle.OracleStore(app.oreshkov.delva.testkit.oracle.OracleOptions(hashTerms = hashTerms, limits = app.oreshkov.delva.testkit.json.JsonLimits(maxDepth = 10_000)))
    val docId = oracle.insert(value)
    return oracle.postings(docId).associate { (OracleTerms.term(it.key, it.value) to it.containerPre) to (it.memberPre to it.inArray) }
}

/**
 * Checks that every posting's locator points where it should: PART/SUBTREE → the object's tag byte in that fragment;
 * HEADER → the split object's header; UNRESOLVED → only in-array postings.
 */
internal fun checkLocators(postings: Collection<Posting>, fragments: CollectingFragments, root: OValue) {
    val nodes = OracleNodes(root)
    val starts = HashMap<FragmentKey, Map<Int, Long>>()
    fun objectStarts(key: FragmentKey): Map<Int, Long> = starts.getOrPut(key) {
        val fragment = fragments.byKey[key] ?: throw AssertionError("locator names missing fragment $key")
        val reader = PayloadReader()
        reader.open(fragment.payload, 0, fragment.payload.size, key.kind, key.pre)
        val map = HashMap<Int, Long>()
        while (true) {
            when (reader.next()) {
                PayloadReader.END -> break
                PayloadReader.START_OBJECT -> map[reader.valueOffset] = reader.pre
            }
        }
        map
    }
    for (p in postings) {
        if (nodes[p.containerPre] !is OObj) throw AssertionError("posting container ${p.containerPre} isn't an object: $p")
        when (p.locatorKind) {
            PostingLocator.PART, PostingLocator.SUBTREE -> {
                val kind = if (p.locatorKind == PostingLocator.PART) FragmentKind.PART else FragmentKind.SUBTREE
                val at = objectStarts(FragmentKey(p.fragmentPre, kind, 0))[p.locatorOffset]
                if (at != p.containerPre) throw AssertionError("locator of $p points at object $at")
            }
            PostingLocator.HEADER -> {
                val header = fragments.byKey[FragmentKey(p.containerPre, FragmentKind.HEADER, 0)] ?: throw AssertionError("no HEADER for $p")
                val reader = HeaderReader()
                reader.open(header.payload, 0, header.payload.size, 0)
                if (reader.containerKind != PayloadTag.OBJ || p.fragmentPre != p.containerPre || p.locatorOffset != 0) throw AssertionError("HEADER locator of $p")
            }
            PostingLocator.UNRESOLVED -> if (!p.inArray || p.fragmentPre != 0L || p.locatorOffset != 0) throw AssertionError("UNRESOLVED locator for $p")
            else -> throw AssertionError("locator kind of $p")
        }
    }
}
