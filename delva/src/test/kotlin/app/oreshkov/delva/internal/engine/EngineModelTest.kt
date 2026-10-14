package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.query.TypeQueryPlan
import app.oreshkov.delva.testkit.json.JsonLimits
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.model.ModelGen
import app.oreshkov.delva.testkit.model.ModelRunner
import app.oreshkov.delva.testkit.oracle.OracleOptions
import app.oreshkov.delva.testkit.oracle.OracleStore
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test

/**
 * Model-based test with metamorphic fragment sizes: random sequences of inserts (valid and damaged), searches in
 * both modes with both nested-hit filters, limits, byte budgets, inserts between pages, and cursors resumed in new searches, all
 * compared exactly with the oracle at T = 32 B, 4 KiB and 64 KiB. Type operations are interleaved: inserts with
 * type paths, assignments, reassignments and removals, `typeOf`, `objectsOfType`, `countOfType`, and searches filtered by
 * type, with the plan chosen by size or forced term-driven or type-driven per sequence. Delete calls are
 * interleaved too, including deletes between the pages of an open search and deletes of docIds that were never stored,
 * replaces, alone and in `insertAll` calls where sources replace what earlier ones replace or insert, which conflict,
 * and comparisons: `isEqual` and `diff` of stored nodes, respelled or edited JSON text and documents inserted as
 * edited copies, with and without types, ignored keys and paths, and limits.
 *
 * The `with flushes` variants add reopens and tiny memtable limits (a flush after every batch, every few
 * inserts, or rarely), small segment blocks, flushes inline or on the flush thread, and Linux or Windows file
 * semantics, so searches read memtables and segments together and reopens replay only the log tail. Most of them compact
 * aggressively: merges of two segments, rewrites for any delete or tombstone, runs of 64 bytes and spilled
 * posting lists, inline after each flush or on the compaction thread. They also reclaim lane files of 8 KiB:
 * on the reclamation thread, or before each reopen with inline flushes, while searches hold pages open.
 *
 * The `with keys` variants run on a store with document keys: every document carries a key from a small set, so
 * inserts conflict, upserts replace, and lookups, key-order pages and the key of every hit are compared too.
 */
