package app.oreshkov.delva

/**
 * What a store holds, as of the moment [ReadableJsonStore.info] was called; read from memory, without a scan.
 *
 * - [approximateDocumentCount]: documents a search can find now. An upper bound: an insert rejected before it
 *   wrote anything leaves a docId that is counted but names no document, so the count is exact in a store that
 *   never rejected a document that way. Inserts still running aren't counted, except, in a secondary, small ones its
 *   writer hadn't logged yet.
 * - [deletedDocumentCount]: documents deleted over the store's life.
 * - [secondary]: whether the counts are those of a [SecondaryJsonStore], as of its last refresh.
 */
public class StoreInfo internal constructor(
    public val approximateDocumentCount: Long,
    public val deletedDocumentCount: Long,
    secondary: Boolean,
) {
    /** Whether this describes a [SecondaryJsonStore]: the state its last refresh read. */
    public val secondary: Boolean = secondary

    override fun toString(): String =
        "StoreInfo(approximateDocumentCount=$approximateDocumentCount, deletedDocumentCount=$deletedDocumentCount, secondary=$secondary)"
}
