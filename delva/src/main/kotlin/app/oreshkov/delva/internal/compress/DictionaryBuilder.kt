package app.oreshkov.delva.internal.compress

import app.oreshkov.delva.internal.bytes.LittleEndian

/**
 * Builds a deflate preset dictionary from sampled fragment payloads, after the "cover" method
 * of zstd's dictionary trainer (Liao, Petri, Moffat, Wirth, "Effective construction of relative Lempel-Ziv
 * dictionaries", 2016) with a fingerprint table instead of a suffix array:
 *
 * 1. Every [dmerBytes]-byte substring (dmer) of a sample is counted in a table of `2^⌈log2 sampleBytes⌉` slots
 *    (32-bit fingerprints, linear probing): a slot counts the **samples** that contain its dmer, so content shared
 *    across documents scores, repetition inside one fragment doesn't (deflate finds that without a dictionary). Dmers
 *    that find no free slot nearby aren't counted, which only happens when most dmers are distinct noise.
 * 2. The sample bytes are cut into epochs, one per [segmentBytes] of dictionary. In each epoch the window of
 *    [segmentBytes] with the highest sum of counts over its distinct dmers is chosen, trimmed to its first and last
 *    counted dmer, and the counts of its dmers are zeroed so later segments add new content. Epochs are visited again
 *    while the dictionary has room and segments are found.
 * 3. Segments are concatenated in ascending score, so the most valuable bytes are last: deflate encodes nearer matches
 *    in fewer bits, and it only sees the last 32 KiB.
 *
 * Memory while building: the sample plus 16 to 28 bytes per sample byte (fingerprints, counts, marks, dmer slots).
 * Not thread-safe.
 */
