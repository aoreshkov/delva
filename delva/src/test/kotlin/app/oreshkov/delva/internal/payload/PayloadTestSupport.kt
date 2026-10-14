package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The whole document as one pending value. */
internal fun pendingOf(json: ByteArray, window: Int = 64 * 1024): GrowableBytes {
    val out = GrowableBytes()
    PendingEncoder().encode(Utf8JsonTokenizer(ByteArrayInputStream(json), ParseLimits(maxDepth = 10_000), window), out)
    return out
}

/** The whole document as a single SUBTREE payload. */
internal fun subtreePayload(json: ByteArray, window: Int = 64 * 1024): ByteArray {
    val pending = pendingOf(json, window)
    val out = GrowableBytes()
    FragmentEncoder().encodeValue(pending.array, 0, pending.size, out)
    return out.toByteArray()
}

internal fun subtreePayload(json: String) = subtreePayload(json.toByteArray())

/** Decodes a whole payload to JSON text. */
internal fun payloadJson(payload: ByteArray, kind: Int = FragmentKind.SUBTREE, pre: Long = 0): String {
    val reader = PayloadReader()
    reader.open(payload, 0, payload.size, kind, pre)
    return writeJson(reader)
}

internal fun writeJson(reader: PayloadReader): String {
    val bytes = ByteArrayOutputStream()
    val out = JsonOutput(bytes, bufferBytes = 16)
    PayloadJson.write(reader, out)
    out.flush()
    check(out.bytesWritten == bytes.size().toLong())
    return bytes.toString(Charsets.UTF_8)
}

/** Every value of [value] in pre-order: the list index is the value's `pre`. */
internal fun preOrder(value: OValue): List<OValue> = ArrayList<OValue>().also { collect(value, it) }

private fun collect(value: OValue, out: MutableList<OValue>) {
    out += value
    when (value) {
        is OArr -> value.items.forEach { collect(it, out) }
        is OObj -> value.members.forEach { collect(it.second, out) }
        else -> {}
    }
}

/** Offsets of each member's start (its key for objects) in a pending container at [containerOffset], plus the end. */
internal fun pendingMemberOffsets(pending: ByteArray, containerOffset: Int): IntArray {
    val isObject = pending[containerOffset].toInt() == PayloadTag.OBJ
    val count = PendingFormat.count(pending, containerOffset)
    val offsets = IntArray(count + 1)
    var pos = containerOffset + PendingFormat.CONTAINER_HEADER_BYTES
    for (i in 0 until count) {
        offsets[i] = pos
        if (isObject) pos = PendingFormat.varintEnd(pending, pos) + PendingFormat.varint(pending, pos).toInt()
        pos = skipPendingValue(pending, pos)
    }
    offsets[count] = pos
    return offsets
}

internal fun skipPendingValue(pending: ByteArray, offset: Int): Int {
    val tag = pending[offset].toInt() and 0xFF
    if (tag != PayloadTag.OBJ && tag != PayloadTag.ARR) return PendingFormat.scalarEnd(pending, offset)
    return pendingMemberOffsets(pending, offset).last()
}

/** Members as they appear in a PART's JSON: `"k":v,…` for objects, `v,…` for arrays. */
internal fun membersJson(container: OValue, from: Int, to: Int): String = buildString {
    for (i in from until to) {
        if (i > from) append(',')
        when (container) {
            is OObj -> {
                JsonText.writeMinifiedString(this, container.members[i].first)
                append(':').append(JsonText.minified(container.members[i].second))
            }
            is OArr -> append(JsonText.minified(container.items[i]))
            else -> error("not a container")
        }
    }
}
