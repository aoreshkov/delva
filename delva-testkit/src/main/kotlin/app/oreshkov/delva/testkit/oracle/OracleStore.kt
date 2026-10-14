package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.testkit.json.JsonLimits
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.json.OracleNumbers
import app.oreshkov.delva.testkit.json.ParseResult
import app.oreshkov.delva.testkit.json.canonicalForm
import java.util.TreeMap

enum class OResultMode { WHOLE_DOCUMENT, CONTAINING_ELEMENT }

enum class ONestedHits { ALL, OUTERMOST_ONLY }

data class ONodeRef(val docId: Long, val pre: Long) : Comparable<ONodeRef> {
    override fun compareTo(other: ONodeRef): Int = compareValuesBy(this, other, { it.docId }, { it.pre })
}

/** Resume point: hits strictly after `(docId, containerPre)`. */
data class OCursor(val docId: Long, val containerPre: Long)

data class OQuery(
    val key: String,
    val value: OValue,
    val mode: OResultMode = OResultMode.WHOLE_DOCUMENT,
    val nested: ONestedHits = ONestedHits.ALL,
    val limit: Int = 100,
    val after: OCursor? = null,
    /** Only containing objects of this type match. */
    val type: String? = null,
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }
}

/** One posting of a document after collapsing: the term `(key, value)` found in the object at [containerPre]. */
data class OPosting(val key: String, val value: OValue, val containerPre: Long, val memberPre: Long, val inArray: Boolean)

/**
 * A search hit. [node] is the containing object (or the root for [OResultMode.WHOLE_DOCUMENT]); [memberPre] is the
 * matched member value (-1 for whole-document hits); [json] is the expected output of the node.
 */
data class OHit(val node: ONodeRef, val memberPre: Long, val inArray: Boolean, val type: String?, val json: String) {
    val cursor: OCursor get() = OCursor(node.docId, node.pre)
}

data class OPage(val hits: List<OHit>, val next: OCursor?)

/**
 * One document of [OracleStore.insertAll]: its JSON, its types, the documents it replaces, and whether it replaces the
 * document that has its key ([OracleOptions.documentKey]).
 */
class OSource(val json: ByteArray, val types: Map<String, String> = emptyMap(), val replaces: List<Long> = emptyList(), val replaceKey: Boolean = false)

/** What [OracleStore.insertAll] did with one source. */
sealed interface OOutcome {
    data class Inserted(val docId: Long) : OOutcome

    data class Rejected(val reason: RejectReason) : OOutcome

    /** The documents the source replaces that weren't live, ascending; or the document that has [key]. */
    data class Conflicted(val conflicting: List<Long>, val key: String? = null) : OOutcome
}

data class OracleOptions(
    /** Whole-object and whole-array terms, needed to search for object and array literals. */
    val hashTerms: Boolean = true,
    val firstDocId: Long = 1,
    val limits: JsonLimits = JsonLimits(),
    val maxTypeNameBytes: Int = 256,
    /** The document key path (a singular path); null for a store without document keys. */
    val documentKey: String? = null,
)

/**
 * Executable model of Delva's observable semantics, used as the oracle in model-based tests. Sequential and in
 * memory; no snapshots or concurrency.
 *
 * - Every value gets a pre-order number: the root is 0, a value's descendants follow it, and a container's `size` is
 *   its number of descendants.
 * - Each object member `k: v` of the object at pre `c` produces the term `(k, v)` → `(c, pre(v))`. When `v` is an
 *   array, each element `e` also produces `(k, e)` → `(c, pre(e))` with the in-array flag (one level deep only).
 *   Objects anywhere, including inside arrays, are containing objects for their own members. A scalar root has no
 *   terms. Container values produce terms only with [OracleOptions.hashTerms].
 * - One posting per (term, containing object); the smallest member pre is kept.
 * - Terms match by semantic equality: typed, numbers by value, strings by code points, objects ignoring member order,
 *   arrays in order.
 * - Hits are ordered by (docId, container pre). Nested-hit filtering applies to the whole result sequence of a
 *   document, before pagination.
 * - A rejected insert still consumes its docId.
 */
