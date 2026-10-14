package app.oreshkov.delva

/**
 * The options of one open store, which may differ at every open. Every option has a safe default, so `StoreOptions()`
 * opens a store that works on any heap; the values below bound what a store does, never how much of the JVM's heap
 * it needs, which the engine derives from the heap it runs on. The options fixed when a store is created are
 * [CreationOptions].
 *
 * | Option | Default | Range |
 * |---|---|---|
 * | [encoderBudgetBytes] | 8 MiB, or `2 × fragmentBytes` if that is more | ≥ `2 × fragmentBytes`, ≤ 1 GiB |
 * | [maxDepth] | 1024 | 1 … 1 000 000 |
 * | [maxDocumentBytes] | unlimited | ≥ 1 |
 * | [maxStringBytes] | unlimited | ≥ 0 |
 * | [ioParallelism] | 16 | 1 … 1024 |
 * | [durability] | [Durability.SyncEachBatch] | |
 * | [compression] | true | |
 * | [mmapReads] | false | |
 *
 * Options are values: two instances with the same settings are equal, and [copy] derives a variant, such as
 * `StoreOptions().copy(compression = false)`.
 *
 * - [encoderBudgetBytes]: pending bytes an insert keeps before it writes fragments early; `null` for the default. It
 *   only steers how new inserts are split. Opening a store fails with [IllegalArgumentException] when it is below
 *   twice the store's [CreationOptions.fragmentBytes].
 * - [maxDepth], [maxDocumentBytes], [maxStringBytes]: limits of SPEC.md §1.3; a document over one of them is rejected
 *   with [RejectReason.LIMIT_EXCEEDED].
 * - [ioParallelism]: threads for the suspending operations of [JsonStore].
 * - [durability]: when acknowledged writes are synced to disk.
 * - [compression]: compress fragment payloads. Each payload records how it was stored, so a store written with
 *   compression reads back with it off and the other way round; the option only chooses what new writes do.
 * - [mmapReads]: read sealed segment files through memory mappings instead of positional reads. Saves a copy per block
 *   read and leaves the pages to the OS. Off by default: on Windows a mapped file can't be deleted, so a compaction
 *   that retires a mapped segment waits for its readers, and a store that maps far more than the machine's memory
 *   pages harder than it reads. Try it on read-heavy stores whose segments fit in memory.
 */
public class StoreOptions(
    public val encoderBudgetBytes: Int? = null,
    public val maxDepth: Int = 1024,
    public val maxDocumentBytes: Long = Long.MAX_VALUE,
    public val maxStringBytes: Long = Long.MAX_VALUE,
    public val ioParallelism: Int = 16,
    public val durability: Durability = Durability.SyncEachBatch,
    public val compression: Boolean = true,
    public val mmapReads: Boolean = false,
) {
    init {
        require(encoderBudgetBytes == null || encoderBudgetBytes in 2 * CreationOptions.MIN_FRAGMENT_BYTES..MAX_ENCODER_BUDGET_BYTES) {
            "encoderBudgetBytes $encoderBudgetBytes outside ${2 * CreationOptions.MIN_FRAGMENT_BYTES}..$MAX_ENCODER_BUDGET_BYTES"
        }
        require(maxDepth in 1..MAX_DEPTH_LIMIT) { "maxDepth $maxDepth outside 1..$MAX_DEPTH_LIMIT" }
        require(maxDocumentBytes > 0) { "maxDocumentBytes must be positive, was $maxDocumentBytes" }
        require(maxStringBytes >= 0) { "maxStringBytes must not be negative, was $maxStringBytes" }
        require(ioParallelism in 1..MAX_IO_PARALLELISM) { "ioParallelism $ioParallelism outside 1..$MAX_IO_PARALLELISM" }
    }

    /** The encoder budget a store with fragments of [fragmentBytes] uses: [encoderBudgetBytes], or the default. */
    internal fun encoderBudgetBytesFor(fragmentBytes: Int): Int =
        encoderBudgetBytes ?: maxOf(DEFAULT_ENCODER_BUDGET_BYTES, 2 * fragmentBytes)

    /** These options with the given ones changed; the result is validated like a new instance. */
    public fun copy(
        encoderBudgetBytes: Int? = this.encoderBudgetBytes,
        maxDepth: Int = this.maxDepth,
        maxDocumentBytes: Long = this.maxDocumentBytes,
        maxStringBytes: Long = this.maxStringBytes,
        ioParallelism: Int = this.ioParallelism,
        durability: Durability = this.durability,
        compression: Boolean = this.compression,
        mmapReads: Boolean = this.mmapReads,
    ): StoreOptions = StoreOptions(
        encoderBudgetBytes, maxDepth, maxDocumentBytes, maxStringBytes, ioParallelism, durability, compression, mmapReads,
    )

    override fun equals(other: Any?): Boolean =
        other is StoreOptions && other.encoderBudgetBytes == encoderBudgetBytes && other.maxDepth == maxDepth &&
            other.maxDocumentBytes == maxDocumentBytes && other.maxStringBytes == maxStringBytes &&
            other.ioParallelism == ioParallelism && other.durability == durability && other.compression == compression &&
            other.mmapReads == mmapReads

    override fun hashCode(): Int = listOf(
        encoderBudgetBytes, maxDepth, maxDocumentBytes, maxStringBytes, ioParallelism, durability, compression, mmapReads,
    ).hashCode()

    override fun toString(): String = buildString {
        append("StoreOptions(")
        if (encoderBudgetBytes != null) append("encoderBudgetBytes=").append(encoderBudgetBytes).append(", ")
        append("maxDepth=").append(maxDepth)
        if (maxDocumentBytes != Long.MAX_VALUE) append(", maxDocumentBytes=").append(maxDocumentBytes)
        if (maxStringBytes != Long.MAX_VALUE) append(", maxStringBytes=").append(maxStringBytes)
        append(", ioParallelism=").append(ioParallelism)
        append(", durability=").append(durability)
        if (!compression) append(", compression=false")
        if (mmapReads) append(", mmapReads=true")
        append(')')
    }

    public companion object {
        /** The encoder budget when none is given, unless twice the fragment size is more. */
        public const val DEFAULT_ENCODER_BUDGET_BYTES: Int = 8 * 1024 * 1024

        /** Largest [encoderBudgetBytes]: an insert's pending bytes are held on the heap. */
        public const val MAX_ENCODER_BUDGET_BYTES: Int = 1024 * 1024 * 1024

        /** Largest [maxDepth]; the splitter keeps per-level state, so depth costs memory. */
        public const val MAX_DEPTH_LIMIT: Int = 1_000_000

        /** Largest [ioParallelism]. */
        public const val MAX_IO_PARALLELISM: Int = 1024
    }
}
