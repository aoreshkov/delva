package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.Normalization
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.FragmentRecords
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.PostingRun
import app.oreshkov.delva.internal.parse.JsonTokenSource
import app.oreshkov.delva.internal.parse.NfcTokenSource
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.split.FragmentSink
import app.oreshkov.delva.internal.split.Splitter
import app.oreshkov.delva.internal.split.TypePaths
import app.oreshkov.delva.internal.split.TypeSink
import app.oreshkov.delva.internal.term.SortedTermRun
import app.oreshkov.delva.internal.term.TermSink
import java.io.InputStream

/**
 * One insert on the caller's thread: tokenizes and splits the input into a [PreparedInsert] — framed fragment
 * records and sorted posting runs. While the insert stays within [EngineOptions.smallInsertThreshold] and the ingest
 * budget, nothing leaves the buffer, so a rejection leaves no trace. Past either limit it's **promoted**: it takes a
 * large lane (waiting for a free one), has the writer log BEGIN, moves the buffer to the lane and continues
 * writing fragments there directly, with its postings in the memtables as an in-flight document. The engine then
 * commits or aborts it.
 *
 * Memory: a buffering insert charges its fragments, its runs and the postings waiting in its sorter to the
 * ingest budget; a promoted insert holds only its working buffers (bounded by the options) and waits for memtable room
 * before it adds a run, so a flush can always catch up.
 *
 * A promoted insert keeps its index watermark: after inserting a sorter run, every posting of the fragments
 * before the last appended one is in a memtable (the splitter hands a fragment over before indexing it, so a run
 * emitted meanwhile may hold only part of that fragment's postings). The watermark only moves at runs, because
 * postings of complete fragments may still wait in the sorter otherwise.
 *
 * Reusable after [release]; not thread-safe. The engine pools instances.
 */
internal class Ingest(private val engine: Engine) : FragmentSink, TermSink, TypeSink {
    private val options = engine.options
    private val nfc = options.creation.normalization == Normalization.NFC
    private val tokenizer = Utf8JsonTokenizer(
        InputStream.nullInputStream(),
        if (nfc) options.parseLimits.forNfc() else options.parseLimits,
        options.windowBytes,
        options.stringChunkBytes,
    )

    /** What the splitter reads: the tokenizer, or its NFC-normalizing view when the store was created with one. */
    private val source: JsonTokenSource =
        if (nfc) NfcTokenSource(tokenizer, options.parseLimits) else tokenizer
    private val splitter = Splitter(options.split, this, this, this, engine.spillFiles)

    val prepared = PreparedInsert()
    private val newRuns = ArrayList<PostingRun>()
    private val runScratch = GrowableBytes(1024)
    private val record = GrowableBytes(16 * 1024)
    private var charged = 0L
    private var docId = 0L
    private var watermark: LaneWatermark? = null

    /** The large lane of a promoted insert. */
    var lane: LargeLane? = null
        private set

    /** Location of the last fragment record appended to the large lane. */
    private var lastLocation = -1L

    /** Whether the current insert was promoted to a large lane. */
    var promoted: Boolean = false
        private set

    /** Heap this instance keeps for its next insert; the engine doesn't pool instances that grew large. */
    val retainedBytes: Long
        get() = splitter.retainedBytes + prepared.retainedBytes + runScratch.capacity + record.capacity + options.windowBytes + options.stringChunkBytes

    /**
     * Splits the document from [input] as [docId], collecting the type assignments [typePaths] match, and fills in the
     * COMMIT; throws on rejection.
     */
    fun split(docId: Long, input: InputStream, keySeed: Long, typePaths: TypePaths = TypePaths.EMPTY) {
        check(lane == null && charged == 0L) { "ingest wasn't released" }
        promoted = false
        watermark = null
        lastLocation = -1
        prepared.clear()
        this.docId = docId
        prepared.commit.docId = docId
        tokenizer.reset(input)
        (source as? NfcTokenSource)?.reset()
        splitter.split(docId, source, typePaths, keySeed)
        val commit = prepared.commit
        commit.key = splitter.documentKey
        commit.flags = (if (promoted) 0 else ControlRecords.COMMIT_IMPLICIT_BEGIN) or (if (commit.key != null) ControlRecords.COMMIT_KEYED else 0)
        commit.fragmentCount = splitter.fragmentCount
        commit.rootKind = splitter.rootKind
        commit.rootHashLow = splitter.rootHashLow
        commit.rootHashHigh = splitter.rootHashHigh
        commit.encodedBytes = splitter.payloadBytes
        commit.typeCount = 0
    }

