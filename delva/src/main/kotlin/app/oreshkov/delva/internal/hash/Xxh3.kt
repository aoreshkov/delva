/*
 * XXH3-64 and XXH3-128: a Kotlin port of the scalar code path of XXH3 from xxHash (xxhash.h, version 0.8).
 *
 * xxHash Library
 * Copyright (c) 2012-2021 Yann Collet
 * All rights reserved.
 * BSD 2-Clause License (https://www.opensource.org/licenses/bsd-license.php). See NOTICE for the full license text.
 */
package app.oreshkov.delva.internal.hash

import app.oreshkov.delva.internal.bytes.LittleEndian
import java.lang.Math.unsignedMultiplyHigh
import java.util.Objects

/**
 * One-shot XXH3 hashes with the default secret, unseeded or seeded, bit-compatible with the reference implementation.
 * Hash values are persisted, so this code must never change its output; golden and reference-vector tests
 * freeze it.
 *
 * A seeded hash uses the secret derived from the seed. It isn't materialized: its 64-bit words are the default
 * secret's words plus or minus the seed, so they're computed on the fly and nothing is allocated.
 *
 * 128-bit results are written to caller-provided slots: `out[outIndex]` = low 64 bits, `out[outIndex + 1]` = high.
 */
internal object Xxh3 {
    fun hash64(input: ByteArray, offset: Int = 0, length: Int = input.size - offset, seed: Long = 0L): Long {
        Objects.checkFromIndexSize(offset, length, input.size)
        return when {
            length <= 16 -> len0To16x64(input, offset, length, seed)
            length <= 128 -> len17To128x64(input, offset, length, seed)
            length <= MIDSIZE_MAX -> len129To240x64(input, offset, length, seed)
            else -> hashLong(input, offset, length, seed, null, 0)
        }
    }

    fun hash128(input: ByteArray, offset: Int, length: Int, seed: Long, out: LongArray, outIndex: Int) {
        Objects.checkFromIndexSize(offset, length, input.size)
        Objects.checkFromIndexSize(outIndex, 2, out.size)
        when {
            length <= 16 -> len0To16x128(input, offset, length, seed, out, outIndex)
            length <= 128 -> len17To128x128(input, offset, length, seed, out, outIndex)
            length <= MIDSIZE_MAX -> len129To240x128(input, offset, length, seed, out, outIndex)
            else -> hashLong(input, offset, length, seed, out, outIndex)
        }
    }

    // ---- constants ------------------------------------------------------------------------------------------------

    internal const val MIDSIZE_MAX = 240
    internal const val STRIPE_LEN = 64
    internal const val STRIPES_PER_BLOCK = 16 // (SECRET_SIZE - STRIPE_LEN) / 8
    private const val SECRET_SIZE = 192
    private const val SECRET_SIZE_MIN = 136
    private const val SECRET_LASTACC_START = 7
    private const val SECRET_MERGEACCS_START = 11
    private const val MIDSIZE_STARTOFFSET = 3
    private const val MIDSIZE_LASTOFFSET = 17
    private const val M32 = 0xFFFF_FFFFL

    internal val PRIME32_1 = 0x9E3779B1L
    internal val PRIME32_2 = 0x85EBCA77L
    internal val PRIME32_3 = 0xC2B2AE3DL
    internal val PRIME64_1 = 0x9E3779B185EBCA87uL.toLong()
    internal val PRIME64_2 = 0xC2B2AE3D27D4EB4FuL.toLong()
    internal val PRIME64_3 = 0x165667B19E3779F9uL.toLong()
    internal val PRIME64_4 = 0x85EBCA77C2B2AE63uL.toLong()
    internal val PRIME64_5 = 0x27D4EB2F165667C5uL.toLong()
    private val PRIME_MX1 = 0x165667919E3779F9uL.toLong()
    private val PRIME_MX2 = 0x9FB21C651E98DF25uL.toLong()

