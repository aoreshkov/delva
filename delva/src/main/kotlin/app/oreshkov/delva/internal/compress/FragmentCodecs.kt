package app.oreshkov.delva.internal.compress

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * How fragment payloads are compressed.
 *
 * - [enabled]: false stores every payload as NONE.
 * - [level]: deflate level of new fragments (reads don't depend on it).
 * - [minSavingRatio]: a payload is stored compressed only when that saves at least this share of its bytes.
 * - [dictionaries]: build preset dictionaries from sampled payloads ([DictionaryTrainer]).
 * - [dictionaryMaxPayloadBytes]: only payloads this size or smaller are deflated with the dictionary. zlib indexes the
 *   whole dictionary on every `setDictionary` (~36 µs for 32 KiB), which is worth it for a 1 KiB payload — it stores
 *   about half as many bytes — and not for a 64 KiB one, which deflate's own window already compresses to within
 *   a tenth of that. Inflating with a dictionary costs nothing extra.
 * - [sampleBytes], [sampleChunkBytes]: payload bytes a build samples, and at most how many per fragment.
 * - [firstTrainingBytes]: payload bytes the first build samples from; [retrainingBytes]: between later builds.
 * - [maxDictionaryBytes]: dictionary size (deflate uses at most the last 32 KiB).
 * - [minDictionaryGain]: a new dictionary is adopted only when it compresses held-out samples by at least this share
 *   better than the current one (or than none).
 * - [maxDictionaries]: builds stop once this many dictionaries exist; they're never deleted.
 */
internal class CompressionOptions(
    val enabled: Boolean = true,
    val level: Int = DEFAULT_LEVEL,
    val minSavingRatio: Double = 0.10,
    val dictionaries: Boolean = true,
    val dictionaryMaxPayloadBytes: Int = 16 * 1024,
    val sampleBytes: Int = 256 * 1024,
    val sampleChunkBytes: Int = 8 * 1024,
    val firstTrainingBytes: Long = 4L shl 20,
    val retrainingBytes: Long = 256L shl 20,
    val maxDictionaryBytes: Int = MAX_DICTIONARY_BYTES,
    val minDictionaryGain: Double = 0.05,
    val maxDictionaries: Int = 64,
) {
    init {
        require(level in 0..9 && minSavingRatio in 0.0..1.0 && minDictionaryGain in 0.0..1.0)
        require(sampleBytes >= 0 && sampleChunkBytes > 0 && firstTrainingBytes > 0 && retrainingBytes > 0)
        require(dictionaryMaxPayloadBytes >= 0)
        require(maxDictionaryBytes in 1..MAX_DICTIONARY_BYTES && maxDictionaries in 0..MAX_DICTIONARY_ID)
    }

    companion object {
        const val DEFAULT_LEVEL: Int = 6
        const val MAX_DICTIONARY_BYTES: Int = 32 * 1024
        const val MAX_DICTIONARY_ID: Int = 0xFFFF
        val DISABLED: CompressionOptions = CompressionOptions(enabled = false)
    }
}

/** An immutable preset dictionary; referenced by [id] from FRAGMENT records. */
internal class CompressionDictionary(val id: Int, val bytes: ByteArray)

/**
 * The fragment payload codecs (the `codec` byte of a FRAGMENT record): NONE, DEFLATE (raw RFC 1951 without a
 * dictionary) and DEFLATE_DICT (raw deflate with the preset dictionary `dictId`), the dictionaries readers may need, and pools of
 * reusable `Deflater`/`Inflater`s (native zlib state, reset between fragments). Thread-safe.
 */
internal class FragmentCodecs(val options: CompressionOptions = CompressionOptions()) : AutoCloseable {
    private val deflaters = ConcurrentLinkedQueue<Deflater>()
    private val inflaters = ConcurrentLinkedQueue<Inflater>()
    private val idleDeflaters = AtomicInteger()
    private val idleInflaters = AtomicInteger()
    private val lock = Any()

    @Volatile
    private var closed = false

    /** Dictionaries by id; null where none was loaded. */
    @Volatile
    private var byId: Array<CompressionDictionary?> = arrayOfNulls(1)

    /** Ids listed in the MANIFEST whose file is missing or damaged: fragments using them can't be read. */
    @Volatile
    private var damaged: Set<Int> = emptySet()

    /** The dictionary new fragments use, or null; only ever one whose listing is durable. */
    @Volatile
    var current: CompressionDictionary? = null
        private set

    /** Payloads written compressed and as NONE, and compressed payloads inflated; for tests and reports. */
    val compressedFragments = AtomicInteger()
    val storedFragments = AtomicInteger()
    val inflatedFragments = LongAdder()

    /** The largest id known, loaded or damaged; new dictionaries get larger ones. */
    val maxId: Int get() = maxOf(byId.size - 1, damaged.maxOrNull() ?: 0)

    val dictionaryCount: Int get() = byId.count { it != null }

    fun dictionary(id: Int): CompressionDictionary? = byId.getOrNull(id)

    /** Makes [dictionary] readable; with [use], new fragments use it from now on. */
    fun register(dictionary: CompressionDictionary, use: Boolean) = synchronized(lock) {
        require(dictionary.id in 1..CompressionOptions.MAX_DICTIONARY_ID)
        val ids = if (dictionary.id < byId.size) byId.copyOf() else byId.copyOf(dictionary.id + 1)
        ids[dictionary.id] = dictionary
        byId = ids
        if (use) current = dictionary
    }

    fun markDamaged(id: Int) = synchronized(lock) { damaged = damaged + id }

    /**
     * Appends `codecId:u8 | dictId:u16 | rawLength:u32` and the stored payload of `payload[offset, offset + length)`
     * to [out]: deflated with the current dictionary when that saves [CompressionOptions.minSavingRatio], else as is.
     */
    fun writePayload(out: GrowableBytes, payload: ByteArray, offset: Int, length: Int) {
        val header = out.reserve(CODEC_HEADER_BYTES)
        if (options.enabled && length >= MIN_COMPRESSED_BYTES) {
            val limit = length - maxOf(1, (length * options.minSavingRatio).toInt())
            val dictionary = if (length <= options.dictionaryMaxPayloadBytes) current else null
            val deflater = borrowDeflater()
            try {
                if (dictionary != null) deflater.setDictionary(dictionary.bytes)
                deflater.setInput(payload, offset, length)
                deflater.finish()
                val at = out.reserve(limit)
                var n = 0
                while (n < limit && !deflater.finished()) n += deflater.deflate(out.array, at + n, limit - n)
                if (deflater.finished()) {
                    out.truncate(at + n)
                    putHeader(out, header, if (dictionary != null) DEFLATE_DICT else DEFLATE, dictionary?.id ?: 0, length)
                    compressedFragments.incrementAndGet()
                    return
                }
                out.truncate(at)
            } finally {
                giveBack(deflater)
            }
        }
        putHeader(out, header, NONE, 0, length)
        out.writeBytes(payload, offset, length)
        storedFragments.incrementAndGet()
    }

    /**
     * Inflates the stored payload `source[offset, offset + length)` of codec [codecId] into
     * `destination[destinationOffset, + rawLength)`; any mismatch is a [CorruptionException] for [location].
     */
    fun inflate(codecId: Int, dictId: Int, source: ByteArray, offset: Int, length: Int, destination: ByteArray, destinationOffset: Int, rawLength: Int, location: String) {
        val dictionary = when (codecId) {
            DEFLATE -> null
            DEFLATE_DICT -> dictionary(dictId) ?: throw CorruptionException(location, if (dictId in damaged) "compression dictionary $dictId is damaged" else "unknown compression dictionary $dictId")
            else -> throw IllegalArgumentException("codec $codecId isn't compressed")
        }
        val inflater = borrowInflater()
        try {
            if (dictionary != null) inflater.setDictionary(dictionary.bytes)
            inflater.setInput(source, offset, length)
            var n = 0
            while (n < rawLength) {
                val k = inflater.inflate(destination, destinationOffset + n, rawLength - n)
                // No progress: the stream ended, lacks input or wants a dictionary it wasn't written with.
                if (k == 0) break
                n += k
            }
            // The stream must end exactly here: no more output, all input consumed.
            if (n == rawLength && !inflater.finished()) {
                val probe = ByteArray(1)
                if (inflater.inflate(probe, 0, 1) != 0) n++
            }
            if (n != rawLength || !inflater.finished() || inflater.remaining != 0) {
                throw CorruptionException(location, "compressed payload of $length bytes doesn't inflate to $rawLength bytes")
            }
            inflatedFragments.increment()
        } catch (e: DataFormatException) {
            throw CorruptionException(location, "compressed payload is damaged: ${e.message}")
        } finally {
            giveBack(inflater)
        }
    }

    /** Deflated size of `bytes[offset, offset + length)` with [dictionary] at the configured level; for evaluations. */
    fun deflatedSize(bytes: ByteArray, offset: Int, length: Int, dictionary: ByteArray?, scratch: GrowableBytes): Int {
        val deflater = borrowDeflater()
        try {
            if (dictionary != null) deflater.setDictionary(dictionary)
            deflater.setInput(bytes, offset, length)
            deflater.finish()
            scratch.clear()
            var n = 0
            while (!deflater.finished()) {
                scratch.ensureCapacity(n + 4096)
                n += deflater.deflate(scratch.array, n, scratch.array.size - n)
            }
            return n
        } finally {
            giveBack(deflater)
        }
    }

    override fun close() {
        closed = true
        while (true) (deflaters.poll() ?: break).end()
        while (true) (inflaters.poll() ?: break).end()
    }

    private fun borrowDeflater(): Deflater = deflaters.poll()?.also { idleDeflaters.decrementAndGet() } ?: Deflater(options.level, true)

    private fun borrowInflater(): Inflater = inflaters.poll()?.also { idleInflaters.decrementAndGet() } ?: Inflater(true)

    private fun giveBack(deflater: Deflater) {
        deflater.reset()
        if (closed || idleDeflaters.incrementAndGet() > MAX_IDLE) {
            idleDeflaters.decrementAndGet()
            deflater.end()
        } else {
            deflaters.offer(deflater)
            if (closed) close()
        }
    }

    private fun giveBack(inflater: Inflater) {
        inflater.reset()
        if (closed || idleInflaters.incrementAndGet() > MAX_IDLE) {
            idleInflaters.decrementAndGet()
            inflater.end()
        } else {
            inflaters.offer(inflater)
            if (closed) close()
        }
    }

    private fun putHeader(out: GrowableBytes, at: Int, codecId: Int, dictId: Int, rawLength: Int) {
        out.putByte(at, codecId)
        out.putByte(at + 1, dictId and 0xFF)
        out.putByte(at + 2, dictId ushr 8)
        out.putIntLE(at + 3, rawLength)
    }

    companion object {
        const val NONE: Int = 0
        const val DEFLATE: Int = 1
        const val DEFLATE_DICT: Int = 2

        /** `codecId:u8 | dictId:u16 | rawLength:u32`. */
        const val CODEC_HEADER_BYTES: Int = 7

        /** Shorter payloads aren't worth a deflate call. */
        const val MIN_COMPRESSED_BYTES: Int = 32

        /** Idle deflaters and inflaters kept each; zlib state is ~300 KiB and ~40 KiB of native memory. */
        private const val MAX_IDLE = 32
    }
}
