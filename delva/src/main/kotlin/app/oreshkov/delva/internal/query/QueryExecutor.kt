package app.oreshkov.delva.internal.query

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.ResultMode
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
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.payload.Reassembler
import app.oreshkov.delva.internal.store.DocumentFragments
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.term.PostingLocator
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.internal.visibility.Visibility

/**
 * Where a hit's node is: the document root ([wholeDocument]), a [located] node, or an object that a resolved posting
 * locator names at [offset] of fragment `(fragmentPre, fragmentKind)`, not decoded yet. [typeId] is the node's type when
 * the search already knows it (a type filter), else [TYPE_UNKNOWN]. [pathNode] is the node reached from the document
 * root when the search walked there (a JSONPath search), so its parent chain names it; else null. [documentKey] is the
 * document's key when the search read it (a search in key order); else null. Immutable.
 */
internal class HitAddress(
    val docId: Long,
    val pre: Long,
    val wholeDocument: Boolean,
    val located: LocatedNode?,
    val fragmentPre: Long,
    val fragmentKind: Int,
    val offset: Int,
    val typeId: Int = TYPE_UNKNOWN,
    val pathNode: PathNode? = null,
    val documentKey: String? = null,
) {
    companion object {
        const val TYPE_UNKNOWN: Int = -1
    }
}

/** The hits of one search in `(docId, pre)` order, strictly after its cursor; pagination is the caller's. */
internal interface HitSource {
    /** The last hit found by [next]. */
    val hit: HitAddress?

    /** Finds the next hit; false when there are no more. */
    fun next(): Boolean
}

/** How a search with a type filter finds its documents. */
internal enum class TypeQueryPlan {
    /** By estimated sizes: type-driven when the type has fewer objects than the term has documents. */
    AUTO,

    /** Walk the term's postings and look up each containing object's type. */
    TERM_DRIVEN,

    /** Walk the documents that have objects of the type, and look up the type of each containing object in them. */
    TYPE_DRIVEN,
}

/** Work counters of one search, for tests and diagnostics. */
internal class QueryStats {
    var documentsOpened: Long = 0
    var documentsSkipped: Long = 0
    var runsOpened: Long = 0
    var rechecks: Long = 0
    var locates: Long = 0
    var segmentsSearched: Long = 0
    var segmentsSkipped: Long = 0

    /** Whether a type filter ran type-driven. */
    var typeDriven: Boolean = false
}

/**
 * Runs one key–value query over one snapshot: the term's sources — memtables, and the segments whose
 * docId range reaches the cursor and whose bloom filter may hold the term — merged by docId → visibility → k-way run
 * merge with duplicate drop → type filter → recheck of lossy terms → locator resolution → nested-hit filter → hits in `(docId, pre)` order, strictly after the cursor.
 * Pagination is the caller's.
 *
 * With [descending], the sources are read from the top down, and hits come in `(docId, pre)` descending order, strictly
 * before the cursor. Each document's hits are found forward exactly as for an ascending search, kept (a `pre` and a
 * locator each), and handed out last first. At most [bufferHits] are kept: a document with more is read in chunks of
 * that many hits, the last chunk first, merging its runs again for each earlier chunk. Memory stays bounded however
 * many hits a document has, and a document with fewer is read once.
 *
 * - WHOLE_DOCUMENT with an exact term and no type filter decodes no entries: a document with a visible run is a hit.
 * - OUTERMOST_ONLY judges nesting over all hits of a document (SPEC.md §4.5), so a search resumed inside a document
 *   evaluates the entries up to the cursor again, without emitting them. With a type filter, nesting is judged among
 *   the objects of the type.
 * - A type filter runs **term-driven** (every document of the term) or **type-driven** (the documents that have objects
 *   of the type, from the type's inverted entries, each document's postings sought), chosen by [plan]. Either way a
 *   forward type cursor per document, merge-joined with the entries' ascending containers, keeps the objects of the
 *   type, so memory doesn't grow with their number. Both give the same hits, in either order.
 *
 * [typeFilter] is [NO_TYPE_FILTER], [UNKNOWN_TYPE] (a name without an id: no hits) or a typeId. Not thread-safe.
 */
