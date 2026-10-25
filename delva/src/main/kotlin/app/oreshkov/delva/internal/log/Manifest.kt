package app.oreshkov.delva.internal.log

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.UnsupportedFormatException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.discard
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.io.replace
import app.oreshkov.delva.internal.path.SingularPath
import app.oreshkov.delva.internal.term.IndexPolicy
import java.nio.file.Path
import java.util.Locale

/**
 * A log file as the MANIFEST lists it. For lane files, [committedEnd] is where committed bytes end as of `indexedUpToLsn`,
 * and [discardedBytes] how many of its record bytes belong to fragment-index entries that flushes and compactions have
 * dropped: an estimate of its dead bytes for space reclamation.
 */
internal class ManifestFile(val fileNo: Long, val control: Boolean, val laneId: Int, val sealed: Boolean, val committedEnd: Long = 0, val discardedBytes: Long = 0)

/** A live segment: its file, which index it belongs to ([app.oreshkov.delva.internal.lsm.SegmentFormat]) and its size. */
internal class ManifestSegment(val fileNo: Long, val index: Int, val fileBytes: Long)

/**
 * An index watermark: the postings of in-flight document [docId] are all in segments up to lane position
 * `(fileNo, offset)`; `fileNo` 0 when none are.
 */
internal class ManifestWatermark(val docId: Long, val fileNo: Long, val offset: Long)

/**
 * The store's root metadata, rewritten whole on every change. Little-endian:
 * ```
 * manifest := magic:4B "DVMF" | formatVersion:u16 | flags:u16 | manifestNo:u64
 *           | fragmentBytes:u32 | encoderBudgetBytes:u32 | hashTerms:u8 | normalization:u8 (0 none, 1 NFC) | reserved:2B
 *           | nextFileNo:u64 | nextDocId:u64 | lastLsn:u64 | indexedUpToLsn:u64 | abortedFileNo:u64 (0 = none)
 *           | typeDictionaryFileNo:u64 (0 = none) | deletedFileNo:u64 (0 = none)
 *           | fileCount:varint | { fileNo:u64 | kind:u8 (1 control, 2 lane) | laneId:u32 | state:u8 (0 active, 1 sealed)
 *                                | committedEnd:u64 | discardedBytes:u64 (both 0 for control files) }*
 *           | segmentCount:varint | { fileNo:u64 | index:u8 (1 posting, 2 fragment, 3 type, 4 key) | fileBytes:u64 }*
 *           | watermarkCount:varint | { docId:u64 | fileNo:u64 | offset:u64 }*
 *           | dictionaryCount:varint | { dictId:u16 }*                     ascending
 *           | minIndexedStringBytes:u32 | maxIndexedStringBytes:u32                index controls
 *           | indexedKeyCount:varint | { length:varint utf8 }*                    empty: every key
 *           | excludedKeyCount:varint | { length:varint utf8 }*
 *           | documentKeyLength:varint | utf8                                    the documentKey path; 0: none
 *           | crc32c:u32                                                 over everything before it
 * ```
 * - `fragmentBytes`, `hashTerms`, `normalization` and the index controls shape stored data and are checked when the store opens;
 *   the encoder budget is recorded for diagnostics.
 * - `nextDocId` and `lastLsn` are lower bounds: recovery takes the larger of them and what the control log holds.
 * - Every control record up to `indexedUpToLsn` has all its effects in the listed segments, the bitmap files (aborted
 *   and deleted docIds) and the type dictionary file (the names of TYPEDEFs); recovery
 *   replays only the records after it.
 * - `committedEnd` of a lane file and the watermarks are as of `indexedUpToLsn`; a watermark exists for every
 *   document begun but not ended by then.
 * - `dictId`s are the compression dictionaries whose files were durable when this MANIFEST was
 *   written; a FRAGMENT record refers to one only after a MANIFEST lists it.
 * - A log file is listed from its creation until it's reclaimed. A log file on disk that isn't
 *   listed has a file number below `nextFileNo` only when it was reclaimed, and it's then deleted when the store opens.
 */
