package app.oreshkov.delva.internal.bits

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian

/**
 * An immutable set of docIds: pages of 65 536 docIds reached through a two-level page table of 1 024 pages
 * per table. A page is absent when empty, a sorted array of 16-bit offsets while it holds at most [SPARSE_MAX] docIds
 * (2 bytes per docId, so a few deletes scattered over a large store stay small), a bitset of 1 024 longs above that,
 * and one shared read-only bitset when full. [Builder] copies only the tables and pages it changes, so a batch of
 * additions never copies a large array, whatever the docIds. Deleted docIds are never removed, so the set only
 * grows. Thread-safe (immutable).
 */
internal class DocIdSet private constructor(private val tables: Array<Array<Any?>?>, val size: Long) {
    fun contains(docId: Long): Boolean {
        if (docId < 0 || docId > MAX_DOC_ID) return false
        return when (val page = page(tables, docId ushr PAGE_BITS)) {
            null -> false
            is CharArray -> java.util.Arrays.binarySearch(page, (docId and PAGE_MASK).toInt().toChar()) >= 0
            else -> {
                val bit = (docId and PAGE_MASK).toInt()
                (page as LongArray)[bit ushr 6] and (1L shl bit) != 0L
            }
        }
    }

    val isEmpty: Boolean get() = size == 0L

