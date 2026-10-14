package app.oreshkov.delva

/** What [JsonStore.verify] is reading, in the order the phases run. */
public enum class VerifyPhase {
    /** File headers, control-log records, and the fragments the COMMITs still in the log name. */
    LOG,

    /** Index segments: every data block, external value chunk and bloom-filter page. */
    INDEX,

    /** Fragment-index entries against the log records they locate. */
    FRAGMENTS,
}

/** Called by [JsonStore.verify] as it works, at least once per phase and then every few MiB. */
public fun interface VerifyProgress {
    /**
     * `bytesChecked` of `bytesTotal` are the phase's own bytes, so they restart at each phase; `bytesTotal` is an
     * estimate made when the phase starts. Throwing from here stops the scrub with that exception.
     */
    public fun onProgress(phase: VerifyPhase, bytesChecked: Long, bytesTotal: Long)
}

/** What kind of structure a [VerifyIssue] was found in. */
public enum class VerifyIssueKind {
    /** A file the store's metadata lists is missing, too short, or has no valid header. */
    FILE,

    /** A log record's frame or body is damaged: its checksum, its framing or its fields. */
    LOG_RECORD,

    /** A segment's block, external value chunk or bloom-filter page is damaged. */
    SEGMENT,

    /** A COMMIT's extents don't hold the fragments it recorded, or the deletes of a replace don't match its COMMIT. */
    COMMIT,

    /** A fragment-index entry doesn't locate the fragment it names. */
    FRAGMENT_INDEX,

    /**
     * The document-key index of a store with [CreationOptions.documentKey] disagrees with itself: two visible documents
     * have one key, or a document's key isn't indexed both ways.
     */
    KEY_INDEX,
}

/**
 * One problem [JsonStore.verify] found. [location] names the file and byte position, as [CorruptionException.location]
 * does; [damagedBytes] is how many bytes the damage spans where that's known, and 0 otherwise.
 */
public class VerifyIssue internal constructor(
    /** What kind of data is damaged. */
    public val kind: VerifyIssueKind,
    public val location: String,
    /** What is wrong with the bytes, without the location. */
    public val detail: String,
    public val damagedBytes: Long,
    /** The document the damage makes unreadable, when the scrub could tell which one it is. */
    public val document: DocId?,
) {
    override fun toString(): String = buildString {
        append(kind).append(" at ").append(location).append(": ").append(detail)
        if (damagedBytes > 0) append(" (").append(damagedBytes).append(" bytes)")
        document?.let { append(" [document ").append(it.raw).append(']') }
    }
}

/**
 * What [JsonStore.verify] read and what it found. A store is intact when [issues] is empty; every issue names data that
 * reads would fail on, not a reason to stop using the store, which keeps serving everything else.
 */
public class VerifyReport internal constructor(
    /** How many files were read. */
    public val filesChecked: Int,
    /** How many bytes of those files were read and checked. */
    public val bytesChecked: Long,
    /** How many records of the control log were checked. */
    public val logRecordsChecked: Long,
    /** How many fragment records were read and checked. */
    public val fragmentsChecked: Long,
    /** How many data blocks of the index segments were checked. */
    public val segmentBlocksChecked: Long,
    public val issues: List<VerifyIssue>,
) {
    /** Whether nothing was found. */
    public val intact: Boolean get() = issues.isEmpty()

    /** Bytes the issues span; 0 when the damage's extent isn't known. */
    public val damagedBytes: Long get() = issues.sumOf { it.damagedBytes }

    /** The documents the issues name, in docId order, each once. */
    public val damagedDocuments: List<DocId>
        get() = issues.mapNotNull { it.document }.distinctBy { it.raw }.sortedBy { it.raw }

    override fun toString(): String =
        "VerifyReport($filesChecked files, $bytesChecked bytes, $logRecordsChecked records, $fragmentsChecked fragments, " +
            "$segmentBlocksChecked blocks, ${issues.size} issues)"
}