internal class Manifest(
    val manifestNo: Long,
    val fragmentBytes: Int,
    val encoderBudgetBytes: Int,
    val hashTerms: Boolean,
    val nextFileNo: Long,
    val nextDocId: Long,
    val lastLsn: Long,
    val files: List<ManifestFile>,
    val indexedUpToLsn: Long = 0,
    val abortedFileNo: Long = 0,
    val typeDictionaryFileNo: Long = 0,
    val deletedFileNo: Long = 0,
    val segments: List<ManifestSegment> = emptyList(),
    val watermarks: List<ManifestWatermark> = emptyList(),
    val dictionaries: List<Int> = emptyList(),
    val indexPolicy: IndexPolicy = IndexPolicy.EVERYTHING,
    val normalization: Normalization = Normalization.NONE,
    /** [CreationOptions.documentKey]; validated when decoded. */
    val documentKey: String? = null,
) {
    /** What the store was created with. The encoder budget isn't part of it: each open writes its own. */
    fun creationOptions(): CreationOptions = CreationOptions(
        fragmentBytes, hashTerms, indexPolicy.includedKeys, indexPolicy.excludedKeys, indexPolicy.minStringBytes,
        indexPolicy.maxStringBytes, normalization, documentKey,
    )

    fun encode(): ByteArray {
        val out = GrowableBytes(256)
        out.writeIntLE(MAGIC)
        out.writeShortLE(FORMAT_VERSION)
        out.writeShortLE(0)
        out.writeLongLE(manifestNo)
        out.writeIntLE(fragmentBytes)
        out.writeIntLE(encoderBudgetBytes)
        out.writeByte(if (hashTerms) 1 else 0)
        out.writeByte(normalization.ordinal)
        out.writeShortLE(0)
        out.writeLongLE(nextFileNo)
        out.writeLongLE(nextDocId)
        out.writeLongLE(lastLsn)
        out.writeLongLE(indexedUpToLsn)
        out.writeLongLE(abortedFileNo)
        out.writeLongLE(typeDictionaryFileNo)
        out.writeLongLE(deletedFileNo)
        out.writeVarLong(files.size.toLong())
        for (file in files) {
            out.writeLongLE(file.fileNo)
            out.writeByte(if (file.control) KIND_CONTROL else KIND_LANE)
            out.writeIntLE(file.laneId)
            out.writeByte(if (file.sealed) 1 else 0)
            out.writeLongLE(file.committedEnd)
            out.writeLongLE(file.discardedBytes)
        }
        out.writeVarLong(segments.size.toLong())
        for (s in segments) {
            out.writeLongLE(s.fileNo)
            out.writeByte(s.index)
            out.writeLongLE(s.fileBytes)
        }
        out.writeVarLong(watermarks.size.toLong())
        for (w in watermarks) {
            out.writeLongLE(w.docId)
            out.writeLongLE(w.fileNo)
            out.writeLongLE(w.offset)
        }
        out.writeVarLong(dictionaries.size.toLong())
        for (id in dictionaries) out.writeShortLE(id)
        out.writeIntLE(indexPolicy.minStringBytes)
        out.writeIntLE(indexPolicy.maxStringBytes)
        for (keys in listOf(indexPolicy.includedKeys, indexPolicy.excludedKeys)) {
            out.writeVarLong(keys.size.toLong())
            for (key in keys.sorted()) {
                val bytes = key.toByteArray(Charsets.UTF_8)
                out.writeVarLong(bytes.size.toLong())
                out.writeBytes(bytes)
            }
        }
        val key = documentKey?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        out.writeVarLong(key.size.toLong())
        out.writeBytes(key)
        out.writeIntLE(Crc32c.of(out.array, 0, out.size))
        return out.toByteArray()
    }

    companion object {
        /**
         * The store's format version: the version of every file format of the store, not only the MANIFEST's. Any
         * change to how a file of the store is read changes it too, so that the MANIFEST, which every way of opening a
         * store reads first, refuses a store of another format before anything else of it is read or changed.
         *
         * Every format version keeps what finds and checks a MANIFEST: the names `CURRENT` and `MANIFEST-n`, the magic
         * and this field in its first six bytes, and the CRC32C over everything before it in its last four. So a
         * version that can't read a store tells it from a damaged one: a MANIFEST whose checksum holds and whose
         * version isn't one of [SUPPORTED_VERSIONS] is another format ([UnsupportedFormatException]), never damage, and
         * is never passed over for an older MANIFEST.
         *
         * Delva 0.1 and 0.2 don't: they take a MANIFEST of another format for a damaged one and fall back to the one
         * before it, and a writer then deletes every file that one doesn't list. So an upgrade, a version rewriting a
         * store in a new format, must never leave a MANIFEST of format 1 beside one of the new format, not even across a
         * crash: it deletes them once its first new MANIFEST is durable and before `CURRENT` names it. A crash between
         * the two leaves `CURRENT` naming a missing MANIFEST, which 0.1 and 0.2 refuse, as every MANIFEST left is one
         * they don't read, and which the upgrading version's fallback passes over to its new one.
         */
        const val FORMAT_VERSION: Int = 1

        /** The format versions this version reads, ascending. */
        val SUPPORTED_VERSIONS: List<Int> = listOf(FORMAT_VERSION)

        private const val MAGIC = 0x464D5644 // "DVMF" read little-endian
        private const val KIND_CONTROL = 1
        private const val KIND_LANE = 2
        private const val FILE_BYTES = 30
        private const val SEGMENT_BYTES = 17
        private const val WATERMARK_BYTES = 24
        private val NORMALIZATIONS = Normalization.entries

        /**
         * Decodes the MANIFEST file [path] holds as [bytes]. Throws [UnsupportedFormatException] for an intact MANIFEST
         * of another format version, and [CorruptionException] for anything else that isn't a valid MANIFEST.
         */
        fun decode(bytes: ByteArray, path: Path): Manifest {
            val source = path.toString()
            if (bytes.size < 8) throw CorruptionException(source, "${bytes.size} bytes")
            val body = bytes.size - 4
            if (Crc32c.of(bytes, 0, body) != LittleEndian.getInt(bytes, body)) throw CorruptionException(source, "checksum mismatch")
            val r = ByteReader(bytes, 0, body, source)
            if (r.readIntLE() != MAGIC) throw CorruptionException(source, "not a MANIFEST")
            val version = r.readShortLE()
            if (version !in SUPPORTED_VERSIONS) throw UnsupportedFormatException(path.parent ?: path, version, SUPPORTED_VERSIONS)
            r.readShortLE()
            val manifestNo = r.readLongLE()
            val fragmentBytes = r.readIntLE()
            val budget = r.readIntLE()
            val hashTerms = r.readByte() != 0
            val normalizationCode = r.readByte()
            if (normalizationCode !in NORMALIZATIONS.indices) throw CorruptionException(source, "unknown normalization $normalizationCode")
            val normalization = NORMALIZATIONS[normalizationCode]
            r.skip(2)
            val nextFileNo = r.readLongLE()
            val nextDocId = r.readLongLE()
            val lastLsn = r.readLongLE()
            val indexedUpToLsn = r.readLongLE()
            val abortedFileNo = r.readLongLE()
            val typeDictionaryFileNo = r.readLongLE()
            val deletedFileNo = r.readLongLE()
            val count = r.readVarInt(r.remaining / FILE_BYTES)
            val files = ArrayList<ManifestFile>(count)
            repeat(count) {
                val fileNo = r.readLongLE()
                val kind = r.readByte()
                val laneId = r.readIntLE()
                val state = r.readByte()
                val committedEnd = r.readLongLE()
                val discardedBytes = r.readLongLE()
                if (fileNo <= 0 || fileNo >= nextFileNo || (kind != KIND_CONTROL && kind != KIND_LANE) || laneId < 0 || state > 1 || committedEnd < 0 || discardedBytes < 0 || (kind == KIND_CONTROL && (committedEnd != 0L || discardedBytes != 0L))) {
                    throw CorruptionException(source, "invalid file entry ($fileNo, $kind, $laneId, $state, $committedEnd, $discardedBytes)")
                }
                files += ManifestFile(fileNo, kind == KIND_CONTROL, laneId, state == 1, committedEnd, discardedBytes)
            }
            val segmentCount = r.readVarInt(r.remaining / SEGMENT_BYTES)
            val segments = ArrayList<ManifestSegment>(segmentCount)
            repeat(segmentCount) {
                val fileNo = r.readLongLE()
                val index = r.readByte()
                val bytes = r.readLongLE()
                if (fileNo <= 0 || fileNo >= nextFileNo || index !in 1..4 || bytes <= 0) throw CorruptionException(source, "invalid segment entry ($fileNo, $index, $bytes)")
                segments += ManifestSegment(fileNo, index, bytes)
            }
            val watermarkCount = r.readVarInt(r.remaining / WATERMARK_BYTES)
            val watermarks = ArrayList<ManifestWatermark>(watermarkCount)
            repeat(watermarkCount) {
                val docId = r.readLongLE()
                val fileNo = r.readLongLE()
                val offset = r.readLongLE()
                if (docId < 0 || fileNo < 0 || fileNo >= nextFileNo || offset < 0 || (fileNo == 0L && offset != 0L)) throw CorruptionException(source, "invalid watermark ($docId, $fileNo, $offset)")
                watermarks += ManifestWatermark(docId, fileNo, offset)
            }
            val dictionaryCount = r.readVarInt(r.remaining / 2)
            val dictionaries = ArrayList<Int>(dictionaryCount)
            repeat(dictionaryCount) {
                val id = r.readShortLE()
                if (id == 0 || (dictionaries.isNotEmpty() && id <= dictionaries.last())) throw CorruptionException(source, "invalid dictionary id $id")
                dictionaries += id
            }
            val minStringBytes = r.readIntLE()
            val maxStringBytes = r.readIntLE()
            if (minStringBytes < 0 || maxStringBytes < minStringBytes) throw CorruptionException(source, "indexed string lengths $minStringBytes..$maxStringBytes")
            val keySets = List(2) {
                val count = r.readVarInt(r.remaining)
                LinkedHashSet<String>(count).also { set ->
                    repeat(count) {
                        val length = r.readVarInt(r.remaining)
                        val bytes = ByteArray(length)
                        r.readBytes(bytes, 0, length)
                        set += String(bytes, Charsets.UTF_8)
                    }
                }
            }
            val indexPolicy = IndexPolicy(keySets[0], keySets[1], minStringBytes, maxStringBytes)
            val documentKey = r.readVarInt(r.remaining).let { length ->
                if (length == 0) return@let null
                val bytes = ByteArray(length)
                r.readBytes(bytes, 0, length)
                val path = String(bytes, Charsets.UTF_8)
                try {
                    if (SingularPath.parse(path).isEmpty()) throw CorruptionException(source, "document key path $path")
                } catch (e: IllegalArgumentException) {
                    throw CorruptionException(source, "invalid document key path: ${e.message}")
                }
                path
            }
            if (r.remaining != 0) throw CorruptionException(source, "${r.remaining} trailing bytes")
            if (manifestNo <= 0 || fragmentBytes <= 0 || nextDocId < 0 || lastLsn < 0 || indexedUpToLsn < 0 || indexedUpToLsn > lastLsn || abortedFileNo !in 0 until nextFileNo || typeDictionaryFileNo !in 0 until nextFileNo || deletedFileNo !in 0 until nextFileNo) {
                throw CorruptionException(source, "invalid field values")
            }
            return Manifest(manifestNo, fragmentBytes, budget, hashTerms, nextFileNo, nextDocId, lastLsn, files, indexedUpToLsn, abortedFileNo, typeDictionaryFileNo, deletedFileNo, segments, watermarks, dictionaries, indexPolicy, normalization, documentKey)
        }
    }
}

