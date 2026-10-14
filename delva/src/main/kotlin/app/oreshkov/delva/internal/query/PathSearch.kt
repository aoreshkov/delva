package app.oreshkov.delva.internal.query

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.lsm.DocRuns
import app.oreshkov.delva.internal.lsm.MemtableTermSource
import app.oreshkov.delva.internal.lsm.PostingSegments
import app.oreshkov.delva.internal.lsm.ReverseSegmentTermSource
import app.oreshkov.delva.internal.lsm.RunMerger
import app.oreshkov.delva.internal.lsm.SegmentTermSource
import app.oreshkov.delva.internal.lsm.TermSource
import app.oreshkov.delva.internal.lsm.TermSources
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.path.CompareOp
import app.oreshkov.delva.internal.path.LiteralValue
import app.oreshkov.delva.internal.path.LogicalExpr
import app.oreshkov.delva.internal.path.NormalizedPath
import app.oreshkov.delva.internal.path.PathQuery
import app.oreshkov.delva.internal.path.PathSegment
import app.oreshkov.delva.internal.path.Selector
import app.oreshkov.delva.internal.path.ValueExpr
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.term.IndexPolicy
import app.oreshkov.delva.internal.term.PathHash
import app.oreshkov.delva.internal.term.TermEncoder
import app.oreshkov.delva.internal.term.TermTag
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.internal.visibility.Visibility

/**
 * What the posting index can say about the documents a query may select: a formula over index terms that every such
 * document satisfies. A term `(key, value)` is in a document's postings when an object of it has the member `key`
 * whose value, or an element of whose array value, equals `value` (SPEC.md §4.2).
 */
internal sealed interface Requirement {
    class Term(val term: QueryTerm, val text: String) : Requirement

    /**
     * Every term between [from] and [to] (each bound inclusive or not), and every term that starts with [lossyPrefix]:
     * the exact terms of the key and value type whose values lie in a range, and the lossy long terms of that type,
     * whose order the index doesn't keep.
     */
    class Range(val from: ByteArray, val fromInclusive: Boolean, val to: ByteArray, val toInclusive: Boolean, val lossyPrefix: ByteArray, val text: String) : Requirement

    class All(val items: List<Requirement>) : Requirement
    class AnyOf(val items: List<Requirement>) : Requirement

    fun describe(): String = when (this) {
        is Term -> text
        is Range -> text
        is All -> items.joinToString(" AND ") { if (it is AnyOf) "(${it.describe()})" else it.describe() }
        is AnyOf -> items.joinToString(" OR ") { if (it is All) "(${it.describe()})" else it.describe() }
    }
}

/**
 * Extracts the index terms a query requires (SPEC.md §10.4). A term is kept only when every node the query selects
 * implies it:
 *
 * - every node passes each segment, so the requirements of the segments are ANDed;
 * - a segment requires something only when each of its selectors is a filter that requires something: the
 *   selectors' requirements are ORed;
 * - in a filter, `&&` ANDs and `||` ORs (an OR needs both sides), and `!` and everything else require nothing;
 * - a comparison `q == literal` (either way round), where the singular query `q` ends in a name, or in a name and an
 *   index, requires the term (that name, literal): the object holding the name has it as a member value or as an
 *   element of its array value.
 *
 * A document filter (SPEC.md §10.5) is one more filter every selected node's document passes, so its requirement is
 * ANDed with the query's. A term the store's index controls leave out ([IndexPolicy]) requires nothing, as it can't be
 * looked up.
 */
internal class PathPlanner(private val policy: IndexPolicy, private val hashTerms: Boolean, private val maxDepth: Int, private val normalization: Normalization) {
    fun requirement(query: PathQuery, filter: LogicalExpr? = null): Requirement? = all(listOf(filter?.let { expr(it) }) + query.segments.map { segment(it) })

    /**
     * Terms one of which every document [filter] accepts has with its root as the containing object: the filter's
     * comparisons of `@.name` or `@.name[i]` with a literal, and of `$.name` or `$.name[i]`, since both `@` and `$` are
     * the root. Null when there are none.
     */
    fun rootTerms(filter: LogicalExpr): List<Requirement.Term>? {
        val terms = ArrayList<Requirement.Term>()
        return if (anchorTerms(filter, terms, rootIsCurrent = true) && terms.isNotEmpty()) terms else null
    }

    /**
     * The segment whose filters pin down the nodes it selects, and the terms that pin them: the last segment whose every
     * selector is a filter that implies one of [Anchor.terms] with the selected node itself as the containing object —
     * a comparison of `@.name` or `@.name[i]` with a literal. A node that segment selects is then the containing object
     * of a posting of one of the terms.
     */
    fun anchor(query: PathQuery): Anchor? {
        for (k in query.segments.indices.reversed()) {
            val terms = ArrayList<Requirement.Term>()
            val anchored = query.segments[k].selectors.all { s -> s is Selector.Filter && anchorTerms(s.expr, terms) }
            if (anchored && terms.isNotEmpty()) return Anchor(k, terms, parentPath(query, k))
        }
        return null
    }

