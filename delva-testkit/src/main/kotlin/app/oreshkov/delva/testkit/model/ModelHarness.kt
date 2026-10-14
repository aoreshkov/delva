package app.oreshkov.delva.testkit.model

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CompareOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DiffOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyConflict
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchHit
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonMutator
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonShrinker
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.oracle.JsonPath
import app.oreshkov.delva.testkit.oracle.JsonPathOracle
import app.oreshkov.delva.testkit.oracle.OCompareOptions
import app.oreshkov.delva.testkit.oracle.OCursor
import app.oreshkov.delva.testkit.oracle.OHit
import app.oreshkov.delva.testkit.oracle.ONestedHits
import app.oreshkov.delva.testkit.oracle.ONodeRef
import app.oreshkov.delva.testkit.oracle.OOperand
import app.oreshkov.delva.testkit.oracle.OOutcome
import app.oreshkov.delva.testkit.oracle.OQuery
import app.oreshkov.delva.testkit.oracle.OResultMode
import app.oreshkov.delva.testkit.oracle.OSource
import app.oreshkov.delva.testkit.oracle.OracleStore
import app.oreshkov.delva.testkit.oracle.toOracle
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.Shrinker
import app.oreshkov.delva.testkit.property.Shrinkers
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import kotlin.random.Random

/** One step of a model-based test. Operations pick their targets when they run, so any subsequence is valid. */
sealed interface ModelOp {
    /** Inserts [doc], spelled with the formatting chosen by [spelling]. */
    data class Insert(val doc: OValue, val spelling: Long) : ModelOp

    /** Inserts [doc] spelled with one damaged byte; engine and oracle must agree on accepting or rejecting it. */
    data class InsertDamaged(val doc: OValue, val damage: Long) : ModelOp

    /**
     * Inserts [doc] with type assignments picked by [paths]: paths to some of its values (mostly objects), sometimes one
     * that selects nothing.
     */
    data class InsertTyped(val doc: OValue, val spelling: Long, val paths: Long) : ModelOp

    /**
     * Searches for a term picked by [pick] from the stored documents (or a miss), reads up to [pages] pages (all
     * with -1) and inserts [between] after the first page, which the open result must not see. With [deleteBetween],
     * it also deletes a document picked by it after the first page, preferably one with hits still to come, which the
     * open result must keep returning. With [typePick], only containing objects of a type picked by it match. With
     * [descending], the hits come newest first and are compared with the oracle's reversed.
     */
    data class Search(
        val pick: Long,
        val mode: OResultMode,
        val nested: ONestedHits,
        val limit: Int,
        val maxBytes: Long?,
        val pages: Int,
        val between: List<OValue>,
        val typePick: Long? = null,
        val deleteBetween: Long? = null,
        val descending: Boolean = false,
    ) : ModelOp

    /**
     * Deletes documents picked by [picks] in one call: mostly stored ones, sometimes deleted ones, repeated docIds,
     * docIds of rejected inserts or docIds not assigned yet.
     */
    data class Delete(val picks: List<Long>) : ModelOp

    /** Assigns, reassigns or removes types of nodes picked by [picks] in one call: mostly objects, sometimes not. */
    data class AssignTypes(val picks: List<Long>) : ModelOp

    /** Reads the type of a node picked by [pick]. */
    data class TypeOf(val pick: Long) : ModelOp

    /** Lists up to [limit] objects of a type picked by [pick], from the start or after one of them. */
    data class ObjectsOfType(val pick: Long, val limit: Int) : ModelOp

    /** Lists every object of a type picked by [pick] in pages of [limit], resuming from each page's cursor as text. */
    data class ObjectsOfTypePage(val pick: Long, val limit: Int) : ModelOp

    /** Counts the objects of a type picked by [pick], and compares the type names listed. */
    data class CountOfType(val pick: Long) : ModelOp

    /** Removes a type picked by [pick] from every object that has it. */
    data class ClearType(val pick: Long) : ModelOp

    /** Resumes a cursor recorded by an earlier search, picked by [pick], in a new search. */
    data class Resume(val pick: Int) : ModelOp

    /**
     * Compares two values picked by [pick] ([diff]: the differences, otherwise equality) with options picked by it: stored
     * nodes, JSON text of stored values (respelled, or edited a little, or damaged), deleted documents and missing nodes.
     * With [variant], a document edited a little from a stored one is inserted first and compared with it.
     */
    data class Compare(val pick: Long, val diff: Boolean, val variant: Boolean) : ModelOp

    /**
     * Inserts [doc] replacing documents picked by [picks] in one call, as a stream or a string by [spelling]: mostly
     * stored ones, sometimes deleted ones, repeated docIds, docIds of rejected inserts or docIds not assigned yet, which
     * conflict.
     */
    data class Replace(val doc: OValue, val spelling: Long, val picks: List<Long>) : ModelOp

    /**
     * One `insertAll` of [docs], each replacing documents picked by its picks (maybe none) before the call, so a later
     * source may replace what an earlier one replaces or inserts, and conflict.
     */
    data class ReplaceAll(val docs: List<Pair<OValue, List<Long>>>, val spelling: Long) : ModelOp

    /** Closes the store and opens it again; everything acknowledged must still be there. */
    data object Reopen : ModelOp

    /** In a store with document keys: inserts [doc], replacing the document that has its key. */
    data class Upsert(val doc: OValue, val spelling: Long) : ModelOp

    /** In a store with document keys: finds the document of a key picked by [pick]. */
    data class Get(val pick: Long) : ModelOp

    /**
     * In a store with document keys: reads the documents whose key starts with a prefix picked by [pick] in key order,
     * in pages of [limit], resuming each page from the last one's cursor as text.
     */
    data class KeyScan(val pick: Long, val limit: Int, val descending: Boolean) : ModelOp
}

/**
 * Generates operation sequences over documents of the given profiles, sized for fragment size [fragmentBytes]; with
 * [reopen], about one operation in 25 reopens the store. With [keys], for a store whose document key is [KEY_PATH]:
 * every document is an object with the generated value under `v` and, last, a key from [KEYS] under `id` (sometimes
 * none, one that isn't a string or one that is too long), and one operation in five upserts, gets or scans keys.
 */
