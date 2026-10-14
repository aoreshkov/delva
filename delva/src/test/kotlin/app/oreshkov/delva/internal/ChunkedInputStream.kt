package app.oreshkov.delva.internal

import java.io.InputStream

/** The concatenation of [chunks], generated while it's read: documents larger than the heap. */
internal class ChunkedInputStream(chunks: Sequence<String>) : InputStream() {
    private val iterator = chunks.iterator()
    private var current = ByteArray(0)
    private var position = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (position == current.size) {
            if (!iterator.hasNext()) return -1
            current = iterator.next().toByteArray()
            position = 0
        }
        val n = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, n)
        position += n
        return n
    }
}
