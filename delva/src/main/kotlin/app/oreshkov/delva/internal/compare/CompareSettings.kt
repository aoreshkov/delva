package app.oreshkov.delva.internal.compare

import app.oreshkov.delva.internal.split.TypePaths
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Arrays

/**
 * Validated comparison options: the ignored paths as a trie whose marked nodes are left out, and the ignored
 * keys as sorted UTF-8 byte strings. Immutable.
 */
internal class CompareSettings(
    val includeTypes: Boolean,
    ignoreKeys: Set<String>,
    ignorePaths: Set<String>,
    val maxDifferences: Int,
    val maxValueBytes: Int,
    val verifySkippedSubtrees: Boolean,
) {
    /** Marked trie nodes ([TypePaths.typeOf] non-null) are ignored; [TypePaths.NONE] means no path continues. */
    val paths: TypePaths = TypePaths.compile(ignorePaths.associateWith { IGNORED })

    private val keys: Array<ByteArray> = ignoreKeys.map { utf8(it) }.sortedWith { a, b -> Arrays.compareUnsigned(a, b) }.toTypedArray()

    val hasIgnoredKeys: Boolean get() = keys.isNotEmpty()

    fun isIgnoredKey(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (keys.isEmpty()) return false
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = Arrays.compareUnsigned(keys[mid], 0, keys[mid].size, bytes, offset, offset + length)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    fun isIgnored(pathNode: Int): Boolean = pathNode != TypePaths.NONE && paths.typeOf(pathNode) != null

    /** Whether nothing inside the value at [pathNode] can be ignored, so hashes, counts and sizes decide equality. */
    fun ignoresNothingBelow(pathNode: Int): Boolean = keys.isEmpty() && !paths.hasChildren(pathNode)

    private companion object {
        const val IGNORED = "ignored"

        fun utf8(key: String): ByteArray {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                val buffer = encoder.encode(java.nio.CharBuffer.wrap(key))
                return ByteArray(buffer.remaining()).also { buffer.get(it) }
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("ignored key has an unpaired surrogate")
            }
        }
    }
}
