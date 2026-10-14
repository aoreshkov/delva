package app.oreshkov.delva.internal.term

import app.oreshkov.delva.internal.hash.Xxh3

/**
 * Hashes of label paths (SPEC.md §15): the member keys from the document root down to a container, with every array
 * index written as the same step. A posting carries the low 16 bits of the path hash of its containing object's
 * parent, so a search whose query fixes that path can skip postings of objects elsewhere without reading them.
 *
 * States are 32 bits and are extended step by step; [NONE] stands for a container that doesn't exist (the parent of
 * the root). Stateless.
 */
internal object PathHash {
    /** The path of the root value. */
    const val ROOT: Int = 0x2F2F2F2F

    /** No container: the parent of the root, or a fragment without one. */
    const val NONE: Int = 0

    private const val MEMBER_TAG = 0x4BL shl 32
    private const val ELEMENT_TAG = 0x41L shl 32
    private val EMPTY = ByteArray(0)

    /** The path of the member `key[offset, offset + length)` of the object at [state]. */
    fun member(state: Int, key: ByteArray, offset: Int, length: Int): Int =
        Xxh3.hash64(key, offset, length, MEMBER_TAG or (state.toLong() and 0xFFFFFFFFL)).toInt()

    /** The path of any element of the array at [state]. */
    fun element(state: Int): Int = Xxh3.hash64(EMPTY, 0, 0, ELEMENT_TAG or (state.toLong() and 0xFFFFFFFFL)).toInt()

    /** What a posting stores of the state. */
    fun posting(state: Int): Int = state and 0xFFFF
}