    private val K_SECRET: ByteArray = intArrayOf(
        0xb8, 0xfe, 0x6c, 0x39, 0x23, 0xa4, 0x4b, 0xbe, 0x7c, 0x01, 0x81, 0x2c, 0xf7, 0x21, 0xad, 0x1c,
        0xde, 0xd4, 0x6d, 0xe9, 0x83, 0x90, 0x97, 0xdb, 0x72, 0x40, 0xa4, 0xa4, 0xb7, 0xb3, 0x67, 0x1f,
        0xcb, 0x79, 0xe6, 0x4e, 0xcc, 0xc0, 0xe5, 0x78, 0x82, 0x5a, 0xd0, 0x7d, 0xcc, 0xff, 0x72, 0x21,
        0xb8, 0x08, 0x46, 0x74, 0xf7, 0x43, 0x24, 0x8e, 0xe0, 0x35, 0x90, 0xe6, 0x81, 0x3a, 0x26, 0x4c,
        0x3c, 0x28, 0x52, 0xbb, 0x91, 0xc3, 0x00, 0xcb, 0x88, 0xd0, 0x65, 0x8b, 0x1b, 0x53, 0x2e, 0xa3,
        0x71, 0x64, 0x48, 0x97, 0xa2, 0x0d, 0xf9, 0x4e, 0x38, 0x19, 0xef, 0x46, 0xa9, 0xde, 0xac, 0xd8,
        0xa8, 0xfa, 0x76, 0x3f, 0xe3, 0x9c, 0x34, 0x3f, 0xf9, 0xdc, 0xbb, 0xc7, 0xc7, 0x0b, 0x4f, 0x1d,
        0x8a, 0x51, 0xe0, 0x4b, 0xcd, 0xb4, 0x59, 0x31, 0xc8, 0x9f, 0x7e, 0xc9, 0xd9, 0x78, 0x73, 0x64,
        0xea, 0xc5, 0xac, 0x83, 0x34, 0xd3, 0xeb, 0xc3, 0xc5, 0x81, 0xa0, 0xff, 0xfa, 0x13, 0x63, 0xeb,
        0x17, 0x0d, 0xdd, 0x51, 0xb7, 0xf0, 0xda, 0x49, 0xd3, 0x16, 0x55, 0x26, 0x29, 0xd4, 0x68, 0x9e,
        0x2b, 0x16, 0xbe, 0x58, 0x7d, 0x47, 0xa1, 0xfc, 0x8f, 0xf8, 0xb8, 0xd1, 0x7a, 0xd0, 0x31, 0xce,
        0x45, 0xcb, 0x3a, 0x8f, 0x95, 0x16, 0x04, 0x28, 0xaf, 0xd7, 0xfb, 0xca, 0xbb, 0x4b, 0x40, 0x7e,
    ).let { ints -> ByteArray(ints.size) { ints[it].toByte() } }

    /** The default secret as 24 little-endian words (offsets 0, 8, … 184). */
    private val K_WORDS: LongArray = LongArray(SECRET_SIZE / 8) { LittleEndian.getLong(K_SECRET, it * 8) }

    // ---- primitives -----------------------------------------------------------------------------------------------

    private fun read64(bytes: ByteArray, index: Int): Long = LittleEndian.getLong(bytes, index)

    private fun read32(bytes: ByteArray, index: Int): Long = LittleEndian.getInt(bytes, index).toLong() and M32

    private fun mul128Fold64(lhs: Long, rhs: Long): Long = (lhs * rhs) xor unsignedMultiplyHigh(lhs, rhs)

    private fun xxh64Avalanche(hash: Long): Long {
        var h = hash
        h = h xor (h ushr 33)
        h *= PRIME64_2
        h = h xor (h ushr 29)
        h *= PRIME64_3
        return h xor (h ushr 32)
    }

    private fun avalanche(hash: Long): Long {
        var h = hash
        h = h xor (h ushr 37)
        h *= PRIME_MX1
        return h xor (h ushr 32)
    }

    private fun rrmxmx(hash: Long, length: Int): Long {
        var h = hash
        h = h xor (java.lang.Long.rotateLeft(h, 49) xor java.lang.Long.rotateLeft(h, 24))
        h *= PRIME_MX2
        h = h xor ((h ushr 35) + length)
        h *= PRIME_MX2
        return h xor (h ushr 28)
    }

    private fun mix16B(input: ByteArray, inputIndex: Int, secretIndex: Int, seed: Long): Long =
        mul128Fold64(
            read64(input, inputIndex) xor (read64(K_SECRET, secretIndex) + seed),
            read64(input, inputIndex + 8) xor (read64(K_SECRET, secretIndex + 8) - seed),
        )

