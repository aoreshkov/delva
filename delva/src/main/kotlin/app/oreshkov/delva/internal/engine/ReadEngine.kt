package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.BlockingReadableJsonStore
import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CompareOptions
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DiffOptions
import app.oreshkov.delva.Difference
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InvalidCursorException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.QueryPlan
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.SelectResult
import app.oreshkov.delva.StoreInfo
import app.oreshkov.delva.TypeInfo
import app.oreshkov.delva.TypePage
import app.oreshkov.delva.VerifyProgress
import app.oreshkov.delva.VerifyReport
import app.oreshkov.delva.internal.canon.Nfc
import app.oreshkov.delva.internal.compare.CompareSettings
import app.oreshkov.delva.internal.compare.CompareSide
import app.oreshkov.delva.internal.compare.CompareStats
import app.oreshkov.delva.internal.compare.Comparison
import app.oreshkov.delva.internal.compare.LiteralFragments
import app.oreshkov.delva.internal.compare.LiteralParser
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.KeyIndexView
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.path.LogicalExpr
import app.oreshkov.delva.internal.path.PathQuery
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.query.Candidates
import app.oreshkov.delva.internal.query.DocumentOrderEvaluator
import app.oreshkov.delva.internal.query.HitReader
import app.oreshkov.delva.internal.query.LocatedNode
import app.oreshkov.delva.internal.query.NodeLocator
import app.oreshkov.delva.internal.query.PathEvaluator
import app.oreshkov.delva.internal.query.PathPlanner
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.internal.query.QueryExecutor
import app.oreshkov.delva.internal.query.QueryTerm
import app.oreshkov.delva.internal.query.StoredDocument
import app.oreshkov.delva.internal.split.SpillFiles
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.internal.types.TypeNames
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The read operations of a store, over views of it that a subclass provides: the writer's [Engine], or a
 * [SecondaryEngine] that replays what another process writes. A view is a [Snapshot] and the index [Version] that holds
 * at least everything it sees; reads pin one for as long as they read and never lock. The type names a version's indexes
 * refer to are [dictionaryOf] it, and its fragments are read through [fragmentsOf] it.
 */
internal abstract class ReadEngine(val options: EngineOptions) : BlockingReadableJsonStore, EngineHealth, AutoCloseable {
    val blockCache = ByteCache(options.blockCacheBytes)
    val fragmentCache = ByteCache(options.fragmentCacheBytes)

    @Suppress("LeakingThis")
    val budget = IngestBudget(options.ingestBudgetBytes, this)

    /** Payload codecs and the dictionaries fragments may use. */
    val codecs = FragmentCodecs(options.compression)

    /** Where JSON operands of comparisons spill duplicate-key hashes. */
    abstract val spillFiles: SpillFiles

    /** The file system and directory for the temporary fragments of JSON operands that don't fit in memory. */
    protected abstract val literalIo: FileSystemIo
    protected abstract val literalDir: Path

    private val hitReaders = ConcurrentLinkedQueue<HitReader>()
    private val literalParsers = ConcurrentLinkedQueue<LiteralParser>()

    /** What comparisons did; for tests. */
    val compareStats = CompareStats()

    /**
     * A snapshot and a version holding at least everything it sees, whose segments hold nothing newer than it (a type
     * segment keeps only the newest version of a key). Release the version when done.
     */
    abstract fun openView(): Pair<Snapshot, Version>

    /** The newest snapshot, for what [info] reports. */
    protected abstract fun currentSnapshot(): Snapshot

    /** Fragments as [version] indexes them. */
    abstract fun fragmentsOf(version: Version): FragmentStore

    /** The type names [version]'s type index refers to. */
    abstract fun dictionaryOf(version: Version): TypeDictionary

    /** A view for [verify] with the files it checks; its version is retained. */
    protected abstract fun openScrubView(): ScrubView

    /** Whether this is a secondary, for [info]. */
    protected abstract val secondary: Boolean

    override fun search(query: KeyValueQuery): SearchResult {
        checkUsable()
        val term = QueryTerm.of(query.key, query.value, options.creation.hashTerms, options.store.maxDepth, options.indexPolicy, options.creation.normalization)
        val type = validTypeName(query.type)
        return openResult { snapshot, version ->
            val fragments = fragmentsOf(version)
            val types = TypeIndexView(version, snapshot.visibility, snapshot.lsn)
            val executor = QueryExecutor(
                version, fragments, term, query.mode, query.nested, snapshot.visibility,
                afterDocId = query.after?.docId?.raw ?: -1L,
                afterPre = query.after?.nodePre ?: -1L,
                types = types,
                typeFilter = typeFilter(type, version),
                plan = options.typeQueryPlan,
                descending = query.order == SearchOrder.DESCENDING,
                bufferHits = options.descendingBufferHits,
            )
            EngineSearchResult(this, query.limit, query.maxBytes, query.order, snapshot, version, fragments, types, executor, keysOf(version), keyOrder = false, dictionaryOf(version))
        }
    }