class EngineModelTest {
    private fun run(fragmentBytes: Int, hashTerms: Boolean, iterations: Int, maxSize: Int, profiles: List<JsonProfile> = JsonProfile.entries, flushes: Boolean = false, keys: Boolean = false) {
        val creation = CreationOptions(fragmentBytes = fragmentBytes, hashTerms = hashTerms).withDocumentKey(if (keys) ModelGen.KEY_PATH else null)
        val store = StoreOptions(encoderBudgetBytes = maxOf(2 * fragmentBytes, 256), maxDepth = 512)
        val atom = maxOf(fragmentBytes / 4, 64)
        val oracleOptions = OracleOptions(hashTerms = hashTerms, limits = JsonLimits(maxDepth = 512, maxKeyBytes = atom, maxNumberLength = minOf(4096, atom)), documentKey = if (keys) ModelGen.KEY_PATH else null)
        var inserts = 0L
        var rejections = 0L
        var hits = 0L
        var resumes = 0L
        var searches = 0L
        var empty = 0L
        var reopens = 0L
        var flushCount = 0L
        var segmentsAtEnd = 0L
        var compactions = 0L
        var reclaimed = 0L
        var typeOps = 0L
        var typedHits = 0L
        var deletes = 0L
        var compares = 0L
        var differences = 0L
        var replaces = 0L
        var conflicts = 0L
        var keyReads = 0L
        val label = "model T=$fragmentBytes hashTerms=$hashTerms" + (if (keys) " with keys" else "") + if (flushes) " with flushes" else ""
        forAll(label, ModelGen(fragmentBytes, profiles, reopen = flushes, keys = keys).ops(), iterations = iterations, maxSize = maxSize, shrinker = ModelGen.shrinker) { ops ->
            val seed = ops.hashCode().toLong()
            // Small sorter buffers give several runs per document; a zero threshold promotes every insert.
            val options = EngineOptions(
                creation, store, sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = if (seed and 1L == 0L) 0 else 1L shl 22, keySeed = seed, log = if (flushes) RECLAIM_LOG else TestEngines.LOG,
                memtableBytes = if (flushes) listOf(0L, 8L shl 10, 256L shl 10)[Math.floorMod(seed shr 1, 3)] else EngineOptions.DEFAULT_MEMTABLE_BYTES,
                backgroundFlush = !flushes || (seed shr 3) and 1L == 0L,
                segmentBlockBytes = if (flushes) listOf(256, 4096)[((seed shr 4) and 1L).toInt()] else EngineOptions.DEFAULT_SEGMENT_BLOCK_BYTES,
                typeQueryPlan = TypeQueryPlan.entries[Math.floorMod(seed shr 6, 3)],
                // Aggressive compaction: merges of two segments, rewrites for any delete or tombstone, tiny runs and spills.
                compaction = if (flushes && (seed shr 7) and 3L != 0L) {
                    CompactionOptions(minMergeSegments = 2, deletedRatio = 0.01, tombstoneRatio = 0.01, bytesPerSecond = 0, maxRunBytes = 64, listMemoryBytes = 1024)
                } else {
                    CompactionOptions(bytesPerSecond = 0)
                },
                reclaim = ReclaimOptions(maxLiveRatio = if (flushes) 0.9 else 0.5, bytesPerSecond = 0, maxRelocateFragments = 3),
                compression = TestEngines.compression(Math.floorMod(seed shr 9, 3)),
            )
            val fs = SimulatedFileSystemIo(isWindows = (seed shr 5) and 1L == 1L)
            val runner = ModelRunner(TestEngines.open(options, fs), OracleStore(oracleOptions)) { old ->
                // Inline flushes: nothing reclaims by itself.
                old as Engine
                if (flushes && !options.backgroundFlush) old.reclaim()
                old.close()
                flushCount += old.indexes.flushes
                reclaimed += old.reclaimer.retiredFiles
                compactions += old.indexes.compactor.compactions
                fs.restart()
                TestEngines.open(options, fs)
            }
            try {
                runner.run(ops)
            } finally {
                val last = runner.current as Engine
                last.close()
                flushCount += last.indexes.flushes
                compactions += last.indexes.compactor.compactions
                reclaimed += last.reclaimer.retiredFiles
                segmentsAtEnd += last.indexes.versions.current().postingSegments.size
            }
            reopens += runner.reopens
            inserts += runner.inserts
            rejections += runner.rejections
            hits += runner.hitsCompared
            resumes += runner.resumes
            searches += runner.searches
            empty += runner.emptySearches
            typeOps += runner.typeOperations
            typedHits += runner.typedHits
            deletes += runner.deletes
            compares += runner.compares
            differences += runner.differencesCompared
            replaces += runner.replaces
            conflicts += runner.conflicts
            keyReads += runner.keyReads
        }
        println("$label: $inserts inserts, $rejections rejections, $hits hits compared, $resumes resumed cursors, $searches searches ($empty without hits), $reopens reopens, $flushCount flushes, $compactions compactions, $reclaimed lane files reclaimed, $segmentsAtEnd posting segments at the end, $typeOps type operations, $typedHits typed hits, $deletes delete calls, $replaces replace calls ($conflicts conflicts), $compares comparisons ($differences differences), $keyReads key reads")
    }

    @Test
    fun `tiny fragments`() = run(32, hashTerms = true, iterations = ITERATIONS, maxSize = 150)

    @Test
    fun `small fragments`() = run(4096, hashTerms = true, iterations = ITERATIONS, maxSize = 400)

    @Test
    fun `default fragments`() = run(64 * 1024, hashTerms = true, iterations = ITERATIONS, maxSize = 400)

    @Test
    fun `tiny fragments with flushes`() = run(32, hashTerms = true, iterations = ITERATIONS, maxSize = 150, flushes = true)

    @Test
    fun `small fragments with flushes`() = run(4096, hashTerms = true, iterations = ITERATIONS, maxSize = 400, flushes = true)

    @Test
    fun `default fragments with flushes`() = run(64 * 1024, hashTerms = true, iterations = ITERATIONS, maxSize = 400, flushes = true)

    @Test
    fun `without hash terms`() = run(64, hashTerms = false, iterations = ITERATIONS / 4, maxSize = 150)

    @Test
    fun `small fragments with keys`() = run(4096, hashTerms = true, iterations = ITERATIONS / 2, maxSize = 300, keys = true)

    @Test
    fun `tiny fragments with keys and flushes`() = run(32, hashTerms = true, iterations = ITERATIONS / 2, maxSize = 150, flushes = true, keys = true)

    private companion object {
        /** Sequences per fragment size; `-Pdelva.testProfile=long` runs ten times as many. */
        val ITERATIONS = System.getProperty("delva.model.iterations")?.toInt() ?: 300

        /** Small lane files, so the flush variants reclaim some. */
        val RECLAIM_LOG = LogOptions(laneFileBytes = 8L shl 10, controlFileBytes = 16L shl 10, preallocateBytes = 4L shl 10, writeBufferBytes = 4096)
    }
}
