package app.oreshkov.delva.internal.term

import app.oreshkov.delva.internal.hash.Xxh3

/**
 * Which postings a store writes: the key–value terms are limited to certain member
 * keys and to string values of a certain length. Smaller indexes, in exchange for searches that can't be answered:
 * a search whose key or value length is outside the policy is rejected instead of returning fewer hits
 * ([app.oreshkov.delva.internal.query.QueryTerm.of]).
 *
 * - [includedKeys] empty means every key is indexed; otherwise only these are. [excludedKeys] wins over both.
 * - [minStringBytes] and [maxStringBytes] bound the UTF-8 length of **string** values, the values that dominate an
 *   index. Numbers, booleans and null are always indexed; container values follow `hashTerms`, and a string
 *   longer than the exact-term limit is already indexed by its hash.
 *
 * The policy shapes stored data, so it's fixed when a store is created and checked when it opens. Keys are matched as
 * UTF-8 bytes through a hash table; unequal keys with equal hashes are told apart by their bytes. Immutable and
 * thread-safe.
 */
internal class IndexPolicy(
    val includedKeys: Set<String> = emptySet(),
    val excludedKeys: Set<String> = emptySet(),
    val minStringBytes: Int = 0,
    val maxStringBytes: Int = Int.MAX_VALUE,
) {
    private val included = KeySet(includedKeys)
    private val excluded = KeySet(excludedKeys)

    /** Whether every key and every string value is indexed: then nothing has to be checked. */
    val indexesEverything: Boolean = includedKeys.isEmpty() && excludedKeys.isEmpty() && minStringBytes == 0 && maxStringBytes == Int.MAX_VALUE

    val limitsKeys: Boolean = includedKeys.isNotEmpty() || excludedKeys.isNotEmpty()

    val limitsStrings: Boolean = minStringBytes > 0 || maxStringBytes != Int.MAX_VALUE

    init {
        require(minStringBytes >= 0 && maxStringBytes >= minStringBytes) { "indexed string lengths $minStringBytes..$maxStringBytes" }
    }

    /** Whether members with the key `bytes[offset, offset + length)` are indexed. */
    fun indexesKey(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (!limitsKeys) return true
        if (includedKeys.isNotEmpty() && !included.contains(bytes, offset, length)) return false
        return !excluded.contains(bytes, offset, length)
    }

    fun indexesKey(key: String): Boolean = key.toByteArray(Charsets.UTF_8).let { indexesKey(it, 0, it.size) }

    /** Whether a string value of [byteLength] UTF-8 bytes is indexed. */
    fun indexesString(byteLength: Long): Boolean = !limitsStrings || (byteLength >= minStringBytes && byteLength <= maxStringBytes)

    override fun equals(other: Any?): Boolean =
        other is IndexPolicy && includedKeys == other.includedKeys && excludedKeys == other.excludedKeys &&
            minStringBytes == other.minStringBytes && maxStringBytes == other.maxStringBytes

    override fun hashCode(): Int = ((includedKeys.hashCode() * 31 + excludedKeys.hashCode()) * 31 + minStringBytes) * 31 + maxStringBytes

    override fun toString(): String = "IndexPolicy(included=$includedKeys, excluded=$excludedKeys, strings=$minStringBytes..$maxStringBytes)"

    /** Keys as UTF-8 bytes, found by hash without decoding the bytes that are looked up. */
    private class KeySet(keys: Set<String>) {
        private val byHash = HashMap<Long, List<ByteArray>>()

        init {
            for (key in keys) {
                val bytes = key.toByteArray(Charsets.UTF_8)
                byHash.merge(Xxh3.hash64(bytes, 0, bytes.size, 0L), listOf(bytes)) { a, b -> a + b }
            }
        }

        fun contains(bytes: ByteArray, offset: Int, length: Int): Boolean {
            val candidates = byHash[Xxh3.hash64(bytes, offset, length, 0L)] ?: return false
            for (key in candidates) {
                if (key.size != length) continue
                var i = 0
                while (i < length && key[i] == bytes[offset + i]) i++
                if (i == length) return true
            }
            return false
        }
    }

    companion object {
        /** Every key and every string value. */
        val EVERYTHING: IndexPolicy = IndexPolicy()
    }
}