    override fun search(query: JsonPathQuery): SearchResult {
        checkUsable()
        val path = pathQuery(query.path)
        if (path.segments.size > DocumentOrderEvaluator.MAX_SEGMENTS) {
            throw QueryRejectedException(
                QueryRejectedException.Reason.TOO_MANY_SEGMENTS,
                "${query.path} has ${path.segments.size} segments, and a search takes at most ${DocumentOrderEvaluator.MAX_SEGMENTS}: select evaluates it on one document",
            )
        }
        val filter = documentFilter(query)
        val planner = planner()
        val requirement = planner.requirement(path, filter)
        val anchor = if (requirement == null) null else planner.anchor(path)
        val rootTerms = filter?.let { planner.rootTerms(it) }.orEmpty()
        if (requirement == null && query.scan != ScanPolicy.ALLOW_SCAN) {
            throw QueryRejectedException(
                QueryRejectedException.Reason.NEEDS_SCAN,
                "no index term narrows the search for ${query.path}: filter on a member equal to a literal, or use ScanPolicy.ALLOW_SCAN",
            )
        }
        val type = validTypeName(query.type)
        return openResult { snapshot, version ->
            val fragments = fragmentsOf(version)
            val types = TypeIndexView(version, snapshot.visibility, snapshot.lsn)
            val afterDocId = query.after?.docId?.raw ?: -1L
            val descending = query.order == SearchOrder.DESCENDING
            // Where the candidates start; the search seeks on from there.
            val maxDocId = snapshot.visibility.maxAllocatedDocId
            val from = if (descending) (if (afterDocId < 0) maxDocId else minOf(afterDocId, maxDocId)) else maxOf(afterDocId, 0)
            val terms = HashMap<String, Candidates.TermDocs>()
            val scan = { Candidates.Scan(fragments, snapshot.visibility, from, descending) }
            val candidates = try {
                if (requirement == null) scan() else Candidates.of(requirement, version, from, descending, terms, scan.takeIf { query.scan == ScanPolicy.ALLOW_SCAN }, options.maxRangeDocuments)
            } catch (e: Candidates.RangeTooWide) {
                throw QueryRejectedException(
                    QueryRejectedException.Reason.RANGE_TOO_WIDE,
                    "the range ${e.range.text} of ${query.path} matches more than ${options.maxRangeDocuments} documents: narrow it, or use ScanPolicy.ALLOW_SCAN",
                )
            }
            val anchorTerms = anchor?.terms?.map { t -> terms.getOrPut(Candidates.key(t.term.term)) { Candidates.TermDocs(version, t.term.term, from, descending) } }.orEmpty()
            val anchorKeys = anchor?.terms?.mapTo(HashSet()) { Candidates.key(it.term.term) }.orEmpty()
            val rootTermDocs = rootTerms.distinctBy { Candidates.key(it.term.term) }.map { t ->
                val key = Candidates.key(t.term.term)
                if (key in anchorKeys) Candidates.TermDocs(version, t.term.term, from, descending) else terms.getOrPut(key) { Candidates.TermDocs(version, t.term.term, from, descending) }
            }
            val search = PathSearch(
                fragments, path, candidates, query.mode, query.nested, snapshot.visibility,
                afterDocId = afterDocId,
                afterPre = query.after?.nodePre ?: -1L,
                types = types,
                typeFilter = typeFilter(type, version),
                compareCacheBytes = options.compareCacheBytes,
                anchor = anchor,
                anchorTerms = anchorTerms,
                bufferHits = options.descendingBufferHits,
                filter = filter,
                rootTerms = rootTermDocs,
            )
            EngineSearchResult(this, query.limit, query.maxBytes, query.order, snapshot, version, fragments, types, search, keysOf(version), keyOrder = false, dictionaryOf(version))
        }
    }

    override fun count(query: KeyValueQuery, atMost: Long): Long = countHits(atMost) { search(query) }

    override fun count(query: JsonPathQuery, atMost: Long): Long = countHits(atMost) { search(query) }