class OracleStore(val options: OracleOptions = OracleOptions()) {
    private class Node(val pre: Long, val value: OValue, val size: Long, val parentPre: Long)

    private class Posting(val key: String, val value: OValue, val containerPre: Long, val memberPre: Long, val inArray: Boolean)

    private class Doc(val docId: Long, val root: OValue, val nodes: List<Node>, val postings: Map<String, TreeMap<Long, Posting>>) {
        var deleted = false

        /** The document key, with [OracleOptions.documentKey]. */
        var key: String? = null
    }

    private val docs = TreeMap<Long, Doc>()
    private val types = TreeMap<ONodeRef, String>()
    private val names = java.util.TreeSet<String>()

    /** Every type name ever assigned, sorted. */
    val typeNames: Set<String> get() = names
    private var nextDocId = options.firstDocId

    /** The docId the next insert will get. */
    val peekNextDocId: Long get() = nextDocId

    /** Makes the next insert get [docId]; the skipped docIds stay unused, as after a crash. */
    fun skipTo(docId: Long) {
        require(docId >= nextDocId) { "docId $docId is below the next docId $nextDocId" }
        nextDocId = docId
    }

    // ---- insert ---------------------------------------------------------------------------------------------------

    /**
     * Inserts a document. [types] maps JSON paths ([JsonPath]) to type names, applied atomically with the insert.
     * Throws [IllegalArgumentException] for an invalid path, [InvalidTypeNameException] for an invalid type name
     * (neither consumes a docId), and [DocumentRejectedException] when the document is rejected.
     */
    fun insert(json: ByteArray, types: Map<String, String> = emptyMap()): Long = add(json, types, emptyList(), replaceKey = false)

    /**
     * Adds a document, deleting [replaces] (all live) with it, and with [replaceKey] the document that has its key.
     *
     * Rejections come in the order the engine finds them while streaming: parse errors first (the oracle doesn't model
     * where in the text they are), then a type path at a value that isn't an object and a key that isn't a string or is
     * too long, whichever value comes first, then a type path that matches nothing, then a missing key. A key that a
     * live document other than [replaces] has conflicts; that consumes the docId, as a rejection does.
     */
    private fun add(json: ByteArray, types: Map<String, String>, replaces: List<Long>, replaceKey: Boolean): Long {
        val paths = types.map { (path, type) -> JsonPath.parse(path) to validateTypeName(type) }
        val docId = nextDocId++
        val root = when (val parsed = OracleJson.parse(json, options.limits)) {
            is ParseResult.Accepted -> parsed.value
            is ParseResult.Rejected -> throw DocumentRejectedException(parsed.reason, -1, detail = parsed.detail)
        }
        val doc = index(docId, root)
        fun nodeAt(path: JsonPath): Node? = path.resolve(root)?.let { t -> doc.nodes.first { it.value === t } }
        val targets = paths.map { (path, type) -> Triple(path, type, nodeAt(path)) }
        val keyNode = keyPath?.let { nodeAt(it) }
        var first: DocumentRejectedException? = null
        var firstPre = Long.MAX_VALUE
        for ((path, _, node) in targets) {
            if (node != null && node.value !is OObj && node.pre < firstPre) {
                first = DocumentRejectedException(RejectReason.TYPE_PATH_UNMATCHED, -1, jsonPath = path.text)
                firstPre = node.pre
            }
        }
        if (keyNode != null && keyNode.pre < firstPre) {
            val value = keyNode.value
            val reason = when {
                value !is OStr -> RejectReason.KEY_MISSING
                value.value.toByteArray().size > MAX_KEY_BYTES -> RejectReason.LIMIT_EXCEEDED
                else -> null
            }
            if (reason != null) first = DocumentRejectedException(reason, -1, jsonPath = keyPath.text)
        }
        first?.let { throw it }
        targets.firstOrNull { it.third == null }?.let { throw DocumentRejectedException(RejectReason.TYPE_PATH_UNMATCHED, -1, jsonPath = it.first.text) }
        if (keyPath != null && keyNode == null) throw DocumentRejectedException(RejectReason.KEY_MISSING, -1, jsonPath = keyPath.text)
        val assignments = HashMap<Long, String>()
        for ((path, type, node) in targets) {
            val previous = assignments.put(node!!.pre, type)
            require(previous == null || previous == type) { "paths assign different types to the object at $path" }
        }
        val key = (keyNode?.value as? OStr)?.value
        val deleting = replaces.toMutableList()
        if (key != null) {
            val holder = keys[key]
            if (holder != null && holder !in replaces) {
                if (!replaceKey) throw WriteConflictException(listOf(DocId(holder)), key)
                deleting += holder
            }
        }
        doc.key = key
        docs[docId] = doc
        assignments.forEach { (pre, type) ->
            this.types[ONodeRef(docId, pre)] = type
            names += type
        }
        for (target in deleting) markDeleted(docs.getValue(target))
        if (key != null) keys[key] = docId
        return docId
    }

