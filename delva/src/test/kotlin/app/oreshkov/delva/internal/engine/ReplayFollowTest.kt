package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.DocId
import app.oreshkov.delva.Durability
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.log.LogReaders
import app.oreshkov.delva.internal.log.Manifest
import app.oreshkov.delva.internal.log.ManifestFile
import app.oreshkov.delva.internal.log.ManifestStore
import app.oreshkov.delva.internal.lsm.FragmentSegments
import app.oreshkov.delva.internal.lsm.Memtable
import app.oreshkov.delva.internal.lsm.PostingSegments
import app.oreshkov.delva.internal.lsm.TypeSegments
import app.oreshkov.delva.internal.types.TypeDictionary
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Replay] is resumable: following a writer's control log while the writer appends to it, through another process's
 * handles that may see the newest write page-torn, ends in the state one pass over the finished log gives, which is the
 * writer's.
 */
class ReplayFollowTest {
    private val dir = TestEngines.DIR

    private fun options(durability: Durability) = EngineOptions(
        CreationOptions(fragmentBytes = 64), StoreOptions(encoderBudgetBytes = 1024, durability = durability),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 17, smallInsertThreshold = 512,
        // Small log files, so the follower crosses many SEALs; no flush, so the whole state is in the log.
        log = LogOptions(laneFileBytes = 8L shl 10, controlFileBytes = 4L shl 10, preallocateBytes = 4L shl 10, writeBufferBytes = 4096),
        memtableBytes = 64L shl 20, memtableBudgetBytes = 256L shl 20, backgroundFlush = false,
        compression = CompressionOptions.DISABLED,
    )

    /** Log files through another process's read handles, each opened as soon as it is found and kept. */
    private class Readers(private val io: SimulatedFileSystemIo, dir: Path) : LogReaders {
        private val controlDir = dir.resolve(LogFiles.CONTROL_DIR)
        private val dataDir = dir.resolve(LogFiles.DATA_DIR)
        private val handles = HashMap<Long, FileIo>()

        override fun pathOf(fileNo: Long, control: Boolean, laneId: Int): Path =
            if (control) controlDir.resolve(LogFiles.controlName(fileNo)) else dataDir.resolve(LogFiles.laneName(fileNo, laneId))

        override fun reader(fileNo: Long, control: Boolean, laneId: Int): FileIo = handles.getOrPut(fileNo) { io.open(pathOf(fileNo, control, laneId), OpenMode.READ) }

        /** The log files on disk, opened. */
        fun discover(): List<ManifestFile> {
            val found = io.list(controlDir).mapNotNull { LogFiles.parseControl(it.fileName.toString())?.let { no -> ManifestFile(no, true, 0, false) } } +
                io.list(dataDir).mapNotNull { LogFiles.parseLane(it.fileName.toString())?.let { (no, lane) -> ManifestFile(no, false, lane, false) } }
            for (f in found) reader(f.fileNo, f.control, f.laneId)
            return found.sortedBy { it.fileNo }
        }
    }

    /** A replay of the log from [manifest] on, stepped forward through [readers]. */
    private class Follower(private val readers: Readers, private val manifest: Manifest, options: EngineOptions) {
        val memtable = Memtable()
        val dictionary = TypeDictionary()
        val replay = Replay(readers, memtable, manifest, DocIdSet.EMPTY, DocIdSet.EMPTY, dictionary, emptyList(), options, FragmentCodecs(options.compression), RecoveryStats(), Replay.Tail.FOLLOW)
        private var file: ManifestFile? = null
        private var position = LogFormat.HEADER_BYTES.toLong()

        /** Replays what the log holds now; returns how many records that applied. */
        fun step(): Long {
            val before = replay.lsn
            val found = readers.discover()
            replay.listLaneFiles(found)
            val control = found.filter { it.control }
            while (true) {
                val current = file ?: control.firstOrNull() ?: return 0
                file = current
                val end = replay.scanControl(current, last = true, from = position)
                if (end >= 0) {
                    position = end
                    return replay.lsn - before
                }
                // After the SEAL: the writer created the next file before appending anything else.
                file = control.first { it.fileNo > current.fileNo }
                position = LogFormat.HEADER_BYTES.toLong()
            }
        }
    }