internal class QueryExecutor(
    private val version: Version,
    private val fragments: FragmentStore,
    private val term: QueryTerm,
    private val mode: ResultMode,
    private val nested: NestedHits,
    private val visibility: Visibility,
    private val afterDocId: Long,
    private val afterPre: Long,
    private val types: TypeIndexView,
    private val typeFilter: Int = NO_TYPE_FILTER,
    private val plan: TypeQueryPlan = TypeQueryPlan.AUTO,
    private val descending: Boolean = false,
    private val bufferHits: Int = DESCENDING_BUFFER_HITS,
) : HitSource {
    val stats = QueryStats()

    private val locator = NodeLocator(fragments)
    private val matcher = ValueMatcher()
    private val lookup = DocumentFragments(fragments)
    private val reassembler = Reassembler(lookup)
    private val view = FragmentView()
    private val merger = RunMerger()

    /** The first document the sources reach: they start at or past it in the search's order. */
    private val fromDocId = if (descending) {
        when {
            afterDocId < 0 -> visibility.maxAllocatedDocId
            mode == ResultMode.WHOLE_DOCUMENT -> minOf(afterDocId - 1, visibility.maxAllocatedDocId)
            else -> minOf(afterDocId, visibility.maxAllocatedDocId)
        }
    } else {
        when {
            afterDocId < 0 -> 0L
            mode == ResultMode.WHOLE_DOCUMENT -> afterDocId + 1
            else -> afterDocId
        }
    }

    private var sources: TermSources? = null
    private val docRuns = DocRuns()
    private var docId = -1L
    private var docOpen = false
    private var outerEnd = -1L

    /** In the cursor's document, containers up to this `pre` aren't emitted. */
    private var suppressThrough = -1L

    /** Descending: in the cursor's document, containers from this `pre` on aren't emitted. */
    private var suppressFrom = Long.MAX_VALUE

    // Descending: one chunk of the open document's hits in `pre` order, handed out from the last; [bufferedAt] is how
    // many are left. [chunkStarts] holds the `pre` of each chunk's first hit, [chunkOuterEnds] the nesting state before
    // it, and [chunk] is the chunk in the buffer.
    private var bufferedPres = LongArray(16)
    private var bufferedFragments = LongArray(16)
    private var bufferedLocators = LongArray(16)
    private var bufferedAt = 0
    private var chunkStarts = LongArray(4)
    private var chunkOuterEnds = LongArray(4)
    private var chunkCount = 0
    private var chunk = 0

    /** Descending: documents read in more than one pass, and the passes after the first; for tests. */
    var documentsRereadForOrder: Long = 0
        private set
    var passesForOrder: Long = 0
        private set

    // Type filter: a forward cursor tells each containing object's type; type-driven, the type's inverted scan picks
    // the documents.
    private val filtered = typeFilter > 0
    private var typeDriven = false
    private val forward = if (filtered) types.forwardCursor() else null
    private var scan: TypeIndexView.InvertedTypeScan? = null

    override var hit: HitAddress? = null
        private set

    val entriesDecoded: Long get() = merger.entriesDecoded

    override fun next(): Boolean {
        if (descending && mode == ResultMode.CONTAINING_ELEMENT) return previous()
        while (true) {
            if (!docOpen && !openNextDocument()) {
                hit = null
                return false
            }
            if (mode == ResultMode.WHOLE_DOCUMENT) {
                if (term.lossy || filtered) {
                    if (!nextEntry()) {
                        docOpen = false
                        continue
                    }
                    if (term.lossy && !recheck()) continue
                }
                docOpen = false
                hit = HitAddress(docId, 0, true, null, 0, 0, 0)
                return true
            }
            if (!nextEntry()) {
                docOpen = false
                continue
            }
            val containerPre = merger.containerPre
            if (containerPre <= suppressThrough && nested == NestedHits.ALL) continue
            if (term.lossy && !recheck()) continue
            val typeId = if (filtered) typeFilter else HitAddress.TYPE_UNKNOWN
            if (nested == NestedHits.OUTERMOST_ONLY) {
                val node = container()
                if (containerPre <= outerEnd) continue
                outerEnd = containerPre + node.size
                if (containerPre <= suppressThrough) continue
                hit = HitAddress(docId, containerPre, false, node, 0, 0, 0, typeId)
                return true
            }
            hit = when (merger.locatorKind) {
                PostingLocator.PART, PostingLocator.SUBTREE -> HitAddress(docId, containerPre, false, null, merger.fragmentPre, fragmentKind(merger.locatorKind), merger.locatorOffset, typeId)
                else -> HitAddress(docId, containerPre, false, container(), 0, 0, 0, typeId)
            }
            return true
        }
    }

    /** Descending CONTAINING_ELEMENT: the previous hit, from the open document's kept hits or the next document's. */
    private fun previous(): Boolean {
        while (bufferedAt == 0) {
            if (chunk > 0) {
                // The chunk before, from the document's runs merged again.
                chunk--
                passesForOrder++
                docRuns.rewind()
                merger.reset(docId, docRuns)
                forward?.open(docId)
                outerEnd = chunkOuterEnds[chunk]
                keepDocumentHits(chunkStarts[chunk], chunkStarts[chunk + 1])
                continue
            }
            if (!openNextDocument()) {
                hit = null
                return false
            }
            docOpen = false
            chunkCount = 0
            keepDocumentHits(-1, suppressFrom)
            chunk = chunkCount - 1
            if (chunk > 0) documentsRereadForOrder++
        }
        val i = --bufferedAt
        val containerPre = bufferedPres[i]
        val fragmentPre = bufferedFragments[i]
        val locatorKind = (bufferedLocators[i] ushr 32).toInt()
        val offset = bufferedLocators[i].toInt()
        val typeId = if (filtered) typeFilter else HitAddress.TYPE_UNKNOWN
        hit = when (locatorKind) {
            PostingLocator.PART, PostingLocator.SUBTREE -> HitAddress(docId, containerPre, false, null, fragmentPre, fragmentKind(locatorKind), offset, typeId)
            else -> HitAddress(docId, containerPre, false, container(containerPre, locatorKind, fragmentPre, offset), 0, 0, 0, typeId)
        }
        return true
    }

    /**
     * Keeps the hits of the open document that [next] would return ascending, in `pre` order: the same filters, with
     * nesting judged from the start of the document. Only hits in `[from, until)` are kept. Entries from [until] on
     * don't matter: they are never kept, and a later container can't make an earlier one nested. In the cursor's
     * document, [until] is the cursor.
     *
     * On the first pass ([from] = -1), when the buffer is full a new chunk starts: its first `pre` goes to
     * [chunkStarts], the nesting state before it to [chunkOuterEnds], and the buffer is emptied, so it ends up holding
     * the last chunk. A later pass starts from that state and passes over the entries before its chunk unjudged.
     */
    private fun keepDocumentHits(from: Long, until: Long) {
        bufferedAt = 0
        val first = from < 0
        if (first) addChunk(0, outerEnd)
        while (nextEntry()) {
            val containerPre = merger.containerPre
            if (containerPre >= until) return
            if (containerPre < from) continue
            if (term.lossy && !recheck()) continue
            val outerBefore = outerEnd
            if (nested == NestedHits.OUTERMOST_ONLY) {
                val node = container()
                if (containerPre <= outerEnd) continue
                outerEnd = containerPre + node.size
            }
            if (first && bufferedAt == bufferHits) {
                addChunk(containerPre, outerBefore)
                bufferedAt = 0
            }
            if (bufferedAt == bufferedPres.size) {
                bufferedPres = bufferedPres.copyOf(bufferedAt * 2)
                bufferedFragments = bufferedFragments.copyOf(bufferedAt * 2)
                bufferedLocators = bufferedLocators.copyOf(bufferedAt * 2)
            }
            bufferedPres[bufferedAt] = containerPre
            bufferedFragments[bufferedAt] = merger.fragmentPre
            bufferedLocators[bufferedAt] = (merger.locatorKind.toLong() shl 32) or (merger.locatorOffset.toLong() and 0xFFFF_FFFFL)
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

    /** Moves the merger to the next entry whose containing object passes the type filter. */
    private fun nextEntry(): Boolean {
        while (merger.next()) {
            if (!filtered) return true
            if (forward!!.typeAt(merger.containerPre) == typeFilter) return true
        }
        return false
    }

    private fun openNextDocument(): Boolean {
        if (typeFilter == UNKNOWN_TYPE) return false
        val merged = sources ?: openSources().also { sources = it }
        while (true) {
            if (typeDriven && !nextTypedDocument(merged)) return false
            val next = merged.docId
            if (next == merged.exhausted) return false
            if (!visibility.isVisible(next)) {
                merged.skipDocument()
                stats.documentsSkipped++
                continue
            }
            docId = next
            lookup.docId = docId
            stats.documentsOpened++
            if (mode == ResultMode.WHOLE_DOCUMENT && !term.lossy && !filtered) {
                stats.runsOpened += merged.skipDocument()
                docOpen = true
                return true
            }
            docRuns.clear()
            stats.runsOpened += merged.takeRuns(docRuns)
            merger.reset(docId, docRuns)
            forward?.open(docId)
            outerEnd = -1
            suppressThrough = -1
            suppressFrom = Long.MAX_VALUE
            docOpen = true
            if (docId == afterDocId && mode == ResultMode.CONTAINING_ELEMENT) {
                if (descending) suppressFrom = afterPre else suppressThrough = afterPre
            }
            return true
        }
    }

    /**
     * Type-driven: moves [merged] to the next document that has both the term and an object of the type, and [scan]
     * past that document's objects without keeping them: the forward cursor tells them apart as the document's entries
     * are merged. False when either side is exhausted.
     */
    private fun nextTypedDocument(merged: TermSources): Boolean {
        val scan = scan!!
        while (true) {
            if (scan.valid && (if (descending) scan.docId > merged.docId else scan.docId < merged.docId)) scan.seek(merged.docId, if (descending) Long.MAX_VALUE else 0)
            if (!scan.valid) return false
            val docId = scan.docId
            while (scan.valid && scan.docId == docId) scan.next()
            merged.seek(docId)
            if (merged.docId == merged.exhausted) return false
            if (merged.docId == docId) return true
        }
    }

    private fun openSources(): TermSources {
        val list = ArrayList<TermSource>()
        var termDocuments = 0L
        val exhausted = if (descending) -1L else Long.MAX_VALUE
        for (m in version.memtables) {
            val source = MemtableTermSource(m.postings, term.term, fromDocId, descending)
            if (source.docId != exhausted) list += source
        }
        val segments = version.postingSegments
        if (segments.isNotEmpty()) {
            val hash = PostingSegments.bloomHash(term.term)
            for (s in segments) {
                if (descending) {
                    val source = ReverseSegmentTermSource.open(s.reader, term.term, hash, fromDocId)
                    if (source == null) {
                        stats.segmentsSkipped++
                    } else {
                        stats.segmentsSearched++
                        termDocuments += source.docFreq
                        if (source.docId != exhausted) list += source
                    }
                    continue
                }
                val source = SegmentTermSource.open(s.reader, term.term, hash, fromDocId)
                if (source == null) {
                    stats.segmentsSkipped++
                } else {
                    stats.segmentsSearched++
                    termDocuments += source.docFreq
                    if (source.docId != Long.MAX_VALUE) list += source
                }
            }
        }
        if (filtered) {
            typeDriven = when (plan) {
                TypeQueryPlan.TERM_DRIVEN -> false
                TypeQueryPlan.TYPE_DRIVEN -> true
                TypeQueryPlan.AUTO -> typeDrivenIsSmaller(termDocuments)
            }
            stats.typeDriven = typeDriven
            if (typeDriven) scan = if (descending) types.scanDescending(typeFilter, fromDocId, Long.MAX_VALUE) else types.scan(typeFilter, fromDocId - 1, Long.MAX_VALUE)
        }
        return TermSources(list.toTypedArray(), descending)
    }

    /** Whether the type has fewer objects than the term has documents, counting memtable runs only as far as needed. */
    private fun typeDrivenIsSmaller(segmentDocuments: Long): Boolean {
        val typeObjects = types.approximateCount(typeFilter)
        var documents = segmentDocuments
        for (m in version.memtables) {
            val runs = if (descending) m.postings.runsDescending(term.term, fromDocId) else m.postings.runs(term.term, fromDocId)
            while (documents <= typeObjects && runs.hasNext()) {
                runs.next()
                documents++
            }
        }
        return typeObjects < documents
    }

    /** Whether the member value of the merger's entry equals the query literal. */
    private fun recheck(): Boolean {
        stats.rechecks++
        stats.locates++
        val member = locator.locate(docId, merger.memberPre)
        val literal = term.literal
        val literalKind = when (literal) {
            is Literal.Obj -> PayloadTag.OBJ
            is Literal.Arr -> PayloadTag.ARR
            else -> 0
        }
        if (member.containerKind != literalKind || member.size != literal.size) return false
        matcher.reset(literal)
        if (member.isSplit) {
            reassembler.writeSplit(member.pre, matcher)
        } else {
            if (!fragments.find(docId, member.holderPre, member.holderKind, 0, view)) throw CorruptionException("document $docId", "fragment (${member.holderPre}, ${member.holderKind}) vanished")
            reassembler.writeValue(view.slice(), member.valueOffset, member.pre, matcher)
        }
        return matcher.matched
    }

    /** The containing object of the merger's entry. */
    private fun container(): LocatedNode = container(merger.containerPre, merger.locatorKind, merger.fragmentPre, merger.locatorOffset)

    /** The containing object at [containerPre] of an entry with this locator. */
    private fun container(containerPre: Long, locatorKind: Int, fragmentPre: Long, locatorOffset: Int): LocatedNode {
        stats.locates++
        val node = when (locatorKind) {
            PostingLocator.PART, PostingLocator.SUBTREE -> locator.atOffset(docId, fragmentPre, fragmentKind(locatorKind), locatorOffset, containerPre)
            PostingLocator.HEADER -> locator.split(docId, containerPre)
            else -> locator.locate(docId, containerPre)
        }
        if (node.containerKind != PayloadTag.OBJ) throw CorruptionException("document $docId", "posting container $containerPre isn't an object")
        return node
    }

    private fun fragmentKind(locatorKind: Int): Int = if (locatorKind == PostingLocator.PART) FragmentKind.PART else FragmentKind.SUBTREE

    companion object {
        const val NO_TYPE_FILTER: Int = 0
        const val UNKNOWN_TYPE: Int = -1

        /** Hits of one document a descending search keeps at a time: 24 bytes each. */
        const val DESCENDING_BUFFER_HITS: Int = 1 shl 18
    }
}