    /**
     * The path of the nodes whose children segment [k] filters, when segments `0 until k` fix it — each a child segment
     * of one name or index — and segment [k] is a child segment too: its path hash, and its text with every index
     * written `[*]`. The containing objects of the anchor's postings are then children of a node at that path, so their
     * postings carry its hash (SPEC.md §15).
     */
    private fun parentPath(query: PathQuery, k: Int): Pair<Int, String>? {
        if (query.segments[k].descendant) return null
        var hash = PathHash.ROOT
        val text = StringBuilder("$")
        for (segment in query.segments.subList(0, k)) {
            if (segment.descendant) return null
            when (val s = segment.selectors.singleOrNull()) {
                is Selector.Name -> {
                    hash = PathHash.member(hash, s.utf8, 0, s.utf8.size)
                    NormalizedPath.appendName(text, s.key)
                }
                is Selector.Index -> {
                    hash = PathHash.element(hash)
                    text.append("[*]")
                }
                else -> return null
            }
        }
        return hash to text.toString()
    }

    /**
     * Adds to [into] terms one of which every node satisfying [expr] contains; false when there are none. With
     * [rootIsCurrent], `$` is the node as `@` is.
     */
    private fun anchorTerms(expr: LogicalExpr, into: MutableList<Requirement.Term>, rootIsCurrent: Boolean = false): Boolean = when (expr) {
        is LogicalExpr.And -> expr.items.any { item ->
            val terms = ArrayList<Requirement.Term>()
            anchorTerms(item, terms, rootIsCurrent).also { if (it) into += terms }
        }
        is LogicalExpr.Or -> expr.items.all { anchorTerms(it, into, rootIsCurrent) }
        is LogicalExpr.Comparison -> {
            val term = if (expr.op != CompareOp.EQ) {
                null
            } else {
                relativeEquality(expr.left, expr.right, rootIsCurrent) ?: relativeEquality(expr.right, expr.left, rootIsCurrent)
            }
            term?.let { into += it } != null
        }
        else -> false
    }

    /** The term of `@.name == literal` or `@.name[i] == literal`, whose containing object is `@`. */
    private fun relativeEquality(query: ValueExpr, literal: ValueExpr, rootIsCurrent: Boolean): Requirement.Term? {
        val q = (query as? ValueExpr.Query)?.query ?: return null
        if (q.absolute && !rootIsCurrent) return null
        val shape = q.segments.map { it.selectors.single() }
        val fits = (shape.size == 1 && shape[0] is Selector.Name) || (shape.size == 2 && shape[0] is Selector.Name && shape[1] is Selector.Index)
        return if (fits) equality(query, literal) as? Requirement.Term else null
    }

    private fun segment(segment: PathSegment): Requirement? {
        val parts = segment.selectors.map { (it as? Selector.Filter)?.let { f -> expr(f.expr) } ?: return null }
        return any(parts)
    }

    private fun expr(expr: LogicalExpr): Requirement? = when (expr) {
        is LogicalExpr.And -> all(expr.items.map { expr(it) })
        is LogicalExpr.Or -> any(expr.items.map { expr(it) ?: return null })
        is LogicalExpr.Comparison -> when (expr.op) {
            CompareOp.EQ -> equality(expr.left, expr.right) ?: equality(expr.right, expr.left)
            CompareOp.NE -> null
            else -> range(expr.left, expr.op, expr.right) ?: range(expr.right, flip(expr.op), expr.left)
        }
        else -> null
    }

    /** The member name whose value a singular query ends at: its last name, or the name before a last index. */
    private fun memberName(query: ValueExpr): String? {
        val segments = (query as? ValueExpr.Query)?.query?.segments ?: return null
        return when (val last = segments.lastOrNull()?.selectors?.single()) {
            is Selector.Name -> last.key
            is Selector.Index -> (segments.getOrNull(segments.size - 2)?.selectors?.single() as? Selector.Name)?.key
            else -> null
        }
    }

    private fun flip(op: CompareOp) = when (op) {
        CompareOp.LT -> CompareOp.GT
        CompareOp.LE -> CompareOp.GE
        CompareOp.GT -> CompareOp.LT
        CompareOp.GE -> CompareOp.LE
        else -> op
    }

    /**
     * The range of terms `q op literal` implies, for a number or string literal: the member's exact terms of that value
     * type on the right side of the literal (NumberKeys and UTF-8 bytes both order as the values do), and all its lossy
     * long terms of the type. Strings need a store that indexes every string length.
     */
    private fun range(query: ValueExpr, op: CompareOp, literal: ValueExpr): Requirement? {
        val name = memberName(query) ?: return null
        val value = (literal as? ValueExpr.Literal)?.value ?: return null
        val key = name.toByteArray(Charsets.UTF_8)
        if (!policy.indexesKey(key, 0, key.size)) return null
        val (exact, bound, text) = when (value) {
            is LiteralValue.Num -> Triple(TermTag.NUMBER, value.key, value.text)
            is LiteralValue.Str -> if (policy.limitsStrings) return null else Triple(TermTag.STRING, value.utf8, quoted(value.value))
            else -> return null
        }
        val prefix = GrowableBytes(key.size + 8)
        TermEncoder().writeKey(prefix, key, 0, key.size)
        fun term(tag: Int, tail: ByteArray?): ByteArray = GrowableBytes(prefix.size + 1 + (tail?.size ?: 0)).also {
            it.writeBytes(prefix.array, 0, prefix.size)
            it.writeByte(tag)
            if (tail != null) it.writeBytes(tail, 0, tail.size)
        }.toByteArray()
        val first = term(exact, null)
        val at = term(exact, bound)
        val past = term(exact + 1, null)
        val description = "${quoted(name)} ${op.text} $text"
        return when (op) {
            CompareOp.LT -> Requirement.Range(first, true, at, false, past, description)
            CompareOp.LE -> Requirement.Range(first, true, at, true, past, description)
            CompareOp.GT -> Requirement.Range(at, false, past, false, past, description)
            else -> Requirement.Range(at, true, past, false, past, description)
        }
    }