    override fun count(query: DocumentKeyQuery, atMost: Long): Long = countHits(atMost) { search(query) }

    override fun search(query: DocumentKeyQuery): SearchResult {
        checkKeyed()
        checkUsable()
        val normalization = options.creation.normalization
        // A prefix or cursor key with a lone surrogate can't start or equal any stored key.
        val prefix = DocumentKeys.encode(query.prefix, normalization)
        val after = query.after?.key?.let { DocumentKeys.encode(it, normalization) ?: throw InvalidCursorException("not a cursor: invalid key") }
        return openResult { snapshot, version ->
            val keys = KeyIndexView(version, snapshot.visibility)
            val source = KeyHitSource(prefix?.let { keys.scan(it, after, query.order == SearchOrder.DESCENDING) })
            val fragments = fragmentsOf(version)
            val types = TypeIndexView(version, snapshot.visibility, snapshot.lsn)
            EngineSearchResult(this, query.limit, query.maxBytes, query.order, snapshot, version, fragments, types, source, keys, keyOrder = true, dictionaryOf(version))
        }
    }

    override fun get(key: String): DocId? {
        checkKeyed()
        checkUsable()
        val bytes = DocumentKeys.encode(key, options.creation.normalization) ?: return null
        if (bytes.size > CreationOptions.MAX_DOCUMENT_KEY_BYTES) return null
        return withView { snapshot, version -> KeyIndexView(version, snapshot.visibility).docIdOf(bytes) }.takeIf { it >= 0 }?.let(::DocId)
    }

    protected fun checkKeyed() {
        check(options.creation.documentKey != null) { "the store was created without a document key" }
    }

    /** The document keys of [version] for hits to name their documents' keys; null in a store without keys. */
    private fun keysOf(version: Version): KeyIndexView? = if (options.creation.documentKey == null) null else KeyIndexView(version, null)

    /** Opens the search [open] starts, which checks and refuses as a search does, and counts its hits up to [atMost]. */
    private inline fun countHits(atMost: Long, open: () -> SearchResult): Long {
        require(atMost >= 0) { "atMost must not be negative: $atMost" }
        return (open() as EngineSearchResult).use { it.countHits(atMost) }
    }

    override fun explain(query: JsonPathQuery): QueryPlan {
        checkUsable()
        val path = pathQuery(query.path)
        val planner = planner()
        val requirement = planner.requirement(path, documentFilter(query)) ?: return QueryPlan(true, null)
        return QueryPlan(false, requirement.describe(), planner.anchor(path)?.parent?.second)
    }

    /** The query's document filter, normalized as the store's strings are. */
    private fun documentFilter(query: JsonPathQuery): LogicalExpr? = query.documentFilter?.expr?.let {
        if (options.creation.normalization == Normalization.NFC) it.normalized() else it
    }

    private fun planner() = PathPlanner(options.indexPolicy, options.creation.hashTerms, options.store.maxDepth, options.creation.normalization)

    /** [type] as this store spells it, checked; null for none. */
    private fun validTypeName(type: String?): String? = type?.let { typeName(it).also(TypeNames::validate) }

    /** The type filter of a search: none, a typeId, or [QueryExecutor.UNKNOWN_TYPE] for a name no object ever had. */
    private fun typeFilter(name: String?, version: Version): Int = name?.let {
        dictionaryOf(version).idOf(it).takeIf { id -> id > 0 } ?: QueryExecutor.UNKNOWN_TYPE
    } ?: QueryExecutor.NO_TYPE_FILTER

    override fun select(node: NodeRef, path: JsonPath): SelectResult {
        checkUsable()
        val query = pathQuery(path)
        return openResult { snapshot, version ->
            val start = NodeCheck(snapshot, version).locate(node)
            val fragments = fragmentsOf(version)
            val doc = StoredDocument(fragments, node.docId.raw)
            val root = doc.node(start)
            val stream = PathEvaluator(doc, root, options.compareCacheBytes).nodes(query, root)
            EngineSelectResult(this, node.docId.raw, snapshot, version, fragments, stream)
        }
    }

    /** The query of [path] as this store evaluates it: with names and strings normalized when the store normalizes. */
    private fun pathQuery(path: JsonPath): PathQuery =
        if (options.creation.normalization == Normalization.NFC) path.query.normalized() else path.query

