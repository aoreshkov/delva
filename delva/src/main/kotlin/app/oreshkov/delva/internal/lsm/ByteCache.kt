package app.oreshkov.delva.internal.lsm

/**
 * A byte-bounded LRU cache of verified, immutable byte arrays keyed by a `Long` (the block cache
 * and the fragment cache). Keys are spread over shards, each an access-ordered map under its own lock, so concurrent readers
 * rarely contend. Cached arrays must never be modified. Thread-safe.
 */
internal class ByteCache(val capacityBytes: Long, shardCount: Int = 16) {
    private val shards: Array<Shard>
    private val mask: Int

    init {
        require(capacityBytes >= 0 && shardCount > 0 && shardCount and (shardCount - 1) == 0)
        mask = shardCount - 1
        shards = Array(shardCount) { Shard(capacityBytes / shardCount) }
    }

    fun get(key: Long): ByteArray? {
        if (capacityBytes == 0L) return null
        return shard(key).get(key)
    }

    fun put(key: Long, value: ByteArray) {
        if (capacityBytes == 0L) return
        shard(key).put(key, value)
    }

    private fun shard(key: Long): Shard {
        val h = key * -0x61c8864680b583ebL
        return shards[(h ushr 40).toInt() and mask]
    }

    private class Shard(private val capacity: Long) {
        private val map = LinkedHashMap<Long, ByteArray>(64, 0.75f, true)
        private var used = 0L

        @Synchronized
        fun get(key: Long): ByteArray? = map[key]

        @Synchronized
        fun put(key: Long, value: ByteArray) {
            val cost = cost(value)
            if (cost > capacity) return
            map.put(key, value)?.let { used -= cost(it) }
            used += cost
            val iterator = map.entries.iterator()
            while (used > capacity && iterator.hasNext()) {
                used -= cost(iterator.next().value)
                iterator.remove()
            }
        }

        private fun cost(value: ByteArray): Long = value.size.toLong() + ENTRY_OVERHEAD_BYTES
    }

    private companion object {
        /** Map entry, boxed key and array header. */
        const val ENTRY_OVERHEAD_BYTES = 96L
    }
}