    private fun markDeleted(doc: Doc) {
        doc.deleted = true
        doc.key?.let { if (keys[it] == doc.docId) keys.remove(it) }
    }

    // ---- document keys --------------------------------------------------------------------------------------------

    private val keyPath: JsonPath? = options.documentKey?.let { JsonPath.parse(it) }

    /** Live documents by key. */
    private val keys = HashMap<String, Long>()

    /** The live document with [key], or null. */
    fun get(key: String): Long? = keys[key]

    /** The key of the document [docId], live or deleted; null without document keys. */
    fun keyOf(docId: Long): String? = docs[docId]?.key

    /** Live documents whose key starts with [prefix], in key order: by code points, which is UTF-8 byte order. */
    fun keyed(prefix: String = ""): List<Pair<String, Long>> =
        keys.entries.filter { it.key.startsWith(prefix) }.map { it.key to it.value }
            .sortedWith { a, b -> java.util.Arrays.compareUnsigned(a.first.toByteArray(), b.first.toByteArray()) }

    /** Inserts as [insert] does, replacing the live document with the same key, if there is one. */
    fun upsert(json: ByteArray, types: Map<String, String> = emptyMap()): Long {
        checkNotNull(keyPath) { "the store has no document key" }
        return add(json, types, emptyList(), replaceKey = true)
    }

    fun insert(json: String, types: Map<String, String> = emptyMap()): Long = insert(json.toByteArray(), types)

    fun insert(value: OValue, types: Map<String, String> = emptyMap()): Long = insert(JsonText.minifiedBytes(value), types)

    private fun index(docId: Long, root: OValue): Doc {
        val nodes = ArrayList<Node>()
        number(root, -1, nodes)
        val postings = HashMap<String, TreeMap<Long, Posting>>()
        fun add(key: String, value: OValue, containerPre: Long, memberPre: Long, inArray: Boolean) {
            if ((value is OObj || value is OArr) && !options.hashTerms) return
            val byContainer = postings.getOrPut(termKey(key, value)) { TreeMap() }
            val existing = byContainer[containerPre]
            if (existing == null || memberPre < existing.memberPre) byContainer[containerPre] = Posting(key, value, containerPre, memberPre, inArray)
        }
        for (node in nodes) {
            val obj = node.value as? OObj ?: continue
            var childPre = node.pre + 1
            for ((key, member) in obj.members) {
                add(key, member, node.pre, childPre, inArray = false)
                if (member is OArr) {
                    var elementPre = childPre + 1
                    for (element in member.items) {
                        add(key, element, node.pre, elementPre, inArray = true)
                        elementPre += 1 + nodes[elementPre.toInt()].size
                    }
                }
                childPre += 1 + nodes[childPre.toInt()].size
            }
        }
        return Doc(docId, root, nodes, postings)
    }

    /** Appends [value] and its descendants in pre-order; returns the value's size. */
    private fun number(value: OValue, parentPre: Long, nodes: MutableList<Node>): Long {
        val index = nodes.size
        nodes += Node(index.toLong(), value, 0, parentPre) // placeholder until the size is known
        val children: List<OValue> = when (value) {
            is OObj -> value.members.map { it.second }
            is OArr -> value.items
            else -> emptyList()
        }
        var size = 0L
        for (child in children) size += 1 + number(child, index.toLong(), nodes)
        nodes[index] = Node(index.toLong(), value, size, parentPre)
        return size
    }

