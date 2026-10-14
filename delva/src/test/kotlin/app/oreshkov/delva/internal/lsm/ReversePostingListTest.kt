package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [ReversePostingListCursor] against the list it reads: every group last first, seeks to the last group at or below a
 * bound (on a block's last docId, just below its first, below the list, above it), documents whose groups continue
 * into the next block, and damage rejected exactly when the forward cursor rejects it.
 */
class ReversePostingListTest {
    private class Group(val docId: Long, val occurrences: Int, val entries: ByteArray)

    private fun groups(random: Random, count: Int, repeats: Int = 4): List<Group> {
        var docId = random.nextLong(0, 10)
        return List(count) {
            if (random.nextInt(repeats) != 0) docId += random.nextLong(1, 1000)
            val occurrences = random.nextInt(1, 5)
            Group(docId, occurrences, ByteArray(3 * occurrences + random.nextInt(0, 10)) { random.nextInt(256).toByte() })
        }
    }

    private fun write(groups: List<Group>): ByteArray {
        val writer = PostingListWriter()
        for (g in groups) writer.addRun(g.docId, g.occurrences, g.entries, 0, g.entries.size)
        return GrowableBytes(64).also { writer.finish(it) }.toByteArray()
    }

    private fun open(bytes: ByteArray): ReversePostingListCursor {
        val input = ValueInput()
        input.openInline(bytes, 0, bytes.size, "test")
        return ReversePostingListCursor().also { it.open(input) }
    }

    private fun entries(cursor: ReversePostingListCursor): ByteArray {
        val slice = ValueInput()
        cursor.openEntries(slice)
        return ByteArray(slice.length.toInt()) { slice.readByte().toByte() }
    }

    /** Walks [groups] backwards from the cursor's position, which must be [from]'s index. */
    private fun assertWalk(groups: List<Group>, from: Int, cursor: ReversePostingListCursor, label: String) {
        for (i in from downTo 0) {
            val g = groups[i]
            assertEquals(g.docId, cursor.docId, "$label: group $i")
            assertEquals(g.occurrences, cursor.occurrences, "$label: group $i")
            assertContentEquals(g.entries, entries(cursor), "$label: group $i")
            cursor.previous()
        }
        assertEquals(-1, cursor.docId, label)
        cursor.previous()
        assertEquals(-1, cursor.docId, label)
    }

    /** Seeks to [bound] and checks the rest of the walk. */
    private fun assertSeek(groups: List<Group>, bytes: ByteArray, bound: Long, label: String) {
        val cursor = open(bytes)
        cursor.seek(bound)
        val at = groups.indexOfLast { it.docId <= bound }
        assertWalk(groups, at, cursor, "$label seek $bound")
    }

    @Test
    fun `lists of every block shape read backwards and seek to the last group at or below a bound`() {
        val random = Random(17)
        for (count in listOf(1, 2, 127, 128, 129, 255, 256, 257, 1000)) {
            val groups = groups(random, count)
            val bytes = write(groups)
            assertWalk(groups, groups.size - 1, open(bytes), "$count groups")
            val bounds = HashSet<Long>()
            bounds += listOf(-1, 0, groups.first().docId - 1, groups.first().docId, groups.last().docId, groups.last().docId + 1, Long.MAX_VALUE)
            // Each block's last docId and the docIds just below and above its first.
            for (b in 0 until (count + 127) / 128) {
                val first = groups[b * 128].docId
                bounds += listOf(first - 1, first, first + 1, groups[minOf(count, (b + 1) * 128) - 1].docId)
            }
            for (bound in bounds) assertSeek(groups, bytes, bound, "$count groups")
        }
    }

    @Test
    fun `a document whose groups continue into the next block is read whole`() {
        val random = Random(3)
        // Groups 126 to 130 are one document, straddling the boundary between the first two blocks.
        val groups = groups(random, 300).mapIndexed { i, g -> if (i in 126..130) Group(1_000_000, g.occurrences, g.entries) else g }
            .let { list -> list.take(126) + list.subList(126, 131) + list.drop(131).map { Group(it.docId + 2_000_000, it.occurrences, it.entries) } }
        val bytes = write(groups)
        for (bound in listOf(1_000_000L, 1_000_001L, 999_999L, groups[131].docId)) assertSeek(groups, bytes, bound, "straddle")
        // After the seek, the document's groups come from both blocks.
        val cursor = open(bytes)
        cursor.seek(1_000_000)
        var n = 0
        while (cursor.docId == 1_000_000L) {
            n++
            cursor.previous()
        }
        assertEquals(5, n)
    }

    @Test
    fun `seeks never move forwards`() {
        forAll("reverse seeks", Gen { random, size -> groups(random, random.nextInt(1, 6 * size + 2)) to random.nextLong() }, iterations = 200, maxSize = 200) { (groups, seed) ->
            val bytes = write(groups)
            val random = Random(seed)
            val cursor = open(bytes)
            var at = groups.size - 1
            repeat(20) {
                val bound = random.nextLong(-1, groups.last().docId + 2)
                cursor.seek(bound)
                if (at >= 0 && groups[at].docId > bound) at = groups.indexOfLast { it.docId <= bound }
                assertEquals(if (at >= 0) groups[at].docId else -1, cursor.docId, "seek $bound")
                if (at >= 0 && random.nextInt(3) == 0) {
                    cursor.previous()
                    at--
                    assertEquals(if (at >= 0) groups[at].docId else -1, cursor.docId, "previous")
                }
            }
        }
    }

    @Test
    fun `damage is rejected exactly when the forward cursor rejects it`() {
        val random = Random(5)
        val groups = groups(random, 600)
        val original = write(groups)
        var rejected = 0
        repeat(3000) {
            val bytes = original.copyOf()
            bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
            val forward = runCatching {
                val input = ValueInput()
                input.openInline(bytes, 0, bytes.size, "test")
                val cursor = PostingListCursor()
                cursor.open(input)
                val docIds = ArrayList<Long>()
                while (cursor.docId != Long.MAX_VALUE) {
                    docIds += cursor.docId
                    cursor.next()
                }
                docIds
            }
            val backward = runCatching {
                val cursor = open(bytes)
                val docIds = ArrayList<Long>()
                while (cursor.docId >= 0) {
                    docIds += cursor.docId
                    cursor.previous()
                }
                docIds.reversed()
            }
            forward.exceptionOrNull()?.let { if (it !is CorruptionException) throw it }
            backward.exceptionOrNull()?.let { if (it !is CorruptionException) throw it }
            assertEquals(forward.isFailure, backward.isFailure, "forward ${forward.exceptionOrNull()}, backward ${backward.exceptionOrNull()}")
            if (forward.isFailure) rejected++ else assertEquals(forward.getOrThrow(), backward.getOrThrow())
        }
        check(rejected > 100) { "only $rejected damaged lists rejected" }
    }
}
