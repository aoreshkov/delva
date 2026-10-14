package app.oreshkov.delva.internal.bits

/**
 * A set of non-negative longs, such as docIds, in one open-addressing array: 8 to 16 bytes an element, where a
 * `TreeSet<Long>` takes about 50. Iteration order is unspecified. Not thread-safe.
 */
internal class LongHashSet {
    private var slots = LongArray(MIN_SLOTS).also { it.fill(EMPTY) }

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    operator fun contains(value: Long): Boolean {
        if (value < 0) return false
        val mask = slots.size - 1
        var i = slot(value, mask)
        while (true) {
            val s = slots[i]
            if (s == EMPTY) return false
            if (s == value) return true
            i = (i + 1) and mask
        }
    }

    /** Adds [value]; false when it was already in the set. */
    fun add(value: Long): Boolean {
        require(value >= 0) { "negative value $value" }
        if ((size + 1) * 2 > slots.size) rehash(slots.size * 2)
        val mask = slots.size - 1
        var i = slot(value, mask)
        while (true) {
            val s = slots[i]
            if (s == value) return false
            if (s == EMPTY) break
            i = (i + 1) and mask
        }
        slots[i] = value
        size++
        return true
    }

    operator fun plusAssign(value: Long) {
        add(value)
    }

    /** The elements in ascending order. */
    fun toSortedArray(): LongArray {
        val out = LongArray(size)
        var n = 0
        for (value in slots) if (value != EMPTY) out[n++] = value
        out.sort()
        return out
    }

    operator fun iterator(): LongIterator = object : LongIterator() {
        private var i = advance(0)

        private fun advance(from: Int): Int {
            var j = from
            while (j < slots.size && slots[j] == EMPTY) j++
            return j
        }

        override fun hasNext(): Boolean = i < slots.size

        override fun nextLong(): Long {
            if (i >= slots.size) throw NoSuchElementException()
            val value = slots[i]
            i = advance(i + 1)
            return value
        }
    }

    private fun rehash(n: Int) {
        val old = slots
        slots = LongArray(n).also { it.fill(EMPTY) }
        val mask = n - 1
        for (value in old) {
            if (value == EMPTY) continue
            var i = slot(value, mask)
            while (slots[i] != EMPTY) i = (i + 1) and mask
            slots[i] = value
        }
    }

    private companion object {
        const val EMPTY = -1L
        const val MIN_SLOTS = 16

        fun slot(value: Long, mask: Int): Int = ((value * -0x61c8864680b583ebL) ushr 32).toInt() and mask
    }
}
