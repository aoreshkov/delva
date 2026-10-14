package app.oreshkov.delva

import app.oreshkov.delva.internal.canon.Nfc
import app.oreshkov.delva.internal.path.PathStep
import app.oreshkov.delva.internal.path.SingularPath

/**
 * The options a store is created with, fixed for its whole life: they shape the stored data and the index terms.
 * They are given to [JsonStore.create] and [JsonStore.openOrCreate], recorded in the store, and read back by
 * [JsonStore.open], so opening a store never needs them; [JsonStore.creationOptions] tells what a store was created
 * with. The options that may differ at every open are [StoreOptions].
 *
 * | Option | Default | Range |
 * |---|---|---|
 * | [fragmentBytes] | 64 KiB | 32 B … 16 MiB |
 * | [hashTerms] | true | |
 * | [indexedKeys] | empty (every key) | |
 * | [excludedKeys] | empty | disjoint from [indexedKeys] |
 * | [minIndexedStringBytes] | 0 | ≥ 0 |
 * | [maxIndexedStringBytes] | unlimited | ≥ [minIndexedStringBytes] |
 * | [normalization] | [Normalization.NONE] | |
 * | [documentKey] | none | a JSON path other than `$` |
 *
 * Options are values: two instances with the same settings are equal, and [copy] derives a variant, such as
 * `CreationOptions().copy(normalization = Normalization.NFC)`.
 *
 * - [fragmentBytes]: target size T of the fragments a document is split into (SPEC.md §1). Values below 4 KiB are for
 *   testing. It also sets two limits (SPEC.md §1.3): a key is at most `max(T/4, 64)` UTF-8 bytes, and a number at
 *   most `min(4096, max(T/4, 64))` characters, so a larger T allows longer keys and, up to a T of 16 KiB, longer
 *   numbers.
 * - [hashTerms]: index whole object and array values, which searches for object and array literals need.
 * - [indexedKeys]: when not empty, only members with these keys are indexed; [excludedKeys]: keys never indexed.
 * - [minIndexedStringBytes], [maxIndexedStringBytes]: UTF-8 lengths of the string values that are indexed. Numbers,
 *   booleans and null are always indexed. A search the controls leave unanswerable is rejected (SPEC.md §4.6);
 *   documents are stored and read back in full either way.
 * - [normalization]: whether strings and keys are normalized to NFC as they are read (SPEC.md §2.1). With
 *   [Normalization.NFC] the normalized form is what is stored, indexed, compared and read back, and query keys,
 *   query values, type names and type paths are normalized the same way.
 * - [documentKey]: the JSON path of the string that names each document (SPEC.md §3.3), set with [withDocumentKey].
 */
