package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.bytes.GrowableBytes
import java.text.Normalizer

/**
 * Unicode NFC normalization of validated UTF-8, for stores created with normalization on (SPEC.md §2.1). Keys and
 * strings are normalized as they are tokenized, so everything downstream — payload bytes, canonical hashes, index
 * terms, comparisons and the JSON that comes back out — sees one spelling.
 *
 * Two fast paths keep the common case close to free: ASCII is NFC by definition, so an all-ASCII run is recognized
 * by one scan and copied unchanged, and text that is already NFC is detected before anything is rebuilt. Text that
 * is neither is decoded to UTF-16, normalized with [Normalizer] and encoded back, which allocates — the one place
 * in the insert path that does, and only for stores that ask for normalization.
 *
 * A long string arrives in parts, so a part has to be split where normalizing the two halves separately gives what
 * normalizing the whole would: immediately before a **safe starter**, a code point that can never combine with what
 * precedes it. Every code point that NFC can compose onto an earlier one is either a combining mark or a Hangul V
 * or T jamo, so everything else qualifies; [safeStarterAt] tests exactly that, conservatively (a mark with a zero
 * combining class is treated as unsafe, which costs a few buffered bytes and never a wrong answer). A key or string
 * whose normalized form has more than [MAX_COMBINING_RUN] unsafe code points in a row is rejected.
 */
internal object Nfc {
    /**
     * Most code points that can't start a normalization boundary (see [safeStarterAt]) the normalized form of a key
     * or string may hold in a row (SPEC.md §1.3): the bound of the UAX #15 Stream-Safe Text Format. Only text stacked
     * with marks goes past it. It is counted in the normalized form so that every spelling of a text gets the same
     * verdict: what the store hands back can always be inserted again.
     */
    const val MAX_COMBINING_RUN: Int = 30

    /**
     * Most unsafe code points in a row that text as written can hold and still normalize to a run of at most
     * [MAX_COMBINING_RUN]. The canonical decomposition of an unsafe code point is at most 3 unsafe ones, and that of a
     * safe starter begins with a safe starter and ends in at most 3 unsafe ones; NFC and the text as written have the
     * same decomposition, in which their runs are at least as long. Checking it before normalizing bounds both the
     * bytes held back between parts and the work of canonical reordering, which is quadratic in the length of a run,
     * without rejecting anything the normalized check would accept.
     */
    const val MAX_RAW_COMBINING_RUN: Int = 3 * MAX_COMBINING_RUN + 3

    /**
     * Length of the run of code points that aren't safe starters at the end of the validated UTF-8
     * `bytes[offset, end)`, counting on from [run], the length of the run that ended where these bytes begin. Stops
     * and returns `max + 1` as soon as a run passes [max].
     */
    fun combiningRun(bytes: ByteArray, offset: Int, end: Int, run: Int, max: Int): Int {
        var count = run
        var i = offset
        while (i < end) {
            val b = bytes[i]
            if (b >= 0) {
                count = 0
                i++
                continue
            }
            if (Utf8.isContinuation(b)) {
                i++
                continue
            }
            if (safeStarterAt(bytes, i, end)) {
                count = 0
            } else if (++count > max) {
                return count
            }
            i++
        }
        return count
    }

    /** Whether `bytes[offset, offset + length)` is all ASCII, which is always NFC. */
    fun isAscii(bytes: ByteArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        while (i < end) {
            if (bytes[i] < 0) return false
            i++
        }
        return true
    }

