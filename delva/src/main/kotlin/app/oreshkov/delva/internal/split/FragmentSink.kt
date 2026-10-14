package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.payload.FragmentContext

/** Receives the fragments of a document as the splitter writes them. */
internal fun interface FragmentSink {
    /**
     * A fragment with key `(docId, pre, kind, seq)` ([app.oreshkov.delva.internal.payload.FragmentKind]) and its
     * payload `payload[offset, offset + length)`. [context] and [payload] are valid only during the call.
     */
    fun fragment(docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int)
}

/** Receives type assignments given with the insert as their paths match. */
internal fun interface TypeSink {
    fun assign(docId: Long, nodePre: Long, typeName: String)
}