internal class DictionaryBuilder(
    val maxSampleBytes: Int,
    private val dmerBytes: Int = DEFAULT_DMER_BYTES,
    private val segmentBytes: Int = DEFAULT_SEGMENT_BYTES,
) {
    private val sample = ByteArray(maxSampleBytes)
    private var starts = IntArray(64)
    private var sampleCount = 0

    /** Sample bytes held. */
    var sampleBytes: Int = 0
        private set

    init {
        require(dmerBytes in 4..8 && segmentBytes >= 2 * dmerBytes && maxSampleBytes >= 0)
    }

    /** Adds `bytes[offset, offset + length)` as one sample, truncated to the room left; returns the bytes taken. */
    fun add(bytes: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, maxSampleBytes - sampleBytes)
        if (n < dmerBytes) return 0
        if (sampleCount == starts.size) starts = starts.copyOf(sampleCount * 2)
        starts[sampleCount++] = sampleBytes
        System.arraycopy(bytes, offset, sample, sampleBytes, n)
        sampleBytes += n
        return n
    }

    fun clear() {
        sampleBytes = 0
        sampleCount = 0
    }

    /** Samples as `(offset, length)` into [sampleArray], for evaluating a dictionary on them. */
    val sampleArray: ByteArray get() = sample
    val count: Int get() = sampleCount

    fun sampleOffset(i: Int): Int = starts[i]

    fun sampleLength(i: Int): Int = (if (i + 1 < sampleCount) starts[i + 1] else sampleBytes) - starts[i]

    /** Whether sample [i] is left out of a build with `holdoutEvery` [every]. */
    fun isHeldOut(i: Int, every: Int): Boolean = every > 0 && i % every == every - 1

    /**
     * A dictionary of at most [maxDictionaryBytes] from the samples; empty when they share nothing. With [holdoutEvery]
     * n > 0, every n-th sample ([isHeldOut]) is left out, so the dictionary can be judged on unseen samples.
     */
    fun build(maxDictionaryBytes: Int, holdoutEvery: Int = 0): ByteArray {
        require(maxDictionaryBytes > 0)
        val size = sampleBytes
        if (size < segmentBytes) return ByteArray(0)
        val bits = 32 - Integer.numberOfLeadingZeros(maxOf(size, 1024) - 1)
        val mask = (1 shl bits) - 1
        val counts = IntArray(1 shl bits)
        val marks = IntArray(1 shl bits)
        val fingerprints = IntArray(1 shl bits)
        // The slot of the dmer starting at each position, or -1 where it would cross into the next sample.
        val slots = IntArray(size)
        for (s in 0 until sampleCount) {
            val from = starts[s]
            val to = from + sampleLength(s)
            val stamp = s + 1
            val heldOut = isHeldOut(s, holdoutEvery)
            for (p in from until to) {
                if (heldOut || p + dmerBytes > to) {
                    slots[p] = -1
                    continue
                }
                val slot = slotOf(p, bits, mask, fingerprints)
                slots[p] = slot
                if (slot < 0) continue
                if (marks[slot] != stamp) {
                    marks[slot] = stamp
                    counts[slot]++
                }
            }
        }
        marks.fill(0)
        val active = marks

        val epochs = maxOf(1, minOf(maxDictionaryBytes / segmentBytes, size / segmentBytes))
        val epochBytes = size / epochs
        val segments = ArrayList<LongArray>() // (start, end, score)
        var total = 0
        var round = 0
        while (total < maxDictionaryBytes && round < MAX_ROUNDS) {
            var found = false
            for (e in 0 until epochs) {
                if (total >= maxDictionaryBytes) break
                val from = e * epochBytes
                val to = if (e == epochs - 1) size else from + epochBytes
                val best = bestWindow(from, to, slots, counts, active)
                if (best < 0) continue
                // Trim to the first and last dmer that still counts.
                var start = best
                val windowEnd = minOf(best + segmentBytes, to)
                var lastDmer = windowEnd - dmerBytes
                while (start <= lastDmer && (slots[start] < 0 || counts[slots[start]] <= 1)) start++
                while (lastDmer >= start && (slots[lastDmer] < 0 || counts[slots[lastDmer]] <= 1)) lastDmer--
                if (lastDmer < start) continue
                val end = minOf(lastDmer + dmerBytes, to)
                var score = 0L
                for (p in start..lastDmer) {
                    val slot = slots[p]
                    if (slot >= 0 && counts[slot] > 0) {
                        score += counts[slot]
                        counts[slot] = 0
                    }
                }
                val length = minOf(end - start, maxDictionaryBytes - total)
                segments += longArrayOf(start.toLong(), (start + length).toLong(), score)
                total += length
                found = true
            }
            if (!found) break
            round++
        }
        segments.sortBy { it[2] }
        val out = ByteArray(total)
        var at = 0
        for (s in segments) {
            val length = (s[1] - s[0]).toInt()
            System.arraycopy(sample, s[0].toInt(), out, at, length)
            at += length
        }
        return out
    }

    /**
     * Start of the window of [segmentBytes] in `[from, to)` whose distinct dmers have the largest count sum above
     * one sample each, or -1 if none has any.
     */
    private fun bestWindow(from: Int, to: Int, slots: IntArray, counts: IntArray, active: IntArray): Int {
        if (to - from < dmerBytes) return -1
        val dmersPerWindow = segmentBytes - dmerBytes + 1
        var score = 0L
        var best = -1
        var bestScore = 0L
        var p = from
        val lastStart = to - dmerBytes
        while (p <= lastStart) {
            val entering = slots[p]
            if (entering >= 0 && counts[entering] > 1 && active[entering]++ == 0) score += counts[entering]
            val windowStart = p - dmersPerWindow + 1
            if (windowStart > from) {
                val leaving = slots[windowStart - 1]
                if (leaving >= 0 && counts[leaving] > 1 && --active[leaving] == 0) score -= counts[leaving]
            }
            if (score > bestScore) {
                bestScore = score
                best = maxOf(windowStart, from)
            }
            p++
        }
        // Clear the marks of the last window.
        for (q in maxOf(from, lastStart - dmersPerWindow + 1)..lastStart) {
            val slot = slots[q]
            if (slot >= 0) active[slot] = 0
        }
        return best
    }

    /**
     * The table slot of the dmer at [p]: its fingerprint's slot, found by linear probing from its hash, or claimed when
     * free; -1 when [MAX_PROBES] slots are taken by other dmers (the table is full of distinct noise).
     */
    private fun slotOf(p: Int, bits: Int, mask: Int, fingerprints: IntArray): Int {
        val word = if (p + 8 <= sample.size) LittleEndian.getLong(sample, p) else tailWord(p)
        val masked = if (dmerBytes == 8) word else word and ((1L shl (8 * dmerBytes)) - 1)
        var slot = ((masked * -0x61c8864680b583ebL) ushr (64 - bits)).toInt() and mask
        val fingerprint = ((masked * -0x3d4d51c2d82b14b1L) ushr 32).toInt() or 1
        repeat(MAX_PROBES) {
            val held = fingerprints[slot]
            if (held == fingerprint) return slot
            if (held == 0) {
                fingerprints[slot] = fingerprint
                return slot
            }
            slot = (slot + 1) and mask
        }
        return -1
    }

    private fun tailWord(p: Int): Long {
        var w = 0L
        for (i in 0 until minOf(8, sample.size - p)) w = w or ((sample[p + i].toLong() and 0xFF) shl (8 * i))
        return w
    }

    companion object {
        const val DEFAULT_DMER_BYTES: Int = 8
        const val DEFAULT_SEGMENT_BYTES: Int = 256
        private const val MAX_ROUNDS = 4
        private const val MAX_PROBES = 16
    }
}
