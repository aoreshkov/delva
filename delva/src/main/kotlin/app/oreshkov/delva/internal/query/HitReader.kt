package app.oreshkov.delva.internal.query

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.JsonEvents
import app.oreshkov.delva.internal.payload.JsonOutput
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PrettyJsonOutput
import app.oreshkov.delva.internal.payload.Reassembler
import app.oreshkov.delva.internal.store.DocumentFragments
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.store.SwitchableFragmentStore
import java.io.OutputStream

/**
 * Reads the node of a hit: streams its JSON, compact or formatted, through the reassembler with O(depth) memory,
 * and measures its encoded size. [store] selects the fragments it reads (a search's version), so the engine can pool
 * instances across searches. Reusable; not thread-safe.
 */
internal class HitReader {
    private val fragments = SwitchableFragmentStore()

    var store: FragmentStore
        get() = fragments.target
        set(value) {
            fragments.target = value
        }

    private val locator = NodeLocator(fragments)
    private val lookup = DocumentFragments(fragments)
    private val reassembler = Reassembler(lookup)
    private val view = FragmentView()
    private val reader = PayloadReader()
    private val output = JsonOutput(OutputStream.nullOutputStream(), OUTPUT_BUFFER_BYTES)
    private val pretty = PrettyJsonOutput(output)

    fun writeJson(address: HitAddress, out: OutputStream, format: JsonFormat) {
        output.reset(out)
        val events: JsonEvents = if (format.isCompact) output else pretty.also { it.reset(format) }
        lookup.docId = address.docId
        val node = address.located
        when {
            address.wholeDocument -> reassembler.writeDocument(events)
            node == null -> writeInline(address.docId, address.fragmentPre, address.fragmentKind, address.offset, address.pre, events)
            node.isSplit -> reassembler.writeSplit(node.pre, events)
            else -> writeInline(address.docId, node.holderPre, node.holderKind, node.valueOffset, node.pre, events)
        }
        output.flush()
    }

    /**
     * Payload bytes of the node: the whole document's fragments, or the node's bytes in its holder fragment plus every
     * fragment inside its subtree (those keyed from `(pre, SUBTREE)` through `pre + size`).
     */
    fun encodedSize(address: HitAddress): Long {
        val docId = address.docId
        if (address.wholeDocument) return fragments.payloadBytes(docId, 0, 0, Long.MAX_VALUE)
        val node = address.located ?: locator.atOffset(docId, address.fragmentPre, address.fragmentKind, address.offset, address.pre)
        val stored = fragments.payloadBytes(docId, node.pre, FragmentKind.SUBTREE, node.pre + node.size)
        if (node.isSplit || (node.holderKind == FragmentKind.SUBTREE && node.holderPre == node.pre)) return stored
        holder(docId, node.holderPre, node.holderKind)
        val start = view.payloadOffset + node.valueOffset
        reader.openValue(view.bytes, view.payloadOffset, view.payloadLength, start, node.pre)
        reader.next()
        reader.skipValue()
        return stored + (reader.position - start)
    }

    private fun writeInline(docId: Long, holderPre: Long, holderKind: Int, valueOffset: Int, pre: Long, events: JsonEvents) {
        holder(docId, holderPre, holderKind)
        reassembler.writeValue(view.slice(), valueOffset, pre, events)
    }

    private fun holder(docId: Long, pre: Long, kind: Int) {
        if (!fragments.find(docId, pre, kind, 0, view)) throw CorruptionException("document $docId", "missing fragment ($pre, $kind)")
    }

    private companion object {
        const val OUTPUT_BUFFER_BYTES = 16 * 1024
    }
}
