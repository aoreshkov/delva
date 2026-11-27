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
 * | [vectorField] | none | a [VectorField] |
 *
 * Options are values: two instances with the same settings are equal, and [copy] derives a variant, such as
 * `CreationOptions().copy(normalization = Normalization.NFC)`. The [vectorField] is still experimental, so it is set
 * with [withVectorField] instead.
 *
 * - [fragmentBytes]: target size T of the fragments a document is split into (SPEC.md §1). Values below 4 KiB are for
 *   testing. It also sets two limits (SPEC.md §1.3): a key is at most `max(T/4, 64)` UTF-8 bytes, and a number at
 *   most `min(4096, max(T/4, 64))` characters, so a larger T allows longer keys and, up to a T of 16 KiB, longer
 *   numbers.
 * - [hashTerms]: index whole object and array values, which searches for object and array literals need.
 * - [indexedKeys]: when not empty, only members with these keys are indexed; [excludedKeys]: keys never indexed.
 * - [minIndexedStringBytes], [maxIndexedStringBytes]: UTF-8 lengths of the string values that are indexed. Numbers,
 *   booleans and null are always indexed. A search the controls leave unanswerable is rejected (SPEC.md §4.1);
 *   documents are stored and read back in full either way.
 * - [normalization]: whether strings and keys are normalized to NFC as they are read (SPEC.md §2.1). With
 *   [Normalization.NFC] the normalized form is what is stored, indexed, compared and read back, and query keys,
 *   query values, type names and type paths are normalized the same way.
 * - [documentKey]: the JSON path of the string that names each document (SPEC.md §3.3).
 * - [vectorField]: where each document holds its vector, and how vectors are compared (SPEC.md §11), set with
 *   [withVectorField].
 */
public class CreationOptions private constructor(
    // First, so that this constructor's JVM signature differs from the public one.
    private val vector: VectorField?,
    public val fragmentBytes: Int,
    public val hashTerms: Boolean,
    public val indexedKeys: Set<String>,
    public val excludedKeys: Set<String>,
    public val minIndexedStringBytes: Int,
    public val maxIndexedStringBytes: Int,
    public val normalization: Normalization,
    documentKey: String?,
) {
    /** Options with these settings, each in the range the class's table gives; [withVectorField] adds a [vectorField]. */
    public constructor(
        fragmentBytes: Int = 64 * 1024,
        hashTerms: Boolean = true,
        indexedKeys: Set<String> = emptySet(),
        excludedKeys: Set<String> = emptySet(),
        minIndexedStringBytes: Int = 0,
        maxIndexedStringBytes: Int = Int.MAX_VALUE,
        normalization: Normalization = Normalization.NONE,
        documentKey: String? = null,
    ) : this(
        null, fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization,
        documentKey,
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
    public val documentKey: String? = documentKey

    /**
     * Where each document holds its vector, and how vectors are compared, or null for a store without vectors
     * (SPEC.md §11). Set with [withVectorField].
     *
     * In a store created with one, each document's array of [VectorField.dimensions] numbers at [VectorField.path] is
     * its vector, and a document with anything else there is rejected. Its path can't be the [documentKey] path, nor
     * one inside it or around it.
     */
    @ExperimentalDelvaApi
    public val vectorField: VectorField? get() = vector

    init {
        val key = documentKey
        val keySteps = key?.let { checkedPath(it, normalization, "the document key can't be the whole document") }
        val vectorSteps = vector?.let { checkedPath(it.path, normalization, "the vector can't be the whole document") }
        if (key != null && keySteps != null && vector != null && vectorSteps != null) {
            // A document key is a string and a vector an array, so no document could have both if one held the other.
            val shorter = minOf(keySteps.size, vectorSteps.size)
            require(keySteps.subList(0, shorter) != vectorSteps.subList(0, shorter)) {
                "the vector field ${vector.path} and the document key $key overlap: one holds the other"
            }
        }
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

    /**
     * These options with the given ones changed, the [vectorField] kept; the result is validated like a new instance.
     * A [documentKey] of null removes the key. Throws [InvalidJsonPathException] when [documentKey] isn't a JSON path
     * that can name a document's key, and [IllegalArgumentException] when it overlaps the [vectorField].
     */
    public fun copy(
        fragmentBytes: Int = this.fragmentBytes,
        hashTerms: Boolean = this.hashTerms,
        indexedKeys: Set<String> = this.indexedKeys,
        excludedKeys: Set<String> = this.excludedKeys,
        minIndexedStringBytes: Int = this.minIndexedStringBytes,
        maxIndexedStringBytes: Int = this.maxIndexedStringBytes,
        normalization: Normalization = this.normalization,
        documentKey: String? = this.documentKey,
    ): CreationOptions = CreationOptions(
        vector, fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization,
        documentKey,
    )

    /**
     * These options with [vectorField] set to [field], or removed with null. Throws [InvalidJsonPathException] when the
     * field's path names no key under these options' [normalization], and [IllegalArgumentException] when it overlaps
     * the [documentKey].
     */
    @ExperimentalDelvaApi
    public fun withVectorField(field: VectorField?): CreationOptions = CreationOptions(
        field, fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization,
        documentKey,
    )

    override fun equals(other: Any?): Boolean =
        other is CreationOptions && other.fragmentBytes == fragmentBytes && other.hashTerms == hashTerms &&
            other.indexedKeys == indexedKeys && other.excludedKeys == excludedKeys &&
            other.minIndexedStringBytes == minIndexedStringBytes && other.maxIndexedStringBytes == maxIndexedStringBytes &&
            other.normalization == normalization && other.documentKey == documentKey && other.vector == vector

    override fun hashCode(): Int = listOf(
        fragmentBytes, hashTerms, indexedKeys, excludedKeys, minIndexedStringBytes, maxIndexedStringBytes, normalization, documentKey,
        vector,
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
        if (vector != null) append(", vectorField=").append(vector)
        append(')')
    }

    public companion object {
        /** The smallest fragment size T ([fragmentBytes]); values below 4 KiB are for testing only. */
        public const val MIN_FRAGMENT_BYTES: Int = 32

        /** The largest fragment size T ([fragmentBytes]). */
        public const val MAX_FRAGMENT_BYTES: Int = 16 * 1024 * 1024

        /** The longest document key, in UTF-8 bytes after normalization ([documentKey]). */
        public const val MAX_DOCUMENT_KEY_BYTES: Int = 1024

        /**
         * The steps of [path], a path into each document other than `$`, with its names as [normalization] makes the
         * keys they match.
         */
        private fun checkedPath(path: String, normalization: Normalization, whole: String): List<PathStep> {
            val steps = SingularPath.parse(path)
            if (steps.isEmpty()) throw InvalidJsonPathException(path, 0, whole)
            if (normalization != Normalization.NFC) return steps
            return steps.map { step ->
                if (step !is PathStep.Name) return@map step
                val key = Nfc.normalizeOrNull(step.key) ?: throw InvalidJsonPathException(
                    path,
                    0,
                    "a name with more than ${Nfc.MAX_COMBINING_RUN} combining marks in a row once normalized matches no key",
                )
                PathStep.Name(key)
            }
        }
    }
}