    /**
     * Opens a view for a result that takes the version over: [open] builds everything that can fail and then the
     * result, which releases the version from then on. Until [open] returns, the version is released here if it throws.
     */
    internal inline fun <R> openResult(open: (Snapshot, Version) -> R): R {
        val (snapshot, version) = openView()
        try {
            return open(snapshot, version)
        } catch (t: Throwable) {
            version.release()
            throw t
        }
    }

    internal inline fun <T> withView(block: (Snapshot, Version) -> T): T {
        val (snapshot, version) = openView()
        try {
            return block(snapshot, version)
        } finally {
            version.release()
        }
    }

    // ---- types ---------------------------------------------------------------------------------------------------

    /** A type name as this store spells it: normalized when it was created with normalization (SPEC.md §2.1). */
    protected fun typeName(name: String): String =
        if (options.creation.normalization == Normalization.NFC) Nfc.normalize(name, "type name") else name

    override fun typeOf(node: NodeRef): String? {
        checkUsable()
        return withView { snapshot, version ->
            NodeCheck(snapshot, version).check(node, requireObject = false)
            nameOf(dictionaryOf(version), TypeIndexView(version, snapshot.visibility, snapshot.lsn).typeIdOf(node.docId.raw, node.nodePre))
        }
    }

    override fun pathOf(node: NodeRef): String {
        checkUsable()
        return withView { snapshot, version ->
            val check = NodeCheck(snapshot, version)
            check.locate(node)
            StoredDocument(check.store, node.docId.raw).nodeWithPath(node.nodePre).normalizedPath()
        }
    }

    override fun objectsOfType(type: String, after: NodeRef?, limit: Int): List<NodeRef> =
        typePage(type, after?.docId?.raw ?: -1, after?.nodePre ?: -1, limit).nodes

    override fun objectsOfTypePage(type: String, after: Cursor?, limit: Int): TypePage {
        if (after != null && !after.forTypes) throw InvalidCursorException("the cursor is from a search, not from a page of objects of a type")
        return typePage(type, after?.docId?.raw ?: -1, after?.nodePre ?: -1, limit)
    }

    /** Up to [limit] objects of [type] strictly after `(afterDocId, afterPre)` (-1 for the start), from one snapshot. */
    private fun typePage(type: String, afterDocId: Long, afterPre: Long, limit: Int): TypePage {
        require(limit > 0) { "limit must be positive, was $limit" }
        checkUsable()
        val name = typeName(type)
        TypeNames.validate(name)
        return withView { snapshot, version ->
            val typeId = dictionaryOf(version).idOf(name)
            if (typeId == 0) return@withView TypePage(emptyList(), null)
            val scan = TypeIndexView(version, snapshot.visibility, snapshot.lsn).scan(typeId, afterDocId, afterPre)
            val nodes = ArrayList<NodeRef>(minOf(limit, 256))
            while (scan.valid && nodes.size < limit) {
                nodes += NodeRef(DocId(scan.docId), scan.pre)
                scan.next()
            }
            val cursor = if (scan.valid) {
                val last = nodes.last()
                Cursor(last.docId, last.nodePre, snapshot.lsn, snapshot.visibility.inFlightFloor, SearchOrder.ASCENDING, forTypes = true)
            } else {
                null
            }
            TypePage(nodes, cursor)
        }
    }

    /** The objects of [type] strictly after [after], read from one snapshot that the cursor holds until it's closed. */
    fun openTypeScan(type: String, after: NodeRef?): TypeScanCursor {
        checkUsable()
        val name = typeName(type)
        TypeNames.validate(name)
        val (snapshot, version) = openView()
        try {
            val typeId = dictionaryOf(version).idOf(name)
            if (typeId == 0) {
                version.release()
                return TypeScanCursor(null, null)
            }
            val scan = TypeIndexView(version, snapshot.visibility, snapshot.lsn).scan(typeId, after?.docId?.raw ?: -1, after?.nodePre ?: -1)
            return TypeScanCursor(scan, version)
        } catch (t: Throwable) {
            version.release()
            throw t
        }
    }

    /** A type scan and the version it reads; empty when both are null. */
    internal class TypeScanCursor(private val scan: TypeIndexView.InvertedTypeScan?, private val version: Version?) : AutoCloseable {
        private var closed = false

        fun next(): NodeRef? {
            check(!closed) { "type scan closed" }
            if (scan == null || !scan.valid) return null
            val node = NodeRef(DocId(scan.docId), scan.pre)
            scan.next()
            return node
        }

        override fun close() {
            if (closed) return
            closed = true
            version?.release()
        }
    }