    /** Word `k` of the secret derived from [seed]: even words get `+seed`, odd words `-seed`. */
    internal fun secretWord(k: Int, seed: Long): Long = K_WORDS[k] + (if (k and 1 == 0) seed else -seed)

    /** Little-endian 64-bit read at any [offset] of the secret derived from [seed]. */
    internal fun secret64(offset: Int, seed: Long): Long {
        val k = offset ushr 3
        val shift = (offset and 7) * 8
        return if (shift == 0) {
            secretWord(k, seed)
        } else {
            (secretWord(k, seed) ushr shift) or (secretWord(k + 1, seed) shl (64 - shift))
        }
    }

    // ---- 64-bit, up to 240 bytes ----------------------------------------------------------------------------------

    private fun len0To16x64(input: ByteArray, offset: Int, length: Int, seed: Long): Long = when {
        length > 8 -> {
            val bitflip1 = (read64(K_SECRET, 24) xor read64(K_SECRET, 32)) + seed
            val bitflip2 = (read64(K_SECRET, 40) xor read64(K_SECRET, 48)) - seed
            val inputLo = read64(input, offset) xor bitflip1
            val inputHi = read64(input, offset + length - 8) xor bitflip2
            avalanche(length + java.lang.Long.reverseBytes(inputLo) + inputHi + mul128Fold64(inputLo, inputHi))
        }
        length >= 4 -> {
            val s = seed xor ((Integer.reverseBytes(seed.toInt()).toLong() and M32) shl 32)
            val input1 = read32(input, offset)
            val input2 = read32(input, offset + length - 4)
            val bitflip = (read64(K_SECRET, 8) xor read64(K_SECRET, 16)) - s
            rrmxmx((input2 + (input1 shl 32)) xor bitflip, length)
        }
        length > 0 -> {
            val bitflip = (read32(K_SECRET, 0) xor read32(K_SECRET, 4)) + seed
            xxh64Avalanche(combined1To3(input, offset, length) xor bitflip)
        }
        else -> xxh64Avalanche(seed xor (read64(K_SECRET, 56) xor read64(K_SECRET, 64)))
    }

    private fun combined1To3(input: ByteArray, offset: Int, length: Int): Long {
        val c1 = input[offset].toInt() and 0xFF
        val c2 = input[offset + (length shr 1)].toInt() and 0xFF
        val c3 = input[offset + length - 1].toInt() and 0xFF
        return ((c1 shl 16) or (c2 shl 24) or c3 or (length shl 8)).toLong() and M32
    }

    private fun len17To128x64(input: ByteArray, offset: Int, length: Int, seed: Long): Long {
        var acc = length * PRIME64_1
        val end = offset + length
        if (length > 32) {
            if (length > 64) {
                if (length > 96) {
                    acc += mix16B(input, offset + 48, 96, seed)
                    acc += mix16B(input, end - 64, 112, seed)
                }
                acc += mix16B(input, offset + 32, 64, seed)
                acc += mix16B(input, end - 48, 80, seed)
            }
            acc += mix16B(input, offset + 16, 32, seed)
            acc += mix16B(input, end - 32, 48, seed)
        }
        acc += mix16B(input, offset, 0, seed)
        acc += mix16B(input, end - 16, 16, seed)
        return avalanche(acc)
    }

    private fun len129To240x64(input: ByteArray, offset: Int, length: Int, seed: Long): Long {
        var acc = length * PRIME64_1
        for (i in 0 until 8) acc += mix16B(input, offset + 16 * i, 16 * i, seed)
        var accEnd = mix16B(input, offset + length - 16, SECRET_SIZE_MIN - MIDSIZE_LASTOFFSET, seed)
        acc = avalanche(acc)
        val rounds = length / 16
        for (i in 8 until rounds) {
            accEnd += mix16B(input, offset + 16 * i, 16 * (i - 8) + MIDSIZE_STARTOFFSET, seed)
        }
        return avalanche(acc + accEnd)
    }

    // ---- 128-bit, up to 240 bytes ---------------------------------------------------------------------------------