    /** The smallest member ≥ [from], or -1. */
    fun nextMember(from: Long): Long {
        var docId = maxOf(from, 0)
        while (docId <= MAX_DOC_ID) {
            val pageNo = docId ushr PAGE_BITS
            val top = (pageNo ushr TABLE_BITS).toInt()
            if (top >= tables.size) return -1
            val table = tables[top]
            if (table == null) {
                docId = (top + 1).toLong() shl (TABLE_BITS + PAGE_BITS)
                continue
            }
            when (val page = table[(pageNo and TABLE_MASK).toInt()]) {
                null -> {}
                is CharArray -> {
                    val i = ceilingIndex(page, (docId and PAGE_MASK).toInt())
                    if (i < page.size) return (pageNo shl PAGE_BITS) + page[i].code
                }
                else -> {
                    val words = page as LongArray
                    var word = ((docId and PAGE_MASK) ushr 6).toInt()
                    var bits = words[word] and (-1L shl (docId and 63).toInt())
                    while (true) {
                        if (bits != 0L) return (pageNo shl PAGE_BITS) + (word.toLong() shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
                        if (++word == PAGE_WORDS) break
                        bits = words[word]
                    }
                }
            }
            docId = (pageNo + 1) shl PAGE_BITS
        }
        return -1
    }

    /** The number of members in `[from, to]`. */
    fun countInRange(from: Long, to: Long): Long {
        val first = maxOf(from, 0)
        val last = minOf(to, MAX_DOC_ID)
        if (first > last || size == 0L) return 0
        var n = 0L
        var pageNo = first ushr PAGE_BITS
        val lastPage = last ushr PAGE_BITS
        while (pageNo <= lastPage) {
            val top = (pageNo ushr TABLE_BITS).toInt()
            if (top >= tables.size) break
            val table = tables[top]
            if (table == null) {
                pageNo = (top + 1).toLong() shl TABLE_BITS
                continue
            }
            val page = table[(pageNo and TABLE_MASK).toInt()]
            if (page != null) {
                val lo = if (pageNo == first ushr PAGE_BITS) (first and PAGE_MASK).toInt() else 0
                val hi = if (pageNo == lastPage) (last and PAGE_MASK).toInt() else PAGE_SIZE - 1
                n += countInPage(page, lo, hi)
            }
            pageNo++
        }
        return n
    }

    fun toLongArray(): LongArray {
        val out = LongArray(Math.toIntExact(size))
        var i = 0
        var docId = nextMember(0)
        while (docId >= 0) {
            out[i++] = docId
            docId = nextMember(docId + 1)
        }
        return out
    }

    fun builder(): Builder = Builder(this)

    /**
     * Encodes the set (bitmap file), little-endian:
     * ```
     * set  := magic "DVDS" | formatVersion:u16 | reserved:u16 | size:u64 | pageCount:u64
     *       | { pageNo:u64 | kind:u8 | page }* | crc32c:u32
     * page := kind 1 bits:   word:u64 × 1024                     4 097 … 65 535 members
     *       | kind 2 full:   nothing                             65 536 members
     *       | kind 3 sparse: count:u16 | offset:u16 × count      1 … 4 096 members, strictly ascending
     * ```
     * Pages are in increasing `pageNo`; empty pages are left out. Each page uses the kind its member count calls for.
     */
    fun encode(): ByteArray {
        val out = GrowableBytes(64)
        out.writeIntLE(MAGIC)
        out.writeShortLE(FORMAT_VERSION)
        out.writeShortLE(0)
        out.writeLongLE(size)
        val countAt = out.reserve(8)
        var pages = 0L
        for ((top, table) in tables.withIndex()) {
            if (table == null) continue
            for ((low, page) in table.withIndex()) {
                if (page == null) continue
                pages++
                out.writeLongLE((top.toLong() shl TABLE_BITS) or low.toLong())
                when {
                    page === FULL -> out.writeByte(KIND_FULL)
                    page is CharArray -> {
                        out.writeByte(KIND_SPARSE)
                        out.writeShortLE(page.size)
                        for (c in page) out.writeShortLE(c.code)
                    }
                    else -> {
                        out.writeByte(KIND_BITS)
                        for (word in page as LongArray) out.writeLongLE(word)
                    }
                }
            }
        }
        out.putLongLE(countAt, pages)
        out.writeIntLE(Crc32c.of(out.array, 0, out.size))
        return out.toByteArray()
    }

    /** Adds docIds to a copy of a set; [build] returns the new set. Not thread-safe. */
    class Builder internal constructor(from: DocIdSet) {
        private var tables = from.tables
        private var tablesCopied = false
        private val copiedTables = HashSet<Int>()

        /** Bitset pages this builder owns (copied or created), which it may change in place. */
        private val copiedPages = HashSet<Long>()
        private var size = from.size

        /** Adds [docId]; returns false when it was already in the set. */
        fun add(docId: Long): Boolean {
            require(docId in 0..MAX_DOC_ID) { "docId $docId out of range" }
            val pageNo = docId ushr PAGE_BITS
            val top = (pageNo ushr TABLE_BITS).toInt()
            val low = (pageNo and TABLE_MASK).toInt()
            val offset = (docId and PAGE_MASK).toInt()
            val existing = page(tables, pageNo)
            val sparseIndex = if (existing is CharArray) java.util.Arrays.binarySearch(existing, offset.toChar()) else -1
            when {
                existing === FULL || sparseIndex >= 0 -> return false
                existing is LongArray && existing[offset ushr 6] and (1L shl offset) != 0L -> return false
            }
            val table = writableTable(top)
            when (existing) {
                null -> table[low] = charArrayOf(offset.toChar())
                is CharArray -> {
                    val at = -sparseIndex - 1
                    if (existing.size < SPARSE_MAX) {
                        // Sparse pages are copied on every addition, so they're never shared with an older set.
                        val grown = CharArray(existing.size + 1)
                        System.arraycopy(existing, 0, grown, 0, at)
                        grown[at] = offset.toChar()
                        System.arraycopy(existing, at, grown, at + 1, existing.size - at)
                        table[low] = grown
                    } else {
                        val words = LongArray(PAGE_WORDS)
                        for (c in existing) words[c.code ushr 6] = words[c.code ushr 6] or (1L shl c.code)
                        words[offset ushr 6] = words[offset ushr 6] or (1L shl offset)
                        table[low] = words
                        copiedPages += pageNo
                    }
                }
                else -> {
                    var words = existing as LongArray
                    if (copiedPages.add(pageNo)) {
                        words = words.copyOf()
                        table[low] = words
                    }
                    words[offset ushr 6] = words[offset ushr 6] or (1L shl offset)
                }
            }
            size++
            return true
        }

        private fun writableTable(top: Int): Array<Any?> {
            if (top >= tables.size) {
                tables = tables.copyOf(maxOf(top + 1, tables.size * 2))
                tablesCopied = true
            } else if (!tablesCopied) {
                tables = tables.copyOf()
                tablesCopied = true
            }
            var table = tables[top]
            if (table == null) {
                table = arrayOfNulls(TABLE_SIZE)
                tables[top] = table
                copiedTables += top
            } else if (copiedTables.add(top)) {
                table = table.copyOf()
                tables[top] = table
            }
            return table
        }

        fun build(): DocIdSet {
            // Share full pages, so a dense set of aborted or deleted docIds stays small in memory and in its file.
            for (pageNo in copiedPages) {
                val table = tables[(pageNo ushr TABLE_BITS).toInt()]!!
                val low = (pageNo and TABLE_MASK).toInt()
                if ((table[low] as LongArray).all { it == -1L }) table[low] = FULL
            }
            val set = DocIdSet(tables, size)
            tables = set.tables
            tablesCopied = false
            copiedTables.clear()
            copiedPages.clear()
            return set
        }
    }

    companion object {
        private const val PAGE_BITS = 16
        private const val PAGE_SIZE = 1 shl PAGE_BITS
        private const val PAGE_WORDS = 1 shl (PAGE_BITS - 6)
        private const val PAGE_MASK = (1L shl PAGE_BITS) - 1
        private const val TABLE_BITS = 10
        private const val TABLE_SIZE = 1 shl TABLE_BITS
        private const val TABLE_MASK = (TABLE_SIZE - 1).toLong()

        /** Most members of a sparse page: at 4 096 its offsets take as many bytes as a bitset. */
        const val SPARSE_MAX: Int = PAGE_WORDS * 8 / 2

        /** Largest docId a set holds: 2⁴⁸ − 1, so the page table's top level stays below 2²² entries. */
        const val MAX_DOC_ID: Long = (1L shl 48) - 1

        const val FORMAT_VERSION: Int = 1
        private const val MAGIC = 0x5344_5644 // "DVDS" read little-endian
        private const val KIND_BITS = 1
        private const val KIND_FULL = 2
        private const val KIND_SPARSE = 3

        private val FULL = LongArray(PAGE_WORDS) { -1L }

        val EMPTY: DocIdSet = DocIdSet(arrayOfNulls(0), 0)

        fun of(vararg docIds: Long): DocIdSet = EMPTY.builder().apply { for (d in docIds) add(d) }.build()

        private fun page(tables: Array<Array<Any?>?>, pageNo: Long): Any? {
            val top = pageNo ushr TABLE_BITS
            if (top >= tables.size) return null
            return tables[top.toInt()]?.get((pageNo and TABLE_MASK).toInt())
        }

        /** Index of the first offset ≥ [offset] in a sparse page. */
        private fun ceilingIndex(page: CharArray, offset: Int): Int {
            val i = java.util.Arrays.binarySearch(page, offset.toChar())
            return if (i >= 0) i else -i - 1
        }

        private fun countInPage(page: Any, lo: Int, hi: Int): Long {
            // hi + 1 doesn't fit a char at the end of the page.
            if (page is CharArray) return ((if (hi == PAGE_SIZE - 1) page.size else ceilingIndex(page, hi + 1)) - ceilingIndex(page, lo)).toLong()
            val words = page as LongArray
            var n = 0L
            var word = lo ushr 6
            val lastWord = hi ushr 6
            while (word <= lastWord) {
                var bits = words[word]
                if (word == lo ushr 6) bits = bits and (-1L shl (lo and 63))
                if (word == lastWord && (hi and 63) != 63) bits = bits and ((1L shl ((hi and 63) + 1)) - 1)
                n += java.lang.Long.bitCount(bits)
                word++
            }
            return n
        }

        /**
         * Decodes a set [encode] wrote. Every member must be below [endDocId], the store's next docId: that bounds the
         * tables a damaged or crafted file can make this allocate by the docIds the store has, not by the file's size.
         */
        fun decode(bytes: ByteArray, source: String, endDocId: Long): DocIdSet {
            if (bytes.size < 28) throw CorruptionException(source, "${bytes.size} bytes")
            val body = bytes.size - 4
            if (Crc32c.of(bytes, 0, body) != LittleEndian.getInt(bytes, body)) throw CorruptionException(source, "checksum mismatch")
            val r = ByteReader(bytes, 0, body, source)
            if (r.readIntLE() != MAGIC) throw CorruptionException(source, "not a docId set")
            val version = r.readShortLE()
            if (version != FORMAT_VERSION) throw CorruptionException(source, "unsupported format version $version")
            r.readShortLE()
            val size = r.readLongLE()
            val pages = r.readLongLE()
            if (pages < 0 || pages > r.remaining / 9) throw CorruptionException(source, "$pages pages")
            var tables = arrayOfNulls<Array<Any?>>(0)
            var previous = -1L
            var counted = 0L
            for (p in 0 until pages) {
                val pageNo = r.readLongLE()
                if (pageNo <= previous || pageNo > MAX_DOC_ID ushr PAGE_BITS) throw CorruptionException(source, "page $pageNo after $previous")
                if (pageNo shl PAGE_BITS >= endDocId) throw CorruptionException(source, "page $pageNo at or past docId $endDocId")
                previous = pageNo
                val top = (pageNo ushr TABLE_BITS).toInt()
                if (top >= tables.size) tables = tables.copyOf(maxOf(top + 1, tables.size * 2))
                val table = tables[top] ?: arrayOfNulls<Any?>(TABLE_SIZE).also { tables[top] = it }
                val page: Any = when (r.readByte()) {
                    KIND_FULL -> FULL.also {
                        if ((pageNo + 1) shl PAGE_BITS > endDocId) throw CorruptionException(source, "full page $pageNo past docId $endDocId")
                        counted += PAGE_SIZE
                    }
                    KIND_BITS -> LongArray(PAGE_WORDS) { r.readLongLE() }.also { words ->
                        val members = words.sumOf { java.lang.Long.bitCount(it) }
                        if (members <= SPARSE_MAX || members == PAGE_SIZE) throw CorruptionException(source, "bits page $pageNo with $members members")
                        val w = words.indexOfLast { it != 0L }
                        val last = (pageNo shl PAGE_BITS) + w * 64 + 63 - java.lang.Long.numberOfLeadingZeros(words[w])
                        if (last >= endDocId) throw CorruptionException(source, "bits page $pageNo has docId $last, past $endDocId")
                        counted += members
                    }
                    KIND_SPARSE -> {
                        val count = r.readShortLE()
                        if (count !in 1..SPARSE_MAX) throw CorruptionException(source, "sparse page $pageNo with $count members")
                        CharArray(count) { r.readShortLE().toChar() }.also { offsets ->
                            for (i in 1 until count) if (offsets[i] <= offsets[i - 1]) throw CorruptionException(source, "sparse page $pageNo out of order")
                            if ((pageNo shl PAGE_BITS) + offsets[count - 1].code >= endDocId) throw CorruptionException(source, "sparse page $pageNo past docId $endDocId")
                            counted += count
                        }
                    }
                    else -> throw CorruptionException(source, "page kind")
                }
                table[(pageNo and TABLE_MASK).toInt()] = page
            }
            if (r.remaining != 0) throw CorruptionException(source, "${r.remaining} trailing bytes")
            if (counted != size) throw CorruptionException(source, "$counted members, header says $size")
            return DocIdSet(tables, size)
        }
    }
}
