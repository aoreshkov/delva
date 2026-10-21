package app.oreshkov.delva

import kotlinx.coroutines.flow.Flow

/**
 * The read operations of a store, shared by the [JsonStore] that writes it and the [SecondaryJsonStore]s that read it
 * beside its writer: code that only reads takes a `ReadableJsonStore` and serves both.
 *
 * The suspending operations run on a dispatcher with [StoreOptions.ioParallelism] threads; [blocking] offers the same
 * operations without coroutines. Thread-safe. After [close], operations throw [StoreClosedException].
 *
 * [search] and [select] aren't suspending: their results are resources read on the caller's thread, and starting one
 * already reads — [select] locates its node, and a JSONPath search with a range reads the range's postings. Call them,
 * and read their results, on a dispatcher that allows blocking work, such as `Dispatchers.IO`.
 *
 * Every operation reads one consistent state of the store (SPEC.md §8), and a result keeps reading the state it started
 * on until it is closed, whatever is written meanwhile.
 */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface ReadableJsonStore : AutoCloseable {
    /**
     * Starts a search on the current state, reading on the caller's thread; close the result when done. A search the
     * index controls of [CreationOptions] leave unanswerable throws [QueryRejectedException] with
     * [QueryRejectedException.Reason.NOT_INDEXED] (SPEC.md §4.1).
     */
    public fun search(query: KeyValueQuery): SearchResult

    /**
     * The nodes [path] selects from the value of [node], as RFC 9535 defines its nodelist: in the RFC's order, with
     * duplicates, each with its Normalized Path, where `$` is [node] (SPEC.md §10.3). The result reads the current state
     * lazily; close it when done.
     *
     * Throws [DocumentNotFoundException] when the document doesn't exist or isn't visible, and [IllegalArgumentException]
     * when the node doesn't exist.
     */
    public fun select(node: NodeRef, path: JsonPath): SelectResult

    /**
     * Starts a JSONPath search on the current state (SPEC.md §10.4): the distinct nodes the query selects from every
     * document, in the query's order of `(docId, nodePre)`. Close the result when done.
     *
     * The index narrows the documents down by the terms the query requires ([explain]); a query that requires none
     * throws [QueryRejectedException] unless [JsonPathQuery.scan] allows reading every document, as does a range that
     * matches too many documents, and a query of more than 31 segments.
     */
    public fun search(query: JsonPathQuery): SearchResult

    /** How [search] would find the candidate documents of [query], without running it. */
    public fun explain(query: JsonPathQuery): QueryPlan

    /**
     * How many hits [search] returns for [query] on the current state, counting no further than [atMost] (SPEC.md §4.7):
     * the same candidates and matches, with the query's `after`, type, mode and nested-hit filter, but no hit's JSON is
     * read, and `limit` and `maxBytes` don't apply. `count(query, n) == n` tells whether there are at least `n` hits,
     * and stops at the `n`-th. Refuses what [search] refuses; a negative [atMost] throws [IllegalArgumentException].
     */
    public suspend fun count(query: KeyValueQuery, atMost: Long = Long.MAX_VALUE): Long

    /** How many hits the JSONPath [search] returns for [query], counting no further than [atMost]; see the other [count]. */
    public suspend fun count(query: JsonPathQuery, atMost: Long = Long.MAX_VALUE): Long

    /**
     * Starts a search of documents in key order (SPEC.md §3.3) on the current state; close the result when done. A store
     * without document keys throws [IllegalStateException].
     */
    @ExperimentalDelvaApi
    public fun search(query: DocumentKeyQuery): SearchResult

    /** How many documents the key-order [search] returns for [query], counting no further than [atMost]; see the other [count]. */
    @ExperimentalDelvaApi
    public suspend fun count(query: DocumentKeyQuery, atMost: Long = Long.MAX_VALUE): Long

    /**
     * The visible document whose key is [key] in a store with a [CreationOptions.documentKey], or null when none is
     * (SPEC.md §3.3); with [Normalization.NFC], [key] is normalized first. A store without document keys throws
     * [IllegalStateException].
     */
    @ExperimentalDelvaApi
    public suspend fun get(key: String): DocId?

    /** The type of [node], or null, which nodes that aren't objects always get. Errors as in [JsonStore.assignType]. */
    public suspend fun typeOf(node: NodeRef): String?

    /**
     * Where [node] is in its document, as an RFC 9535 Normalized Path from the document root (SPEC.md §3.2):
     * `$['orders'][3]`, and `$` for the root. Reads one fragment per level from the root down to the node.
     *
     * Throws [DocumentNotFoundException] when the document doesn't exist or isn't visible, and [IllegalArgumentException]
     * when the node doesn't exist.
     */
    public suspend fun pathOf(node: NodeRef): String

    /**
     * Up to [limit] objects of [type] in `(docId, nodePre)` order from the current state, strictly after [after] when
     * it's given; resume with the last one returned. Throws [InvalidTypeNameException] for an invalid name.
     */
    public suspend fun objectsOfType(type: String, after: NodeRef? = null, limit: Int = 100): List<NodeRef>

    /**
     * Up to [limit] objects of [type] in `(docId, nodePre)` order from the current state, strictly after [after] when
     * it's given, and where the next page starts: [TypePage.cursor], null when no objects were left. Only the cursor of
     * a [TypePage] resumes it, anything else throws [InvalidCursorException].
     *
     * Each page reads the state of its own call, as [objectsOfType] does: an object that gets the type behind the
     * cursor after a page was read isn't returned by later pages, and [Cursor.mayMissLateCommits] tells whether an
     * insert that was in flight could have been passed. Throws [InvalidTypeNameException] for an invalid name.
     */
    public suspend fun objectsOfTypePage(type: String, after: Cursor? = null, limit: Int = 100): TypePage

    /**
     * Every object of [type] in `(docId, nodePre)` order, strictly after [after] when it's given, as a flow. Each
     * collection reads one new snapshot on the store's dispatcher and holds it until the collection ends, so what one
     * collection emits is consistent: the objects of the type as the operations acknowledged before it started left
     * them. Throws [InvalidTypeNameException] for an invalid name, when collecting.
     */
    public fun objectsOfTypeFlow(type: String, after: NodeRef? = null): Flow<NodeRef>

    /** The exact number of objects of [type]; reads the entry of every one of them. */
    public suspend fun countOfType(type: String): Long

    /** Every type name ever assigned, sorted by name, with an approximate count of its objects. */
    public fun listTypes(): List<TypeInfo>

    /**
     * Whether the values [a] and [b] are equal (SPEC.md §9): typed, numbers by decimal value, strings by code points,
     * object members in any order, array elements in order. Reads one snapshot of the store with memory independent of
     * the values' sizes; values given as JSON are read and split first.
     *
     * Throws [DocumentNotFoundException] when a stored operand's document isn't visible, [IllegalArgumentException] when
     * its node doesn't exist, and [DocumentRejectedException] when JSON text is invalid.
     */
    public suspend fun isEqual(a: CompareOperand, b: CompareOperand, options: CompareOptions = CompareOptions()): Boolean

    /**
     * The differences between [a] (left) and [b] (right), streamed in canonical order (SPEC.md §9.3): depth first,
     * object members by key, array elements by index. Each collection runs the comparison on one new snapshot, on the
     * store's dispatcher, and stops after [DiffOptions.maxDifferences]. Errors as in [isEqual], thrown when collecting.
     */
    public fun diff(a: CompareOperand, b: CompareOperand, options: DiffOptions = DiffOptions()): Flow<Difference>

    /**
     * Reads everything the store can read back and reports what doesn't check out (SPEC.md §8): record and block
     * checksums, the fragments each COMMIT still in the log names, and every fragment-index entry against the record it
     * locates. Nothing is changed or repaired.
     *
     * Meant to run in the background while the store serves requests: it reads one snapshot, so concurrent writes,
     * flushes and compactions neither disturb it nor appear in its report. It reads the whole store, so it takes as
     * long as reading the store's files takes; [progress] follows it, and cancelling the call stops it at the next
     * report.
     *
     * A [VerifyReport] with issues doesn't stop the store working: reads of the damaged data fail with
     * [CorruptionException], everything else keeps being served.
     */
    public suspend fun verify(progress: VerifyProgress = VerifyProgress { _, _, _ -> }): VerifyReport

    /**
     * The same store without coroutines: a view that shares this store's state, valid until this store is closed.
     * The view has no `close` of its own; closing this store is what releases it.
     */
    public fun blocking(): BlockingReadableJsonStore

    /** What this store was created with; fixed for its life (see [CreationOptions]). */
    public val creationOptions: CreationOptions

    /** How many documents the store holds, and has deleted; read from memory, so it costs nothing to ask. */
    public fun info(): StoreInfo
}

