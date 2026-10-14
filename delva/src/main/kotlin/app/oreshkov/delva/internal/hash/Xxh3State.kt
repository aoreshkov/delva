/*
 * Streaming XXH3: a Kotlin port of XXH3_update / XXH3_digest from xxHash (xxhash.h, version 0.8).
 *
 * xxHash Library
 * Copyright (c) 2012-2021 Yann Collet
 * All rights reserved.
 * BSD 2-Clause License (https://www.opensource.org/licenses/bsd-license.php). See NOTICE for the full license text.
 */
package app.oreshkov.delva.internal.hash

import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.hash.Xxh3.LAST_STRIPE_OFFSET
import app.oreshkov.delva.internal.hash.Xxh3.MERGE_HIGH_OFFSET
import app.oreshkov.delva.internal.hash.Xxh3.MERGE_LOW_OFFSET
import app.oreshkov.delva.internal.hash.Xxh3.MIDSIZE_MAX
import app.oreshkov.delva.internal.hash.Xxh3.PRIME32_1
import app.oreshkov.delva.internal.hash.Xxh3.PRIME32_2
import app.oreshkov.delva.internal.hash.Xxh3.PRIME32_3
import app.oreshkov.delva.internal.hash.Xxh3.PRIME64_1
import app.oreshkov.delva.internal.hash.Xxh3.PRIME64_2
import app.oreshkov.delva.internal.hash.Xxh3.PRIME64_3
import app.oreshkov.delva.internal.hash.Xxh3.PRIME64_4
import app.oreshkov.delva.internal.hash.Xxh3.PRIME64_5
import app.oreshkov.delva.internal.hash.Xxh3.STRIPES_PER_BLOCK
import app.oreshkov.delva.internal.hash.Xxh3.STRIPE_LEN
import java.util.Objects

/**
 * Incremental XXH3: any split of the input into [update] calls gives the same digest as the one-shot [Xxh3]
 * hash of the concatenated input. Reusable via [reset]; allocates only at construction. Not thread-safe.
 *
 * [digest64] and [digest128] don't modify the state, so more input may follow a digest.
 */
internal class Xxh3State {
    private val acc = LongArray(8)
    private val digestAcc = LongArray(8)
    private val buffer = ByteArray(BUFFER_SIZE)
    private val lastStripe = ByteArray(STRIPE_LEN)
    private val oneByte = ByteArray(1)
    private var bufferedSize = 0
    private var stripesSoFar = 0
    private var totalLength = 0L
    private var seed = 0L

    init {
        reset()
    }

    fun reset(seed: Long = 0L): Xxh3State {
        acc[0] = PRIME32_3
        acc[1] = PRIME64_1
        acc[2] = PRIME64_2
        acc[3] = PRIME64_3
        acc[4] = PRIME64_4
        acc[5] = PRIME32_2
        acc[6] = PRIME64_5
        acc[7] = PRIME32_1
        bufferedSize = 0
        stripesSoFar = 0
        totalLength = 0
        this.seed = seed
        return this
    }