public class CreationOptions private constructor(
    // First, so that this constructor's JVM signature differs from the public one that takes a documentKey.
    private val key: String?,
    public val fragmentBytes: Int,
    public val hashTerms: Boolean,
    public val indexedKeys: Set<String>,
    public val excludedKeys: Set<String>,
    public val minIndexedStringBytes: Int,
    public val maxIndexedStringBytes: Int,
    public val normalization: Normalization,
) {
    /** Options without a [documentKey]. */
    public constructor(
        fragmentBytes: Int = 64 * 1024,
        hashTerms: Boolean = true,
        indexedKeys: Set<String> = emptySet(),
        excludedKeys: Set<String> = emptySet(),
        minIndexedStringBytes: Int = 0,
        maxIndexedStringBytes: Int = Int.MAX_VALUE,
        normalization: Normalization = Normalization.NONE,
    ) : this(null, fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization)

    /** Options with a [documentKey]; validated like the others. */
    @ExperimentalDelvaApi
    public constructor(
        fragmentBytes: Int = 64 * 1024,
        hashTerms: Boolean = true,
        indexedKeys: Set<String> = emptySet(),
        excludedKeys: Set<String> = emptySet(),
        minIndexedStringBytes: Int = 0,
        maxIndexedStringBytes: Int = Int.MAX_VALUE,
        normalization: Normalization = Normalization.NONE,
        documentKey: String?,
    ) : this(
        checkedDocumentKey(documentKey, normalization),
        fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization,
    )

    /**
     * The JSON path of the string member that names each document, or null for a store without document keys
     * (SPEC.md §3.3): an RFC 9535 singular query without negative indexes, such as `$.id` or `$['meta']['key']`, as type
     * paths are (SPEC.md §6.1), and not `$` itself.
     *
     * In a store created with one, every document must have a string of at most [MAX_DOCUMENT_KEY_BYTES] UTF-8 bytes
     * there, or it's rejected ([RejectReason.KEY_MISSING], [RejectReason.LIMIT_EXCEEDED]), and no two visible documents
     * have the same key: an insert of a key that a visible document has throws [WriteConflictException], and
     * [JsonStore.upsert] replaces that document instead. [JsonStore.get] finds a document by its key,
     * [DocumentKeyQuery] reads documents in key order, and [SearchHit.documentKey] names a hit's document.
     */
    @ExperimentalDelvaApi
    public val documentKey: String? get() = key

    init {
        require(fragmentBytes in MIN_FRAGMENT_BYTES..MAX_FRAGMENT_BYTES) {
            "fragmentBytes $fragmentBytes outside $MIN_FRAGMENT_BYTES..$MAX_FRAGMENT_BYTES"
        }
        require(minIndexedStringBytes >= 0) { "minIndexedStringBytes $minIndexedStringBytes is negative" }
        require(maxIndexedStringBytes >= minIndexedStringBytes) {
            "indexed string lengths $minIndexedStringBytes..$maxIndexedStringBytes"
        }
        require(indexedKeys.none { it in excludedKeys }) {
            "keys are both indexed and excluded: ${indexedKeys.intersect(excludedKeys)}"
        }
    }

    /** These options with the given ones changed, the [documentKey] kept; the result is validated like a new instance. */
    public fun copy(
        fragmentBytes: Int = this.fragmentBytes,
        hashTerms: Boolean = this.hashTerms,
        indexedKeys: Set<String> = this.indexedKeys,
        excludedKeys: Set<String> = this.excludedKeys,
        minIndexedStringBytes: Int = this.minIndexedStringBytes,
        maxIndexedStringBytes: Int = this.maxIndexedStringBytes,
        normalization: Normalization = this.normalization,
    ): CreationOptions = CreationOptions(
        fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization, documentKey,
    )

    /**
     * These options with [documentKey] set to [path], or removed with null. Throws [InvalidJsonPathException] when
     * [path] isn't a JSON path that can name a document's key.
     */
    @ExperimentalDelvaApi
    public fun withDocumentKey(path: String?): CreationOptions = CreationOptions(
        fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization, path,
    )

    override fun equals(other: Any?): Boolean =
        other is CreationOptions && other.fragmentBytes == fragmentBytes && other.hashTerms == hashTerms &&
            other.indexedKeys == indexedKeys && other.excludedKeys == excludedKeys &&
            other.minIndexedStringBytes == minIndexedStringBytes && other.maxIndexedStringBytes == maxIndexedStringBytes &&
            other.normalization == normalization && other.documentKey == documentKey

    override fun hashCode(): Int = listOf(
        fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization, documentKey,
    ).hashCode()

    override fun toString(): String = buildString {
        append("CreationOptions(fragmentBytes=").append(fragmentBytes)
        append(", hashTerms=").append(hashTerms)
        if (indexedKeys.isNotEmpty()) append(", indexedKeys=").append(indexedKeys.size)
        if (excludedKeys.isNotEmpty()) append(", excludedKeys=").append(excludedKeys.size)
        if (minIndexedStringBytes != 0) append(", minIndexedStringBytes=").append(minIndexedStringBytes)
        if (maxIndexedStringBytes != Int.MAX_VALUE) append(", maxIndexedStringBytes=").append(maxIndexedStringBytes)
        if (normalization != Normalization.NONE) append(", normalization=").append(normalization)
        if (documentKey != null) append(", documentKey=").append(documentKey)
        append(')')
    }

    public companion object {
        /** The smallest fragment size T ([fragmentBytes]); values below 4 KiB are for testing only. */
        public const val MIN_FRAGMENT_BYTES: Int = 32

        /** The largest fragment size T ([fragmentBytes]). */
        public const val MAX_FRAGMENT_BYTES: Int = 16 * 1024 * 1024

        /** The longest document key, in UTF-8 bytes after normalization ([documentKey]). */
        @ExperimentalDelvaApi
        public const val MAX_DOCUMENT_KEY_BYTES: Int = 1024

        private fun checkedDocumentKey(documentKey: String?, normalization: Normalization): String? {
            if (documentKey == null) return null
            val steps = SingularPath.parse(documentKey)
            if (steps.isEmpty()) throw InvalidJsonPathException(documentKey, 0, "the document key can't be the whole document")
            if (normalization == Normalization.NFC && steps.any { it is PathStep.Name && Nfc.normalizeOrNull(it.key) == null }) {
                throw InvalidJsonPathException(
                    documentKey,
                    0,
                    "a name with more than ${Nfc.MAX_COMBINING_RUN} combining marks in a row once normalized matches no key",
                )
            }
            return documentKey
        }
    }
}