    /** The memtable as the segments a flush would write: equal bytes, equal contents. */
    private fun image(memtable: Memtable): List<ByteArray> {
        val fs = SimulatedFileSystemIo()
        val dir = Path.of("image")
        fs.createDirectories(dir)
        return listOf<(FileIo) -> Unit>(
            { PostingSegments.write(memtable.postings, it, 512) },
            { FragmentSegments.write(memtable.fragments, it, 512) },
            { TypeSegments.write(memtable.types, it, 512) },
        ).mapIndexed { i, write ->
            val path = dir.resolve("$i")
            fs.open(path, OpenMode.CREATE_NEW).use(write)
            fs.open(path, OpenMode.READ).use { ByteArray(it.size().toInt()).also { bytes -> it.readFully(0, bytes, 0, bytes.size) } }
        }
    }

    private fun state(follower: Follower): List<Any> = with(follower.replay) {
        listOf(lsn, maxDocId, begun.toList(), committed.sorted(), aborted.toSortedArray().toList(), deleted.toSortedArray().toList(), damaged.toList(), committedEnd.toSortedMap(), follower.dictionary.names(follower.dictionary.size))
    }

    private fun doc(random: Random, i: Int): String {
        val items = (0 until random.nextInt(1, 40)).joinToString(",") { """{"k":$it,"s":"${"x".repeat(random.nextInt(0, 60))}"}""" }
        return """{"all":true,"i":$i,"items":[$items]}"""
    }

    @Test
    fun `following a writer, resuming wherever the log ends for now, replays what one pass does`() {
        for (seed in 0L until 12L) {
            val random = Random(seed)
            val durability = if (seed % 2 == 0L) Durability.SyncEachBatch else Durability.None
            val label = "seed=$seed $durability"
            val options = options(durability)
            val fs = SimulatedFileSystemIo()
            val store = Engine.open(dir, options, fs)
            // The MANIFEST of a store with nothing flushed: everything is in the log from LSN 1.
            val manifest = ManifestStore(fs, dir).peek()!!
            val reader = fs.process()
            reader.tearReads(Random(seed + 100))
            val readers = Readers(reader, dir)
            val follower = Follower(readers, manifest, options)
            val live = ArrayList<DocId>()
            var applied = 0L
            repeat(150) { i ->
                when (random.nextInt(10)) {
                    in 0..4 -> live += store.insert(doc(random, i), if (random.nextBoolean()) mapOf("$" to "Root", "$.items[0]" to "First") else emptyMap())
                    5 -> if (live.isNotEmpty()) store.deleteAll(live.shuffled(random).take(random.nextInt(1, 4)).also { live.removeAll(it.toSet()) })
                    6 -> if (live.isNotEmpty()) {
                        val old = live.removeAt(random.nextInt(live.size))
                        live += store.replace(listOf(old), doc(random, i), mapOf("$" to "Replaced"))
                    }
                    7 -> if (live.isNotEmpty()) store.assignTypes(live.shuffled(random).take(3).map { NodeRef(it, 0) to if (random.nextBoolean()) "Tagged" else null })
                    8 -> runCatching { store.insert("""{"all":true,"items":[${(0 until 60).joinToString(",") { """{"n":$it}""" }}],"all":false}""") }
                        .onSuccess { error("a duplicate key was accepted") }.onFailure { assertTrue(it is DelvaException, "$label: $it") }
                    else -> live += store.insert(doc(random, i).replace("\"all\":true", "\"all\":true,\"big\":\"${"y".repeat(2000)}\""))
                }
                if (random.nextInt(3) == 0) applied += follower.step()
            }
            val expected = store.documents().keys
            store.close()
            reader.tearReads(null)
            applied += follower.step()
            assertTrue(applied > 0 && follower.replay.lsn == applied, "$label: ${follower.replay.lsn} records, $applied applied in steps")

            // One pass over the finished log, as a reader opening now would replay it.
            val once = Follower(readers, manifest, options)
            once.step()
            assertEquals(state(once), state(follower), label)
            val a = image(once.memtable)
            val b = image(follower.memtable)
            for (i in a.indices) assertContentEquals(a[i], b[i], "$label: index $i")
            assertEquals(expected, once.replay.committed - once.replay.deleted.toSortedArray().toSet(), label)
            assertEquals(emptyList(), once.replay.begun.toList(), label)
            reader.exit()
        }
    }
}