class ModelGen(
    private val fragmentBytes: Int,
    private val profiles: List<JsonProfile> = JsonProfile.entries,
    private val maxOps: Int = 40,
    private val reopen: Boolean = false,
    private val keys: Boolean = false,
) {
    fun ops(): Gen<List<ModelOp>> = Gen { random, size ->
        val count = random.nextInt(1, maxOps + 1)
        List(count) { op(random, size) }
    }

    private fun doc(random: Random, size: Int): OValue {
        val hot = profiles.filter { it in HOT_PROFILES }
        val profile = if (hot.isNotEmpty() && random.nextBoolean()) hot[random.nextInt(hot.size)] else profiles[random.nextInt(profiles.size)]
        val value = JsonGen(profile, fragmentBytes).value().generate(random, maxOf(1, random.nextInt(size / 2, size + 1)))
        if (!keys) return value
        // The key comes last, so the engine meets a damaged or rejected body before it, as the oracle does.
        val key = when (random.nextInt(40)) {
            0 -> return OObj("v" to value)
            1 -> ONum("7")
            2 -> OStr("x".repeat(1025))
            else -> OStr(KEYS[random.nextInt(KEYS.size)])
        }
        return OObj("v" to value, "id" to key)
    }

    private fun op(random: Random, size: Int): ModelOp = if (reopen && random.nextInt(25) == 0) {
        ModelOp.Reopen
    } else if (keys && random.nextInt(5) == 0) {
        when (random.nextInt(5)) {
            0, 1 -> ModelOp.Upsert(doc(random, size), random.nextLong())
            2 -> ModelOp.Get(random.nextLong())
            else -> ModelOp.KeyScan(random.nextLong(), random.nextInt(1, 6), random.nextBoolean())
        }
    } else if (random.nextInt(8) == 0) {
        ModelOp.Compare(random.nextLong(), diff = random.nextInt(3) != 0, variant = random.nextInt(3) == 0)
    } else when (random.nextInt(36)) {
        in 0..6 -> ModelOp.Insert(doc(random, size), random.nextLong())
        in 7..9 -> ModelOp.InsertTyped(doc(random, size), random.nextLong(), random.nextLong())
        10 -> ModelOp.InsertDamaged(doc(random, size), random.nextLong())
        in 30..31 -> ModelOp.Delete(List(if (random.nextInt(3) == 0) random.nextInt(2, 6) else 1) { random.nextLong() })
        in 11..21 -> ModelOp.Search(
            pick = random.nextLong(),
            mode = if (random.nextBoolean()) OResultMode.WHOLE_DOCUMENT else OResultMode.CONTAINING_ELEMENT,
            nested = if (random.nextInt(3) == 0) ONestedHits.OUTERMOST_ONLY else ONestedHits.ALL,
            limit = if (random.nextBoolean()) random.nextInt(1, 4) else random.nextInt(1, 200),
            maxBytes = if (random.nextInt(4) == 0) random.nextLong(1, 4L * fragmentBytes) else null,
            pages = if (random.nextBoolean()) -1 else random.nextInt(1, 4),
            between = if (random.nextInt(4) == 0) List(random.nextInt(1, 3)) { doc(random, size) } else emptyList(),
            typePick = if (random.nextInt(4) == 0) random.nextLong() else null,
            deleteBetween = if (random.nextInt(6) == 0) random.nextLong() else null,
            descending = random.nextInt(3) == 0,
        )
        in 22..23 -> ModelOp.Resume(random.nextInt(Int.MAX_VALUE))
        in 24..26 -> ModelOp.AssignTypes(List(if (random.nextInt(3) == 0) random.nextInt(2, 6) else 1) { random.nextLong() })
        27 -> ModelOp.TypeOf(random.nextLong())
        28 -> ModelOp.ObjectsOfType(random.nextLong(), random.nextInt(1, 20))
        29 -> ModelOp.CountOfType(random.nextLong())
        32 -> ModelOp.ObjectsOfTypePage(random.nextLong(), random.nextInt(1, 20))
        34 -> ModelOp.Replace(doc(random, size), random.nextLong(), List(if (random.nextInt(4) == 0) random.nextInt(0, 4) else 1) { random.nextLong() })
        35 -> ModelOp.ReplaceAll(
            List(random.nextInt(1, 5)) { doc(random, size) to List(random.nextInt(0, 3)) { random.nextLong() } },
            random.nextLong(),
        )
        else -> ModelOp.ClearType(random.nextLong())
    }

    companion object {
        /** Profiles whose terms repeat, so searches have many hits and several pages. */
        private val HOT_PROFILES = setOf(JsonProfile.HOT_KEYS, JsonProfile.ARRAYS_OF_OBJECTS, JsonProfile.TYPE_MEMBERS, JsonProfile.NUMBER_SPELLINGS)

        val shrinker: Shrinker<List<ModelOp>> = Shrinkers.list { op ->
            when (op) {
                is ModelOp.Insert -> JsonShrinker.candidates(op.doc).map { op.copy(doc = it) }
                is ModelOp.InsertDamaged -> JsonShrinker.candidates(op.doc).map { op.copy(doc = it) }
                is ModelOp.InsertTyped -> sequenceOf<ModelOp>(ModelOp.Insert(op.doc, op.spelling)) + JsonShrinker.candidates(op.doc).map { op.copy(doc = it) }
                is ModelOp.Search -> sequence {
                    if (op.between.isNotEmpty()) yield(op.copy(between = emptyList()))
                    if (op.maxBytes != null) yield(op.copy(maxBytes = null))
                    if (op.nested != ONestedHits.ALL) yield(op.copy(nested = ONestedHits.ALL))
                    if (op.typePick != null) yield(op.copy(typePick = null))
                    if (op.deleteBetween != null) yield(op.copy(deleteBetween = null))
                }
                is ModelOp.AssignTypes -> if (op.picks.size > 1) op.picks.asSequence().map { ModelOp.AssignTypes(listOf(it)) } else emptySequence()
                is ModelOp.Delete -> if (op.picks.size > 1) op.picks.asSequence().map { ModelOp.Delete(listOf(it)) } else emptySequence()
                is ModelOp.Replace -> sequenceOf<ModelOp>(ModelOp.Insert(op.doc, op.spelling)) + JsonShrinker.candidates(op.doc).map { op.copy(doc = it) }
                is ModelOp.ReplaceAll -> if (op.docs.size > 1) op.docs.indices.asSequence().map { i -> op.copy(docs = op.docs.filterIndexed { j, _ -> j != i }) } else emptySequence()
                is ModelOp.Compare -> if (op.variant) sequenceOf(op.copy(variant = false)) else emptySequence()
                is ModelOp.Upsert -> sequenceOf<ModelOp>(ModelOp.Insert(op.doc, op.spelling))
                is ModelOp.Resume, ModelOp.Reopen, is ModelOp.TypeOf, is ModelOp.ObjectsOfType, is ModelOp.CountOfType,
                is ModelOp.ObjectsOfTypePage, is ModelOp.ClearType, is ModelOp.Get, is ModelOp.KeyScan -> emptySequence()
            }
        }

        /** The document key path of the documents generated with `keys`. */
        const val KEY_PATH: String = "$.id"

        /**
         * Keys of generated documents, few so that they repeat: the empty key, one that is a prefix of others, with a NUL,
         * outside the BMP, decomposed and precomposed `é` (equal only with NFC), and the longest allowed.
         */
        val KEYS: List<String> = listOf("", "a", "ab", "a\u0000", "b", "😀", "é", "é", "k1", "k2", "k10", "x".repeat(1024))

        /** Type names the model assigns: plain, non-ASCII, one that looks like the `@type` data member, and a long one. */
        val TYPE_NAMES: List<String> = listOf("Order", "Item", "Größe·Δ", "@type", "t".repeat(256))
    }
}