    private fun len0To16x128(input: ByteArray, offset: Int, length: Int, seed: Long, out: LongArray, outIndex: Int) {
        when {
            length > 8 -> {
                val bitflipL = (read64(K_SECRET, 32) xor read64(K_SECRET, 40)) - seed
                val bitflipH = (read64(K_SECRET, 48) xor read64(K_SECRET, 56)) + seed
                val inputLo = read64(input, offset)
                var inputHi = read64(input, offset + length - 8)
                val mulIn = inputLo xor inputHi xor bitflipL
                var mLo = mulIn * PRIME64_1
                var mHi = unsignedMultiplyHigh(mulIn, PRIME64_1)
                mLo += (length - 1).toLong() shl 54
                inputHi = inputHi xor bitflipH
                mHi += inputHi + (inputHi and M32) * (PRIME32_2 - 1)
                mLo = mLo xor java.lang.Long.reverseBytes(mHi)
                val hLo = mLo * PRIME64_2
                val hHi = unsignedMultiplyHigh(mLo, PRIME64_2) + mHi * PRIME64_2
                out[outIndex] = avalanche(hLo)
                out[outIndex + 1] = avalanche(hHi)
            }
            length >= 4 -> {
                val s = seed xor ((Integer.reverseBytes(seed.toInt()).toLong() and M32) shl 32)
                val inputLo = read32(input, offset)
                val inputHi = read32(input, offset + length - 4)
                val bitflip = (read64(K_SECRET, 16) xor read64(K_SECRET, 24)) + s
                val keyed = (inputLo + (inputHi shl 32)) xor bitflip
                val multiplier = PRIME64_1 + (length.toLong() shl 2)
                var mLo = keyed * multiplier
                var mHi = unsignedMultiplyHigh(keyed, multiplier)
                mHi += mLo shl 1
                mLo = mLo xor (mHi ushr 3)
                mLo = mLo xor (mLo ushr 35)
                mLo *= PRIME_MX2
                mLo = mLo xor (mLo ushr 28)
                out[outIndex] = mLo
                out[outIndex + 1] = avalanche(mHi)
            }
            length > 0 -> {
                val combinedL = combined1To3(input, offset, length)
                val combinedH = Integer.rotateLeft(Integer.reverseBytes(combinedL.toInt()), 13).toLong() and M32
                val bitflipL = (read32(K_SECRET, 0) xor read32(K_SECRET, 4)) + seed
                val bitflipH = (read32(K_SECRET, 8) xor read32(K_SECRET, 12)) - seed
                out[outIndex] = xxh64Avalanche(combinedL xor bitflipL)
                out[outIndex + 1] = xxh64Avalanche(combinedH xor bitflipH)
            }
            else -> {
                out[outIndex] = xxh64Avalanche(seed xor (read64(K_SECRET, 64) xor read64(K_SECRET, 72)))
                out[outIndex + 1] = xxh64Avalanche(seed xor (read64(K_SECRET, 80) xor read64(K_SECRET, 88)))
            }
        }
    }

    /** `XXH128_mix32B` with the accumulator held in `out[outIndex]` (low) and `out[outIndex + 1]` (high). */
    private fun mix32B(out: LongArray, outIndex: Int, input: ByteArray, input1: Int, input2: Int, secretIndex: Int, seed: Long) {
        var lo = out[outIndex]
        var hi = out[outIndex + 1]
        lo += mix16B(input, input1, secretIndex, seed)
        lo = lo xor (read64(input, input2) + read64(input, input2 + 8))
        hi += mix16B(input, input2, secretIndex + 16, seed)
        hi = hi xor (read64(input, input1) + read64(input, input1 + 8))
        out[outIndex] = lo
        out[outIndex + 1] = hi
    }

    private fun finish128(out: LongArray, outIndex: Int, length: Int, seed: Long) {
        val lo = out[outIndex]
        val hi = out[outIndex + 1]
        val hLo = lo + hi
        val hHi = lo * PRIME64_1 + hi * PRIME64_4 + (length - seed) * PRIME64_2
        out[outIndex] = avalanche(hLo)
        out[outIndex + 1] = -avalanche(hHi)
    }

    private fun len17To128x128(input: ByteArray, offset: Int, length: Int, seed: Long, out: LongArray, outIndex: Int) {
        out[outIndex] = length * PRIME64_1
        out[outIndex + 1] = 0
        val end = offset + length
        if (length > 32) {
            if (length > 64) {
                if (length > 96) mix32B(out, outIndex, input, offset + 48, end - 64, 96, seed)
                mix32B(out, outIndex, input, offset + 32, end - 48, 64, seed)
            }
            mix32B(out, outIndex, input, offset + 16, end - 32, 32, seed)
        }
        mix32B(out, outIndex, input, offset, end - 16, 0, seed)
        finish128(out, outIndex, length, seed)
    }

