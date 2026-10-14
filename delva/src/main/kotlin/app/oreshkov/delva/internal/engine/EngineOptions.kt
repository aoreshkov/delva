package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.internal.query.Candidates
import app.oreshkov.delva.internal.query.QueryExecutor
import app.oreshkov.delva.internal.query.TypeQueryPlan
import app.oreshkov.delva.internal.split.SplitOptions
import app.oreshkov.delva.internal.term.IndexPolicy

/** Monotonic time for the engine; injected so tests control it deterministically. */
internal fun interface Clock {
    fun nanoTime(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.nanoTime() }
    }
}

/**
 * Engine configuration: the public [CreationOptions] and [StoreOptions] plus internal knobs that tests turn.
 *
 * Memory. The heap the engine needs is bounded by these options, never by document sizes:
 * ```
 * memtableBudgetBytes                                   active + frozen memtables; inserts wait for flushes above it
 * + ingestBudgetBytes                                   buffered small inserts and their working memory
 * + largeLanes × (B + 2·T + sorter + key arena)         promoted inserts, one per lane
 * + blockCacheBytes + fragmentCacheBytes
 * + a few KiB per open segment                          O(log n) segments per index with compaction
 * + compaction: read cache + list spill threshold + run bytes + 10 bits per key of the segment it writes
 * + compression: ~300 KiB native zlib state per inserting thread; one dictionary build at a time (sampleBytes × 13)
 * + reclamation: one record buffer (≤ T + T/2) + ~100 B per fragment of one RELOCATE chunk (maxRelocateFragments)
 * + per running comparison: compareCacheBytes + one decoded fragment per open level (≈ 4 × T) + for each value given
 *   as JSON, literalMemoryBytes and ~90 B per fragment of it
 * ```
 * Options left null are derived from [heapBytes] (the JVM's maximum heap by default) so a store fits small heaps: an
 * eighth of the heap each for memtables and ingest, 1/32 for the block cache, 1/64 for the fragment cache, sorter and
 * key arena, one large lane per 512 MiB. The results are clamped to at most the former fixed defaults (the `DEFAULT_*`
 * constants), which a heap of 2 GiB or more gets.
 *
 * - [sorterBytes], [keyArenaBytes]: per-insert term sorter buffer and duplicate-key arena.
 * - [smallInsertThreshold]: fragment, posting and sorter bytes an insert buffers before it's promoted to a large lane.
 * - [ingestBudgetBytes]: bytes all buffered inserts may hold together; an insert that can't get more is
 *   promoted, one that can't get its base reservation waits.
 * - [largeLanes]: concurrent promoted inserts; each has a lane file of its own.
 * - [keySeed]: seed of the duplicate-key hashes; random when null.
 * - [log]: log file sizes and the large-lane sync interval; [writerQueueCapacity] and [maxBatchRequests] bound the
 *   writer's queue and batches.
 * - [startWriterThread]: false leaves the writer to [Writer.runBatch] calls, for deterministic tests. Inserts then never
 *   wait for memtable room, since no other thread could make it.
 * - [memtableBytes]: estimated heap of the active memtables that triggers a freeze and flush.
 * - [memtableBudgetBytes]: active and frozen memtables together; inserts wait for a flush above it (back-pressure). At
 *   least [memtableBytes].
 * - [backgroundFlush]: flush on a thread of its own; false flushes on the writer thread right after the freeze, which
 *   makes simulations deterministic.
 * - [segmentBlockBytes]: target size of segment data blocks; [blockCacheBytes], [fragmentCacheBytes]: cache sizes.
 * - [typeQueryPlan]: how searches with a type filter run; tests force either plan.
 * - [descendingBufferHits]: hits of one document a descending search keeps at a time; tests make it tiny.
 * - [maxRangeDocuments]: the most documents a JSONPath range comparison collects for one search; tests make it tiny.
 * - [maxDeleteRecordDocIds]: docIds per DELETE record; a larger delete call spans several records.
 * - [compaction]: when and how segments are compacted; [reclaim]: when and how lane files are reclaimed.
 * - [compression]: how fragment payloads are compressed and dictionaries built; off unless
 *   [StoreOptions.compression] is on.
 * - [compareCacheBytes]: per comparison, the decoded PART indexes a split object's key-order merge keeps;
 *   [literalMemoryBytes]: fragments of a value given as JSON kept on the heap before they go to `tmp/`.
 */