    private fun termKey(key: String, value: OValue): String = "${key.length}:$key${canonicalForm(value)}"

    // ---- search ---------------------------------------------------------------------------------------------------

    fun search(query: OQuery): OPage {
        validateLiteral(query.key, query.value)
        require(options.hashTerms || (query.value !is OObj && query.value !is OArr)) {
            "object and array literals need hash terms"
        }
        val term = termKey(query.key, query.value)
        val hits = ArrayList<OHit>()
        for (doc in docs.values) {
            if (doc.deleted) continue
            val postings = doc.postings[term] ?: continue
            var matching = postings.values.filter { query.type == null || types[ONodeRef(doc.docId, it.containerPre)] == query.type }
            if (matching.isEmpty()) continue
            if (query.mode == OResultMode.WHOLE_DOCUMENT) {
                hits += OHit(ONodeRef(doc.docId, 0), -1, false, types[ONodeRef(doc.docId, 0)], JsonText.minified(doc.root))
                continue
            }
            if (query.nested == ONestedHits.OUTERMOST_ONLY) {
                var outerEnd = -1L
                matching = matching.filter { p ->
                    val node = doc.nodes[p.containerPre.toInt()]
                    if (p.containerPre <= outerEnd) {
                        false
                    } else {
                        outerEnd = node.pre + node.size
                        true
                    }
                }
            }
            for (p in matching) {
                val ref = ONodeRef(doc.docId, p.containerPre)
                hits += OHit(ref, p.memberPre, p.inArray, types[ref], JsonText.minified(doc.nodes[p.containerPre.toInt()].value))
            }
        }
        val after = query.after
        val remaining = if (after == null) hits else hits.filter { OCursor(it.node.docId, it.node.pre) > after }
        val page = remaining.take(query.limit)
        val next = if (remaining.size > query.limit) page.last().cursor else null
        return OPage(page, next)
    }

    /** All hits of [query], following cursors page by page. */
    fun searchAll(query: OQuery): List<OHit> {
        val all = ArrayList<OHit>()
        var page = search(query)
        all += page.hits
        while (page.next != null) {
            page = search(query.copy(after = page.next))
            all += page.hits
        }
        return all
    }

    private fun validateLiteral(key: String, value: OValue) {
        if (hasLoneSurrogate(key)) throw DocumentRejectedException(RejectReason.INVALID_STRING, -1, detail = "query key")
        when (value) {
            is ONum -> if (!OracleNumbers.inRange(value.text)) {
                throw DocumentRejectedException(RejectReason.NUMBER_OUT_OF_RANGE, -1, detail = "query literal ${value.text}")
            }
            is OStr -> if (hasLoneSurrogate(value.value)) throw DocumentRejectedException(RejectReason.INVALID_STRING, -1, detail = "query literal")
            is OArr -> value.items.forEach { validateLiteral("", it) }
            is OObj -> value.members.forEach { (k, v) -> validateLiteral(k, v) }
            ONull, is OBool -> Unit
        }
    }

    // ---- read, types, delete --------------------------------------------------------------------------------------

    /** The stored value at [ref]. */
    fun value(ref: ONodeRef): OValue = node(ref).value

    /** Expected `writeJsonTo` output of the value at [ref]. */
    fun read(ref: ONodeRef): String = JsonText.minified(value(ref))

    /** Pre-order number and size of every node of a document, in pre order. */
    fun nodes(docId: Long): List<Pair<Long, Long>> = liveDoc(docId).nodes.map { it.pre to it.size }

    /**
     * Every posting of a document, one per (term, containing object) with the smallest member pre, ordered by
     * container pre. The key and value of a collapsed posting are those of its smallest member.
     */
    fun postings(docId: Long): List<OPosting> =
        liveDoc(docId).postings.values.flatMap { byContainer -> byContainer.values.map { OPosting(it.key, it.value, it.containerPre, it.memberPre, it.inArray) } }
            .sortedWith(compareBy({ it.containerPre }, { it.memberPre }))

