package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.Normalization
import app.oreshkov.delva.internal.canon.Nfc
import app.oreshkov.delva.internal.lsm.KeyIndexView
import app.oreshkov.delva.internal.query.HitAddress
import app.oreshkov.delva.internal.query.HitSource
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Document keys as the key index holds them. */
internal object DocumentKeys {
    /**
     * [key] as UTF-8, normalized as the store normalizes keys; null when it has a lone surrogate, which no stored key has
     * (stored strings are Unicode scalar values).
     */
    fun encode(key: String, normalization: Normalization): ByteArray? {
        val text = if (normalization == Normalization.NFC) Nfc.normalize(key, "document key") else key
        return try {
            val buffer = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun decode(key: ByteArray): String = String(key, Charsets.UTF_8)
}

/** The hits of a search in key order: each visible document of [scan] once, as its root, with its key. */
internal class KeyHitSource(private val scan: KeyIndexView.KeyScan?) : HitSource {
    override var hit: HitAddress? = null
        private set

    override fun next(): Boolean {
        if (scan == null || !scan.valid) {
            hit = null
            return false
        }
        hit = HitAddress(scan.docId, 0, true, null, 0, 0, 0, documentKey = DocumentKeys.decode(scan.key))
        scan.next()
        return true
    }
}
