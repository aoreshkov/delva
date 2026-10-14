package app.oreshkov.delva.internal.compare

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.payload.PayloadWriter
import app.oreshkov.delva.internal.store.MemoryFragmentStore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A split container's part directory is sized by the entries it holds, not by the part count its HEADER claims. */
class SplitInfoTest {
    private fun storeWithHeader(partCount: Long, entries: Int): MemoryFragmentStore {
        val payload = GrowableBytes()
        payload.writeVarLong(0) // no keys
        PayloadWriter.headerSummary(payload, PayloadTag.ARR, count = partCount, size = partCount, hashLow = 0, hashHigh = 0, partCount = partCount)
        PayloadWriter.dirEntryCount(payload, entries)
        for (i in 0 until entries) PayloadWriter.dirEntry(payload, firstPre = 1L + i, firstIndex = i.toLong(), memberCount = 1)
        return MemoryFragmentStore(1 shl 16).apply { add(DOC_ID, 0, FragmentKind.HEADER, 0, FragmentContext(), payload.array, 0, payload.size) }
    }

    private fun splitInfo(store: MemoryFragmentStore) =
        CompareSide(store, DOC_ID, stored = true, partCacheBytes = 0, types = null, CompareStats()).splitInfo(0, 0, -1, -1)

    @Test
    fun `a HEADER claiming billions of parts with one directory entry is corruption, not OutOfMemoryError`() {
        val e = assertFailsWith<CorruptionException> { splitInfo(storeWithHeader(Int.MAX_VALUE.toLong(), entries = 1)) }
        assertContains(e.message!!, "missing HEADER 1")
    }

    @Test
    fun `a directory longer than the first allocation is read whole`() {
        val info = splitInfo(storeWithHeader(100, entries = 100))
        assertEquals(100, info.partCount)
        assertEquals((1L..100L).toList(), info.firstPres.toList())
        assertEquals(List(100) { 1L }, info.memberCounts.toList())
    }

    private companion object {
        const val DOC_ID = 1L
    }
}
