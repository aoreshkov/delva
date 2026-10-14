package app.oreshkov.delva.internal.types

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import java.util.concurrent.ConcurrentHashMap

/**
 * The type dictionary: type names and their ids, allocated from 1 in order of first use and never
 * reused. The writer adds names as it logs TYPEDEF records; recovery adds them from the dictionary file and replayed
 * TYPEDEFs. Readers never lock: a name is readable by id before its id is found by name. Thread-safe.
 *
 * Dictionary file (little-endian):
 * ```
 * dictionary := magic "DVTD" | formatVersion:u16 | reserved:u16 | count:u32 | { nameLength:varint | utf8 }* | crc32c:u32
 * ```
 * Name `i` (from 0) has typeId `i + 1`.
 */
internal class TypeDictionary(names: List<String> = emptyList()) {
    private val lock = Any()
    private val ids = ConcurrentHashMap<String, Int>()

    @Volatile
    private var names: Array<String?> = arrayOfNulls(maxOf(16, names.size))

    /** Number of names; typeIds are `1..size`. */
    @Volatile
    var size: Int = 0
        private set

    init {
        for (name in names) add(name)
    }

    /** The id of [name], or 0 when it has none. */
    fun idOf(name: String): Int = ids[name] ?: 0

    /** The name of [typeId], or null when there's no such id. */
    fun nameOf(typeId: Int): String? {
        val n = size
        return if (typeId in 1..n) names[typeId - 1] else null
    }

    /** Gives [name] the next id; returns it. The name must be new. */
    fun add(name: String): Int = synchronized(lock) {
        check(!ids.containsKey(name)) { "type $name already has an id" }
        val id = size + 1
        if (id > names.size) names = names.copyOf(names.size * 2)
        names[id - 1] = name
        size = id
        ids[name] = id
        id
    }

    /** Adds a stored name with its stored id; throws [CorruptionException] unless it's the next id and a new name. */
    fun addStored(typeId: Int, name: String, source: String) {
        synchronized(lock) {
            if (typeId != size + 1) throw CorruptionException(source, "type $typeId defined after ${size} types")
            if (ids.containsKey(name)) throw CorruptionException(source, "type name defined twice as ${ids[name]} and $typeId")
            add(name)
        }
    }

    /** The first [count] names in id order. */
    fun names(count: Int = size): List<String> {
        val n = minOf(count, size)
        val array = names
        return List(n) { array[it]!! }
    }

    companion object {
        const val FORMAT_VERSION: Int = 1
        private const val MAGIC = 0x4454_5644 // "DVTD" read little-endian

        fun encode(names: List<String>): ByteArray {
            val out = GrowableBytes(64 + 32 * names.size)
            out.writeIntLE(MAGIC)
            out.writeShortLE(FORMAT_VERSION)
            out.writeShortLE(0)
            out.writeIntLE(names.size)
            for (name in names) {
                val bytes = name.toByteArray(Charsets.UTF_8)
                out.writeVarLong(bytes.size.toLong())
                out.writeBytes(bytes)
            }
            out.writeIntLE(Crc32c.of(out.array, 0, out.size))
            return out.toByteArray()
        }

        /** Reads a dictionary file; every name is validated. */
        fun decode(bytes: ByteArray, source: String): TypeDictionary {
            if (bytes.size < 16) throw CorruptionException(source, "${bytes.size} bytes")
            val body = bytes.size - 4
            if (Crc32c.of(bytes, 0, body) != LittleEndian.getInt(bytes, body)) throw CorruptionException(source, "checksum mismatch")
            val r = ByteReader(bytes, 0, body, source)
            if (r.readIntLE() != MAGIC) throw CorruptionException(source, "not a type dictionary")
            val version = r.readShortLE()
            if (version != FORMAT_VERSION) throw CorruptionException(source, "unsupported format version $version")
            r.readShortLE()
            val count = r.readIntLE()
            if (count < 0 || count > r.remaining / 2) throw CorruptionException(source, "$count names")
            val dictionary = TypeDictionary()
            for (i in 0 until count) {
                val length = r.readVarInt(TypeNames.MAX_BYTES)
                val at = r.position
                r.skip(length)
                if (!TypeNames.isValid(bytes, at, length)) throw CorruptionException(source, "invalid type name ${i + 1}")
                dictionary.addStored(i + 1, String(bytes, at, length, Charsets.UTF_8), source)
            }
            if (r.remaining != 0) throw CorruptionException(source, "${r.remaining} trailing bytes")
            return dictionary
        }
    }
}