    private fun equality(query: ValueExpr, literal: ValueExpr): Requirement? {
        val name = memberName(query) ?: return null
        val value = (literal as? ValueExpr.Literal)?.value ?: return null
        val (queryValue, text) = when (value) {
            LiteralValue.Null -> JsonQueryValue.Null to "null"
            is LiteralValue.Bool -> JsonQueryValue.Bool(value.value) to value.value.toString()
            is LiteralValue.Num -> JsonQueryValue.Json(value.text) to value.text
            is LiteralValue.Str -> JsonQueryValue.Str(value.value) to quoted(value.value)
        }
        val term = try {
            QueryTerm.of(name, queryValue, hashTerms, maxDepth, policy, normalization)
        } catch (_: QueryRejectedException) {
            return null
        }
        return Requirement.Term(term, "${quoted(name)} == $text")
    }

    private fun quoted(s: String): String = StringBuilder().also { NormalizedPath.appendName(it, s) }.let { it.substring(1, it.length - 1) }

    private fun all(parts: List<Requirement?>): Requirement? {
        val kept = parts.filterNotNull().flatMap { if (it is Requirement.All) it.items else listOf(it) }
        return when (kept.size) {
            0 -> null
            1 -> kept[0]
            else -> Requirement.All(kept)
        }
    }

    private fun any(parts: List<Requirement>): Requirement? {
        val flat = parts.flatMap { if (it is Requirement.AnyOf) it.items else listOf(it) }
        return when (flat.size) {
            0 -> null
            1 -> flat[0]
            else -> Requirement.AnyOf(flat)
        }
    }
}

/**
 * Segment [segment] selects only containing objects of postings of [terms] (see [PathPlanner.anchor]); with [parent],
 * only those whose postings carry that path hash, the objects being children of the node at the path's text.
 */
internal class Anchor(val segment: Int, val terms: List<Requirement.Term>, val parent: Pair<Int, String>?) {
    /** The 16-bit hash postings must carry, or -1 for any. */
    val postingHash: Int = parent?.let { PathHash.posting(it.first) } ?: -1
}

/**
 * Candidate documents in docId order: ascending, or with [descending] from the top down. [docId] is [exhausted] after
 * the last: [Long.MAX_VALUE] ascending, -1 descending.
 */
internal abstract class Candidates(val descending: Boolean) {
    abstract val docId: Long

    val exhausted: Long get() = if (descending) -1L else Long.MAX_VALUE

    /** Moves to the first candidate at or past [target] in the candidates' order; never moves back. */
    abstract fun seek(target: Long)

    /** Whether document [a] comes before [b] in the candidates' order. */
    protected fun before(a: Long, b: Long): Boolean = if (descending) a > b else a < b

    /** The documents of one term's postings: memtables, and the segments whose bloom filter may hold it. */
    class TermDocs(version: Version, term: ByteArray, from: Long, descending: Boolean = false) : Candidates(descending) {
        private val sources: TermSources

        init {
            val list = ArrayList<TermSource>()
            for (m in version.memtables) {
                val source = MemtableTermSource(m.postings, term, from, descending)
                if (source.docId != exhausted) list += source
            }
            if (version.postingSegments.isNotEmpty()) {
                val hash = PostingSegments.bloomHash(term)
                for (s in version.postingSegments) {
                    val source = if (descending) {
                        ReverseSegmentTermSource.open(s.reader, term, hash, from)
                    } else {
                        SegmentTermSource.open(s.reader, term, hash, from)
                    } ?: continue
                    if (source.docId != exhausted) list += source
                }
            }
            sources = TermSources(list.toTypedArray(), descending)
        }

        override val docId: Long get() = sources.docId

        override fun seek(target: Long) = sources.seek(target)

        private val runs = DocRuns()
        private val merger = RunMerger()
        private var containersDoc = -1L
        private var containersHash = -1

        /** Posting entries read by [nextContainer], for tests. */
        val entriesDecoded: Long get() = merger.entriesDecoded

        /**
         * Starts reading the containing objects of the term's postings in document [docId] ([nextContainer]), with
         * [pathHash] only those whose parent has that path hash, and moves past the document; false when the term
         * isn't in it. Documents come in the candidates' order.
         */
        fun openContainers(docId: Long, pathHash: Int = -1): Boolean {
            runs.clear()
            containersDoc = -1
            sources.seek(docId)
            if (sources.docId != docId) return false
            sources.takeRuns(runs)
            merger.reset(docId, runs)
            containersDoc = docId
            containersHash = pathHash
            return true
        }

        /** The next containing object of the open document, in ascending `pre`, or -1 after the last. */
        fun nextContainer(): Long {
            if (containersDoc < 0) return -1
            while (merger.next()) if (containersHash < 0 || merger.pathHash == containersHash) return merger.containerPre
            return -1
        }

        /** Reads the open document's containing objects again from the first. */
        fun rewindContainers() {
            if (containersDoc < 0) return
            runs.rewind()
            merger.reset(containersDoc, runs)
        }
    }

    /** The documents every item has: each item is sought to the furthest document any of them is on, until they agree. */
    class All(private val items: List<Candidates>, descending: Boolean = false) : Candidates(descending) {
        override var docId: Long = align()
            private set