internal class EngineOptions(
    val creation: CreationOptions = CreationOptions(),
    val store: StoreOptions = StoreOptions(),
    val heapBytes: Long = Runtime.getRuntime().maxMemory(),
    sorterBytes: Int? = null,
    keyArenaBytes: Long? = null,
    smallInsertThreshold: Long? = null,
    ingestBudgetBytes: Long? = null,
    largeLanes: Int? = null,
    val keySeed: Long? = null,
    val windowBytes: Int = Utf8JsonTokenizer.DEFAULT_WINDOW_BYTES,
    val stringChunkBytes: Int = Utf8JsonTokenizer.DEFAULT_STRING_CHUNK_BYTES,
    val log: LogOptions = LogOptions(),
    val writerQueueCapacity: Int = 1024,
    val maxBatchRequests: Int = 512,
    val startWriterThread: Boolean = true,
    val clock: Clock = Clock.SYSTEM,
    memtableBytes: Long? = null,
    memtableBudgetBytes: Long? = null,
    val backgroundFlush: Boolean = true,
    val segmentBlockBytes: Int = DEFAULT_SEGMENT_BLOCK_BYTES,
    blockCacheBytes: Long? = null,
    fragmentCacheBytes: Long? = null,
    val typeQueryPlan: TypeQueryPlan = TypeQueryPlan.AUTO,
    val descendingBufferHits: Int = QueryExecutor.DESCENDING_BUFFER_HITS,
    val maxRangeDocuments: Int = Candidates.MAX_RANGE_DOCUMENTS,
    val maxDeleteRecordDocIds: Int = DEFAULT_MAX_DELETE_RECORD_DOC_IDS,
    val compaction: CompactionOptions = CompactionOptions(),
    val reclaim: ReclaimOptions = ReclaimOptions(),
    val compression: CompressionOptions = CompressionOptions(enabled = store.compression),
    compareCacheBytes: Long? = null,
    literalMemoryBytes: Long? = null,
) {
    val ingestBudgetBytes: Long = ingestBudgetBytes ?: share(8, MIN_BUDGET_BYTES, DEFAULT_INGEST_BUDGET_BYTES)
    val smallInsertThreshold: Long = smallInsertThreshold ?: minOf(DEFAULT_SMALL_INSERT_THRESHOLD, this.ingestBudgetBytes / 4)

    /** Base reservation of every running insert in the ingest budget: its tokenizer window and initial buffers. */
    val ingestBaseBytes: Long = windowBytes.toLong() + stringChunkBytes + INGEST_BUFFER_BYTES
    val largeLanes: Int = largeLanes ?: (heapBytes / (512L shl 20)).coerceIn(1L, DEFAULT_LARGE_LANES.toLong()).toInt()
    val memtableBytes: Long = memtableBytes ?: (share(8, MIN_BUDGET_BYTES, DEFAULT_MEMTABLE_BUDGET_BYTES) / 4).coerceAtMost(DEFAULT_MEMTABLE_BYTES)
    val memtableBudgetBytes: Long = memtableBudgetBytes ?: maxOf(share(8, MIN_BUDGET_BYTES, DEFAULT_MEMTABLE_BUDGET_BYTES), 4 * this.memtableBytes)
    val blockCacheBytes: Long = blockCacheBytes ?: share(32, 4L shl 20, DEFAULT_BLOCK_CACHE_BYTES)
    val fragmentCacheBytes: Long = fragmentCacheBytes ?: share(64, 2L shl 20, DEFAULT_FRAGMENT_CACHE_BYTES)
    val compareCacheBytes: Long = compareCacheBytes ?: share(64, 1L shl 20, DEFAULT_COMPARE_CACHE_BYTES)
    val literalMemoryBytes: Long = literalMemoryBytes ?: share(64, 1L shl 20, DEFAULT_LITERAL_MEMORY_BYTES)

    /** Which postings are written; fixed when the store is created. */
    val indexPolicy: IndexPolicy =
        IndexPolicy(creation.indexedKeys, creation.excludedKeys, creation.minIndexedStringBytes, creation.maxIndexedStringBytes)

    /** The encoder budget B, which has to be at least twice the fragment size. */
    val encoderBudgetBytes: Int = store.encoderBudgetBytesFor(creation.fragmentBytes).also {
        require(it >= 2 * creation.fragmentBytes) {
            "encoderBudgetBytes $it is below 2 × fragmentBytes (${2 * creation.fragmentBytes})"
        }
    }

    val split: SplitOptions = SplitOptions(
        fragmentBytes = creation.fragmentBytes,
        budgetBytes = encoderBudgetBytes,
        hashTerms = creation.hashTerms,
        indexPolicy = indexPolicy,
        sorterBytes = maxOf(sorterBytes ?: share(64, 1L shl 20, SplitOptions.DEFAULT_SORTER_BYTES.toLong()).toInt(), 4 * (maxOf(creation.fragmentBytes / 4, 64) + 128)),
        keyArenaBytes = keyArenaBytes ?: share(64, 1L shl 20, SplitOptions.DEFAULT_KEY_ARENA_BYTES),
    )

    val parseLimits: ParseLimits = split.parseLimits(
        ParseLimits(maxDocumentBytes = store.maxDocumentBytes, maxDepth = store.maxDepth, maxStringBytes = store.maxStringBytes),
    )

    /** Working memory one promoted insert may hold besides what it streams out: B + 2·T, sorter, key arena, buffers. */
    val largeInsertWorkingBytes: Long
        get() = split.budgetBytes + 2L * split.fragmentBytes + 2L * split.sorterBytes + split.keyArenaBytes + windowBytes + 4L * split.maxRecordBytes

    init {
        require(heapBytes > 0)
        require(this.smallInsertThreshold >= 0 && this.ingestBudgetBytes >= 0 && this.largeLanes in 1..MAX_LARGE_LANES)
        require(writerQueueCapacity >= 1 && maxBatchRequests >= 1)
        require(this.memtableBytes >= 0 && this.memtableBudgetBytes >= this.memtableBytes) { "memtable budget ${this.memtableBudgetBytes} is below the flush size ${this.memtableBytes}" }
        require(segmentBlockBytes >= 256 && this.blockCacheBytes >= 0 && this.fragmentCacheBytes >= 0)
        require(descendingBufferHits >= 1)
        require(maxRangeDocuments >= 1)
        require(this.compareCacheBytes >= 0 && this.literalMemoryBytes >= 0)
        // A record of this many docIds stays well below the control record limit.
        require(maxDeleteRecordDocIds in 1..MAX_DELETE_RECORD_DOC_IDS)
    }

    /** `heapBytes / divisor` within `[min, max]`. */
    private fun share(divisor: Int, min: Long, max: Long): Long = (heapBytes / divisor).coerceIn(min, max)

    companion object {
        /** Defaults a heap of 2 GiB or more gets. */
        const val DEFAULT_SMALL_INSERT_THRESHOLD: Long = 4L * 1024 * 1024
        const val DEFAULT_INGEST_BUDGET_BYTES: Long = 256L * 1024 * 1024
        const val DEFAULT_MEMTABLE_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MEMTABLE_BUDGET_BYTES: Long = 256L * 1024 * 1024
        const val DEFAULT_SEGMENT_BLOCK_BYTES: Int = 64 * 1024
        const val DEFAULT_BLOCK_CACHE_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_FRAGMENT_CACHE_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_COMPARE_CACHE_BYTES: Long = 16L * 1024 * 1024
        const val DEFAULT_LITERAL_MEMORY_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_LARGE_LANES: Int = 4
        const val DEFAULT_MAX_DELETE_RECORD_DOC_IDS: Int = 4096
        const val MAX_DELETE_RECORD_DOC_IDS: Int = 65_536

        /** Lane ids are small integers in file names and BEGIN records. */
        const val MAX_LARGE_LANES: Int = 64

        private const val MIN_BUDGET_BYTES: Long = 16L * 1024 * 1024

        /** Initial splitter, sorter and record buffers of an insert. */
        private const val INGEST_BUFFER_BYTES: Long = 384L * 1024
    }
}