/**
 * Applies operations to a store and to the [OracleStore] and compares every observable result exactly: docIds,
 * rejections and their reasons, hits (node, order, JSON text, type), page sizes, cursors, that an open result doesn't
 * see later inserts or deletes, type operations: assignments (their errors), `typeOf`, `objectsOfType`, its pages and
 * their cursors, `countOfType`, `clearType` and the names `listTypes` returns, deletes (their results and errors;
 * every later operation sees them), replaces and `insertAll` calls whose sources replace (their docIds, rejections and
 * conflicts), and comparisons: `isEqual` results and every difference of `diff` (path, kind,
 * values, nodes, types), or the errors. With a byte
 * budget, pages may end early: then every page must hold at least one hit and stay within the budget unless it holds
 * exactly one.
 *
 * [reopen] closes the given store and returns it opened again; [ModelOp.Reopen] is skipped without it.
 */
@OptIn(ExperimentalDelvaApi::class)
class ModelRunner(private var store: BlockingJsonStore, private val oracle: OracleStore, private val reopen: ((BlockingJsonStore) -> BlockingJsonStore)? = null) {
    /** The store after the last reopen. */
    val current: BlockingJsonStore get() = store

    private class Recorded(val query: OQuery, val engineQuery: KeyValueQuery, val cursor: Cursor)

    private val cursors = ArrayList<Recorded>()
    private var lastSnapshotLsn = -1L

    /** Counters of what the run exercised. */
    var inserts: Int = 0
        private set
    var rejections: Int = 0
        private set
    var hitsCompared: Int = 0
        private set
    var resumes: Int = 0
        private set
    var searches: Int = 0
        private set
    var emptySearches: Int = 0
        private set
    var reopens: Int = 0
        private set
    var typeOperations: Int = 0
        private set
    var typedHits: Int = 0
        private set
    var deletes: Int = 0
        private set

    fun run(ops: List<ModelOp>) {
        for ((index, op) in ops.withIndex()) {
            try {
                apply(op)
            } catch (e: AssertionError) {
                throw AssertionError("operation $index ($op): ${e.message}", e)
            }
        }
    }

    private fun apply(op: ModelOp) {
        when (op) {
            is ModelOp.Insert -> insert(JsonText.randomlyFormatted(op.doc, Random(op.spelling)).toByteArray())
            is ModelOp.InsertDamaged -> insert(damage(JsonText.minifiedBytes(op.doc), Random(op.damage)))
            is ModelOp.InsertTyped -> insert(JsonText.randomlyFormatted(op.doc, Random(op.spelling)).toByteArray(), typePaths(op.doc, Random(op.paths)))
            is ModelOp.Search -> search(op)
            is ModelOp.Resume -> resume(op)
            ModelOp.Reopen -> reopen?.let {
                store = it(store)
                reopens++
            }
            is ModelOp.AssignTypes -> assignTypes(op)
            is ModelOp.TypeOf -> typeOf(op)
            is ModelOp.ObjectsOfType -> objectsOfType(op)
            is ModelOp.CountOfType -> countOfType(op)
            is ModelOp.ObjectsOfTypePage -> objectsOfTypePage(op)
            is ModelOp.ClearType -> clearType(op)
            is ModelOp.Delete -> delete(op.picks.map { pickDocId(Random(it)) })
            is ModelOp.Replace -> replace(op)
            is ModelOp.ReplaceAll -> replaceAll(op)
            is ModelOp.Compare -> compare(op)
            is ModelOp.Upsert -> upsert(op)
            is ModelOp.Get -> get(op)
            is ModelOp.KeyScan -> keyScan(op)
        }
    }

    // ---- compare --------------------------------------------------------------------------------------------------

    /** Counters of comparisons run and differences compared. */
    var compares: Int = 0
        private set
    var differencesCompared: Int = 0
        private set

    /** An operand and, when it's valid, its value. */
    private class Picked(val operand: OOperand, val value: OValue?)