    fun liveDocIds(): List<Long> = docs.values.filter { !it.deleted }.map { it.docId }

    /** Sets (or with null, removes) the type of the object at [ref]. The last assignment wins. */
    fun assignType(ref: ONodeRef, type: String?) = assignTypes(listOf(ref to type))

    /**
     * Applies [assignments] in order, atomically: every name is validated ([InvalidTypeNameException]), then every node
     * ([DocumentNotFoundException], or [IllegalArgumentException] for a missing node or one that isn't an object), before
     * anything changes.
     */
    fun assignTypes(assignments: List<Pair<ONodeRef, String?>>) {
        for ((_, type) in assignments) type?.let { validateTypeName(it) }
        for ((ref, _) in assignments) require(node(ref).value is OObj) { "types can only be assigned to objects; $ref is not an object" }
        for ((ref, type) in assignments) {
            if (type == null) {
                types.remove(ref)
            } else {
                types[ref] = type
                names += type
            }
        }
    }

    /** Pre-order numbers of the objects of a document. */
    fun objectPres(docId: Long): List<Long> = liveDoc(docId).nodes.filter { it.value is OObj }.map { it.pre }

    fun typeOf(ref: ONodeRef): String? {
        node(ref)
        return types[ref]
    }

    /** Objects of [type] in (docId, pre) order, strictly after [after]. */
    fun objectsOfType(type: String, after: ONodeRef? = null, limit: Int = 100): List<ONodeRef> =
        types.entries.asSequence()
            .filter { it.value == type && docs.getValue(it.key.docId).deleted.not() && (after == null || it.key > after) }
            .map { it.key }
            .take(limit)
            .toList()

    fun countOfType(type: String): Long =
        types.entries.count { it.value == type && !docs.getValue(it.key.docId).deleted }.toLong()

    /** Removes [type] from every object of a live document, atomically; returns how many lost it. The name stays listed. */
    fun clearType(type: String): Long {
        validateTypeName(type)
        val cleared = types.entries.filter { it.value == type && !docs.getValue(it.key.docId).deleted }.map { it.key }
        cleared.forEach { types.remove(it) }
        return cleared.size.toLong()
    }

    /**
     * Deletes a document; returns false when it was already deleted. A docId that never named a stored document (not
     * assigned yet, or given to a rejected insert) throws [DocumentNotFoundException].
     */
    fun delete(docId: Long): Boolean = deleteAll(listOf(docId)) == 1

    /**
     * Deletes the documents of [docIds] atomically: every docId is checked as in [delete] before anything changes.
     * Returns how many were deleted, counting a repeated docId once.
     */
    fun deleteAll(docIds: Collection<Long>): Int {
        val targets = docIds.distinct().map { docs[it] ?: throw DocumentNotFoundException(DocId(it)) }
        return targets.count { doc -> !doc.deleted.also { markDeleted(doc) } }
    }

    /** DocIds of deleted documents, ascending. */
    fun deletedDocIds(): List<Long> = docs.values.filter { it.deleted }.map { it.docId }

    // ---- replace --------------------------------------------------------------------------------------------------

    /**
     * Inserts a document as [insert] does and deletes [replaces] with it, atomically. Invalid types throw first, as in
     * [insert]; then, when a document of [replaces] isn't live (deleted, never stored, not assigned yet), it throws
     * [WriteConflictException] naming those, ascending, before the document is parsed and without consuming a docId.
     */
    fun replace(replaces: Collection<Long>, json: ByteArray, types: Map<String, String> = emptyMap()): Long =
        replace(replaces, json, types, alsoConflicting = emptySet())

    fun replace(replaces: Collection<Long>, json: String, types: Map<String, String> = emptyMap()): Long =
        replace(replaces, json.toByteArray(), types)

