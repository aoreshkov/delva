package app.oreshkov.delva.internal.compress

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.discard
import app.oreshkov.delva.internal.io.readFully
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Locale

/**
 * Compression dictionary files (in `dict/`), little-endian:
 * ```
 * dictionary := magic "DVCD" | formatVersion:u16 | codecId:u8 (2 = DEFLATE_DICT) | reserved:u8 | dictId:u16
 *             | reserved:u16 | length:u32 (1..32 KiB) | bytes[length] | crc32c:u32   over everything before it
 * ```
 * A file is written to a temporary name, synced, renamed and its directory synced before a MANIFEST lists it, and a
 * FRAGMENT record refers to a dictionary only once that MANIFEST is durable. Files are never deleted while the store
 * holds data; only a reset ([app.oreshkov.delva.internal.engine.StoreReset]) removes them.
 */
internal object DictionaryFiles {
    const val FORMAT_VERSION: Int = 1
    const val DIR: String = "dict"
    private const val MAGIC = 0x44435644 // "DVCD" read little-endian
    private const val HEADER_BYTES = 16
    private val NAME = Regex("deflate-(\\d{5})\\.dict")

    fun name(id: Int): String = "deflate-%05d.dict".format(Locale.ROOT, id)

    fun parseName(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..CompressionOptions.MAX_DICTIONARY_ID }

    fun encode(dictionary: CompressionDictionary): ByteArray {
        val out = GrowableBytes(HEADER_BYTES + dictionary.bytes.size + 4)
        out.writeIntLE(MAGIC)
        out.writeShortLE(FORMAT_VERSION)
        out.writeByte(FragmentCodecs.DEFLATE_DICT)
        out.writeByte(0)
        out.writeShortLE(dictionary.id)
        out.writeShortLE(0)
        out.writeIntLE(dictionary.bytes.size)
        out.writeBytes(dictionary.bytes)
        out.writeIntLE(Crc32c.of(out.array, 0, out.size))
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedId: Int, source: String): CompressionDictionary {
        val r = ByteReader(bytes, 0, bytes.size, source)
        if (bytes.size < HEADER_BYTES + 4) throw CorruptionException(source, "dictionary file of ${bytes.size} bytes")
        if (Crc32c.of(bytes, 0, bytes.size - 4) != app.oreshkov.delva.internal.bytes.LittleEndian.getInt(bytes, bytes.size - 4)) {
            throw CorruptionException(source, "dictionary checksum mismatch")
        }
        if (r.readIntLE() != MAGIC) throw CorruptionException(source, "not a dictionary file")
        val version = r.readShortLE()
        if (version != FORMAT_VERSION) throw CorruptionException(source, "dictionary format version $version")
        val codec = r.readByte()
        r.readByte()
        val id = r.readShortLE()
        r.readShortLE()
        val length = r.readIntLE()
        if (codec != FragmentCodecs.DEFLATE_DICT || id != expectedId || length !in 1..CompressionOptions.MAX_DICTIONARY_BYTES || length != bytes.size - HEADER_BYTES - 4) {
            throw CorruptionException(source, "dictionary header (codec $codec, id $id, $length bytes)")
        }
        return CompressionDictionary(id, bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + length))
    }

    /** Writes [dictionary] durably under [dir]. */
    fun write(io: FileSystemIo, dir: Path, dictionary: CompressionDictionary) {
        val path = dir.resolve(name(dictionary.id))
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        val bytes = encode(dictionary)
        io.delete(tmp)
        io.open(tmp, OpenMode.CREATE_NEW).use { file ->
            file.write(0, bytes, 0, bytes.size)
            file.force(true)
        }
        io.atomicRename(tmp, path)
        io.syncDirectory(dir)
    }

    /**
     * Loads the dictionaries in [dir] into [codecs] when a store opens; returns the ids of valid files. The newest
     * valid id in [listed] becomes the current one. A listed id without a valid file is marked damaged (fragments using
     * it fail to read); an unlisted file that isn't valid was cut off before any MANIFEST listed it and is deleted.
     * Valid unlisted files are kept and loaded: a fallback MANIFEST may predate a dictionary fragments use. Deleting goes
     * through [graveyard] ([discard]). Only a file that is corrupt or gone counts as not valid: any other I/O error is
     * thrown, so a file that can't be read just now isn't given up for good.
     */
    fun load(io: FileSystemIo, dir: Path, listed: Collection<Int>, codecs: FragmentCodecs, graveyard: Path): List<Int> {
        val valid = ArrayList<Int>()
        for (path in io.list(dir)) {
            val name = path.fileName.toString()
            if (name.endsWith(".tmp")) {
                io.discard(path, graveyard)
                continue
            }
            val id = parseName(name) ?: continue
            val dictionary = try {
                io.open(path, OpenMode.READ).use { file ->
                    val size = file.size()
                    if (size > HEADER_BYTES + 4 + CompressionOptions.MAX_DICTIONARY_BYTES) throw CorruptionException(path.toString(), "dictionary file of $size bytes")
                    val bytes = ByteArray(size.toInt())
                    file.readFully(0, bytes, 0, bytes.size)
                    decode(bytes, id, path.toString())
                }
            } catch (_: CorruptionException) {
                null
            } catch (_: NoSuchFileException) {
                null
            }
            if (dictionary == null) {
                if (id !in listed) io.discard(path, graveyard)
                continue
            }
            codecs.register(dictionary, use = false)
            valid += id
        }
        for (id in listed) if (id !in valid) codecs.markDamaged(id)
        listed.filter { it in valid }.maxOrNull()?.let { codecs.register(codecs.dictionary(it)!!, use = true) }
        return valid.sorted()
    }

    /**
     * Loads the valid dictionary files in [dir] that [codecs] doesn't have yet, for a process that only reads the store:
     * nothing is deleted or marked damaged, since the writer may still be writing a file under its temporary name, and
     * a fragment refers to a dictionary only once its file is durable. Returns the ids loaded.
     */
    fun loadNew(io: FileSystemIo, dir: Path, codecs: FragmentCodecs): List<Int> {
        val loaded = ArrayList<Int>()
        for (path in io.list(dir)) {
            val id = parseName(path.fileName.toString()) ?: continue
            if (codecs.dictionary(id) != null) continue
            val dictionary = try {
                io.open(path, OpenMode.READ).use { file ->
                    val size = file.size()
                    if (size > HEADER_BYTES + 4 + CompressionOptions.MAX_DICTIONARY_BYTES) throw CorruptionException(path.toString(), "dictionary file of $size bytes")
                    val bytes = ByteArray(size.toInt())
                    file.readFully(0, bytes, 0, bytes.size)
                    decode(bytes, id, path.toString())
                }
            } catch (_: CorruptionException) {
                null
            } catch (_: IOException) {
                null
            } ?: continue
            codecs.register(dictionary, use = false)
            loaded += id
        }
        return loaded
    }
}

