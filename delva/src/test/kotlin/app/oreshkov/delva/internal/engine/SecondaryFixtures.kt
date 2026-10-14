package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.Durability
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import kotlin.random.Random

/** Writers, documents and comparisons for the tests of a secondary beside its writer. */
internal object SecondaryFixtures {
    /** A writer that flushes, compacts and reclaims only when asked (or, with small [memtableBytes], flushes often). */
    fun options(
        documentKey: String? = null,
        durability: Durability = Durability.SyncEachBatch,
        memtableBytes: Long? = null,
        log: LogOptions = TestEngines.LOG,
        compression: Int = 0,
        smallInsertThreshold: Long = 1024,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = 256, documentKey = documentKey), StoreOptions(encoderBudgetBytes = 1024, durability = durability),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 7, smallInsertThreshold = smallInsertThreshold,
        log = log, memtableBytes = memtableBytes, backgroundFlush = false,
        compaction = CompactionOptions(enabled = false, minMergeSegments = 2),
        reclaim = ReclaimOptions(enabled = false, maxLiveRatio = 0.9, bytesPerSecond = 0),
        compression = TestEngines.compression(compression),
    )

    /** Small log files, so writers roll them, retire them and cover them with flushes often. */
    val SMALL_LOG = LogOptions(laneFileBytes = 8L shl 10, controlFileBytes = 4L shl 10, preallocateBytes = 1024, writeBufferBytes = 4096)

    /** A secondary of the store in [TestEngines.DIR] in another process of [fs]'s disk, spilling to a disk of its own. */
    fun secondary(fs: SimulatedFileSystemIo, options: EngineOptions): SecondaryEngine =
        SecondaryEngine.open(TestEngines.DIR, fs.process(), SimulatedFileSystemIo()) { creation -> EngineOptions(creation, options.store) }

    /** Document [i]: found by `"all": true`, keyed by `"id"`, with items in nested fragments; [big] ones fill several lane records. */
    fun doc(random: Random, i: Int, big: Boolean = false): String {
        val items = (0 until random.nextInt(1, 12)).joinToString(",") { """{"k":$it,"s":"${"x".repeat(random.nextInt(0, 40))}"}""" }
        val pad = if (big) ""","pad":"${"p".repeat(3000)}"""" else ""
        return """{"all":true,"id":"d$i","i":$i,"items":[$items]$pad}"""
    }

    /** What a reader can see of a store: documents, the objects of each type, and document keys. */
    data class State(val documents: Map<Long, String>, val types: Map<String, List<NodeRef>>, val keys: Map<String, Long>)

    val TYPES = listOf("Root", "Replaced", "Tagged", "Other")
}

internal fun ReadEngine.typed(type: String): List<NodeRef> = objectsOfType(type, null, 100_000)

/** The nodes each value of the items' `k` and `s` finds: the postings of nested fragments, not only of the root. */
internal fun ReadEngine.itemHits(): Map<KeyValueQuery, List<NodeRef>> {
    val queries = (0 until 12).map { KeyValueQuery("k", JsonQueryValue.Num(it.toBigDecimal()), limit = 100_000) } +
        (0 until 40).map { KeyValueQuery("s", JsonQueryValue.Str("x".repeat(it)), limit = 100_000) }
    return queries.associateWith { query -> search(query).use { r -> r.iterator().asSequence().map { it.node }.toList() } }
}

internal fun ReadEngine.state(): SecondaryFixtures.State {
    val types = SecondaryFixtures.TYPES.associateWith { typed(it) }
    val keys = if (creationOptions.documentKey == null) {
        emptyMap()
    } else {
        search(DocumentKeyQuery("", limit = 100_000)).use { r -> r.iterator().asSequence().associate { it.documentKey!! to it.node.docId.raw } }
    }
    return SecondaryFixtures.State(documents(), types, keys)
}

internal fun ReadEngine.keyOf(docId: DocId): String =
    search(DocumentKeyQuery("", limit = 100_000)).use { r -> r.iterator().asSequence().first { it.node.docId == docId }.documentKey!! }