/**
 * Writes and finds MANIFESTs in the store directory: `MANIFEST-n` is written to a temporary file,
 * synced and renamed, then `CURRENT` is replaced the same way and the directory synced. The replace of `CURRENT` is
 * retried for a moment while another process has it open, which on Windows makes it fail ([replace]). MANIFEST
 * numbers are never written twice: an open continues above the highest on disk ([highestOnDisk]). Loading falls back
 * from a damaged or missing current MANIFEST to another one only if every file it lists still exists, and never from one of another format version
 * ([UnsupportedFormatException]), which only another version of Delva can read.
 */
internal class ManifestStore(private val io: FileSystemIo, private val dir: Path) {
    /** The MANIFEST written or loaded last; kept on disk as the fallback. */
    private var previousNo = 0L

    /** The MANIFEST [previousNo] superseded, deleted by the next [write]; 0 when there is none to delete. */
    private var supersededNo = 0L

    private val graveyard: Path = dir.resolve(LogFiles.TMP_DIR)

    /**
     * Writes [manifest] and makes it current, then deletes the MANIFEST before the previous one, by name. It never lists
     * the directory: [sweep] removes the others when the store opens. A delete that fails doesn't fail the write
     * ([discard]).
     */
    fun write(manifest: Manifest) {
        val name = nameOf(manifest.manifestNo)
        writeDurably(dir.resolve("$name.tmp"), dir.resolve(name), manifest.encode())
        writeDurably(dir.resolve("$CURRENT.tmp"), dir.resolve(CURRENT), "$name\n".toByteArray(Charsets.US_ASCII))
        io.syncDirectory(dir)
        if (supersededNo != 0L && supersededNo != manifest.manifestNo) io.discard(dir.resolve(nameOf(supersededNo)), graveyard)
        supersededNo = previousNo
        previousNo = manifest.manifestNo
    }