        private fun align(): Long {
            while (true) {
                var furthest = items[0].docId
                for (c in items) if (before(furthest, c.docId)) furthest = c.docId
                if (furthest == exhausted) return exhausted
                var agreed = true
                for (c in items) {
                    c.seek(furthest)
                    if (c.docId != furthest) agreed = false
                }
                if (agreed) return furthest
            }
        }

        override fun seek(target: Long) {
            if (!before(docId, target)) return
            for (c in items) c.seek(target)
            docId = align()
        }
    }

    /** The documents any item has. */
    class AnyOf(private val items: List<Candidates>, descending: Boolean = false) : Candidates(descending) {
        override val docId: Long
            get() {
                var first = items[0].docId
                for (c in items) if (before(c.docId, first)) first = c.docId
                return first
            }

        override fun seek(target: Long) {
            for (c in items) c.seek(target)
        }
    }

    /** Every document with stored data, from [from] on, for a search allowed to scan. */
    class Scan(private val store: FragmentStore, private val visibility: Visibility, from: Long, descending: Boolean = false) : Candidates(descending) {
        private val view = FragmentView()

        override var docId: Long = if (descending) previous(minOf(from, visibility.maxAllocatedDocId)) else next(maxOf(from, 0))
            private set

        private fun stored(d: Long): Boolean =
            visibility.isVisible(d) && (store.find(d, 0, FragmentKind.SUBTREE, 0, view) || store.find(d, 0, FragmentKind.HEADER, 0, view))

        private fun next(from: Long): Long {
            var d = from
            while (d <= visibility.maxAllocatedDocId) {
                if (stored(d)) return d
                d++
            }
            return Long.MAX_VALUE
        }

        private fun previous(from: Long): Long {
            var d = from
            while (d >= 0) {
                if (stored(d)) return d
                d--
            }
            return -1
        }

        override fun seek(target: Long) {
            if (before(docId, target)) docId = if (descending) previous(target) else next(target)
        }
    }

    /** Sorted distinct docIds, read in the candidates' order. */
    class DocList(private val list: LongList, descending: Boolean = false) : Candidates(descending) {
        private var i = if (descending) list.size - 1 else 0

        override val docId: Long get() = if (i in 0 until list.size) list.values[i] else exhausted

        override fun seek(target: Long) {
            if (!before(docId, target)) return
            i = if (descending) minOf(i, list.ceiling(target + 1) - 1) else maxOf(i, list.ceiling(target))
        }
    }

    /** A range with more documents than a search collects; see [of]. */
    class RangeTooWide(val range: Requirement.Range) : RuntimeException(null, null, false, false)

    companion object {
        /** The most documents a range requirement collects for one search, unless [of] is given another limit. */
        const val MAX_RANGE_DOCUMENTS: Int = 1 shl 20

        /**
         * The candidates of [requirement] from document [from] on, in the order [descending] picks; each term's
         * [TermDocs] is also put in [terms], keyed by [key]. A range is read up front into a list of documents; one
         * with more than [maxRangeDocuments] throws [RangeTooWide], or, with [wideRange], narrows nothing:
         * [wideRange] stands for every document.
         */
        fun of(
            requirement: Requirement,
            version: Version,
            from: Long,
            descending: Boolean = false,
            terms: MutableMap<String, TermDocs> = HashMap(),
            wideRange: (() -> Candidates)? = null,
            maxRangeDocuments: Int = MAX_RANGE_DOCUMENTS,
        ): Candidates = when (requirement) {
            is Requirement.Term -> terms.getOrPut(key(requirement.term.term)) { TermDocs(version, requirement.term.term, from, descending) }
            is Requirement.Range -> rangeDocs(requirement, version, from, descending, maxRangeDocuments)?.let { DocList(it, descending) } ?: wideRange?.invoke() ?: throw RangeTooWide(requirement)
            is Requirement.All -> All(requirement.items.map { of(it, version, from, descending, terms, wideRange, maxRangeDocuments) }, descending)
            is Requirement.AnyOf -> AnyOf(requirement.items.map { of(it, version, from, descending, terms, wideRange, maxRangeDocuments) }, descending)
        }

        /**
         * The documents from [from] on — at or above it, or at or below it when [descending] — with a term of [range],
         * sorted; null past [max].
         */
        private fun rangeDocs(range: Requirement.Range, version: Version, from: Long, descending: Boolean, max: Int): LongList? {
            val out = LongList()
            // Exact terms between the bounds: -1 skips a term, 0 takes it, 1 ends the scan.
            val exact = { t: ByteArray, n: Int ->
                val lo = java.util.Arrays.compareUnsigned(t, 0, n, range.from, 0, range.from.size)
                val hi = java.util.Arrays.compareUnsigned(t, 0, n, range.to, 0, range.to.size)
                when {
                    lo == 0 && !range.fromInclusive -> -1
                    hi > 0 || (hi == 0 && !range.toInclusive) -> 1
                    else -> 0
                }
            }
            val lossy = { t: ByteArray, n: Int ->
                val p = range.lossyPrefix
                if (n >= p.size && java.util.Arrays.equals(t, 0, p.size, p, 0, p.size)) 0 else 1
            }
            val low = if (descending) 0L else from
            val high = if (descending) from else Long.MAX_VALUE
            if (!scan(version, range.from, low, high, out, 2L * max, exact)) return null
            if (!scan(version, range.lossyPrefix, low, high, out, 2L * max, lossy)) return null
            out.sortDistinct()
            return if (out.size > max) null else out
        }

        /**
         * Adds the documents in `[low, high]` of the terms from [start] on that [accept] takes; false once more than
         * [limit] were added.
         */
        private fun scan(version: Version, start: ByteArray, low: Long, high: Long, out: LongList, limit: Long, accept: (ByteArray, Int) -> Int): Boolean {
            for (m in version.memtables) {
                val runs = m.postings.runsFrom(start)
                while (runs.hasNext()) {
                    val run = runs.next()
                    val verdict = accept(run.term, run.term.size)
                    if (verdict == 1) break
                    if (verdict == 0 && run.docId in low..high) {
                        out.add(run.docId)
                        if (out.size > limit) return false
                    }
                }
            }
            for (s in version.postingSegments) {
                if (s.reader.stats.maxDocId < low || s.reader.stats.minDocId > high) continue
                val cursor = s.reader.cursor()
                if (!cursor.seekCeiling(start)) continue
                while (cursor.valid) {
                    val verdict = accept(cursor.key.array, cursor.key.size)
                    if (verdict == 1) break
                    if (verdict == 0) {
                        val source = SegmentTermSource.at(cursor)
                        source.seek(low)
                        while (source.docId != Long.MAX_VALUE && source.docId <= high) {
                            out.add(source.docId)
                            if (out.size > limit) return false
                            source.skipDocument()
                        }
                    }
                    cursor.next()
                }
            }
            return true
        }

        fun key(term: ByteArray): String = String(term, Charsets.ISO_8859_1)
    }
}