    private fun len129To240x128(input: ByteArray, offset: Int, length: Int, seed: Long, out: LongArray, outIndex: Int) {
        out[outIndex] = length * PRIME64_1
        out[outIndex + 1] = 0
        var i = 32
        while (i < 160) {
            mix32B(out, outIndex, input, offset + i - 32, offset + i - 16, i - 32, seed)
            i += 32
        }
        out[outIndex] = avalanche(out[outIndex])
        out[outIndex + 1] = avalanche(out[outIndex + 1])
        i = 160
        while (i <= length) {
            mix32B(out, outIndex, input, offset + i - 32, offset + i - 16, MIDSIZE_STARTOFFSET + i - 160, seed)
            i += 32
        }
        mix32B(
            out, outIndex, input, offset + length - 16, offset + length - 32,
            SECRET_SIZE_MIN - MIDSIZE_LASTOFFSET - 16, -seed,
        )
        finish128(out, outIndex, length, seed)
    }

    // ---- more than 240 bytes --------------------------------------------------------------------------------------

    /**
     * Long-input hash with the eight accumulator lanes in locals (no allocation). Returns the 64-bit hash, or writes
     * the 128-bit hash to [out] when it's non-null. [Xxh3State] has the same loop over an array for streaming.
     */
    private fun hashLong(input: ByteArray, offset: Int, length: Int, seed: Long, out: LongArray?, outIndex: Int): Long {
        var a0 = PRIME32_3
        var a1 = PRIME64_1
        var a2 = PRIME64_2
        var a3 = PRIME64_3
        var a4 = PRIME64_4
        var a5 = PRIME32_2
        var a6 = PRIME64_5
        var a7 = PRIME32_1

        // Every full block is 16 stripes followed by a scramble; a final partial block has at most 15 stripes, so it
        // never scrambles. The last stripe (the input's final 64 bytes) is accumulated separately below.
        val blocks = (length - 1) / (STRIPE_LEN * STRIPES_PER_BLOCK)
        val stripes = blocks * STRIPES_PER_BLOCK + ((length - 1) - blocks * STRIPE_LEN * STRIPES_PER_BLOCK) / STRIPE_LEN
        var p = offset
        var n = 0
        for (s in 0 until stripes) {
            val sEven = if (n and 1 == 0) seed else -seed
            val sOdd = -sEven
            val d0 = read64(input, p)
            val d1 = read64(input, p + 8)
            val d2 = read64(input, p + 16)
            val d3 = read64(input, p + 24)
            val d4 = read64(input, p + 32)
            val d5 = read64(input, p + 40)
            val d6 = read64(input, p + 48)
            val d7 = read64(input, p + 56)
            val k0 = d0 xor (K_WORDS[n] + sEven)
            val k1 = d1 xor (K_WORDS[n + 1] + sOdd)
            val k2 = d2 xor (K_WORDS[n + 2] + sEven)
            val k3 = d3 xor (K_WORDS[n + 3] + sOdd)
            val k4 = d4 xor (K_WORDS[n + 4] + sEven)
            val k5 = d5 xor (K_WORDS[n + 5] + sOdd)
            val k6 = d6 xor (K_WORDS[n + 6] + sEven)
            val k7 = d7 xor (K_WORDS[n + 7] + sOdd)
            a0 += d1 + (k0 and M32) * (k0 ushr 32)
            a1 += d0 + (k1 and M32) * (k1 ushr 32)
            a2 += d3 + (k2 and M32) * (k2 ushr 32)
            a3 += d2 + (k3 and M32) * (k3 ushr 32)
            a4 += d5 + (k4 and M32) * (k4 ushr 32)
            a5 += d4 + (k5 and M32) * (k5 ushr 32)
            a6 += d7 + (k6 and M32) * (k6 ushr 32)
            a7 += d6 + (k7 and M32) * (k7 ushr 32)
            p += STRIPE_LEN
            if (++n == STRIPES_PER_BLOCK) {
                n = 0
                val sw = STRIPES_PER_BLOCK // scramble key words 16..23
                a0 = ((a0 xor (a0 ushr 47)) xor secretWord(sw, seed)) * PRIME32_1
                a1 = ((a1 xor (a1 ushr 47)) xor secretWord(sw + 1, seed)) * PRIME32_1
                a2 = ((a2 xor (a2 ushr 47)) xor secretWord(sw + 2, seed)) * PRIME32_1
                a3 = ((a3 xor (a3 ushr 47)) xor secretWord(sw + 3, seed)) * PRIME32_1
                a4 = ((a4 xor (a4 ushr 47)) xor secretWord(sw + 4, seed)) * PRIME32_1
                a5 = ((a5 xor (a5 ushr 47)) xor secretWord(sw + 5, seed)) * PRIME32_1
                a6 = ((a6 xor (a6 ushr 47)) xor secretWord(sw + 6, seed)) * PRIME32_1
                a7 = ((a7 xor (a7 ushr 47)) xor secretWord(sw + 7, seed)) * PRIME32_1
            }
        }

        // Last stripe, keyed from an offset that isn't a multiple of 8.
        p = offset + length - STRIPE_LEN
        val ls = SECRET_SIZE - STRIPE_LEN - SECRET_LASTACC_START
        val d0 = read64(input, p)
        val d1 = read64(input, p + 8)
        val d2 = read64(input, p + 16)
        val d3 = read64(input, p + 24)
        val d4 = read64(input, p + 32)
        val d5 = read64(input, p + 40)
        val d6 = read64(input, p + 48)
        val d7 = read64(input, p + 56)
        val k0 = d0 xor secret64(ls, seed)
        val k1 = d1 xor secret64(ls + 8, seed)
        val k2 = d2 xor secret64(ls + 16, seed)
        val k3 = d3 xor secret64(ls + 24, seed)
        val k4 = d4 xor secret64(ls + 32, seed)
        val k5 = d5 xor secret64(ls + 40, seed)
        val k6 = d6 xor secret64(ls + 48, seed)
        val k7 = d7 xor secret64(ls + 56, seed)
        a0 += d1 + (k0 and M32) * (k0 ushr 32)
        a1 += d0 + (k1 and M32) * (k1 ushr 32)
        a2 += d3 + (k2 and M32) * (k2 ushr 32)
        a3 += d2 + (k3 and M32) * (k3 ushr 32)
        a4 += d5 + (k4 and M32) * (k4 ushr 32)
        a5 += d4 + (k5 and M32) * (k5 ushr 32)
        a6 += d7 + (k6 and M32) * (k6 ushr 32)
        a7 += d6 + (k7 and M32) * (k7 ushr 32)

        val len = length.toLong()
        val low = mergeAccs(a0, a1, a2, a3, a4, a5, a6, a7, SECRET_MERGEACCS_START, seed, len * PRIME64_1)
        if (out != null) {
            out[outIndex] = low
            out[outIndex + 1] = mergeAccs(
                a0, a1, a2, a3, a4, a5, a6, a7,
                SECRET_SIZE - STRIPE_LEN - SECRET_MERGEACCS_START, seed, (len * PRIME64_2).inv(),
            )
        }
        return low
    }

    internal fun mergeAccs(
        a0: Long, a1: Long, a2: Long, a3: Long, a4: Long, a5: Long, a6: Long, a7: Long,
        secretOffset: Int, seed: Long, start: Long,
    ): Long {
        var result = start
        result += mul128Fold64(a0 xor secret64(secretOffset, seed), a1 xor secret64(secretOffset + 8, seed))
        result += mul128Fold64(a2 xor secret64(secretOffset + 16, seed), a3 xor secret64(secretOffset + 24, seed))
        result += mul128Fold64(a4 xor secret64(secretOffset + 32, seed), a5 xor secret64(secretOffset + 40, seed))
        result += mul128Fold64(a6 xor secret64(secretOffset + 48, seed), a7 xor secret64(secretOffset + 56, seed))
        return avalanche(result)
    }

    internal const val MERGE_LOW_OFFSET = SECRET_MERGEACCS_START
    internal const val MERGE_HIGH_OFFSET = SECRET_SIZE - STRIPE_LEN - SECRET_MERGEACCS_START
    internal const val LAST_STRIPE_OFFSET = SECRET_SIZE - STRIPE_LEN - SECRET_LASTACC_START
}