    override fun countOfType(type: String): Long {
        checkUsable()
        val name = typeName(type)
        TypeNames.validate(name)
        return withView { snapshot, version ->
            val typeId = dictionaryOf(version).idOf(name)
            if (typeId == 0) return@withView 0L
            val scan = TypeIndexView(version, snapshot.visibility, snapshot.lsn).scan(typeId)
            var n = 0L
            while (scan.valid) {
                n++
                scan.next()
            }
            n
        }
    }

    override val creationOptions: CreationOptions get() = options.creation

    override fun info(): StoreInfo {
        checkUsable()
        val visibility = currentSnapshot().visibility
        val deleted = visibility.deleted.size
        val documents = visibility.maxAllocatedDocId - visibility.inFlightCount - visibility.aborted.size - deleted
        return StoreInfo(documents, deleted, secondary)
    }

    override fun listTypes(): List<TypeInfo> {
        checkUsable()
        return withView { snapshot, version ->
            val view = TypeIndexView(version, snapshot.visibility, snapshot.lsn)
            dictionaryOf(version).names().mapIndexed { i, name -> TypeInfo(name, view.approximateCount(i + 1)) }.sortedBy { it.name }
        }
    }

    /** The name of [typeId] in [dictionary]; 0 is no type. */
    fun nameOf(dictionary: TypeDictionary, typeId: Int): String? {
        if (typeId == 0) return null
        return dictionary.nameOf(typeId) ?: throw CorruptionException("type index", "unknown type $typeId")
    }

    // ---- verify ---------------------------------------------------------------------------------------------------

    override fun verify(progress: VerifyProgress): VerifyReport {
        checkUsable()
        return Scrub(this, openScrubView(), progress).run()
    }

    // ---- compare --------------------------------------------------------------------------------------------------

    override fun isEqual(a: CompareOperand, b: CompareOperand, options: CompareOptions): Boolean {
        checkUsable()
        return withView { snapshot, version ->
            openComparison(a, b, options.settings, snapshot, version).use { it.isEqual() }
        }
    }

    override fun diff(a: CompareOperand, b: CompareOperand, options: DiffOptions): List<Difference> =
        openDiff(a, b, options).use { cursor -> generateSequence { cursor.next() }.toList() }

    /** A diff read difference by difference from one snapshot; close it to release the snapshot's files. */
    fun openDiff(a: CompareOperand, b: CompareOperand, options: DiffOptions): DiffCursor {
        checkUsable()
        val (snapshot, version) = openView()
        try {
            return DiffCursor(openComparison(a, b, options.settings, snapshot, version), version)
        } catch (t: Throwable) {
            version.release()
            throw t
        }
    }

    /** The two sides of a comparison, checked and, for values given as JSON, split into temporary fragments. */
    private fun openComparison(a: CompareOperand, b: CompareOperand, settings: CompareSettings, snapshot: Snapshot, version: Version): OpenComparison {
        val resources = ArrayList<AutoCloseable>(2)
        try {
            val nodes = NodeCheck(snapshot, version)
            val types = if (settings.includeTypes) TypeIndexView(version, snapshot.visibility, snapshot.lsn) else null
            // Stored operands are checked before a JSON operand's stream is read.
            if (a is CompareOperand.Stored) nodes.locate(a.node)
            if (b is CompareOperand.Stored) nodes.locate(b.node)
            val left = compareSide(a, nodes, types, resources)
            val right = compareSide(b, nodes, types, resources)
            val dictionary = dictionaryOf(version)
            return OpenComparison(Comparison(left, right, settings) { nameOf(dictionary, it) }, resources)
        } catch (t: Throwable) {
            for (r in resources) runCatching { r.close() }.exceptionOrNull()?.let { t.addSuppressed(it) }
            throw t
        }
    }

    private fun compareSide(operand: CompareOperand, nodes: NodeCheck, types: TypeIndexView?, resources: MutableList<AutoCloseable>): CompareSide {
        val cacheBytes = options.compareCacheBytes / 2
        return when (operand) {
            is CompareOperand.Stored -> {
                val located = nodes.locate(operand.node)
                CompareSide(nodes.store, operand.node.docId.raw, stored = true, cacheBytes, types, compareStats).also {
                    if (located.isSplit) it.setSplitRoot(located.pre) else it.setInlineRoot(located.pre, located.holderPre, located.holderKind, located.valueOffset)
                }
            }
            is CompareOperand.Json -> literalSide(ByteArrayInputStream(utf8Document(operand.json)), cacheBytes, resources)
            is CompareOperand.JsonStream -> literalSide(operand.source, cacheBytes, resources)
        }
    }