    fun update(value: Int): Xxh3State {
        oneByte[0] = value.toByte()
        return update(oneByte, 0, 1)
    }

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): Xxh3State {
        Objects.checkFromIndexSize(offset, length, input.size)
        totalLength += length
        if (length <= BUFFER_SIZE - bufferedSize) {
            System.arraycopy(input, offset, buffer, bufferedSize, length)
            bufferedSize += length
            return this
        }

        val end = offset + length
        var p = offset
        if (bufferedSize > 0) {
            val loadSize = BUFFER_SIZE - bufferedSize
            System.arraycopy(input, p, buffer, bufferedSize, loadSize)
            p += loadSize
            stripesSoFar = consumeStripes(acc, buffer, 0, BUFFER_SIZE / STRIPE_LEN, stripesSoFar, seed)
            bufferedSize = 0
        }
        if (end - p > BUFFER_SIZE) {
            val stripes = (end - 1 - p) / STRIPE_LEN
            stripesSoFar = consumeStripes(acc, input, p, stripes, stripesSoFar, seed)
            p += stripes * STRIPE_LEN
            // Keep the last consumed stripe: the digest may need it for its final stripe.
            System.arraycopy(input, p - STRIPE_LEN, buffer, BUFFER_SIZE - STRIPE_LEN, STRIPE_LEN)
        }
        // At least one byte always remains buffered.
        System.arraycopy(input, p, buffer, 0, end - p)
        bufferedSize = end - p
        return this
    }

    fun digest64(): Long {
        if (totalLength <= MIDSIZE_MAX) return Xxh3.hash64(buffer, 0, totalLength.toInt(), seed)
        digestLong()
        val a = digestAcc
        return Xxh3.mergeAccs(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], MERGE_LOW_OFFSET, seed, totalLength * PRIME64_1)
    }

    fun digest128(out: LongArray, outIndex: Int) {
        if (totalLength <= MIDSIZE_MAX) {
            Xxh3.hash128(buffer, 0, totalLength.toInt(), seed, out, outIndex)
            return
        }
        Objects.checkFromIndexSize(outIndex, 2, out.size)
        digestLong()
        val a = digestAcc
        out[outIndex] = Xxh3.mergeAccs(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], MERGE_LOW_OFFSET, seed, totalLength * PRIME64_1)
        out[outIndex + 1] = Xxh3.mergeAccs(
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], MERGE_HIGH_OFFSET, seed, (totalLength * PRIME64_2).inv(),
        )
    }

    private fun digestLong() {
        System.arraycopy(acc, 0, digestAcc, 0, 8)
        val source: ByteArray
        val sourceOffset: Int
        if (bufferedSize >= STRIPE_LEN) {
            val stripes = (bufferedSize - 1) / STRIPE_LEN
            consumeStripes(digestAcc, buffer, 0, stripes, stripesSoFar, seed)
            source = buffer
            sourceOffset = bufferedSize - STRIPE_LEN
        } else {
            val catchup = STRIPE_LEN - bufferedSize
            System.arraycopy(buffer, BUFFER_SIZE - catchup, lastStripe, 0, catchup)
            System.arraycopy(buffer, 0, lastStripe, catchup, bufferedSize)
            source = lastStripe
            sourceOffset = 0
        }
        accumulateLastStripe(digestAcc, source, sourceOffset, seed)
    }

    private companion object {
        const val BUFFER_SIZE = 256
        const val M32 = 0xFFFF_FFFFL

        /** `XXH3_consumeStripes`; returns the new count of stripes consumed in the current block. */
        fun consumeStripes(acc: LongArray, input: ByteArray, offset: Int, stripes: Int, stripesSoFar: Int, seed: Long): Int {
            var remaining = stripes
            var p = offset
            var wordStart = stripesSoFar
            var soFar = stripesSoFar
            if (remaining >= STRIPES_PER_BLOCK - soFar) {
                var thisIteration = STRIPES_PER_BLOCK - soFar
                do {
                    accumulate(acc, input, p, thisIteration, wordStart, seed)
                    scramble(acc, seed)
                    p += thisIteration * STRIPE_LEN
                    remaining -= thisIteration
                    thisIteration = STRIPES_PER_BLOCK
                    wordStart = 0
                } while (remaining >= STRIPES_PER_BLOCK)
                soFar = 0
            }
            if (remaining > 0) {
                accumulate(acc, input, p, remaining, wordStart, seed)
                soFar += remaining
            }
            return soFar
        }

        fun accumulate(acc: LongArray, input: ByteArray, offset: Int, stripes: Int, wordStart: Int, seed: Long) {
            var p = offset
            for (s in 0 until stripes) {
                val w = wordStart + s
                for (lane in 0 until 8) {
                    val data = LittleEndian.getLong(input, p + lane * 8)
                    val key = data xor Xxh3.secretWord(w + lane, seed)
                    acc[lane xor 1] += data
                    acc[lane] += (key and M32) * (key ushr 32)
                }
                p += STRIPE_LEN
            }
        }

        fun scramble(acc: LongArray, seed: Long) {
            for (lane in 0 until 8) {
                val a = acc[lane]
                acc[lane] = ((a xor (a ushr 47)) xor Xxh3.secretWord(STRIPES_PER_BLOCK + lane, seed)) * PRIME32_1
            }
        }

        fun accumulateLastStripe(acc: LongArray, input: ByteArray, offset: Int, seed: Long) {
            for (lane in 0 until 8) {
                val data = LittleEndian.getLong(input, offset + lane * 8)
                val key = data xor Xxh3.secret64(LAST_STRIPE_OFFSET + lane * 8, seed)
                acc[lane xor 1] += data
                acc[lane] += (key and M32) * (key ushr 32)
            }
        }
    }
}