    private fun compare(op: ModelOp.Compare) {
        val random = Random(op.pick)
        val live = oracle.liveDocIds()
        if (live.isEmpty()) return
        if (op.variant) {
            val docId = live[random.nextInt(live.size)]
            insert(JsonText.randomlyFormatted(JsonMutator.mutate(oracle.value(ONodeRef(docId, 0)), random, random.nextInt(0, 4)), random).toByteArray())
        }
        val a = pickOperand(random, null) ?: return
        val b = pickOperand(random, a) ?: return
        val value = a.value ?: b.value
        val options = OCompareOptions(
            includeTypes = random.nextInt(3) == 0,
            ignoreKeys = if (random.nextInt(5) == 0) setOf(JsonGen.HOT_KEYS[random.nextInt(JsonGen.HOT_KEYS.size)], "id") else emptySet(),
            ignorePaths = if (value != null && random.nextInt(5) == 0) List(random.nextInt(1, 3)) { valuePaths(value, random).let { it[random.nextInt(it.size)] } }.toSet() else emptySet(),
            maxDifferences = if (random.nextInt(4) == 0) random.nextInt(1, 5) else Int.MAX_VALUE,
            maxValueBytes = if (random.nextInt(3) == 0) random.nextInt(0, 64) else Int.MAX_VALUE,
        )
        compares++
        if (op.diff) {
            val engineOptions = DiffOptions(options.includeTypes, options.ignoreKeys, options.ignorePaths, options.maxDifferences, options.maxValueBytes, verifySkippedSubtrees = random.nextInt(4) == 0)
            val expected = runCatching { oracle.diff(a.operand, b.operand, options) }
            val actual = runCatching { store.diff(engineOperand(a.operand), engineOperand(b.operand), engineOptions).map { it.toOracle() } }
            differencesCompared += expected.getOrNull()?.size ?: 0
            sameOutcome("diff ${a.operand} ${b.operand} $options", expected, actual)
        } else {
            val engineOptions = CompareOptions(options.includeTypes, options.ignoreKeys, options.ignorePaths)
            sameOutcome("isEqual ${a.operand} ${b.operand} $options", runCatching { oracle.isEqual(a.operand, b.operand, options) }, runCatching { store.isEqual(engineOperand(a.operand), engineOperand(b.operand), engineOptions) })
        }
    }

    /**
     * Mostly a stored node (a root, an object, the node [other] names, or the root of the newest document), else the JSON
     * text of [other]'s value or of a stored node, respelled or edited a little, now and then damaged; sometimes a node
     * of a deleted document or past a document's end.
     */
    private fun pickOperand(random: Random, other: Picked?): Picked? {
        val live = oracle.liveDocIds()
        val otherValue = other?.value
        return when (random.nextInt(12)) {
            0 -> pickNode(random)?.let { Picked(OOperand.Stored(it), null) }
            1 -> if (other != null) other else Picked(OOperand.Stored(ONodeRef(live.last(), 0)), oracle.value(ONodeRef(live.last(), 0)))
            in 2..4 -> {
                val base = otherValue ?: oracle.value(ONodeRef(live[random.nextInt(live.size)], 0))
                val edited = JsonMutator.mutate(base, random, if (random.nextBoolean()) 0 else random.nextInt(1, 4))
                val text = JsonText.randomlyFormatted(edited, random)
                if (random.nextInt(10) == 0) Picked(OOperand.Json(String(damage(text.toByteArray(), random))), null) else Picked(OOperand.Json(text), edited)
            }
            in 5..7 -> {
                val docId = if (other?.operand is OOperand.Stored && random.nextBoolean()) live.last() else live[random.nextInt(live.size)]
                Picked(OOperand.Stored(ONodeRef(docId, 0)), oracle.value(ONodeRef(docId, 0)))
            }
            else -> {
                val docId = live[random.nextInt(live.size)]
                val pres = oracle.nodes(docId).map { it.first }
                val pre = pres[random.nextInt(pres.size)]
                Picked(OOperand.Stored(ONodeRef(docId, pre)), oracle.value(ONodeRef(docId, pre)))
            }
        }
    }

    private fun engineOperand(operand: OOperand): CompareOperand = when (operand) {
        is OOperand.Stored -> CompareOperand.Stored(nodeRef(operand.ref))
        // The oracle reads the text as `toByteArray()` bytes, which replace unpaired surrogates; give the engine the same bytes.
        is OOperand.Json -> operand.json.toByteArray().let { if (it.size % 2 == 0) CompareOperand.Json(String(it)) else CompareOperand.JsonStream(ByteArrayInputStream(it)) }
    }

    /** Paths of the values in [value], relative to it, in random spellings. */
    private fun valuePaths(value: OValue, random: Random): List<String> {
        val paths = ArrayList<String>()
        fun walk(v: OValue, path: String) {
            paths += path
            when (v) {
                is OObj -> for ((key, member) in v.members) walk(member, path + JsonPath.spellMember(key, random))
                is OArr -> v.items.forEachIndexed { i, item -> walk(item, path + JsonPath.spellIndex(i.toLong(), random)) }
                else -> {}
            }
        }
        walk(value, "$")
        return paths
    }

    // ---- delete ---------------------------------------------------------------------------------------------------

    /** A docId: mostly a stored document's, sometimes a deleted one's, a rejected insert's or one not assigned yet. */
    private fun pickDocId(random: Random): Long {
        val live = oracle.liveDocIds()
        val deleted = oracle.deletedDocIds()
        return when (random.nextInt(12)) {
            0 -> oracle.peekNextDocId + random.nextInt(3)
            1 -> {
                val stored = (live + deleted).toSet()
                val rejected = (oracle.options.firstDocId until oracle.peekNextDocId).filter { it !in stored }
                if (rejected.isEmpty()) oracle.peekNextDocId else rejected[random.nextInt(rejected.size)]
            }
            in 2..3 -> if (deleted.isEmpty()) oracle.peekNextDocId else deleted[random.nextInt(deleted.size)]
            else -> if (live.isEmpty()) oracle.peekNextDocId else live[random.nextInt(live.size)]
        }
    }

    private fun delete(docIds: List<Long>) {
        deletes++
        keepPaths(docIds)
        val expected = runCatching { oracle.deleteAll(docIds) }
        val actual = runCatching { if (docIds.size == 1) (if (store.delete(DocId(docIds[0]))) 1 else 0) else store.deleteAll(docIds.map { DocId(it) }) }
        sameOutcome("deleteAll $docIds", expected, actual)
    }

    // ---- replace --------------------------------------------------------------------------------------------------

    /** Replace calls run, and the replaces among them (single or of an `insertAll`) that conflicted. */
    var replaces: Int = 0
        private set
    var conflicts: Int = 0
        private set

    private fun replace(op: ModelOp.Replace) {
        val random = Random(op.spelling)
        val docIds = op.picks.map { pickReplaced(Random(it)) }
        val bytes = JsonText.randomlyFormatted(op.doc, random).toByteArray()
        keepPaths(docIds)
        replaces++
        val expected = runCatching { oracle.replace(docIds, bytes) }
        val targets = docIds.map { DocId(it) }
        val actual = runCatching {
            if (random.nextBoolean()) store.replace(targets, ByteArrayInputStream(bytes)).raw else store.replace(targets, String(bytes)).raw
        }
        if (expected.exceptionOrNull() is WriteConflictException) conflicts++
        sameInsert("replace $docIds", bytes, expected, actual)
    }

