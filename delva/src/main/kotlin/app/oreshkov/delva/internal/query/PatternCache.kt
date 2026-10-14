package app.oreshkov.delva.internal.query

import app.oreshkov.delva.internal.path.IRegexp

/**
 * The compiled I-Regexps of patterns a filter reads from documents, by text. Only patterns of at most [maxChars]
 * characters are kept, so the cache holds a bounded amount however long the stored strings are; a longer pattern is
 * compiled on each use and held only while it's used. Not thread-safe.
 */
internal class PatternCache(private val maxEntries: Int = MAX_ENTRIES, private val maxChars: Int = MAX_CHARS) {
    private val compiled = object : LinkedHashMap<String, IRegexp.Compiled?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IRegexp.Compiled?>): Boolean = size > maxEntries
    }

    /** Patterns held; for tests. */
    val size: Int get() = compiled.size

    /** [text] compiled, or null when it isn't valid I-Regexp. */
    fun compile(text: String): IRegexp.Compiled? =
        if (text.length > maxChars) IRegexp.compile(text) else compiled.getOrPut(text) { IRegexp.compile(text) }

    companion object {
        const val MAX_ENTRIES = 64
        const val MAX_CHARS = 1024
    }
}
