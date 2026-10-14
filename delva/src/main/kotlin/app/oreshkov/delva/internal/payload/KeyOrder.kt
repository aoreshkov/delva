package app.oreshkov.delva.internal.payload

import java.util.Arrays

/** Orders a fragment's key table by unsigned key bytes, for `sortPerm`. */
internal object KeyOrder {
    /**
     * Sets `ranks[i]` to the position of key `i` (bytes at `bytes[offsets[i], offsets[i] + lengths[i])`) in unsigned
     * byte order. [scratch] must hold at least `2 * count` ints. Returns false if two keys are equal.
     */
    fun rank(bytes: ByteArray, offsets: IntArray, lengths: IntArray, count: Int, ranks: IntArray, scratch: IntArray): Boolean =
        rank(bytes, 0, offsets, lengths, count, ranks, scratch)

    /** As [rank], with key `i` at `bytes[base + offsets[i], + lengths[i])`. */
    fun rank(bytes: ByteArray, base: Int, offsets: IntArray, lengths: IntArray, count: Int, ranks: IntArray, scratch: IntArray): Boolean {
        for (i in 0 until count) scratch[i] = i
        // Bottom-up merge sort of key indices between scratch[0, count) and scratch[count, 2 * count).
        var src = 0
        var dst = count
        var width = 1
        while (width < count) {
            var lo = 0
            while (lo < count) {
                val mid = minOf(lo + width, count)
                val hi = minOf(lo + 2 * width, count)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) {
                    val a = scratch[src + i]
                    val b = scratch[src + j]
                    if (compare(bytes, base, offsets, lengths, a, b) <= 0) {
                        scratch[dst + k++] = a
                        i++
                    } else {
                        scratch[dst + k++] = b
                        j++
                    }
                }
                while (i < mid) scratch[dst + k++] = scratch[src + i++]
                while (j < hi) scratch[dst + k++] = scratch[src + j++]
                lo = hi
            }
            val t = src
            src = dst
            dst = t
            width *= 2
        }
        for (i in 0 until count) {
            val key = scratch[src + i]
            if (i > 0 && compare(bytes, base, offsets, lengths, scratch[src + i - 1], key) == 0) return false
            ranks[key] = i
        }
        return true
    }

    private fun compare(bytes: ByteArray, base: Int, offsets: IntArray, lengths: IntArray, a: Int, b: Int): Int =
        Arrays.compareUnsigned(bytes, base + offsets[a], base + offsets[a] + lengths[a], bytes, base + offsets[b], base + offsets[b] + lengths[b])
}