    private fun replaceAll(op: ModelOp.ReplaceAll) {
        val random = Random(op.spelling)
        val sources = op.docs.map { (doc, picks) ->
            OSource(JsonText.randomlyFormatted(doc, random).toByteArray(), replaces = picks.map { pickReplaced(Random(it)) }, replaceKey = keyed && random.nextBoolean())
        }
        keepPaths(sources.flatMap { it.replaces })
        replaces++
        val expected = oracle.insertAll(sources)
        val actual = store.insertAll(
            sources.map { InsertSource(ByteArrayInputStream(it.json), it.types, it.replaces.map { id -> DocId(id) }, if (it.replaceKey) KeyConflict.REPLACE else KeyConflict.FAIL) },
        ).map { outcome ->
            when (outcome) {
                is InsertOutcome.Inserted -> OOutcome.Inserted(outcome.docId.raw)
                is InsertOutcome.Rejected -> OOutcome.Rejected(outcome.exception.reason)
                is InsertOutcome.Conflicted -> OOutcome.Conflicted(outcome.exception.conflicting.map { it.raw }, outcome.exception.key)
            }
        }
        conflicts += expected.count { it is OOutcome.Conflicted }
        inserts += expected.count { it is OOutcome.Inserted }
        rejections += expected.count { it is OOutcome.Rejected }
        check(expected == actual, "insertAll replacing ${sources.map { it.replaces }}: $actual, oracle $expected")
    }

    // ---- document keys --------------------------------------------------------------------------------------------

    /** Whether the store has document keys ([ModelGen.KEY_PATH]); the key operations are skipped without. */
    private val keyed: Boolean = oracle.options.documentKey != null

    /** Key lookups and key-order hits compared. */
    var keyReads: Int = 0
        private set

    private fun upsert(op: ModelOp.Upsert) {
        if (!keyed) return
        val random = Random(op.spelling)
        val bytes = JsonText.randomlyFormatted(op.doc, random).toByteArray()
        // The document the key names is deleted: searches started before keep reading it.
        oracle.get(((op.doc as? OObj)?.get("id") as? OStr)?.value ?: "")?.let { keepPaths(listOf(it)) }
        val expected = runCatching { oracle.upsert(bytes) }
        val actual = runCatching { if (random.nextBoolean()) store.upsert(ByteArrayInputStream(bytes)).raw else store.upsert(String(bytes)).raw }
        sameInsert("upsert", bytes, expected, actual)
    }

    /** A key: mostly one a document has or had, sometimes a prefix of one or one never generated. */
    private fun pickKey(random: Random): String = when (random.nextInt(6)) {
        0 -> "never"
        1 -> ModelGen.KEYS[random.nextInt(ModelGen.KEYS.size)].let { it.take(random.nextInt(it.length + 1)) }
        else -> ModelGen.KEYS[random.nextInt(ModelGen.KEYS.size)]
    }

    private fun get(op: ModelOp.Get) {
        if (!keyed) return
        val key = pickKey(Random(op.pick))
        keyReads++
        val expected = oracle.get(key)
        val actual = store.get(key)?.raw
        check(expected == actual, "get ${key.take(20)}: ${actual}, oracle $expected")
    }

    private fun keyScan(op: ModelOp.KeyScan) {
        if (!keyed) return
        val random = Random(op.pick)
        // A prefix ending inside a surrogate pair would match nothing; the generated keys are cut on code points.
        val prefix = pickKey(random).let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
        val order = if (op.descending) SearchOrder.DESCENDING else SearchOrder.ASCENDING
        val expected = oracle.keyed(prefix).let { if (op.descending) it.reversed() else it }
        check(store.count(DocumentKeyQuery(prefix, order = order)) == expected.size.toLong(), "count of keys starting with ${prefix.take(20)}")
        var query = DocumentKeyQuery(prefix, op.limit, order = order)
        var position = 0
        while (true) {
            val page = store.search(query).use { result ->
                checkSnapshot(result)
                result.nextPage()?.let { page -> page to page.hits.map { Triple(it.documentKey, it.docId.raw, it.readJsonAsString(Int.MAX_VALUE)) } }
            }
            if (page == null) {
                check(position == expected.size, "key scan ended after $position of ${expected.size} documents")
                break
            }
            for ((key, docId, json) in page.second) {
                check(position < expected.size, "more documents than the oracle's ${expected.size}")
                val (expectedKey, expectedDocId) = expected[position++]
                check(key == expectedKey && docId == expectedDocId, "key-order hit ($docId, ${key?.take(20)}), oracle ($expectedDocId, ${expectedKey.take(20)})")
                check(json == oracle.read(ONodeRef(docId, 0)), "key-order hit $docId JSON")
                keyReads++
            }
            val cursor = page.first.cursor ?: break
            check(position < expected.size, "a cursor after the last document")
            query = query.copy(after = Cursor.parse(cursor.toString()))
        }
        check(position == expected.size, "key scan returned $position of ${expected.size} documents")
    }

    /** A document to replace: mostly a live one, so most replaces succeed, else any docId [pickDocId] picks. */
    private fun pickReplaced(random: Random): Long {
        val live = oracle.liveDocIds()
        return if (live.isNotEmpty() && random.nextInt(5) != 0) live[random.nextInt(live.size)] else pickDocId(random)
    }

    /** Searches started before still return deleted documents, so their paths are taken while the oracle has them. */
    private fun keepPaths(docIds: List<Long>) {
        val live = oracle.liveDocIds().toSet()
        for (docId in docIds) if (docId in live) pathsOf(docId)
    }

    // ---- types ----------------------------------------------------------------------------------------------------

    /** Paths to up to three values of [doc], mostly objects, and now and then a path that selects nothing. */
    private fun typePaths(doc: OValue, random: Random): Map<String, String> {
        val values = ArrayList<Pair<String, OValue>>()
        fun walk(value: OValue, path: String) {
            values += path to value
            when (value) {
                is OObj -> for ((key, member) in value.members) walk(member, path + JsonPath.spellMember(key, random))
                is OArr -> value.items.forEachIndexed { i, item -> walk(item, path + JsonPath.spellIndex(i.toLong(), random)) }
                else -> {}
            }
        }
        walk(doc, "$")
        val objects = values.filter { it.second is OObj }
        val paths = LinkedHashMap<String, String>()
        repeat(random.nextInt(1, 4)) {
            val pool = if (objects.isNotEmpty() && random.nextInt(8) != 0) objects else values
            paths[pool[random.nextInt(pool.size)].first] = ModelGen.TYPE_NAMES[random.nextInt(ModelGen.TYPE_NAMES.size)]
        }
        if (random.nextInt(10) == 0) paths["$.noSuchMember[3]"] = ModelGen.TYPE_NAMES[0]
        return paths
    }

