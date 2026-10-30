package app.oreshkov.delva

import app.oreshkov.delva.internal.engine.Engine
import app.oreshkov.delva.internal.engine.EngineOptions
import app.oreshkov.delva.internal.engine.Opening
import app.oreshkov.delva.internal.engine.SecondaryEngine
import app.oreshkov.delva.internal.engine.StoreReset
import app.oreshkov.delva.internal.facade.CoroutineJsonStore
import app.oreshkov.delva.internal.facade.CoroutineSecondaryJsonStore
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import java.io.InputStream
import java.nio.file.Path

/**
 * An embedded JSON document store. Inserts stream their input, searches return lazily read results (SPEC.md). This is
 * the store's writer, which holds the directory's lock until it's closed, with the read operations of
 * [ReadableJsonStore]; other processes can read the store beside it with [openSecondary]. After a failed write or sync,
 * operations throw [EngineFailedException] until the store is reopened (SPEC.md §8).
 *
 * Inserts of any size run concurrently: small documents are logged together in group commits, large ones stream to
 * lanes of their own, so a huge or slow insert doesn't hold up small ones. Memory stays within the store's budgets
 * whatever the document sizes; when indexes can't be flushed as fast as documents arrive, inserts wait (SPEC.md §8).
 *
 * Objects can carry a type name (SPEC.md §6), given with the insert as JSON paths or assigned later. Types are
 * metadata: they never change the stored JSON.
 *
 * Documents are never changed in place: to change one, [replace] it, which inserts the new version and deletes the old
 * one in one atomic change (SPEC.md §7.1).
 *
 * Stored values and JSON text can be compared for equality or diffed (SPEC.md §9), however large they are.
 */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface JsonStore : ReadableJsonStore {
    /**
     * Inserts the JSON document read from [source], which isn't closed; returns its docId once it's visible. Cancelling
     * the call stops it at the next read of [source], and nothing of the document becomes visible; once [source] is read
     * to its end, the insert completes and returns even if the call is cancelled (SPEC.md §8).
     *
     * [types] maps JSON paths to type names, assigned atomically with the insert. A path is an RFC 9535 singular query
     * without negative indexes (SPEC.md §6.1), such as `$.order.items[0]` or `$['@type']`. An invalid path throws
     * [InvalidJsonPathException] and an invalid name [InvalidTypeNameException] before anything is read; a path that
     * selects nothing, or a value that isn't an object, rejects the document with [RejectReason.TYPE_PATH_UNMATCHED].
     *
     * In a store with a [CreationOptions.documentKey], a document without a key is rejected with
     * [RejectReason.KEY_MISSING], and one whose key a visible document already has throws [WriteConflictException] with
     * [WriteConflictException.key] set; [upsert] replaces that document instead.
     */
    public suspend fun insert(source: InputStream, types: Map<String, String> = emptyMap()): DocId

    /** Inserts the document in the file [path], read as it is split; the file is opened and closed by the call. */
    public suspend fun insert(path: Path, types: Map<String, String> = emptyMap()): DocId

    /** Inserts a document given as a string; meant for small documents. */
    public suspend fun insert(json: String, types: Map<String, String> = emptyMap()): DocId

    /**
     * Inserts the JSON document read from [source] as [insert] does, and deletes the documents of [docIds] in the same
     * atomic change (SPEC.md §7.1): once it's acknowledged the new document is visible and they are deleted, and a
     * crash leaves either both or neither. Returns the new document's docId.
     *
     * It is a compare-and-set: every document of [docIds] must still be visible when the change takes effect. When one
     * isn't — deleted, replaced by another call first, or never stored — it throws [WriteConflictException] and changes
     * nothing; a conflict known when the call starts throws before [source] is read. Repeated docIds count once, and
     * with none it is an [insert].
     */
    @ExperimentalDelvaApi
    public suspend fun replace(docIds: Collection<DocId>, source: InputStream, types: Map<String, String> = emptyMap()): DocId

    /** [replace] with the document in the file [path], opened and closed by the call. */
    @ExperimentalDelvaApi
    public suspend fun replace(docIds: Collection<DocId>, path: Path, types: Map<String, String> = emptyMap()): DocId

    /** [replace] with the document given as a string; meant for small documents. */
    @ExperimentalDelvaApi
    public suspend fun replace(docIds: Collection<DocId>, json: String, types: Map<String, String> = emptyMap()): DocId

    /**
     * Inserts the JSON document read from [source] as [insert] does, in a store with a [CreationOptions.documentKey], and
     * deletes the visible document with the same key, if there is one, in the same atomic change (SPEC.md §3.3): once
     * it's acknowledged the new document is visible and the old one deleted, and a crash leaves either both or neither.
     * Of two upserts of one key, the one acknowledged last wins. Returns the new document's docId.
     *
     * The key is read with the document, so a document without one is rejected with [RejectReason.KEY_MISSING]. A store
     * without document keys throws [IllegalStateException].
     */
    @ExperimentalDelvaApi
    public suspend fun upsert(source: InputStream, types: Map<String, String> = emptyMap()): DocId

    /** [upsert] with the document in the file [path], opened and closed by the call. */
    @ExperimentalDelvaApi
    public suspend fun upsert(path: Path, types: Map<String, String> = emptyMap()): DocId

    /** [upsert] with the document given as a string; meant for small documents. */
    @ExperimentalDelvaApi
    public suspend fun upsert(json: String, types: Map<String, String> = emptyMap()): DocId

    /**
     * Inserts the documents of [sources] in order, reading each to its end (streams aren't closed). Small documents are
     * logged in shared group commits, so bulk loads sync far less often than with one [insert] per document. Returns one
     * outcome per source; a rejected document, or a replace that conflicts, doesn't stop the others. Any other error is
     * thrown: documents whose outcome wasn't returned may or may not be visible.
     *
     * Each source's [InsertSource.types] are assigned with its document as [insert] assigns them. They are checked for
     * every source before any is read: an invalid path throws [InvalidJsonPathException] and an invalid name
     * [InvalidTypeNameException], and nothing is inserted.
     *
     * A source with [InsertSource.replaces] is a [replace]: its outcome is [InsertOutcome.Conflicted] when one of the
     * documents it replaces isn't visible, which includes one an earlier source of the call replaces or inserts.
     *
     * In a store with a [CreationOptions.documentKey], sources take effect in order, so a later source sees the keys of
     * the earlier ones: with [KeyConflict.FAIL] a key an earlier source inserted conflicts, with [KeyConflict.REPLACE]
     * the later source replaces it.
     */
    public suspend fun insertAll(sources: Iterable<InsertSource>): List<InsertOutcome>

    /**
     * Sets the type of the object [node] to [type], or removes it with null; returns once the change is acknowledged
     * (SPEC.md §6.1). Assigning the type the object already has changes nothing.
     *
     * Throws [DocumentNotFoundException] when the document doesn't exist or isn't visible, [IllegalArgumentException]
     * when the node doesn't exist or isn't an object, and [InvalidTypeNameException] for an invalid name.
     */
    public suspend fun assignType(node: NodeRef, type: String?)

    /**
     * Applies [assignments] in order as one atomic change; a later assignment to the same object sees the earlier ones.
     * When one of them fails the checks of [assignType], nothing changes.
     */
    public suspend fun assignTypes(assignments: List<Pair<NodeRef, String?>>)

    /**
     * Removes [type] from every object that has it, as one atomic change, and returns how many objects lost it; returns
     * once the change is acknowledged. Every object that has the type when the change is applied loses it, so none of
     * those typed by operations acknowledged before the call keeps it. The name stays in [listTypes]. The call holds 32
     * bytes per object of the type while it runs. Throws [InvalidTypeNameException] for an invalid name.
     */
    public suspend fun clearType(type: String): Long

    /**
     * Deletes the document [docId] with its type assignments (SPEC.md §7); returns once the delete is acknowledged. Returns
     * true when this call deleted it, false when it was already deleted. Searches started before keep seeing the document
     * until they're closed.
     *
     * Throws [DocumentNotFoundException] when [docId] never named a stored document: not assigned yet, still being
     * inserted, or given to an insert that was rejected.
     */
    public suspend fun delete(docId: DocId): Boolean

    /**
     * Deletes the documents of [docIds] as one atomic change; returns how many this call deleted, skipping documents
     * already deleted and repeated docIds. When one of them fails the checks of [delete], nothing changes.
     */
    public suspend fun deleteAll(docIds: Collection<DocId>): Int

    /**
     * The same store without coroutines: a view that shares this store's state, valid until this store is closed.
     * The view has no `close` of its own; closing this store is what releases it.
     */
    override fun blocking(): BlockingJsonStore

    /**
     * Three ways to get a store's writer, one per intent, so that a mistyped path never quietly becomes a new, empty
     * store: [open] for a store that exists, [create] for a new one, and [openOrCreate] where either will do. And
     * [openSecondary] to read a store beside its writer, and [probe] to tell whether this version of Delva reads a
     * store.
     *
     * Each of the first three locks the directory until the store is closed ([StoreLockedException] if it's already
     * open, naming the process that has it open when it can tell) and recovers the store from its log. Each throws
     * [UnsupportedFormatException] when another version of Delva wrote the store in a format this one doesn't read, and
     * then leaves it as it was; [CorruptionException] when the store's metadata or control log is damaged;
     * [IllegalArgumentException] when [StoreOptions.encoderBudgetBytes] is below twice the store's fragment size; and
     * [java.io.IOException] when the files can't be accessed.
     */
    public companion object {
        /**
         * Opens the store in [dir], with the [CreationOptions] it was created with, read from the store. Throws
         * [StoreNotFoundException] when [dir] holds no store, and then leaves it exactly as it was.
         */
        public fun open(dir: Path, options: StoreOptions = StoreOptions()): JsonStore =
            open(dir, options, JdkFileSystemIo(mmapReads = options.mmapReads))

        internal fun open(dir: Path, options: StoreOptions, io: FileSystemIo): JsonStore =
            CoroutineJsonStore(Engine.openStored(dir, io) { creation -> EngineOptions(creation, options) }, options.ioParallelism)

        /**
         * Creates a store in [dir], making the directory if it's missing. Throws [StoreExistsException] when [dir]
         * already holds a store, which is left as it was.
         */
        public fun create(dir: Path, creation: CreationOptions = CreationOptions(), options: StoreOptions = StoreOptions()): JsonStore =
            open(dir, EngineOptions(creation, options), JdkFileSystemIo(mmapReads = options.mmapReads), Opening.CREATE)

        /**
         * Opens the store in [dir], or creates one with [creation] if it holds none. Throws
         * [CreationOptionsMismatchException] when the store there was created with other [CreationOptions], and then
         * leaves it as it was; [open] takes whatever it was created with.
         */
        public fun openOrCreate(
            dir: Path,
            creation: CreationOptions = CreationOptions(),
            options: StoreOptions = StoreOptions(),
        ): JsonStore = open(dir, EngineOptions(creation, options), JdkFileSystemIo(mmapReads = options.mmapReads), Opening.OPEN_OR_CREATE)

        /**
         * Opens the store in [dir] for reading beside its writer, in another process, or with no writer at all
         * (SPEC.md §8.1). It takes no lock and never changes anything in [dir]: it reads what the writer had written
         * when it opened, and catches up with what the writer writes later on [SecondaryJsonStore.refresh].
         *
         * Of [options], the caches and [StoreOptions.ioParallelism] apply; what concerns writing is ignored, and files
         * are never mapped, whatever [StoreOptions.mmapReads] says. The store must be on a local file system of the
         * writer's host. While open, it holds a file handle for each log and index file of the state it reads.
         *
         * Throws [StoreNotFoundException] when [dir] holds no store, and then reads nothing; [UnsupportedFormatException]
         * when the store is in a format this version of Delva doesn't read; [CorruptionException] when the store's
         * metadata, or a part of its log the writer had synced, is damaged; and [java.io.IOException] when the files
         * can't be read, or the writer changed them faster than they could be read.
         */
        public fun openSecondary(dir: Path, options: StoreOptions = StoreOptions()): SecondaryJsonStore =
            openSecondary(dir, options, JdkFileSystemIo(mmapReads = false))

        internal fun openSecondary(dir: Path, options: StoreOptions, io: FileSystemIo): SecondaryJsonStore =
            CoroutineSecondaryJsonStore(SecondaryEngine.open(dir, io) { creation -> EngineOptions(creation, options) }, options.ioParallelism)

        /**
         * Empties the store in [dir], which must not be open, leaving it as [create] left it: it keeps the
         * [CreationOptions] it was created with, which are returned, and loses every document, deletion, type and
         * compression dictionary. DocIds start again at 1, so a [DocId], [NodeRef] or [Cursor] from before the reset
         * may name a new document afterwards, as with a store deleted and created again. A [SecondaryJsonStore] open
         * on the store keeps the state it read until its next refresh, which shows the empty store.
         *
         * A crash during the reset leaves the store either as it was or empty. Only the store's metadata is read, so
         * a store whose log or index files are too damaged to open can still be reset; files in [dir] that aren't
         * the store's are left alone.
         *
         * Throws [StoreNotFoundException] when [dir] holds no store, and then changes nothing; [StoreLockedException]
         * when the store is open; [UnsupportedFormatException] when the store is in a format this version of Delva
         * doesn't read, and then changes nothing; [CorruptionException] when no MANIFEST of the store can be read, so
         * its creation options are lost (delete the directory and [create] the store again); and
         * [java.io.IOException] when the files can't be accessed.
         */
        public fun reset(dir: Path): CreationOptions = reset(dir, JdkFileSystemIo(mmapReads = false))

        internal fun reset(dir: Path, io: FileSystemIo): CreationOptions = StoreReset.run(dir, io)

        /**
         * The format of the store in [dir], or null when [dir] holds no store; it tells whether this version of Delva
         * opens the store ([StoreFormat.supported]) without opening it. It reads only the store's metadata, takes no
         * lock and changes nothing, so it works beside a writer, as [openSecondary] does.
         *
         * Throws [CorruptionException] when the store's metadata is damaged, so its format can't be told, and
         * [java.io.IOException] when the files can't be read, or the writer changed them faster than they could be
         * read.
         */
        @ExperimentalDelvaApi
        public fun probe(dir: Path): StoreFormat? = probe(dir, JdkFileSystemIo(mmapReads = false))

        @ExperimentalDelvaApi
        internal fun probe(dir: Path, io: FileSystemIo): StoreFormat? = SecondaryEngine.probe(dir, io)

        internal fun open(dir: Path, options: EngineOptions, io: FileSystemIo, opening: Opening = Opening.OPEN_OR_CREATE): JsonStore =
            CoroutineJsonStore(Engine.open(dir, options, io, opening), options.store.ioParallelism)
    }
}

