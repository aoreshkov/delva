package app.oreshkov.delva

import java.nio.file.Path

/**
 * Base class of every exception Delva throws for data, state or storage problems. It is sealed, but the constructors of
 * its subclasses are public, so a fake [JsonStore] in a test ([DelvaImplementationApi]) can throw what the store throws.
 *
 * Arguments a caller got wrong stay [IllegalArgumentException], Kotlin's convention for programming errors. Two of
 * them are subclasses a caller can catch on their own, because their text often comes from a user:
 * [InvalidJsonPathException] and [InvalidCursorException].
 */
public sealed class DelvaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Stored bytes don't match their format: a CRC mismatch, a truncated structure, an out-of-range field or an
 * inconsistent index entry. [location] names the file or structure and the byte position.
 */
public class CorruptionException(
    public val location: String,
    /** What is wrong with the bytes, without the location. */
    public val detail: String,
    cause: Throwable? = null,
) : DelvaException("Corrupt data at $location: $detail", cause)

/**
 * The engine hit an unrecoverable error (for example a failed `fsync`) and refuses every further operation.
 * Reopening the store runs recovery.
 */
public class EngineFailedException(message: String, cause: Throwable? = null) : DelvaException(message, cause)

/** An insert was rejected; nothing of the document is visible. */
public class DocumentRejectedException(
    /** Why the document was rejected. */
    public val reason: RejectReason,
    /** Byte offset in the input stream (counting a leading BOM) where the problem was detected, or -1 if unknown. */
    public val byteOffset: Long,
    /** JSON path of the offending value, when known; for [RejectReason.TYPE_PATH_UNMATCHED], the path as it was given. */
    public val jsonPath: String? = null,
    detail: String? = null,
) : DelvaException(buildRejectMessage(reason, byteOffset, jsonPath, detail))

/** Why an insert was rejected (SPEC.md §1.2). */
public enum class RejectReason {
    /** The input isn't valid JSON (RFC 8259). */
    MALFORMED_JSON,

    /** A string or key contains bytes that aren't valid UTF-8; such bytes elsewhere are [MALFORMED_JSON]. */
    INVALID_UTF8,

    /** A string contains an escape that doesn't denote a Unicode scalar value, such as a lone surrogate. */
    INVALID_STRING,

    /** An object contains the same key twice. */
    DUPLICATE_KEY,

    /** A number's normalized decimal exponent doesn't fit a 32-bit signed integer. */
    NUMBER_OUT_OF_RANGE,

    /** The document exceeds a configured limit (size, depth, key, string or number length). */
    LIMIT_EXCEEDED,

    /** A type assignment path given at insert matches no value, or matches a value that isn't an object. */
    TYPE_PATH_UNMATCHED,

    /**
     * The store has a [CreationOptions.documentKey] and the document has no string at its path: no value is there, or
     * the value isn't a string.
     */
    KEY_MISSING,
}

private fun buildRejectMessage(reason: RejectReason, byteOffset: Long, jsonPath: String?, detail: String?): String =
    buildString {
        append("Document rejected: ").append(reason)
        if (byteOffset >= 0) append(" at byte ").append(byteOffset)
        if (jsonPath != null) append(" (").append(jsonPath).append(')')
        if (detail != null) append(": ").append(detail)
    }

/**
 * The document doesn't exist or the caller can't see it: never inserted, deleted, still being inserted, or the docId
 * of an insert that was rejected.
 */
public class DocumentNotFoundException(@get:JvmName("getDocId") public val docId: DocId) :
    DelvaException("Document ${docId.raw} doesn't exist or isn't visible")

/**
 * An insert conflicts with the state it would take effect on. Nothing of the insert is visible and nothing was deleted.
 * Nothing is wrong with the document itself: read the current state and try again. Either:
 * - a replace ([JsonStore.replace], [InsertSource.replaces]) named documents that aren't visible when it would take
 *   effect: deleted, perhaps replaced by another call, or never stored; [key] is then null; or
 * - in a store with [CreationOptions.documentKey], the document's [key] is the key of a visible document the insert
 *   doesn't replace, which [conflicting] names; [JsonStore.upsert] replaces it instead.
 */
public class WriteConflictException(
    /** The documents that weren't visible, ascending; or the one document that has [key]. */
    public val conflicting: List<DocId>,
    /** The document key a visible document already has, or null when the conflict is a replaced document. */
    public val key: String? = null,
) : DelvaException(
    if (key == null) {
        "Documents ${conflicting.joinToString(", ") { it.raw.toString() }} aren't visible any more"
    } else {
        "Document ${conflicting.joinToString(", ") { it.raw.toString() }} already has the key ${quoteKey(key)}"
    },
)

