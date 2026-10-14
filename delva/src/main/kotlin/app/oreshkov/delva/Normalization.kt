package app.oreshkov.delva

/**
 * How a store treats the spelling of strings and keys (SPEC.md §2.1). Fixed when the store is created, because it
 * decides the bytes that are stored and the terms that are indexed.
 */
public enum class Normalization {
    /**
     * Strings and keys are stored and compared exactly as they arrive, code point for code point. The two spellings
     * of `"é"` — `U+00E9`, and `U+0065 U+0301` — are different strings, and neither finds the other.
     */
    NONE,

    /**
     * Every string and key is converted to Unicode Normalization Form C as it is read, on insert and in queries, so
     * the two spellings of `"é"` are one string. What a document reads back is the normalized form, not the text
     * that was inserted.
     *
     * The cost is paid while parsing: ASCII, and text that is already normalized, are recognized without rebuilding
     * anything; the rest is converted, which allocates. Normalization can also make two keys of one object equal,
     * and the document is then rejected with [RejectReason.DUPLICATE_KEY]. A key or string whose normalized
     * form has more than 30 combining marks in a row is rejected with [RejectReason.LIMIT_EXCEEDED] (SPEC.md §1.3), in a
     * document and equally in a query, a type name or path, or a key given to a lookup.
     */
    NFC,
}