/**
 * The operations of [JsonStore] without coroutines, obtained from [JsonStore.blocking]. Thread-safe.
 *
 * It is a view of a [JsonStore] and deliberately not [AutoCloseable]: the [JsonStore] owns the directory lock and
 * the files, so it is the [JsonStore] that is closed, typically with `JsonStore.open(dir).use { … }`.
 */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface BlockingJsonStore : BlockingReadableJsonStore {
    /** See [JsonStore.insert]. */
    public fun insert(source: InputStream, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.insert]. */
    public fun insert(path: Path, types: Map<String, String> = emptyMap()): DocId

    /** Inserts a document given as a string; meant for small documents. */
    public fun insert(json: String, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.replace]. */
    @ExperimentalDelvaApi
    public fun replace(docIds: Collection<DocId>, source: InputStream, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.replace]. */
    @ExperimentalDelvaApi
    public fun replace(docIds: Collection<DocId>, path: Path, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.replace]. */
    @ExperimentalDelvaApi
    public fun replace(docIds: Collection<DocId>, json: String, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.upsert]. */
    @ExperimentalDelvaApi
    public fun upsert(source: InputStream, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.upsert]. */
    @ExperimentalDelvaApi
    public fun upsert(path: Path, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.upsert]. */
    @ExperimentalDelvaApi
    public fun upsert(json: String, types: Map<String, String> = emptyMap()): DocId

    /** See [JsonStore.insertAll]. */
    public fun insertAll(sources: Iterable<InsertSource>): List<InsertOutcome>

    /** See [JsonStore.assignType]. */
    public fun assignType(node: NodeRef, type: String?)

    /** See [JsonStore.assignTypes]. */
    public fun assignTypes(assignments: List<Pair<NodeRef, String?>>)

    /** See [JsonStore.clearType]. */
    public fun clearType(type: String): Long

    /** See [JsonStore.delete]. */
    public fun delete(docId: DocId): Boolean

    /** See [JsonStore.deleteAll]. */
    public fun deleteAll(docIds: Collection<DocId>): Int
}