    private fun replace(replaces: Collection<Long>, json: ByteArray, types: Map<String, String>, alsoConflicting: Set<Long>, replaceKey: Boolean = false): Long {
        types.forEach { (path, type) ->
            JsonPath.parse(path)
            validateTypeName(type)
        }
        val targets = replaces.distinct().sorted()
        val conflicting = targets.filter { it in alsoConflicting || docs[it]?.deleted != false }
        if (conflicting.isNotEmpty()) throw WriteConflictException(conflicting.map { DocId(it) })
        return add(json, types, targets, replaceKey)
    }

    /**
     * `insertAll`: every source's types are checked before any is inserted, then each source is a [replace] in order,
     * except that a source can't replace a document inserted by the same call. A rejection or a conflict is that
     * source's outcome.
     */
    fun insertAll(sources: List<OSource>): List<OOutcome> {
        for (source in sources) {
            source.types.forEach { (path, type) ->
                JsonPath.parse(path)
                validateTypeName(type)
            }
        }
        val inserted = HashSet<Long>()
        return sources.map { source ->
            val first = nextDocId
            try {
                OOutcome.Inserted(replace(source.replaces, source.json, source.types, inserted, source.replaceKey))
            } catch (e: DocumentRejectedException) {
                OOutcome.Rejected(e.reason)
            } catch (e: WriteConflictException) {
                OOutcome.Conflicted(e.conflicting.map { it.raw }, e.key)
            } finally {
                (first until nextDocId).forEach { inserted += it }
            }
        }
    }

    // ---- compare --------------------------------------------------------------------------------------------------

    /**
     * The differences between [a] and [b] ([OracleComparison]). Stored operands are checked first, in order
     * ([DocumentNotFoundException], [IllegalArgumentException] for a missing node), then JSON operands are parsed
     * ([DocumentRejectedException]).
     */
    fun diff(a: OOperand, b: OOperand, options: OCompareOptions = OCompareOptions()): List<ODifference> {
        for (operand in listOf(a, b)) if (operand is OOperand.Stored) node(operand.ref)
        return OracleComparison(this, options, side(a), side(b)).diff()
    }

    fun isEqual(a: OOperand, b: OOperand, options: OCompareOptions = OCompareOptions()): Boolean =
        diff(a, b, options.copy(maxDifferences = 1, maxValueBytes = 0)).isEmpty()

    private fun side(operand: OOperand): OracleComparison.Side = when (operand) {
        is OOperand.Stored -> OracleComparison.Side(operand.ref.docId, operand.ref.pre, node(operand.ref).value)
        is OOperand.Json -> when (val parsed = OracleJson.parse(operand.json.toByteArray(), options.limits)) {
            is ParseResult.Accepted -> OracleComparison.Side(null, 0, parsed.value)
            is ParseResult.Rejected -> throw DocumentRejectedException(parsed.reason, -1, detail = parsed.detail)
        }
    }

    private fun liveDoc(docId: Long): Doc {
        val doc = docs[docId]
        if (doc == null || doc.deleted) throw DocumentNotFoundException(DocId(docId))
        return doc
    }

    private fun node(ref: ONodeRef): Node {
        val doc = liveDoc(ref.docId)
        require(ref.pre in 0 until doc.nodes.size) { "no node $ref" }
        return doc.nodes[ref.pre.toInt()]
    }

    private fun validateTypeName(type: String): String {
        val bytes = type.toByteArray().size
        if (bytes == 0 || bytes > options.maxTypeNameBytes) throw InvalidTypeNameException("length $bytes bytes, allowed 1..${options.maxTypeNameBytes}")
        if (hasLoneSurrogate(type)) throw InvalidTypeNameException("not valid Unicode")
        if (type.codePoints().anyMatch { Character.isISOControl(it) }) throw InvalidTypeNameException("contains a control character")
        return type
    }

    private companion object {
        /** The longest document key, in UTF-8 bytes. */
        const val MAX_KEY_BYTES = 1024
    }

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return true
                i++
            }
        }
        return false
    }
}

private operator fun OCursor.compareTo(other: OCursor): Int = compareValuesBy(this, other, { it.docId }, { it.containerPre })
