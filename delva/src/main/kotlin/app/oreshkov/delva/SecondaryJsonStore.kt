package app.oreshkov.delva

/**
 * A store read beside its writer, opened with [JsonStore.openSecondary]: it offers every read of [ReadableJsonStore] and
 * never writes. It takes no lock, so it works while another process has the store open as a [JsonStore], and with no
 * writer at all (SPEC.md §8.1).
 *
 * It reads one state of the store at a time, the one its last [refresh] (or the open) read: everything the writer had
 * acknowledged before that began, and possibly writes it hadn't acknowledged yet. Every read uses the newest such state,
 * and a result keeps the state it started on until it is closed, however often the store is refreshed meanwhile. A
 * secondary doesn't refresh by itself; call [refresh] whenever newer data is wanted.
 *
 * DocIds, [NodeRef]s and [Cursor]s mean the same in the writer and its secondaries, so a cursor from one resumes on the
 * other, as long as the store isn't reset in between. A secondary learns of an insert only once the writer has logged
 * it, so a cursor's [Cursor.mayMissLateCommits] can't account for small inserts the writer had in flight.
 *
 * Closing it releases its file handles; the space of files its writer deleted meanwhile comes back then. Thread-safe.
 */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface SecondaryJsonStore : ReadableJsonStore {
    /**
     * Catches up with what the store's writer has written since this store last read it, and returns whether the state
     * reads see changed. Reads already running, and results already open, keep the state they started on. Calls are
     * serialized: a call waits for one running. Runs on the store's dispatcher.
     *
     * After the writer flushed its indexes, a refresh reads the writer's unflushed log again, which costs what replaying
     * it at open costs. When it fails, with [CorruptionException] for damage the writer had synced or a
     * [java.io.IOException], reads keep the state they had, and a later call tries again.
     */
    public suspend fun refresh(): Boolean

    /**
     * The process that has the store open as its writer, as it recorded itself when it opened the store, or null when no
     * process has it open, or none could tell. The record is advisory, as [StoreLockedException.holder] is: a writer that
     * couldn't write it still holds the store. Reads the record on each call.
     */
    public fun writer(): LockHolder?

    /**
     * The same store without coroutines: a view that shares this store's state, valid until this store is closed.
     * The view has no `close` of its own; closing this store is what releases it.
     */
    override fun blocking(): BlockingSecondaryJsonStore
}

/** The operations of [SecondaryJsonStore] without coroutines, obtained from [SecondaryJsonStore.blocking]. Thread-safe. */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface BlockingSecondaryJsonStore : BlockingReadableJsonStore {
    /** See [SecondaryJsonStore.refresh]. */
    public fun refresh(): Boolean

    /** See [SecondaryJsonStore.writer]. */
    public fun writer(): LockHolder?
}