    /**
     * Deletes every MANIFEST but the current one and its fallback, the one before it, and the leftovers of unfinished
     * writes; for an open, once it wrote a MANIFEST. Nothing fails the store ([discard]).
     */
    fun sweep() {
        for (path in io.list(dir)) {
            val no = manifestNo(path)
            val unfinished = no == null && path.fileName.toString().let { (it.startsWith("MANIFEST-") && it.endsWith(".tmp")) || it == "$CURRENT.tmp" }
            if ((no != null && no != previousNo && no != supersededNo) || unfinished) io.discard(path, graveyard)
        }
    }

    /**
     * The current MANIFEST, or null for a directory without a store (then leftovers of an unfinished creation are
     * removed). [filesExist] tells whether every file a MANIFEST refers to exists, for the fallback rule.
     */
    fun load(filesExist: (Manifest) -> Boolean, hasLogFiles: Boolean): Manifest? {
        val currentPath = dir.resolve(CURRENT)
        if (!io.exists(currentPath)) {
            if (hasLogFiles) throw CorruptionException(currentPath.toString(), "CURRENT is missing but the store has log files")
            for (path in io.list(dir)) if (manifestNo(path) != null || path.fileName.toString().endsWith(".tmp")) io.discard(path, graveyard)
            return null
        }
        val named = parseCurrent(readAll(currentPath))
        var failure: CorruptionException? = null
        if (named != null) {
            try {
                return decodeAt(named).also { previousNo = it.manifestNo }
            } catch (e: CorruptionException) {
                failure = e
            } catch (e: java.io.IOException) {
                failure = CorruptionException(dir.resolve(nameOf(named)).toString(), "unreadable", e)
            }
        }
        val candidates = io.list(dir).mapNotNull { manifestNo(it) }.filter { it != named }.sortedDescending()
        for (no in candidates) {
            val manifest = try {
                decodeAt(no)
            } catch (_: CorruptionException) {
                continue
            }
            if (filesExist(manifest)) return manifest.also { previousNo = it.manifestNo }
        }
        throw failure ?: CorruptionException(currentPath.toString(), "CURRENT doesn't name a MANIFEST")
    }