    /** Gives back the budget and the large lane. */
    fun release() {
        if (charged > 0) {
            engine.budget.release(charged)
            charged = 0
        }
        lane?.let {
            lane = null
            engine.lanes.release(it)
        }
    }

    override fun fragment(docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int) {
        engine.dictionaryTrainer.offer(payload, offset, length)
        val lane = this.lane
        if (lane != null) {
            record.clear()
            val payloadOffset = FragmentRecords.write(record, docId, pre, kind, seq, context, payload, offset, length, engine.codecs)
            val recordLength = LogFormat.RECORD_HEADER_BYTES + LittleEndian.getInt(record.array, 4)
            val location = engine.appendLarge(lane, record.array, 0, record.size, prepared.commit.extents)
            engine.indexes.withActive { it.fragments.add(FragmentEntry(docId, pre, kind, seq, location, recordLength, payloadOffset, length)) }
            lastLocation = location
            return
        }
        prepared.bytes += prepared.addFragment(docId, pre, kind, seq, context, payload, offset, length, engine.codecs)
        charge(atRun = false)
    }

    override fun run(docId: Long, runSeq: Int, run: SortedTermRun) {
        newRuns.clear()
        PostingRun.fromSortedRun(docId, runSeq, run, runScratch, newRuns)
        if (promoted) {
            addRuns(newRuns, atRun = true)
            return
        }
        for (r in newRuns) prepared.bytes += r.approximateBytes
        prepared.runs.addAll(newRuns)
        charge(atRun = true)
    }

    override fun assign(docId: Long, nodePre: Long, typeName: String) {
        // At most one per path the caller gave; the writer logs them before the COMMIT.
        prepared.types.add(nodePre, typeName)
    }

    /**
     * Charges what the buffer and the sorter hold now; promotes when the insert passes the threshold or the budget
     * can't cover it. Charges only grow while buffering (the sorter's bytes move into runs), so they're given back at
     * promotion or release.
     */
    private fun charge(atRun: Boolean) {
        // A run is handed over before the sorter clears its buffer, so at a run the sorter's bytes are the run's.
        val held = prepared.bytes + if (atRun) 0 else splitter.sorterBufferedBytes
        if (held <= charged) return
        if (held > options.smallInsertThreshold || !engine.budget.tryAcquire(held - charged)) {
            promote(atRun)
        } else {
            charged = held
        }
    }

    /** Moves the buffer to a large lane; [atRun] when called right after a sorter run, so the watermark may move. */
    private fun promote(atRun: Boolean) {
        val lane = engine.lanes.acquire()
        this.lane = lane
        promoted = true
        val mark = LaneWatermark(docId)
        watermark = mark
        // The lane is ours: nothing else appends to its active file or rolls it meanwhile.
        engine.await(engine.writer.submit(WriteRequest.Begin(docId, lane.id, mark, lane.log.appendFloor)))
        val p = prepared
        for (i in 0 until p.count) {
            val location = engine.appendLarge(lane, p.records.array, p.recordStart[i], p.paddedLength(i), p.commit.extents)
            engine.indexes.withActive { it.fragments.add(FragmentEntry(docId, p.pre[i], p.kind[i], p.seq[i], location, p.recordLength[i], p.payloadOffset[i], p.rawLength[i])) }
            lastLocation = location
        }
        addRuns(p.runs, atRun)
        p.clearBuffers()
        if (charged > 0) {
            engine.budget.release(charged)
            charged = 0
        }
    }

    /** Adds runs of the promoted insert to the memtables once there's room, then moves the watermark. */
    private fun addRuns(runs: List<PostingRun>, atRun: Boolean) {
        engine.awaitMemtableRoom()
        engine.indexes.withActive { m ->
            for (r in runs) m.postings.add(r)
            if (atRun && lastLocation >= 0) watermark!!.advanceTo(lastLocation)
        }
        engine.maybeRequestFlush()
    }
}