/** A growable list of longs. */
internal class LongList {
    var values = LongArray(16)
        private set
    var size = 0
        private set

    fun add(v: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = v
    }

    fun clear() {
        size = 0
    }

    /** Sorts the values and drops repeats. */
    fun sortDistinct() {
        java.util.Arrays.sort(values, 0, size)
        var n = 0
        for (i in 0 until size) if (n == 0 || values[n - 1] != values[i]) values[n++] = values[i]
        size = n
    }

    /** Index of the first value ≥ [v], or [size]. */
    fun ceiling(v: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * The containing objects of the anchor terms' postings in one document, ascending and distinct, read as a document
 * walk reaches them: [advanceTo] moves to the first at or after a `pre`, which only grows during a walk. Memory is
 * the terms' posting cursors, whatever the number of containers.
 */
internal class AnchorContainers(terms: List<Candidates.TermDocs>, private val pathHash: Int) {
    // Each source once: a term the query repeats shares one.
    private val terms = terms.distinct()
    private val heads = LongArray(this.terms.size)

    /** The current container, [Long.MAX_VALUE] past the last. */
    var head: Long = Long.MAX_VALUE
        private set

    /** Starts at the first container of document [docId]; false when it has none. */
    fun open(docId: Long): Boolean {
        for (i in terms.indices) heads[i] = if (terms[i].openContainers(docId, pathHash)) next(i) else Long.MAX_VALUE
        head = first()
        return head != Long.MAX_VALUE
    }

    /** Starts at the open document's first container again, for another walk. */
    fun rewind() {
        for (i in terms.indices) {
            terms[i].rewindContainers()
            heads[i] = next(i)
        }
        head = first()
    }

    fun advanceTo(pre: Long) {
        if (head >= pre) return
        for (i in terms.indices) while (heads[i] < pre) heads[i] = next(i)
        head = first()
    }

    /** Whether [pre] is a container. */
    fun isAt(pre: Long): Boolean {
        advanceTo(pre)
        return head == pre
    }

    /** Whether a container lies in `[from, to]`. */
    fun within(from: Long, to: Long): Boolean {
        advanceTo(from)
        return head <= to
    }

    private fun next(i: Int): Long = terms[i].nextContainer().let { if (it < 0) Long.MAX_VALUE else it }

    private fun first(): Long {
        var min = Long.MAX_VALUE
        for (h in heads) if (h < min) min = h
        return min
    }
}

/**
 * The nodes a query selects from one document, each once, in document order (SPEC.md §10.4): a pre-order walk that
 * carries, for each node, the set of query steps it has reached. State `k` means the node is in the nodelist after
 * `k` segments; for a descendant segment `k`, the marker `D(k)` means the node is a descendant of a node in state `k`,
 * so the segment's selectors apply to its children too. A node is selected in state `n` (the number of segments), and
 * the walk skips every subtree that no state reaches. Memory is a cursor per open level of the document.
 */
internal class DocumentOrderEvaluator(private val evaluator: PathEvaluator, private val query: PathQuery) {
    private val n = query.segments.size

    init {
        require(n <= MAX_SEGMENTS) { "$n segments; at most $MAX_SEGMENTS fit the state masks" }
    }

    private val doc = evaluator.doc

    private class Frame(val parent: PathNode, val mask: Long, val cursor: StoredDocument.ChildCursor)

    private val stack = ArrayList<Frame>()
    private var pending: PathNode? = null
    private var pendingMask = 0L

    fun start(root: PathNode) {
        close()
        evaluator.root = root
        pending = root
        pendingMask = state(0)
    }

    /** The next selected node, or null after the last one of the document. */
    fun next(): PathNode? {
        while (true) {
            val node = pending
            if (node != null) {
                pending = null
                val mask = pendingMask
                if (node.isContainer && reachesChildren(mask)) stack += Frame(node, mask, doc.children(node))
                if (mask and state(n) != 0L) return node
                continue
            }
            val top = stack.lastOrNull() ?: return null
            val child = top.cursor.next()
            if (child == null) {
                top.cursor.close()
                stack.removeAt(stack.size - 1)
                continue
            }
            val mask = childMask(top.parent, top.mask, child)
            if (mask != 0L) {
                pending = child
                pendingMask = mask
            }
        }
    }

    fun close() {
        for (f in stack) f.cursor.close()
        stack.clear()
        pending = null
    }

    private fun state(k: Int): Long = 1L shl k

    private fun marker(k: Int): Long = 1L shl (k + 32)

    /** Whether segment [k] applies its selectors to the children of a node with [mask]. */
    private fun applies(mask: Long, k: Int): Boolean = mask and (state(k) or marker(k)) != 0L

    private fun reachesChildren(mask: Long): Boolean {
        for (k in 0 until n) if (applies(mask, k)) return true
        return false
    }

    /** Subtrees that end before this `pre` aren't entered: a pass that wants no node before it sets it. */
    var skipBefore: Long = -1

    // Anchors: segment [anchorSegment] selects only nodes that are among [anchors].
    private var anchorSegment = -1
    private var anchors: AnchorContainers? = null
    private var belowAnchor = 0L

    /** Nodes whose children were considered, for tests and plans. */
    var nodesVisited: Long = 0
        private set

    /**
     * Restricts the walk of the next documents with an anchor: segment [segment] selects only nodes among
     * [containers], so a node that hasn't passed that segment is only entered when its subtree holds one. The walk
     * reads [containers] forward, so they must be at the document's first container when a walk starts.
     */
    fun anchor(segment: Int, containers: AnchorContainers) {
        anchorSegment = segment
        anchors = containers
        belowAnchor = 0L
        for (j in segment + 1..n) belowAnchor = belowAnchor or state(j)
        for (j in segment + 1 until n) belowAnchor = belowAnchor or marker(j)
    }

    private fun childMask(parent: PathNode, mask: Long, child: PathNode): Long {
        if (child.pre + (if (child.isContainer) child.size else 0L) < skipBefore) return 0L
        nodesVisited++
        val anchors = anchors
        var out = 0L
        for (k in 0 until n) {
            if (!applies(mask, k)) continue
            val segment = query.segments[k]
            if (segment.descendant) out = out or marker(k)
            if (out and state(k + 1) != 0L) continue
            if (k == anchorSegment && !anchors!!.isAt(child.pre)) continue
            if (admits(segment, parent, child)) out = out or state(k + 1)
        }
        // Above the anchored segment, only subtrees that hold an anchor can lead to a selected node.
        if (anchors != null && out != 0L && out and belowAnchor == 0L && !anchors.within(child.pre, child.pre + (if (child.isContainer) child.size else 0L))) return 0L
        return out
    }

    private fun admits(segment: PathSegment, parent: PathNode, child: PathNode): Boolean {
        for (selector in segment.selectors) {
            val admitted = when (selector) {
                is Selector.Name -> parent.type == NodeType.OBJECT && child.keyLength == selector.utf8.size &&
                    java.util.Arrays.equals(child.keyBytes, child.keyOffset, child.keyOffset + child.keyLength, selector.utf8, 0, selector.utf8.size)
                Selector.Wildcard -> true
                is Selector.Index -> parent.type == NodeType.ARRAY &&
                    child.index == (if (selector.index >= 0) selector.index else doc.count(parent) + selector.index)
                is Selector.Slice -> parent.type == NodeType.ARRAY && inSlice(selector, child.index, doc.count(parent))
                is Selector.Filter -> evaluator.test(selector.expr, child)
            }
            if (admitted) return true
        }
        return false
    }

    /** Whether index [i] is among those the slice selects from an array of [length] (RFC 9535 section 2.3.4.2.2). */
    private fun inSlice(slice: Selector.Slice, i: Long, length: Long): Boolean {
        val step = slice.step ?: 1
        if (step == 0L) return false
        fun normalize(x: Long) = if (x >= 0) x else length + x
        return if (step > 0) {
            val lower = minOf(maxOf(normalize(slice.start ?: 0), 0), length)
            val upper = minOf(maxOf(normalize(slice.end ?: length), 0), length)
            i in lower until upper && (i - lower) % step == 0L
        } else {
            val upper = minOf(maxOf(normalize(slice.start ?: (length - 1)), -1), length - 1)
            val lower = minOf(maxOf(normalize(slice.end ?: (-length - 1)), -1), length - 1)
            i > lower && i <= upper && (upper - i) % -step == 0L
        }
    }

    companion object {
        /** States and markers share one `Long`: a search rejects a query of more segments. */
        const val MAX_SEGMENTS: Int = 31
    }
}

/**
 * Runs one JSONPath search over one snapshot (SPEC.md §10.4): candidate documents from [candidates], each
 * evaluated from its root in document order; then the type filter, the nested-hit filter and the cursor, as a
 * key–value search applies them. The query has at most [DocumentOrderEvaluator.MAX_SEGMENTS] segments.
 *
 * With a document [filter] (SPEC.md §10.5), a candidate is evaluated only when the filter, tested on its root, is true;
 * with [rootTerms] as well, one of whose terms every accepted document has with the root as the containing object, a
 * candidate without such a posting is skipped unread.
 *
 * With [descending] (and [candidates] in that order), hits come in `(docId, pre)` descending order, strictly before the
 * cursor. Each document is still evaluated forward, and its hits are kept and handed out last first, at most
 * [bufferHits] at a time, as [app.oreshkov.delva.internal.query.QueryExecutor] does: a document with more is walked
 * again for each earlier chunk, entering only the subtrees that reach it. A kept hit's path is found again when read.
 * Not thread-safe.
 */
internal class PathSearch(
    private val fragments: FragmentStore,
    private val query: PathQuery,
    private val candidates: Candidates,
    private val mode: ResultMode,
    private val nested: NestedHits,
    private val visibility: Visibility,
    private val afterDocId: Long,
    private val afterPre: Long,
    private val types: TypeIndexView,
    private val typeFilter: Int,
    private val compareCacheBytes: Long,
    /** The anchor of the query and the posting sources of its terms, when the search uses one. */
    private val anchor: Anchor? = null,
    private val anchorTerms: List<Candidates.TermDocs> = emptyList(),
    private val bufferHits: Int = QueryExecutor.DESCENDING_BUFFER_HITS,
    private val filter: LogicalExpr? = null,
    /** Posting sources of their own: a source shared with [anchorTerms] would already be past the document. */
    private val rootTerms: List<Candidates.TermDocs> = emptyList(),
) : HitSource {
    private val descending = candidates.descending
    private val anchors = if (anchor != null) AnchorContainers(anchorTerms, anchor.postingHash) else null

    /** Nodes whose children the document walks considered, for tests and plans. */
    var nodesVisited: Long = 0
        private set
    private val forward = if (typeFilter > 0) types.forwardCursor() else null

    override var hit: HitAddress? = null
        private set

    /** Documents evaluated, for tests and plans. */
    var documentsEvaluated: Long = 0
        private set

    /**
     * Candidate documents skipped unread: no anchor posting was under the query's path, or no posting of the root
     * terms had the root as its containing object.
     */
    var documentsSkipped: Long = 0
        private set

    /** Documents read whose root the document filter rejected; they count in [documentsEvaluated] too. */
    var documentsFiltered: Long = 0
        private set

    /** Posting entries of the root terms read to decide whether to skip documents, for tests. */
    val rootEntriesDecoded: Long get() = rootTerms.sumOf { it.entriesDecoded }

    /** Descending: walks of a document after the first, for tests. */
    var passesForOrder: Long = 0
        private set

    private var docId = -1L
    private var doc: StoredDocument? = null
    private var root: PathNode? = null
    private var ordered: DocumentOrderEvaluator? = null
    private var outerEnd = -1L
    private var suppressThrough = -1L

    /** Descending: in the cursor's document, nodes from this `pre` on aren't hits. */
    private var suppressFrom = Long.MAX_VALUE
    private var started = false

    // Descending: one chunk of the open document's hits as located nodes, handed out from the last, as in QueryExecutor.
    private var bufferedPres = LongArray(16)
    private var bufferedHolders = LongArray(16)
    private var bufferedSizes = LongArray(16)
    private var bufferedForms = LongArray(16)
    private var bufferedAt = 0
    private var chunkStarts = LongArray(4)
    private var chunkOuterEnds = LongArray(4)
    private var chunkCount = 0
    private var chunk = 0

    override fun next(): Boolean {
        if (typeFilter < 0) return false
        if (descending && mode == ResultMode.CONTAINING_ELEMENT) return previous()
        while (true) {
            if (doc == null && !openNextDocument()) {
                hit = null
                return false
            }
            val node = nextSelected()
            if (node == null) {
                closeDocument()
                continue
            }
            val typeId = if (forward != null) {
                if (node.type != NodeType.OBJECT || forward.typeAt(node.pre) != typeFilter) continue
                typeFilter
            } else {
                HitAddress.TYPE_UNKNOWN
            }
            if (mode == ResultMode.WHOLE_DOCUMENT) {
                closeDocument()
                hit = HitAddress(docId, 0, true, null, 0, 0, 0)
                return true
            }
            val located = node.located()
            if (nested == NestedHits.OUTERMOST_ONLY) {
                if (node.pre <= outerEnd) continue
                outerEnd = node.pre + located.size
            }
            if (node.pre <= suppressThrough) continue
            hit = HitAddress(docId, node.pre, false, located, 0, 0, 0, typeId, node)
            return true
        }
    }

    /** Descending CONTAINING_ELEMENT: the previous hit, from the open document's kept hits or the next document's. */
    private fun previous(): Boolean {
        while (bufferedAt == 0) {
            if (chunk > 0) {
                // The chunk before, from another walk of the document.
                chunk--
                passesForOrder++
                restartDocument()
                outerEnd = chunkOuterEnds[chunk]
                keepDocumentHits(chunkStarts[chunk], chunkStarts[chunk + 1])
                continue
            }
            if (doc != null) closeDocument()
            if (!openNextDocument()) {
                hit = null
                return false
            }
            chunkCount = 0
            keepDocumentHits(-1, suppressFrom)
            chunk = chunkCount - 1
        }
        val i = --bufferedAt
        val form = bufferedForms[i]
        val located = LocatedNode(
            bufferedPres[i], form ushr 48 != 0L, bufferedHolders[i], (form ushr 32).toInt() and 0xFF, form.toInt(), bufferedSizes[i], (form ushr 40).toInt() and 0xFF,
        )
        hit = HitAddress(docId, located.pre, false, located, 0, 0, 0, if (forward != null) typeFilter else HitAddress.TYPE_UNKNOWN)
        return true
    }

    /**
     * Keeps the hits of the open document that [next] would return ascending, in `pre` order, with nesting judged from
     * the start of the document; only hits in `[from, until)` are kept. On the first pass ([from] = -1) a full buffer
     * starts a new chunk, recorded with the nesting state before it; a later pass starts from that state and doesn't
     * enter subtrees that end before its chunk. See [QueryExecutor]'s counterpart.
     */
    private fun keepDocumentHits(from: Long, until: Long) {
        bufferedAt = 0
        val first = from < 0
        if (first) addChunk(0, outerEnd)
        ordered?.skipBefore = from
        while (true) {
            val node = nextSelected() ?: return
            if (node.pre >= until) return
            if (node.pre < from) continue
            if (forward != null && (node.type != NodeType.OBJECT || forward.typeAt(node.pre) != typeFilter)) continue
            val located = node.located()
            val outerBefore = outerEnd
            if (nested == NestedHits.OUTERMOST_ONLY) {
                if (node.pre <= outerEnd) continue
                outerEnd = node.pre + located.size
            }
            if (first && bufferedAt == bufferHits) {
                addChunk(node.pre, outerBefore)
                bufferedAt = 0
            }
            if (bufferedAt == bufferedPres.size) {
                val size = bufferedAt * 2
                bufferedPres = bufferedPres.copyOf(size)
                bufferedHolders = bufferedHolders.copyOf(size)
                bufferedSizes = bufferedSizes.copyOf(size)
                bufferedForms = bufferedForms.copyOf(size)
            }
            bufferedPres[bufferedAt] = located.pre
            bufferedHolders[bufferedAt] = located.holderPre
            bufferedSizes[bufferedAt] = located.size
            bufferedForms[bufferedAt] = (if (located.isSplit) 1L shl 48 else 0L) or (located.containerKind.toLong() shl 40) or
                (located.holderKind.toLong() shl 32) or (located.valueOffset.toLong() and 0xFFFF_FFFFL)
            bufferedAt++
        }
    }

    private fun addChunk(start: Long, outerEnd: Long) {
        if (chunkCount == chunkStarts.size) {
            chunkStarts = chunkStarts.copyOf(chunkCount * 2)
            chunkOuterEnds = chunkOuterEnds.copyOf(chunkCount * 2)
        }
        chunkStarts[chunkCount] = start
        chunkOuterEnds[chunkCount] = outerEnd
        chunkCount++
    }

    private fun openNextDocument(): Boolean {
        var from = when {
            started -> if (descending) docId - 1 else docId + 1
            descending -> when {
                afterDocId < 0 -> visibility.maxAllocatedDocId
                mode == ResultMode.WHOLE_DOCUMENT -> minOf(afterDocId - 1, visibility.maxAllocatedDocId)
                else -> minOf(afterDocId, visibility.maxAllocatedDocId)
            }
            afterDocId < 0 -> 0L
            mode == ResultMode.WHOLE_DOCUMENT -> afterDocId + 1
            else -> afterDocId
        }
        started = true
        var d: StoredDocument
        var root: PathNode
        var evaluator: PathEvaluator
        while (true) {
            candidates.seek(from)
            val next = candidates.docId
            if (next == candidates.exhausted) return false
            from = if (descending) next - 1 else next + 1
            if (!visibility.isVisible(next)) continue
            docId = next
            if (rootTerms.isNotEmpty() && !rootHasTerm()) {
                documentsSkipped++
                continue
            }
            if (anchor != null) {
                // The anchored segment selects only containing objects of the anchor's postings, and with a path hash
                // only those under the right path: a document without one has no hits and isn't opened.
                if (!anchors!!.open(docId)) {
                    documentsSkipped++
                    continue
                }
            }
            documentsEvaluated++
            d = StoredDocument(fragments, docId)
            root = d.node(0)
            evaluator = PathEvaluator(d, root, compareCacheBytes)
            if (filter == null || evaluator.test(filter, root)) break
            documentsFiltered++
        }
        doc = d
        this.root = root
        ordered = DocumentOrderEvaluator(evaluator, query).also { walk ->
            if (anchor != null) walk.anchor(anchor.segment, anchors!!)
            walk.start(root)
        }
        forward?.open(docId)
        outerEnd = -1
        val inCursorDocument = docId == afterDocId && mode == ResultMode.CONTAINING_ELEMENT
        suppressThrough = if (inCursorDocument && !descending) afterPre else -1
        suppressFrom = if (inCursorDocument && descending) afterPre else Long.MAX_VALUE
        return true
    }

    /** Whether a posting of the root terms in the candidate [docId] has the root as its containing object. Containers
     * come in ascending `pre`, so only each term's first is read.
     */
    private fun rootHasTerm(): Boolean = rootTerms.any { it.openContainers(docId) && it.nextContainer() == 0L }

    /** Starts the open document's selected nodes again from its root. */
    private fun restartDocument() {
        anchors?.rewind()
        ordered?.start(root!!)
        forward?.open(docId)
    }

    private fun nextSelected(): PathNode? {
        return ordered?.next()
    }

    private fun closeDocument() {
        ordered?.let { nodesVisited += it.nodesVisited }
        ordered?.close()
        ordered = null
        doc = null
        root = null
    }
}