    /**
     * The store's MANIFEST, read without writing or deleting anything, or null for a directory without a store. Meant
     * for what is fixed when a store is created, which every MANIFEST of the store records alike: so any readable one
     * answers, the current one first. It opens `CURRENT`, so call it only under the store's lock: on Windows a writer in
     * another process can't replace `CURRENT` while it is open here, and its MANIFEST write fails.
     */
    fun peek(): Manifest? {
        val currentPath = dir.resolve(CURRENT)
        if (!io.exists(currentPath)) return null
        val named = try {
            parseCurrent(readAll(currentPath))
        } catch (_: java.io.IOException) {
            null
        }
        val others = io.list(dir).mapNotNull { manifestNo(it) }.filter { it != named }.sortedDescending()
        var failure: CorruptionException? = null
        for (no in listOfNotNull(named) + others) {
            try {
                return decodeAt(no)
            } catch (e: CorruptionException) {
                failure = failure ?: e
            } catch (_: java.io.IOException) {
                // Replaced or deleted since the listing; another MANIFEST answers the same.
            }
        }
        throw failure ?: CorruptionException(currentPath.toString(), "CURRENT doesn't name a readable MANIFEST")
    }

    /**
     * The highest MANIFEST number in the directory, readable or not; 0 when there is none. A crash after a MANIFEST
     * was renamed into place and before `CURRENT` named it leaves one above the current, and the next MANIFEST
     * written must not take its name: replacing it could fail on Windows, and a reader that takes the
     * highest-numbered MANIFEST as the newest would read the wrong one meanwhile.
     */
    fun highestOnDisk(): Long = io.list(dir).maxOfOrNull { manifestNo(it) ?: 0L } ?: 0L

