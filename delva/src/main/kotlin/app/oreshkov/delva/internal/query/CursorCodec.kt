package app.oreshkov.delva.internal.query

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.InvalidCursorException
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64

/**
 * Text form of a [Cursor]: base64url without padding of
 * ```
 * cursor := version:u8 = 2 | flags:u8 | docId:varint | nodePre:varint | snapshotLsn:varint | bound:varint
 *           | [ keyLength:varint | utf8 ] | crc32c:u32 LE
 * ```
 * - Bit 0 of `flags` is set for a descending search, bit 1 for a page of objects of a type (always ascending), bit 2
 *   for a search in document-key order, which alone has the key; any other bit, or types with another, is rejected, so
 *   a later flag fails loudly.
 * - `bound` is [Cursor.lateCommitBound]: as is when ascending, plus one when descending (where it can be -1); 0 or 1
 *   for a key cursor.
 *
 * Version 1 (`version:u8 = 1 | docId | nodePre | snapshotLsn | bound | crc32c`) is still read, as an ascending cursor.
 * The CRC covers the bytes before it, so a mistyped cursor is rejected instead of resuming at a wrong place. Every
 * rejection is an [InvalidCursorException].
 */
internal object CursorCodec {
    private const val VERSION_1 = 1
    private const val VERSION = 2
    private const val DESCENDING = 1
    private const val TYPES = 2
    private const val KEYS = 4

    /** Version, flags, four varints, the longest key with its length, and the CRC, in base64 without padding. */
    private const val MAX_BYTES = 2 + 4 * 10 + 2 + CreationOptions.MAX_DOCUMENT_KEY_BYTES + 4
    const val MAX_TEXT_LENGTH: Int = (MAX_BYTES * 4 + 2) / 3

    // Encodes without padding; like java.util.Base64.getUrlDecoder, still accepts padded input.
    private val BASE64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun encode(cursor: Cursor): String {
        val descending = cursor.order == SearchOrder.DESCENDING
        val key = cursor.key?.toByteArray(Charsets.UTF_8)
        val out = GrowableBytes(48 + (key?.size ?: 0))
        out.writeByte(VERSION)
        out.writeByte((if (descending) DESCENDING else 0) or (if (cursor.forTypes) TYPES else 0) or (if (key != null) KEYS else 0))
        out.writeVarLong(cursor.docId.raw)
        out.writeVarLong(cursor.nodePre)
        out.writeVarLong(cursor.snapshotLsn)
        out.writeVarLong(if (descending && key == null) cursor.lateCommitBound + 1 else cursor.lateCommitBound)
        if (key != null) {
            out.writeVarLong(key.size.toLong())
            out.writeBytes(key)
        }
        out.writeIntLE(Crc32c.of(out.array, 0, out.size))
        return BASE64.encode(out.array, 0, out.size)
    }

    fun decode(text: String): Cursor {
        requireCursor(text.length <= MAX_TEXT_LENGTH) { "too long" }
        val bytes = try {
            BASE64.decode(text)
        } catch (e: IllegalArgumentException) {
            throw InvalidCursorException("not a cursor: invalid base64url", e)
        }
        requireCursor(bytes.size >= 1 + 4 + 4) { "too short" }
        val body = bytes.size - 4
        requireCursor(Crc32c.of(bytes, 0, body) == LittleEndian.getInt(bytes, body)) { "checksum mismatch" }
        val version = bytes[0].toInt()
        if (version != VERSION && version != VERSION_1) throw InvalidCursorException("unsupported cursor version $version")
        val flags = if (version == VERSION) bytes[1].toInt() and 0xFF else 0
        requireCursor(flags and (DESCENDING or TYPES or KEYS).inv() == 0 && (flags and TYPES == 0 || flags == TYPES)) { "unknown flags $flags" }
        val descending = flags and DESCENDING != 0
        try {
            val start = if (version == VERSION) 2 else 1
            val reader = ByteReader(bytes, start, body - start, "cursor")
            val docId = reader.readVarLong()
            val nodePre = reader.readVarLong()
            val snapshotLsn = reader.readVarLong()
            val bound = reader.readVarLong()
            val key = if (flags and KEYS != 0) {
                val length = reader.readVarLong()
                requireCursor(length in 0..minOf(CreationOptions.MAX_DOCUMENT_KEY_BYTES, reader.remaining)) { "key of $length bytes" }
                val utf8 = ByteArray(length.toInt())
                reader.readBytes(utf8, 0, utf8.size)
                decodeKey(utf8)
            } else {
                null
            }
            requireCursor(reader.remaining == 0) { "trailing bytes" }
            requireCursor(docId >= 0 && nodePre >= 0 && snapshotLsn >= 0 && bound >= 0) { "negative field" }
            val order = if (descending) SearchOrder.DESCENDING else SearchOrder.ASCENDING
            return when {
                key != null -> {
                    requireCursor(nodePre == 0L && bound <= 1) { "key cursor fields" }
                    Cursor(DocId(docId), 0, snapshotLsn, bound, order, forTypes = false, key = key)
                }
                descending -> Cursor(DocId(docId), nodePre, snapshotLsn, bound - 1, order)
                else -> Cursor(DocId(docId), nodePre, snapshotLsn, bound, order, forTypes = flags == TYPES)
            }
        } catch (e: CorruptionException) {
            throw InvalidCursorException("not a cursor: ${e.message}", e)
        }
    }

    private fun decodeKey(utf8: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(utf8))
            .toString()
    } catch (e: CharacterCodingException) {
        throw InvalidCursorException("not a cursor: the key isn't UTF-8", e)
    }

    /** Like `require`, but the failure is an [InvalidCursorException] saying the text is not a cursor. */
    private inline fun requireCursor(value: Boolean, detail: () -> String) {
        if (!value) throw InvalidCursorException("not a cursor: ${detail()}")
    }
}