/**
 * The operations of [ReadableJsonStore] without coroutines, obtained from [ReadableJsonStore.blocking]. Thread-safe.
 *
 * It is a view of a store and deliberately not [AutoCloseable]: the store owns the files, so it is the store that is
 * closed, typically with `JsonStore.open(dir).use { … }`.
 */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface BlockingReadableJsonStore {
    /** Starts a search on the current state; close the result when done. */
    public fun search(query: KeyValueQuery): SearchResult

    /** See [ReadableJsonStore.select]. */
    public fun select(node: NodeRef, path: JsonPath): SelectResult

    /** See [ReadableJsonStore.search]. */
    public fun search(query: JsonPathQuery): SearchResult

    /** See [ReadableJsonStore.explain]. */
    public fun explain(query: JsonPathQuery): QueryPlan

    /** See [ReadableJsonStore.count]. */
    public fun count(query: KeyValueQuery, atMost: Long = Long.MAX_VALUE): Long

    /** See [ReadableJsonStore.count]. */
    public fun count(query: JsonPathQuery, atMost: Long = Long.MAX_VALUE): Long

    /** See [ReadableJsonStore.search]. */
    @ExperimentalDelvaApi
    public fun search(query: DocumentKeyQuery): SearchResult

    /** See [ReadableJsonStore.count]. */
    @ExperimentalDelvaApi
    public fun count(query: DocumentKeyQuery, atMost: Long = Long.MAX_VALUE): Long

    /** See [ReadableJsonStore.get]. */
    @ExperimentalDelvaApi
    public fun get(key: String): DocId?

    /** See [ReadableJsonStore.typeOf]. */
    public fun typeOf(node: NodeRef): String?

    /** See [ReadableJsonStore.pathOf]. */
    public fun pathOf(node: NodeRef): String

    /** See [ReadableJsonStore.objectsOfType]. */
    public fun objectsOfType(type: String, after: NodeRef? = null, limit: Int = 100): List<NodeRef>

    /** See [ReadableJsonStore.objectsOfTypePage]. */
    public fun objectsOfTypePage(type: String, after: Cursor? = null, limit: Int = 100): TypePage

    /** See [ReadableJsonStore.countOfType]. */
    public fun countOfType(type: String): Long

    /** See [ReadableJsonStore.creationOptions]. */
    public val creationOptions: CreationOptions

    /** See [ReadableJsonStore.info]. */
    public fun info(): StoreInfo

    /** See [ReadableJsonStore.listTypes]. */
    public fun listTypes(): List<TypeInfo>

    /** See [ReadableJsonStore.isEqual]. */
    public fun isEqual(a: CompareOperand, b: CompareOperand, options: CompareOptions = CompareOptions()): Boolean

    /** The differences of [ReadableJsonStore.diff], at most [DiffOptions.maxDifferences] of them. */
    public fun diff(a: CompareOperand, b: CompareOperand, options: DiffOptions = DiffOptions()): List<Difference>

    /** See [ReadableJsonStore.verify]; throwing from [progress] stops the scrub with that exception. */
    public fun verify(progress: VerifyProgress = VerifyProgress { _, _, _ -> }): VerifyReport
}