    private fun decodeAt(no: Long): Manifest {
        val path = dir.resolve(nameOf(no))
        if (!io.exists(path)) throw CorruptionException(path.toString(), "missing")
        val manifest = Manifest.decode(readAll(path), path)
        if (manifest.manifestNo != no) throw CorruptionException(path.toString(), "holds MANIFEST ${manifest.manifestNo}")
        return manifest
    }

    private fun writeDurably(tmp: Path, target: Path, bytes: ByteArray) {
        io.delete(tmp)
        io.open(tmp, OpenMode.CREATE_NEW).use { file ->
            file.write(0, bytes, 0, bytes.size)
            file.force(true)
        }
        io.replace(tmp, target)
    }

    private fun readAll(path: Path): ByteArray = io.open(path, OpenMode.READ).use { file ->
        val size = file.size()
        if (size > MAX_MANIFEST_BYTES) throw CorruptionException(path.toString(), "$size bytes")
        ByteArray(size.toInt()).also { file.readFully(0, it, 0, it.size) }
    }

    private fun parseCurrent(bytes: ByteArray): Long? {
        val text = String(bytes, Charsets.US_ASCII)
        val match = CURRENT_TEXT.matchEntire(text) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    companion object {
        const val CURRENT: String = "CURRENT"
        private const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
        private val CURRENT_TEXT = Regex("MANIFEST-(\\d{6,18})\n")
        private val NAME = Regex("MANIFEST-(\\d{6,18})")

        fun nameOf(no: Long): String = "MANIFEST-%06d".format(Locale.ROOT, no)

        fun manifestNo(path: Path): Long? = NAME.matchEntire(path.fileName.toString())?.groupValues?.get(1)?.toLongOrNull()
    }
}
