package app.oreshkov.delva.internal.parse

/** Token kinds of a [JsonTokenSource]. `Int` constants, so the hot loop never boxes. */
internal object JsonToken {
    const val EOF: Int = 0
    const val START_OBJECT: Int = 1
    const val END_OBJECT: Int = 2
    const val START_ARRAY: Int = 3
    const val END_ARRAY: Int = 4
    const val KEY: Int = 5
    const val STRING: Int = 6
    const val STRING_PART: Int = 7
    const val NUMBER: Int = 8
    const val TRUE: Int = 9
    const val FALSE: Int = 10
    const val NULL: Int = 11

    private val NAMES = arrayOf(
        "EOF", "START_OBJECT", "END_OBJECT", "START_ARRAY", "END_ARRAY", "KEY",
        "STRING", "STRING_PART", "NUMBER", "TRUE", "FALSE", "NULL",
    )

    fun name(token: Int): String = NAMES.getOrElse(token) { "UNKNOWN($token)" }
}

/**
 * A pull-based stream of JSON tokens. Every problem with the input is a
 * [app.oreshkov.delva.DocumentRejectedException] thrown from [next]; I/O errors of the underlying source propagate
 * unchanged. After a rejection the source is unusable.
 */
internal interface JsonTokenSource : AutoCloseable {
    /**
     * Advances to the next token and returns its kind ([JsonToken]). After [JsonToken.EOF] every call returns
     * [JsonToken.EOF] again.
     *
     * A string value longer than the source's chunk size arrives as two or more [JsonToken.STRING_PART] tokens that
     * end on code-point boundaries; each part is non-empty and the last one has [isLastPart] set. Shorter strings
     * arrive as one [JsonToken.STRING]. Keys always arrive whole.
     */
    fun next(): Int

    /**
     * The current token's content in `bytes[offset, offset + length)`: unescaped, validated UTF-8 for
     * [JsonToken.KEY], [JsonToken.STRING] and [JsonToken.STRING_PART]; the raw ASCII text for [JsonToken.NUMBER];
     * empty for other tokens. Valid until the next call of [next].
     */
    val bytes: ByteArray
    val offset: Int
    val length: Int

    /** For [JsonToken.STRING_PART]: whether this is the string's last part. */
    val isLastPart: Boolean

    /** Offset in the input (counting a leading byte-order mark) of the token's first byte. */
    val byteOffset: Long

    /** Number of open containers after the current token. */
    val depth: Int
}

/**
 * Limits enforced while tokenizing. Byte lengths are UTF-8 lengths after unescaping; depth counts open
 * containers.
 *
 * With [nfc], an [NfcTokenSource] follows the tokenizer and enforces the key and string limits on the normalized
 * form, so the tokenizer checks only [maxRawKeyBytes] and [maxRawStringBytes]: NFC shrinks UTF-8 less than
 * [NFC_MAX_SHRINK]-fold (at most 3.5-fold: U+1FBE, a 3-byte spelling of ι, with a diaeresis and an acute of 2 bytes
 * each composes to the 2 bytes of ΐ), so text longer than that is over the limit after normalization too, and the
 * tokenizer's memory stays bounded.
 */
internal class ParseLimits(
    val maxDocumentBytes: Long = Long.MAX_VALUE,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxKeyBytes: Int = DEFAULT_MAX_KEY_BYTES,
    val maxNumberLength: Int = DEFAULT_MAX_NUMBER_LENGTH,
    val maxStringBytes: Long = Long.MAX_VALUE,
    val nfc: Boolean = false,
) {
    init {
        require(maxDocumentBytes >= 0 && maxDepth >= 0 && maxKeyBytes >= 0 && maxNumberLength >= 1 && maxStringBytes >= 0)
    }

    /** Most bytes of a key before normalization. */
    val maxRawKeyBytes: Int =
        if (!nfc) maxKeyBytes else if (maxKeyBytes > Int.MAX_VALUE / NFC_MAX_SHRINK) Int.MAX_VALUE else maxKeyBytes * NFC_MAX_SHRINK

    /** Most bytes of a string value before normalization. */
    val maxRawStringBytes: Long =
        if (!nfc) maxStringBytes else if (maxStringBytes > Long.MAX_VALUE / NFC_MAX_SHRINK) Long.MAX_VALUE else maxStringBytes * NFC_MAX_SHRINK

    /** These limits, for a tokenizer whose tokens an [NfcTokenSource] then normalizes. */
    fun forNfc(): ParseLimits = ParseLimits(maxDocumentBytes, maxDepth, maxKeyBytes, maxNumberLength, maxStringBytes, nfc = true)

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 1024

        /** A key must fit comfortably in one fragment, together with its value. */
        const val DEFAULT_MAX_KEY_BYTES: Int = 16 * 1024

        /** A number is stored whole in one fragment; it can't be chunked like a string. */
        const val DEFAULT_MAX_NUMBER_LENGTH: Int = 4 * 1024

        /** More than NFC can divide the UTF-8 length of a text by. */
        const val NFC_MAX_SHRINK: Int = 4

        val DEFAULT: ParseLimits = ParseLimits()
    }
}