    /** A node of a live document: mostly an object, sometimes another node, a node past the end or a missing document. */
    private fun pickNode(random: Random): ONodeRef? {
        val live = oracle.liveDocIds()
        if (live.isEmpty()) return null
        val docId = live[random.nextInt(live.size)]
        return when (random.nextInt(20)) {
            0 -> ONodeRef(oracle.peekNextDocId + random.nextInt(3), 0)
            1 -> ONodeRef(docId, oracle.nodes(docId).size.toLong() + random.nextInt(3))
            in 2..4 -> ONodeRef(docId, random.nextLong(oracle.nodes(docId).size.toLong()))
            5 -> oracle.deletedDocIds().let { deleted -> ONodeRef(if (deleted.isEmpty()) oracle.peekNextDocId else deleted[random.nextInt(deleted.size)], 0) }
            else -> oracle.objectPres(docId).let { pres -> if (pres.isEmpty()) ONodeRef(docId, 0) else ONodeRef(docId, pres[random.nextInt(pres.size)]) }
        }
    }

    private fun pickType(pick: Long): String = ModelGen.TYPE_NAMES[Math.floorMod(pick, ModelGen.TYPE_NAMES.size)]

    private fun nodeRef(ref: ONodeRef): NodeRef = NodeRef(DocId(ref.docId), ref.pre)

    private fun assignTypes(op: ModelOp.AssignTypes) {
        val assignments = op.picks.map { pick ->
            val random = Random(pick)
            val node = pickNode(random) ?: return
            node to if (random.nextInt(4) == 0) null else ModelGen.TYPE_NAMES[random.nextInt(ModelGen.TYPE_NAMES.size)]
        }
        typeOperations++
        val expected = runCatching { oracle.assignTypes(assignments) }
        val actual = runCatching { store.assignTypes(assignments.map { (node, type) -> nodeRef(node) to type }) }
        sameOutcome("assignTypes $assignments", expected, actual)
    }

    private fun typeOf(op: ModelOp.TypeOf) {
        val node = pickNode(Random(op.pick)) ?: return
        typeOperations++
        sameOutcome("typeOf $node", runCatching { oracle.typeOf(node) }, runCatching { store.typeOf(nodeRef(node)) })
    }

    private fun objectsOfType(op: ModelOp.ObjectsOfType) {
        val random = Random(op.pick)
        val type = pickType(random.nextLong())
        typeOperations++
        val all = oracle.objectsOfType(type, limit = Int.MAX_VALUE)
        val after = if (all.isNotEmpty() && random.nextBoolean()) all[random.nextInt(all.size)] else null
        val expected = oracle.objectsOfType(type, after, op.limit).map { it.docId to it.pre }
        val actual = store.objectsOfType(type, after?.let { nodeRef(it) }, op.limit).map { it.docId.raw to it.nodePre }
        check(expected == actual, "objectsOfType($type, $after, ${op.limit}): $actual, oracle $expected")
    }

    private fun objectsOfTypePage(op: ModelOp.ObjectsOfTypePage) {
        val type = pickType(op.pick)
        typeOperations++
        val expected = oracle.objectsOfType(type, limit = Int.MAX_VALUE).map { it.docId to it.pre }
        val actual = ArrayList<Pair<Long, Long>>()
        var cursor: Cursor? = null
        var pages = 0
        do {
            val page = store.objectsOfTypePage(type, cursor?.let { Cursor.parse(it.toString()) }, op.limit)
            pages++
            check(page.nodes.size <= op.limit, "objectsOfTypePage($type, ${op.limit}): a page of ${page.nodes.size}")
            check(page.cursor == null || page.nodes.size == op.limit, "objectsOfTypePage($type, ${op.limit}): a page with a cursor isn't full")
            check(page.nodes.isNotEmpty() || pages == 1, "objectsOfTypePage($type, ${op.limit}): an empty page after a full one")
            page.nodes.mapTo(actual) { it.docId.raw to it.nodePre }
            cursor = page.cursor
        } while (cursor != null)
        check(expected == actual, "objectsOfTypePage($type, ${op.limit}): $actual, oracle $expected")
    }

    private fun clearType(op: ModelOp.ClearType) {
        val type = pickType(op.pick)
        typeOperations++
        sameOutcome("clearType $type", runCatching { oracle.clearType(type) }, runCatching { store.clearType(type) })
    }

    private fun countOfType(op: ModelOp.CountOfType) {
        val type = pickType(op.pick)
        typeOperations++
        val expected = oracle.countOfType(type)
        val actual = store.countOfType(type)
        check(expected == actual, "countOfType($type): $actual, oracle $expected")
        val listed = store.listTypes()
        check(listed.map { it.name } == oracle.typeNames.toList(), "listTypes ${listed.map { it.name }}, oracle ${oracle.typeNames}")
        check(listed.all { it.approximateCount >= 0 }, "negative approximate count in $listed")
    }

    /** Both succeeded with the same result, or both failed with the same exception class. */
    private fun sameOutcome(what: String, expected: Result<Any?>, actual: Result<Any?>) {
        val e = expected.exceptionOrNull()
        val a = actual.exceptionOrNull()
        when {
            e == null && a == null -> check(expected.getOrNull() == actual.getOrNull(), "$what: ${actual.getOrNull()}, oracle ${expected.getOrNull()}")
            e != null && a != null -> check(e::class == a::class, "$what: engine threw $a, oracle $e")
            else -> throw AssertionError("$what: engine ${actual.map { it.toString() }.getOrElse { it.toString() }}, oracle ${expected.map { it.toString() }.getOrElse { it.toString() }}", a ?: e)
        }
    }

    private fun insert(bytes: ByteArray, types: Map<String, String> = emptyMap()) {
        val expected = runCatching { oracle.insert(bytes, types) }
        val actual = runCatching { store.insert(ByteArrayInputStream(bytes), types).raw }
        sameInsert("insert", bytes, expected, actual)
    }

