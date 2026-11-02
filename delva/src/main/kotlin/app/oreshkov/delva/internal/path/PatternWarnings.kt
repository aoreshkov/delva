package app.oreshkov.delva.internal.path

import app.oreshkov.delva.QueryWarning
import app.oreshkov.delva.internal.canon.Nfc

/**
 * The warnings of `match` and `search` (SPEC.md §10.1). RFC 9535 makes either function false when its pattern isn't a
 * string that is valid I-Regexp, which is right for a pattern a document supplies. A literal pattern of that kind,
 * though, makes the function false for every value, which is almost never what the query's author meant, so compiling
 * reports it while the query keeps the RFC's meaning.
 *
 * A store with normalization runs a pattern in NFC (SPEC.md §10.2), and composing can turn a valid pattern into an
 * invalid one, or the other way round: `[ỹ-̄]` holds a range from U+0303 to U+0304, but in NFC the range
 * starts at `ỹ`, U+1EF9, and runs backwards. Such a warning says which kind of store the pattern is false in.
 */
internal object PatternWarnings {
    /** Characters of a pattern a reason quotes; a longer one is cut short, since the warning's span locates it. */
    private const val QUOTED_CHARS = 48

    /**
     * The warning for the literal pattern of [call], written as [token] at [offset] of the text, or null when the
     * pattern can match.
     */
    fun of(call: FunctionCall, offset: Int, token: String): QueryWarning? {
        val literal = call.patternLiteral ?: return null
        val function = call.function.functionName
        val reason = when (literal) {
            is LiteralValue.Str -> {
                val asWritten = call.literalRegexp as? IRegexp.Invalid
                // Null when NFC leaves the pattern as it is, or when it has too many marks in a row for any store to run it.
                val nfc = Nfc.normalizeOrNull(literal.value)?.takeIf { it != literal.value }
                val inNfc = nfc?.let { IRegexp.compile(it) as? IRegexp.Invalid }
                when {
                    asWritten != null && (nfc == null || inNfc != null) ->
                        "${quoted(token)} isn't I-Regexp (RFC 9485), so $function() is false for every value: ${asWritten.reason}"
                    asWritten != null ->
                        "${quoted(token)} isn't I-Regexp (RFC 9485), so $function() is false for every value in a store " +
                            "without normalization, though valid in NFC: ${asWritten.reason}"
                    inNfc != null -> {
                        val nfcToken = StringBuilder(nfc.length + 2).also { NormalizedPath.appendString(it, nfc) }.toString()
                        "${quoted(token)} is ${quoted(nfcToken)} in NFC, which isn't I-Regexp (RFC 9485), so $function() is " +
                            "false for every value in a store with normalization: ${inNfc.reason}"
                    }
                    else -> return null
                }
            }
            is LiteralValue.Num -> "the pattern $token is a number, not a string, so $function() is false for every value"
            is LiteralValue.Bool -> "the pattern $token is a boolean, not a string, so $function() is false for every value"
            LiteralValue.Null -> "the pattern null isn't a string, so $function() is false for every value"
        }
        return QueryWarning(offset, token.length, reason)
    }

    private fun quoted(token: String): String {
        if (token.length <= QUOTED_CHARS) return token
        // Not between the two halves of a surrogate pair.
        val cut = if (Character.isHighSurrogate(token[QUOTED_CHARS - 1])) QUOTED_CHARS - 1 else QUOTED_CHARS
        return token.substring(0, cut) + "…"
    }
}