/** A type name and roughly how many objects have it (SPEC.md §6.2), as [ReadableJsonStore.listTypes] lists them. */
public class TypeInfo internal constructor(
    /** The type's name. */
    public val name: String,
    /** How many objects have the type: exact only while no object's type was changed or removed. */
    public val approximateCount: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is TypeInfo && other.name == name && other.approximateCount == approximateCount

    override fun hashCode(): Int = 31 * name.hashCode() + approximateCount.hashCode()

    override fun toString(): String = "TypeInfo(name=$name, approximateCount=$approximateCount)"
}

/**
 * One document for [JsonStore.insertAll]: the stream its JSON is read from, which isn't closed, the types to assign
 * with it, JSON paths to type names as [JsonStore.insert] takes them, the documents it [replaces], and in a store with a
 * [CreationOptions.documentKey] what it does when its key is taken ([onExistingKey]).
 */
public class InsertSource @ExperimentalDelvaApi constructor(
    /** The stream the document's JSON is read from; it isn't closed. */
    public val source: InputStream,
    /** The types to assign with the document: JSON paths to type names, as [JsonStore.insert] takes them. */
    public val types: Map<String, String> = emptyMap(),
    replaces: Collection<DocId>,
    onExistingKey: KeyConflict = KeyConflict.FAIL,
) {
    /** A plain insert: the source replaces nothing, and a key that is taken fails it. */
    public constructor(source: InputStream, types: Map<String, String> = emptyMap()) : this(source, types, emptyList())

    /**
     * The documents deleted in the same atomic change as this insert, each once, as [JsonStore.replace] deletes them;
     * empty for a plain insert.
     */
    @ExperimentalDelvaApi
    public val replaces: List<DocId> = replaces.distinct()

    /**
     * In a store with a [CreationOptions.documentKey], what happens when a visible document that [replaces] doesn't name
     * already has this document's key: [KeyConflict.FAIL] makes the outcome [InsertOutcome.Conflicted],
     * [KeyConflict.REPLACE] replaces that document too, as [JsonStore.upsert] does. Ignored in a store without keys.
     */
    @ExperimentalDelvaApi
    public val onExistingKey: KeyConflict = onExistingKey

    override fun toString(): String = buildString {
        append("InsertSource(types=").append(types)
        if (replaces.isNotEmpty()) append(", replaces=").append(replaces.map { it.raw })
        if (onExistingKey != KeyConflict.FAIL) append(", onExistingKey=").append(onExistingKey)
        append(')')
    }
}

/** What [JsonStore.insertAll] did with one document. */
public sealed interface InsertOutcome {
    /** The document is visible as [docId]; the documents its source replaces are deleted. */
    public class Inserted(@get:JvmName("getDocId") public val docId: DocId) : InsertOutcome {
        override fun toString(): String = "Inserted(${docId.raw})"
    }

    /** The document was rejected, for the reason [exception] gives; nothing of it is visible. */
    public class Rejected(public val exception: DocumentRejectedException) : InsertOutcome {
        override fun toString(): String = "Rejected(${exception.reason})"
    }

    /**
     * The source replaces documents that weren't visible, or its document key is taken, as [exception] tells; nothing of
     * it is visible and nothing was deleted.
     */
    public class Conflicted(public val exception: WriteConflictException) : InsertOutcome {
        override fun toString(): String = "Conflicted(${exception.conflicting.map { it.raw }})"
    }
}