/**
 * Builds dictionaries from the payloads inserts write: a spread sample of about
 * [CompressionOptions.sampleBytes] from the first [CompressionOptions.firstTrainingBytes] of payload, then from each
 * [CompressionOptions.retrainingBytes] after. A build ([DictionaryBuilder]) keeps every fourth sample out; the new
 * dictionary is adopted when it deflates those by [CompressionOptions.minDictionaryGain] better than the current one.
 * [install] makes an adopted dictionary durable and current. Builds run on the inserting thread that completes a
 * sample, one at a time; other inserts don't wait for it. Thread-safe.
 */
internal class DictionaryTrainer(private val codecs: FragmentCodecs, private val install: (ByteArray) -> Unit) {
    private val options = codecs.options
    private val lock = Any()
    private var builder: DictionaryBuilder? = null
    private var seen = 0L
    private var target = options.firstTrainingBytes
    private var credit = options.sampleChunkBytes.toDouble()
    private var building = false

    /** Builds finished and dictionaries adopted; for tests and reports. */
    @Volatile
    var builds: Int = 0
        private set

    @Volatile
    var adopted: Int = 0
        private set

    private val active: Boolean
        get() = options.enabled && options.dictionaries && options.sampleBytes > 0

    /** Notes a payload about to be written; may sample it and run a build. */
    fun offer(payload: ByteArray, offset: Int, length: Int) {
        if (!active) return
        val ready = synchronized(lock) {
            if (building || codecs.dictionaryCount >= options.maxDictionaries) return
            val b = builder ?: DictionaryBuilder(options.sampleBytes).also { builder = it }
            seen += length
            credit += length.toDouble() * options.sampleBytes / target
            val take = minOf(length, options.sampleChunkBytes)
            if (credit >= take) credit -= b.add(payload, offset, take)
            if (seen < target) return
            building = true
            builder = null
            seen = 0
            credit = options.sampleChunkBytes.toDouble()
            target = options.retrainingBytes
            b
        }
        try {
            build(ready)
        } finally {
            synchronized(lock) { building = false }
        }
    }

    private fun build(samples: DictionaryBuilder) {
        val dictionary = samples.build(options.maxDictionaryBytes, HOLDOUT_EVERY)
        builds++
        if (dictionary.size < MIN_DICTIONARY_BYTES) return
        val scratch = GrowableBytes(4096)
        val current = codecs.current?.bytes
        var withNew = 0L
        var withCurrent = 0L
        for (i in 0 until samples.count) {
            if (!samples.isHeldOut(i, HOLDOUT_EVERY)) continue
            val offset = samples.sampleOffset(i)
            val length = samples.sampleLength(i)
            withNew += codecs.deflatedSize(samples.sampleArray, offset, length, dictionary, scratch)
            withCurrent += codecs.deflatedSize(samples.sampleArray, offset, length, current, scratch)
        }
        if (withCurrent == 0L || withNew > withCurrent * (1 - options.minDictionaryGain)) return
        install(dictionary)
        adopted++
    }

    private companion object {
        const val HOLDOUT_EVERY = 4
        const val MIN_DICTIONARY_BYTES = 64
    }
}