    /**
     * Appends the NFC form of the validated UTF-8 `bytes[offset, offset + length)` to [out] and returns how many
     * bytes it wrote.
     */
    fun normalize(bytes: ByteArray, offset: Int, length: Int, out: GrowableBytes): Int {
        if (isAscii(bytes, offset, length)) {
            out.writeBytes(bytes, offset, length)
            return length
        }
        val text = String(bytes, offset, length, Charsets.UTF_8)
        if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            out.writeBytes(bytes, offset, length)
            return length
        }
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        out.writeBytes(normalized, 0, normalized.size)
        return normalized.size
    }

    /** Whether `bytes[offset, offset + length)` is already NFC, so normalizing it would copy it unchanged. */
    fun isNormalized(bytes: ByteArray, offset: Int, length: Int): Boolean =
        isAscii(bytes, offset, length) ||
            Normalizer.isNormalized(String(bytes, offset, length, Charsets.UTF_8), Normalizer.Form.NFC)

    /**
     * The NFC form of [text], [text] itself when it is already normalized; null when the form has a run longer than
     * [MAX_COMBINING_RUN], which no key or string of a document has. A run as written longer than
     * [MAX_RAW_COMBINING_RUN] gives null without being normalized.
     */
    fun normalizeOrNull(text: String): String? {
        if (longestRun(text, MAX_RAW_COMBINING_RUN) > MAX_RAW_COMBINING_RUN) return null
        val normalized = if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) text else Normalizer.normalize(text, Normalizer.Form.NFC)
        return normalized.takeIf { longestRun(it, MAX_COMBINING_RUN) <= MAX_COMBINING_RUN }
    }

    /**
     * The NFC form of [text], the [what] of a query, a type or a key lookup; rejected with
     * [RejectReason.LIMIT_EXCEEDED] as a key or string of a document would be when its run is too long
     * ([normalizeOrNull]).
     */
    fun normalize(text: String, what: String): String = normalizeOrNull(text) ?: throw DocumentRejectedException(
        RejectReason.LIMIT_EXCEEDED,
        -1,
        detail = "$what has more than $MAX_COMBINING_RUN combining marks in a row",
    )

    /** The longest run of code points in [text] that aren't safe starters, or `max + 1` once one passes [max]. */
    private fun longestRun(text: String, max: Int): Int {
        var longest = 0
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isSafeStarter(cp)) {
                count = 0
            } else if (++count > longest) {
                longest = count
                if (longest > max) break
            }
        }
        return longest
    }

    /**
     * The index in `bytes[offset, end)` where the unstable tail begins: the last position that starts a safe
     * starter, so `[offset, index)` can be normalized on its own and `[index, end)` must wait for what follows.
     * Returns [offset] when even the first code point is unsafe.
     *
     * `end` must fall on a code-point boundary, as the parts of a tokenized string do.
     */
    fun tailStart(bytes: ByteArray, offset: Int, end: Int): Int {
        var i = end
        while (i > offset) {
            i--
            if (Utf8.isContinuation(bytes[i])) continue
            if (safeStarterAt(bytes, i, end)) return i
        }
        return offset
    }

    /**
     * Whether the code point at [index] can begin a normalization boundary: it is not a combining mark, and not a
     * Hangul jamo that composes onto a preceding jamo or syllable.
     */
    fun safeStarterAt(bytes: ByteArray, index: Int, end: Int): Boolean = isSafeStarter(codePointAt(bytes, index, end))

    private fun isSafeStarter(cp: Int): Boolean {
        if (cp < 0x300) return true // no mark and no jamo below U+0300, which covers ASCII and Latin-1
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> false
            else -> !isComposingJamo(cp)
        }
    }

    /** Hangul V and T jamo, which compose onto a preceding L jamo or LV syllable. */
    private fun isComposingJamo(cp: Int): Boolean =
        cp in 0x1160..0x11FF || // Hangul Jamo: V and T
            cp in 0xD7B0..0xD7FF // Hangul Jamo Extended-B: V and T

    /** Decodes the code point starting at [index] of validated UTF-8; a truncated tail decodes to its lead byte. */
    private fun codePointAt(bytes: ByteArray, index: Int, end: Int): Int {
        val b0 = bytes[index].toInt() and 0xFF
        val length = Utf8.leadLength(b0)
        if (length <= 1 || index + length > end) return b0
        var cp = when (length) {
            2 -> b0 and 0x1F
            3 -> b0 and 0x0F
            else -> b0 and 0x07
        }
        for (i in 1 until length) cp = (cp shl 6) or (bytes[index + i].toInt() and 0x3F)
        return cp
    }
}