    private fun literalSide(input: InputStream, cacheBytes: Long, resources: MutableList<AutoCloseable>): CompareSide {
        val fragments = LiteralFragments(literalIo, literalDir, options.literalMemoryBytes)
        resources += fragments
        val reserved = budget.acquire(options.ingestBaseBytes)
        val parser = literalParsers.poll() ?: LiteralParser(
            options.split, options.parseLimits, options.windowBytes, options.stringChunkBytes, spillFiles,
            options.creation.normalization,
        )
        var reusable = false
        try {
            try {
                parser.parse(input, fragments)
            } catch (e: DocumentRejectedException) {
                reusable = true
                throw e
            }
            reusable = true
            return CompareSide(fragments, LiteralParser.LITERAL_DOC_ID, stored = false, cacheBytes, types = null, compareStats).also {
                it.setDocumentRoot(parser.rootHashLow, parser.rootHashHigh)
            }
        } finally {
            budget.release(reserved)
            if (reusable && parser.retainedBytes <= MAX_POOLED_PARSER_BYTES && literalParsers.size < MAX_POOLED_PARSERS) literalParsers.offer(parser)
        }
    }

    /** UTF-8 of a document given as a string; unpaired surrogates reject it as [RejectReason.INVALID_UTF8]. */
    protected fun utf8Document(json: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val bytes = try {
            encoder.encode(CharBuffer.wrap(json))
        } catch (_: CharacterCodingException) {
            throw DocumentRejectedException(RejectReason.INVALID_UTF8, -1, detail = "the string has an unpaired surrogate")
        }
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }

    /** A comparison and the temporary fragments of its JSON operands. */
    internal class OpenComparison(private val comparison: Comparison, private val resources: List<AutoCloseable>) : AutoCloseable {
        fun isEqual(): Boolean = comparison.isEqual()

        fun next(): Difference? = comparison.next()

        /** Closes the comparison and every resource; throws the first failure with the later ones suppressed. */
        override fun close() {
            var failure = runCatching { comparison.close() }.exceptionOrNull()
            for (r in resources) {
                runCatching { r.close() }.exceptionOrNull()?.let { t -> failure?.addSuppressed(t) ?: run { failure = t } }
            }
            failure?.let { throw it }
        }
    }

    /** A diff pinning its snapshot's version until closed. */
    internal class DiffCursor(private val comparison: OpenComparison, private val version: Version) : AutoCloseable {
        private var closed = false

        fun next(): Difference? {
            check(!closed) { "diff closed" }
            return comparison.next()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                comparison.close()
            } finally {
                version.release()
            }
        }
    }

    /** Checks the nodes callers name: the document is visible and stored, the node exists. */
    protected inner class NodeCheck(private val snapshot: Snapshot, version: Version) {
        val store = fragmentsOf(version)
        private val locator = NodeLocator(store)
        private val view = FragmentView()

        /** Whether [docId] is visible and has data: a docId of an insert rejected before writing anything has none. */
        fun isStored(docId: Long): Boolean =
            docId >= 0 && snapshot.visibility.isVisible(docId) && (store.find(docId, 0, FragmentKind.SUBTREE, 0, view) || store.find(docId, 0, FragmentKind.HEADER, 0, view))

        fun check(node: NodeRef, requireObject: Boolean) {
            val located = locate(node)
            require(!requireObject || located.containerKind == PayloadTag.OBJ) { "node ${node.nodePre} of document ${node.docId.raw} isn't an object" }
        }

        /** Where [node] is stored; throws like [check]. */
        fun locate(node: NodeRef): LocatedNode {
            val docId = node.docId.raw
            if (!isStored(docId)) throw DocumentNotFoundException(node.docId)
            val root = locator.locate(docId, 0)
            require(node.nodePre <= root.size) { "document $docId has no node ${node.nodePre}" }
            return if (node.nodePre == 0L) root else locator.locate(docId, node.nodePre)
        }
    }

    fun <T> withHitReader(store: FragmentStore, block: (HitReader) -> T): T {
        val reader = hitReaders.poll() ?: HitReader()
        reader.store = store
        var reusable = false
        try {
            return block(reader).also { reusable = true }
        } finally {
            if (reusable) hitReaders.offer(reader)
        }
    }

    private companion object {
        /** Pooled literal parsers at most, and the most heap one may keep to be pooled. */
        const val MAX_POOLED_PARSERS = 64
        const val MAX_POOLED_PARSER_BYTES = 1L shl 20
    }
}
