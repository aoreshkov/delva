package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.bytes.Varint
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.term.IndexPolicy

/**
 * Parameters of the streaming splitter.
 *
 * - [fragmentBytes] T: target fragment size. A closing container of at least T/2 becomes a SUBTREE; a container's
 *   pending members are cut into a PART when they pass T; strings longer than `max(T, 64)` become STRCHUNKs of at most
 *   T bytes. Every record stays within [maxRecordBytes] = T + T/2 + 256.
 * - [budgetBytes] B: pending bytes per insert; above it, the largest pending run of at least T/4 is flushed.
 * - [hashTerms]: whole-object and whole-array terms; [indexPolicy]: which postings are written.
 * - [sorterBytes]: the insert-local term sorter's buffer.
 * - [keyArenaBytes]: memory for duplicate-key hashes before they spill.
 *
 * Keys and numbers can't be cut, so they're limited to [maxAtomBytes] = `max(T/4, 64)` bytes; use [parseLimits] to
 * configure the tokenizer accordingly.
 */
internal class SplitOptions(
    val fragmentBytes: Int = DEFAULT_FRAGMENT_BYTES,
    val budgetBytes: Int = DEFAULT_BUDGET_BYTES,
    val hashTerms: Boolean = true,
    val indexPolicy: IndexPolicy = IndexPolicy.EVERYTHING,
    val sorterBytes: Int = DEFAULT_SORTER_BYTES,
    val keyArenaBytes: Long = DEFAULT_KEY_ARENA_BYTES,
) {
    val maxAtomBytes: Int = maxOf(fragmentBytes / 4, 64)

    val maxRecordBytes: Int = fragmentBytes + fragmentBytes / 2 + 256

    init {
        require(fragmentBytes in MIN_FRAGMENT_BYTES..MAX_FRAGMENT_BYTES) { "fragment size $fragmentBytes outside $MIN_FRAGMENT_BYTES..$MAX_FRAGMENT_BYTES" }
        require(budgetBytes >= 2 * fragmentBytes) { "budget $budgetBytes is below twice the fragment size" }
        require(sorterBytes >= 4 * (maxAtomBytes + 128) && sorterBytes >= 4096) { "sorter buffer $sorterBytes is too small for keys of $maxAtomBytes bytes" }
    }

    /** Strings up to this length stay inline. */
    internal val inlineStringBytes: Int = maxOf(fragmentBytes, 64)

    /** Allowance per object member for its keyRef and `sortPerm` entry, which pending bytes don't hold. */
    internal val memberAllowance: Int = 2 * Varint.size(maxRecordBytes.toLong())

    /** Part directory entries per HEADER record. */
    internal val directoryEntriesPerRecord: Int = maxOf(1, (fragmentBytes - 64) / MAX_DIRECTORY_ENTRY_BYTES)

    /** [base] with key and number lengths capped at [maxAtomBytes]. */
    fun parseLimits(base: ParseLimits = ParseLimits.DEFAULT): ParseLimits = ParseLimits(
        maxDocumentBytes = base.maxDocumentBytes,
        maxDepth = base.maxDepth,
        maxKeyBytes = minOf(base.maxKeyBytes, maxAtomBytes),
        maxNumberLength = minOf(base.maxNumberLength, maxAtomBytes),
        maxStringBytes = base.maxStringBytes,
    )

    companion object {
        const val DEFAULT_FRAGMENT_BYTES: Int = 64 * 1024
        const val DEFAULT_BUDGET_BYTES: Int = 8 * 1024 * 1024
        const val DEFAULT_SORTER_BYTES: Int = 16 * 1024 * 1024
        const val DEFAULT_KEY_ARENA_BYTES: Long = 16L * 1024 * 1024
        const val MIN_FRAGMENT_BYTES: Int = 32
        const val MAX_FRAGMENT_BYTES: Int = 16 * 1024 * 1024

        /**
         * Record bytes besides the payload and the context's owner key: framing (12), fragment key (21), codec (7),
         * padding (≤ 7), and the context's varints (≤ 33).
         */
        const val RECORD_OVERHEAD_BYTES: Int = 80

        private const val MAX_DIRECTORY_ENTRY_BYTES = 30
    }
}
