package app.oreshkov.delva.internal.query

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.HeaderReader
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.store.FragmentStore

/**
 * Where a stored value is: either at [valueOffset] (from the payload start) of the SUBTREE or PART fragment
 * `(holderPre, holderKind)` — inline, or as a REF, SPLIT or BIGSTR stub — or, when [isSplit], as the split container
 * whose HEADER is at [pre] (a split root, or a HEADER posting locator).
 *
 * [size] is the value's descendant count; [containerKind] is [PayloadTag.OBJ] or [PayloadTag.ARR] for containers and
 * 0 for scalars. Immutable.
 */
internal class LocatedNode(
    val pre: Long,
    val isSplit: Boolean,
    val holderPre: Long,
    val holderKind: Int,
    val valueOffset: Int,
    val size: Long,
    val containerKind: Int,
)

/**
 * Finds stored values by `pre`: a fragment-index floor lookup, then parent hops through
 * `context.parentPre` while the fragment doesn't reach the value, and descents into REF and SPLIT stubs that cover it;
 * each fragment is decoded forward at most once. Reusable; not thread-safe.
 */
internal class NodeLocator(private val store: FragmentStore) {
    private val view = FragmentView()
    private val reader = PayloadReader()
    private val header = HeaderReader()

    /** Fragments decoded by this locator. */
    var fragmentsScanned: Long = 0
        private set

    /** The value with `pre` [target] of document [docId]. */
    fun locate(docId: Long, target: Long): LocatedNode {
        require(target >= 0)
        if (target == 0L) {
            if (store.find(docId, 0, FragmentKind.SUBTREE, 0, view)) return scanFor(docId, 0) ?: corrupt(docId, target, "the root SUBTREE doesn't start with it")
            return split(docId, 0)
        }
        var found = store.floor(docId, target, FragmentKind.SUBTREE, FragmentEntry.MAX_SEQ, view)
        var steps = 0
        while (true) {
            if (!found) corrupt(docId, target, "no fragment holds it")
            if (++steps > MAX_STEPS) corrupt(docId, target, "fragment references loop")
            found = when (view.kind) {
                FragmentKind.SUBTREE, FragmentKind.PART -> {
                    scanFor(docId, target)?.let { return it }
                    when (stubEvent) {
                        PayloadReader.REF -> store.find(docId, stubPre, FragmentKind.SUBTREE, 0, view)
                        PayloadReader.SPLIT -> findPart(docId, stubPre, target)
                        else -> hop(docId)
                    }
                }
                FragmentKind.HEADER -> {
                    val containerPre = view.pre
                    if (!store.find(docId, containerPre, FragmentKind.HEADER, 0, view)) corrupt(docId, containerPre, "missing HEADER 0")
                    header.open(view.bytes, view.payloadOffset, view.payloadLength, 0, "fragment ($containerPre, HEADER, 0)")
                    if (target <= containerPre + header.size) findPart(docId, containerPre, target) else hop(docId)
                }
                else -> hop(docId)
            }
        }
    }

    /** The object starting at [offset] of fragment `(fragmentPre, kind)`, as a resolved posting locator names it. */
    fun atOffset(docId: Long, fragmentPre: Long, kind: Int, offset: Int, expectedPre: Long): LocatedNode {
        if (!store.find(docId, fragmentPre, kind, 0, view)) corrupt(docId, expectedPre, "locator names missing fragment ($fragmentPre, $kind)")
        reader.openValue(view.bytes, view.payloadOffset, view.payloadLength, view.payloadOffset + offset, expectedPre, "fragment ($docId, $fragmentPre, $kind)")
        return valueAt(expectedPre)
    }

    /** The split container whose HEADER is at [pre]. */
    fun split(docId: Long, pre: Long): LocatedNode {
        if (!store.find(docId, pre, FragmentKind.HEADER, 0, view)) corrupt(docId, pre, "no SUBTREE or HEADER")
        header.open(view.bytes, view.payloadOffset, view.payloadLength, 0, "fragment ($docId, $pre, HEADER, 0)")
        return LocatedNode(pre, true, pre, FragmentKind.HEADER, 0, header.size, header.containerKind)
    }

    private var stubEvent = 0
    private var stubPre = 0L

    /**
     * Decodes the fragment in [view] looking for the value at [target]. Returns it when found; otherwise sets
     * [stubEvent] to REF or SPLIT with [stubPre] when a stub covers the target, or to END when the fragment ends first.
     */
    private fun scanFor(docId: Long, target: Long): LocatedNode? {
        fragmentsScanned++
        reader.open(view.bytes, view.payloadOffset, view.payloadLength, view.kind, view.pre, "fragment ($docId, ${view.pre}, ${view.kind})")
        stubEvent = PayloadReader.END
        while (true) {
            val event = reader.next()
            when (event) {
                PayloadReader.END -> return null
                PayloadReader.END_OBJECT, PayloadReader.END_ARRAY -> continue
            }
            val pre = reader.pre
            if (pre == target) return located(event)
            if (pre > target) return null
            when (event) {
                PayloadReader.START_OBJECT, PayloadReader.START_ARRAY -> if (target > pre + reader.size) reader.skipValue()
                PayloadReader.REF, PayloadReader.SPLIT -> if (target <= pre + reader.size) {
                    stubEvent = event
                    stubPre = pre
                    return null
                }
            }
        }
    }

    private fun valueAt(expectedPre: Long): LocatedNode {
        val event = reader.next()
        if (event == PayloadReader.END || reader.pre != expectedPre) throw CorruptionException("fragment holding pre $expectedPre", "found event $event at pre ${reader.pre}")
        return located(event)
    }

    private fun located(event: Int): LocatedNode {
        val kind = when (event) {
            PayloadReader.START_OBJECT -> PayloadTag.OBJ
            PayloadReader.START_ARRAY -> PayloadTag.ARR
            PayloadReader.REF, PayloadReader.SPLIT -> reader.containerKind
            else -> 0
        }
        val size = if (kind != 0) reader.size else 0L
        return LocatedNode(reader.pre, false, view.pre, view.kind, reader.valueOffset - view.payloadOffset, size, kind)
    }

    /** Sets [view] to the PART of the split container at [containerPre] whose members include [target]. */
    private fun findPart(docId: Long, containerPre: Long, target: Long): Boolean {
        var partPre = -1L
        var seq = 0
        records@ while (store.find(docId, containerPre, FragmentKind.HEADER, seq, view)) {
            header.open(view.bytes, view.payloadOffset, view.payloadLength, seq, "fragment ($docId, $containerPre, HEADER, $seq)")
            while (header.nextEntry()) {
                if (header.firstPre > target) break@records
                partPre = header.firstPre
            }
            seq++
        }
        if (partPre < 0) corrupt(docId, target, "the directory of the container at $containerPre has no PART for it")
        return store.find(docId, partPre, FragmentKind.PART, 0, view)
    }

    /** Moves to the fragment holding the parent container: a split parent's HEADER, or the fragment before it. */
    private fun hop(docId: Long): Boolean {
        val parent = view.context.parentPre
        if (parent < 0) return false
        return store.floor(docId, parent, FragmentKind.HEADER, FragmentEntry.MAX_SEQ, view)
    }

    private fun corrupt(docId: Long, pre: Long, detail: String): Nothing = throw CorruptionException("document $docId at pre $pre", detail)

    private companion object {
        /** Hops and descents per lookup; each moves to a different fragment, so real documents stay far below. */
        const val MAX_STEPS = 1_000_000
    }
}