    /** Compares the outcomes of an insert or a replace: the docId, the rejection's reason, or the conflicting docIds. */
    private fun sameInsert(what: String, bytes: ByteArray, expected: Result<Long>, actual: Result<Long>) {
        val expectedError = expected.exceptionOrNull()
        val actualError = actual.exceptionOrNull()
        if (expectedError == null) inserts++ else if (expectedError is DocumentRejectedException) rejections++
        when {
            expectedError == null && actualError == null -> check(expected.getOrThrow() == actual.getOrThrow(), "docId ${actual.getOrThrow()}, oracle ${expected.getOrThrow()}")
            expectedError is DocumentRejectedException && actualError is DocumentRejectedException ->
                check(
                    expectedError.reason == actualError.reason || keyBeforeParseError(expectedError, actualError),
                    "rejected with ${actualError.reason}, oracle ${expectedError.reason} (${String(bytes)})",
                )
            expectedError is WriteConflictException && actualError is WriteConflictException ->
                check(expectedError.conflicting == actualError.conflicting && expectedError.key == actualError.key, "$what conflicts with ${actualError.conflicting} (${actualError.key}), oracle ${expectedError.conflicting} (${expectedError.key})")
            else -> {
                val message = "$what: engine ${actual.map { "docId $it" }.getOrElse { it.toString() }}, oracle ${expected.map { "docId $it" }.getOrElse { it.toString() }} (${String(bytes)})"
                throw AssertionError(message, actualError ?: expectedError)
            }
        }
    }

    /**
     * Whether the engine rejected a document for its key, at the key's path, where the oracle found a parse error: the
     * oracle reports parse errors first because it doesn't know where in the text they are, while the engine streams
     * and stops at the first problem, which can be a key that isn't a string (or is too long) before damage further on.
     */
    @OptIn(ExperimentalDelvaApi::class)
    private fun keyBeforeParseError(expected: DocumentRejectedException, actual: DocumentRejectedException): Boolean =
        expected.jsonPath == null && actual.jsonPath != null && actual.jsonPath == store.creationOptions.documentKey &&
            (actual.reason == RejectReason.KEY_MISSING || actual.reason == RejectReason.LIMIT_EXCEEDED)

    private fun search(op: ModelOp.Search) {
        val (key, value) = pickTerm(op.pick) ?: return
        val type = op.typePick?.let { pickType(it) }
        val query = OQuery(key, value, op.mode, op.nested, op.limit, type = type)
        val order = if (op.descending) SearchOrder.DESCENDING else SearchOrder.ASCENDING
        val engineQuery = KeyValueQuery(key, toQueryValue(value, Random(op.pick)), mode(op.mode), nested(op.nested), op.limit, op.maxBytes ?: Long.MAX_VALUE, type = type, order = order)
        val expected = oracle.searchAll(query.copy(limit = Int.MAX_VALUE)).let { if (op.descending) it.reversed() else it }
        searches++
        if (expected.isEmpty()) emptySearches++
        val atMost = Random(op.pick xor op.limit.toLong()).nextLong(0, 5)
        val counted = store.count(engineQuery)
        check(counted == expected.size.toLong(), "count $counted, oracle ${expected.size}")
        check(store.count(engineQuery, atMost) == minOf(atMost, expected.size.toLong()), "count at most $atMost")
        store.search(engineQuery).use { result ->
            checkSnapshot(result)
            var position = 0
            var pages = 0
            while (op.pages < 0 || pages < op.pages) {
                val page = result.nextPage()
                if (page == null) {
                    check(position == expected.size, "result ended after $position of ${expected.size} hits")
                    break
                }
                pages++
                check(page.hits.isNotEmpty(), "empty page")
                check(page.hits.size <= op.limit, "page of ${page.hits.size} hits, limit ${op.limit}")
                if (op.maxBytes == null) {
                    check(page.hits.size == minOf(op.limit, expected.size - position), "page of ${page.hits.size} hits at $position of ${expected.size}")
                } else if (page.hits.size > 1) {
                    val bytes = page.hits.sumOf { it.encodedSize }
                    check(bytes <= op.maxBytes, "page of $bytes encoded bytes, budget ${op.maxBytes}")
                }
                for (hit in page.hits) {
                    check(position < expected.size, "more hits than the oracle's ${expected.size}")
                    checkHit(expected[position++], hit)
                }
                val cursor = page.cursor
                if (position < expected.size) {
                    if (cursor == null) throw AssertionError("no cursor with ${expected.size - position} hits left")
                    val last = page.hits.last()
                    check(cursor.docId == last.node.docId && cursor.nodePre == last.node.nodePre, "cursor $cursor after hit ${last.node}")
                    check(!cursor.mayMissLateCommits, "cursor may miss late commits without concurrent inserts")
                    check(Cursor.parse(cursor.toString()) == cursor, "cursor text doesn't round-trip")
                    cursors += Recorded(query, engineQuery, cursor)
                } else {
                    check(cursor == null, "cursor on the last page")
                }
                if (pages == 1) {
                    for (doc in op.between) insert(JsonText.minifiedBytes(doc))
                    op.deleteBetween?.let { pick ->
                        // Hits still to come of a deleted document must keep coming: the result reads its snapshot.
                        val random = Random(pick)
                        val coming = expected.drop(position).map { it.node.docId }.distinct()
                        delete(listOf(if (coming.isNotEmpty() && random.nextInt(4) != 0) coming[random.nextInt(coming.size)] else pickDocId(random)))
                    }
                }
            }
        }
    }