/** [key] in JSON quotes, cut to 100 characters, for messages. */
private fun quoteKey(key: String): String = buildString {
    append('"')
    for ((i, c) in key.withIndex()) {
        if (i == 100) {
            append('…')
            break
        }
        when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/** The store was closed; reopen it to use it again. */
public class StoreClosedException : DelvaException("The store is closed")

/**
 * Another open store (in this or another process) holds the directory. [holder] names the process that has it open, as
 * that process recorded itself; it is null when the record is missing, unreadable, or left by a process that is gone.
 */
public class StoreLockedException(
    /** The store's directory. */
    public val directory: Path,
    public val holder: LockHolder? = null,
) : DelvaException("The store at $directory is already open" + (holder?.let { " by $it" } ?: ""))

/** [JsonStore.open] found no store in [directory]; nothing was created there. */
public class StoreNotFoundException(public val directory: Path) : DelvaException("There is no store at $directory")

/** [JsonStore.create] found a store already in [directory]; it was left as it was. */
public class StoreExistsException(public val directory: Path) : DelvaException("There is already a store at $directory")

/**
 * [JsonStore.openOrCreate] found a store in [directory] created with other [CreationOptions] than [requested]; it was
 * left as it was. [JsonStore.open] opens it with the [stored] options.
 */
public class CreationOptionsMismatchException(
    public val directory: Path,
    /** What the store was created with. */
    public val stored: CreationOptions,
    /** What the caller asked for. */
    public val requested: CreationOptions,
) : DelvaException("The store at $directory was created with ${differences(stored, requested)}")

/** The options that differ, as `fragmentBytes 65536, not 4096; normalization NONE, not NFC`. */
private fun differences(stored: CreationOptions, requested: CreationOptions): String {
    val parts = listOf<Pair<String, (CreationOptions) -> Any>>(
        "fragmentBytes" to { it.fragmentBytes },
        "hashTerms" to { it.hashTerms },
        "indexedKeys" to { it.indexedKeys.sorted() },
        "excludedKeys" to { it.excludedKeys.sorted() },
        "minIndexedStringBytes" to { it.minIndexedStringBytes },
        "maxIndexedStringBytes" to { it.maxIndexedStringBytes },
        "normalization" to { it.normalization },
        "documentKey" to { it.documentKey ?: "none" },
    ).mapNotNull { (name, get) -> get(stored).takeIf { it != get(requested) }?.let { "$name $it, not ${get(requested)}" } }
    return if (parts.isEmpty()) "other options than $requested" else parts.joinToString("; ")
}

/**
 * A search the store can't answer, refused for [reason] before anything is read (SPEC.md §4.1, §10.4). Nothing is wrong
 * with the query as such: another store, a narrower query, [ScanPolicy.ALLOW_SCAN], or a select of each document
 * answers it.
 */
public class QueryRejectedException(public val reason: Reason, detail: String) : DelvaException("Query rejected: $detail") {
    /** Why the query was refused. */
    public enum class Reason {
        /** A JSONPath search that no index term narrows, with [ScanPolicy.REJECT]. */
        NEEDS_SCAN,

        /** A JSONPath comparison `<`, `<=`, `>` or `>=` whose range matches more documents than a search reads for one. */
        RANGE_TOO_WIDE,

        /**
         * A key–value search the index controls leave unanswerable: a key [CreationOptions.indexedKeys] or
         * [CreationOptions.excludedKeys] don't index, a string length outside the indexed ones, or an object or array
         * value in a store created without [CreationOptions.hashTerms].
         */
        NOT_INDEXED,

        /**
         * A JSONPath search whose query has more than 31 segments, the most a search evaluates in document order;
         * [ReadableJsonStore.select] evaluates such a query on one document.
         */
        TOO_MANY_SEGMENTS,
    }
}

/**
 * A JSONPath query or document filter that doesn't compile (SPEC.md §10.1, §10.5): a syntax or type error, or text
 * beyond the limits. An [IllegalArgumentException], as [JsonPath.compile] and the type paths of [JsonStore.insert] have
 * always thrown.
 */
public class InvalidJsonPathException(
    /** The query as it was given. */
    public val text: String,
    /** The character offset in [text] where the problem was found. */
    public val offset: Int,
    /** What is wrong, without the text and the offset. */
    public val reason: String,
) : IllegalArgumentException("invalid JSONPath query at $offset: $reason: $text")

/**
 * Text that isn't a [Cursor] ([Cursor.parse]), or a cursor that doesn't fit where it's used: from a search of the other
 * [SearchOrder], or from another kind of result. An [IllegalArgumentException], as [Cursor.parse] has always thrown.
 */
public class InvalidCursorException(detail: String, cause: Throwable? = null) : IllegalArgumentException(detail, cause)

/** A type name doesn't meet the rules of SPEC.md §6. */
public class InvalidTypeNameException(detail: String) : DelvaException("Invalid type name: $detail")

/** A value is longer than the [maxChars] characters the caller allowed, for example in [SearchHit.readJsonAsString]. */
public class ResultTooLargeException(public val maxChars: Int) : DelvaException("The JSON text exceeds $maxChars characters")

/**
 * Evaluating a JSONPath query exceeded one of the limits that keep evaluation bounded (SPEC.md §10.2), such as the steps
 * a regular expression of `match` or `search` may take.
 */
public class QueryLimitException(detail: String) : DelvaException("Query limit exceeded: $detail")