    private fun resume(op: ModelOp.Resume) {
        if (cursors.isEmpty()) return
        val recorded = cursors[op.pick % cursors.size]
        resumes++
        val cursor = recorded.cursor
        // The oracle pages ascending only: a descending page is the hits before the cursor, last first.
        val (hits, next) = if (recorded.engineQuery.order == SearchOrder.DESCENDING) {
            val limit = recorded.query.limit
            val before = oracle.searchAll(recorded.query.copy(limit = Int.MAX_VALUE))
                .filter { it.node.docId < cursor.docId.raw || (it.node.docId == cursor.docId.raw && it.node.pre < cursor.nodePre) }
                .reversed()
            before.take(limit) to before.getOrNull(limit - 1)?.takeIf { before.size > limit }?.let { OCursor(it.node.docId, it.node.pre) }
        } else {
            oracle.search(recorded.query.copy(after = OCursor(cursor.docId.raw, cursor.nodePre))).let { it.hits to it.next }
        }
        store.search(recorded.engineQuery.copy(after = Cursor.parse(cursor.toString()), maxBytes = Long.MAX_VALUE)).use { result ->
            checkSnapshot(result)
            val page = result.nextPage()
            if (page == null) {
                check(hits.isEmpty(), "resumed search is empty, oracle has ${hits.size} hits")
                return
            }
            check(page.hits.size == hits.size, "resumed page of ${page.hits.size} hits, oracle ${hits.size}")
            hits.zip(page.hits).forEach { (e, a) -> checkHit(e, a) }
            check((page.cursor == null) == (next == null), "resumed cursor ${page.cursor}, oracle $next")
            if (next != null) check(page.cursor!!.docId.raw == next.docId && page.cursor!!.nodePre == next.containerPre, "resumed cursor ${page.cursor}, oracle $next")
        }
    }

    private fun checkSnapshot(result: SearchResult) {
        check(result.snapshotLsn >= lastSnapshotLsn, "snapshot ${result.snapshotLsn} after $lastSnapshotLsn")
        lastSnapshotLsn = result.snapshotLsn
    }

    /** Normalized Paths by `pre` of each document the hits came from; documents never change, so they're kept. */
    private val paths = HashMap<Long, Map<Long, String>>()

    private fun pathsOf(docId: Long): Map<Long, String> = paths.getOrPut(docId) {
        val root = oracle.value(ONodeRef(docId, 0))
        mapOf(0L to "$") + ALL_DESCENDANTS.select(root).associate { it.pre to it.path }
    }

    private fun checkHit(expected: OHit, actual: SearchHit) {
        hitsCompared++
        check(actual.node.docId.raw == expected.node.docId && actual.node.nodePre == expected.node.pre, "hit ${actual.node}, oracle ${expected.node}")
        val json = actual.readJsonAsString(Int.MAX_VALUE)
        check(json == expected.json, "hit ${actual.node} JSON\n  actual:   ${json.take(300)}\n  expected: ${expected.json.take(300)}")
        check(actual.type == expected.type, "hit ${actual.node} has type ${actual.type}, oracle ${expected.type}")
        check(actual.documentKey == oracle.keyOf(expected.node.docId), "hit ${actual.node} has key ${actual.documentKey}, oracle ${oracle.keyOf(expected.node.docId)}")
        val path = pathsOf(expected.node.docId)[expected.node.pre]
        check(actual.path == path, "hit ${actual.node} has path ${actual.path}, oracle $path")
        if (expected.type != null) typedHits++
        check(actual.encodedSize > 0, "encoded size ${actual.encodedSize}")
    }

    /** A term from a posting of a stored document, respelled, or a term that probably matches nothing. */
    private fun pickTerm(pick: Long): Pair<String, OValue>? {
        val random = Random(pick)
        val live = oracle.liveDocIds()
        if (live.isEmpty() || random.nextInt(10) < 3) {
            return JsonGen.HOT_KEYS[random.nextInt(JsonGen.HOT_KEYS.size)] to OStr(JsonGen.HOT_VALUES[random.nextInt(JsonGen.HOT_VALUES.size)])
        }
        var postings = oracle.postings(live[random.nextInt(live.size)])
        repeat(4) { if (postings.isEmpty()) postings = oracle.postings(live[random.nextInt(live.size)]) }
        if (postings.isEmpty()) return null
        // Container values mostly have unique hash terms; prefer scalars so terms repeat and results span pages.
        val scalars = postings.filter { it.value !is OObj && it.value !is OArr }
        val posting = if (scalars.isNotEmpty() && random.nextInt(3) != 0) scalars[random.nextInt(scalars.size)] else postings[random.nextInt(postings.size)]
        if (!oracle.options.hashTerms && (posting.value is OObj || posting.value is OArr)) return null
        return posting.key to posting.value
    }

    private fun toQueryValue(value: OValue, random: Random): JsonQueryValue = when (value) {
        ONull -> JsonQueryValue.Null
        is OBool -> JsonQueryValue.Bool(value.value)
        is OStr -> JsonQueryValue.Str(value.value)
        is ONum -> {
            val decimal = runCatching { BigDecimal(value.text) }.getOrNull()
            when {
                decimal != null && random.nextBoolean() -> JsonQueryValue.Num(decimal)
                random.nextBoolean() && value.text.none { it == '.' || it == 'e' || it == 'E' } -> JsonQueryValue.Json(value.text + ".0")
                else -> JsonQueryValue.Json(value.text)
            }
        }
        is OArr, is OObj -> JsonQueryValue.Json(JsonText.minified(reorder(value, random)))
    }

    /** The same value with object members in another order, which must match equally. */
    private fun reorder(value: OValue, random: Random): OValue = when (value) {
        is OObj -> OObj(value.members.map { (k, v) -> k to reorder(v, random) }.shuffled(random))
        is OArr -> OArr(value.items.map { reorder(it, random) })
        else -> value
    }

    private fun damage(bytes: ByteArray, random: Random): ByteArray {
        if (bytes.isEmpty()) return byteArrayOf('x'.code.toByte())
        val at = random.nextInt(bytes.size)
        return when (random.nextInt(4)) {
            0 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(at + 1, bytes.size)
            1 -> bytes.copyOfRange(0, at) + DAMAGE[random.nextInt(DAMAGE.size)] + bytes.copyOfRange(at, bytes.size)
            2 -> bytes.copyOf().also { it[at] = DAMAGE[random.nextInt(DAMAGE.size)] }
            else -> bytes.copyOfRange(0, at)
        }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private companion object {
        /** Selects every node below the root once, each with its Normalized Path. */
        val ALL_DESCENDANTS = JsonPathOracle.parse("$..*")

        val DAMAGE = "{}[],:\"\\ 0-eE.tfn".toByteArray() + byteArrayOf(0x80.toByte(), 0xC0.toByte(), 0xFF.toByte())

        fun mode(mode: OResultMode) = if (mode == OResultMode.WHOLE_DOCUMENT) ResultMode.WHOLE_DOCUMENT else ResultMode.CONTAINING_ELEMENT

        fun nested(nested: ONestedHits) = if (nested == ONestedHits.ALL) NestedHits.ALL else NestedHits.OUTERMOST_ONLY
    }
}
