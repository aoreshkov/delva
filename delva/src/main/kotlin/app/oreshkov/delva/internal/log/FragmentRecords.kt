package app.oreshkov.delva.internal.log

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind

/**
 * Body of a FRAGMENT record:
 * ```
 * body    := key | context | codec | payload
 * key     := docId:u64 | pre:u64 | kind:u8 | seq:u32                big-endian, the fragment-index key
 * context := see FragmentRecord                                     "none" stored as 0
 * codec   := codecId:u8 | dictId:u16 | rawLength:u32                little-endian
 * payload := the payload as codecId stores it
 * ```
 * Codecs: 0 NONE (dictId 0, the payload has rawLength bytes), 1 DEFLATE (dictId 0, raw deflate), 2 DEFLATE_DICT
 * (dictId ≥ 1, raw deflate with that preset dictionary); other ids are corruption. `rawLength` is at most the
 * largest record a store's fragment size allows.
 */
internal object FragmentRecords {
    const val KEY_BYTES: Int = 21

    /** Appends a framed FRAGMENT record to [out], its payload stored by [codecs]; returns the payload's offset from the record start. */
    fun write(out: GrowableBytes, docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int, codecs: FragmentCodecs): Int {
        val start = LogFormat.beginRecord(out)
        out.writeLongBE(docId)
        out.writeLongBE(pre)
        out.writeByte(kind)
        out.writeIntBE(seq)
        FragmentRecord.writeContext(out, context)
        val payloadOffset = out.size - start + FragmentCodecs.CODEC_HEADER_BYTES
        codecs.writePayload(out, payload, offset, length)
        LogFormat.endRecord(out, start, RecordType.FRAGMENT)
        return payloadOffset
    }

    /**
     * Decodes the body `bytes[bodyOffset, bodyOffset + bodyLength)` into [into], whose views then refer to [bytes]: the
     * stored payload, which [FragmentView.inflate] turns into the payload when it's compressed. A `rawLength` above
     * [maxRawLength] is corruption. [reader] is scratch.
     */
    fun decode(bytes: ByteArray, bodyOffset: Int, bodyLength: Int, reader: ByteReader, into: FragmentView, source: String, maxRawLength: Int = Int.MAX_VALUE) {
        reader.reset(bytes, bodyOffset, bodyLength, source)
        val docId = reader.readLongBE()
        val pre = reader.readLongBE()
        val kind = reader.readByte()
        val seq = reader.readIntBE()
        if (docId < 0 || pre < 0) throw CorruptionException(source, "fragment key ($docId, $pre)")
        if (kind != FragmentKind.PART && kind != FragmentKind.SUBTREE && kind != FragmentKind.HEADER && kind != FragmentKind.STRCHUNK) {
            throw CorruptionException(source, "unknown fragment kind $kind")
        }
        FragmentRecord.readContext(reader, into.context, source)
        val codec = reader.readByte()
        val dictId = reader.readShortLE()
        val rawLength = reader.readIntLE()
        val stored = reader.remaining
        val valid = when (codec) {
            FragmentCodecs.NONE -> dictId == 0 && rawLength == stored
            FragmentCodecs.DEFLATE -> dictId == 0 && stored > 0 && rawLength in 0..maxRawLength
            FragmentCodecs.DEFLATE_DICT -> dictId != 0 && stored > 0 && rawLength in 0..maxRawLength
            else -> false
        }
        if (!valid) throw CorruptionException(source, "codec $codec with dictionary $dictId: $stored payload bytes stored for $rawLength")
        into.set(docId, pre, kind, seq, bytes, reader.position, stored)
        into.setCodec(codec, dictId, rawLength)
    }
}

/**
 * In-memory record of a fragment: its context, then its payload. The context is encoded as in the durable FRAGMENT
 * record, with "none" (-1) stored as 0:
 * ```
 * context := parentPre + 1:varint | ownerObjectPre + 1:varint | ownerKeyLen:varint | ownerKey | firstIndex:varint
 *          | pathHash:u32le | parentPathHash:u32le | grandparentPathHash:u32le
 * record  := context | payload
 * ```
 */
internal object FragmentRecord {
    /** Appends the record; returns the payload's offset from the record start. */
    fun write(out: GrowableBytes, context: FragmentContext, payload: ByteArray, offset: Int, length: Int): Int {
        val start = out.size
        writeContext(out, context)
        val payloadOffset = out.size - start
        out.writeBytes(payload, offset, length)
        return payloadOffset
    }

    fun writeContext(out: GrowableBytes, context: FragmentContext) {
        out.writeVarLong(context.parentPre + 1)
        out.writeVarLong(context.ownerObjectPre + 1)
        out.writeVarLong(context.ownerKeyLength.toLong())
        out.writeBytes(context.ownerKey, context.ownerKeyOffset, context.ownerKeyLength)
        out.writeVarLong(context.firstIndex)
        out.writeIntLE(context.pathHash)
        out.writeIntLE(context.parentPathHash)
        out.writeIntLE(context.grandparentPathHash)
    }

    /** Reads a context at the reader's position into [into]; the owner key refers to the reader's bytes. */
    fun readContext(reader: ByteReader, into: FragmentContext, source: String) {
        val parentPre = reader.readVarLong() - 1
        val ownerObjectPre = reader.readVarLong() - 1
        val keyLength = reader.readVarInt(reader.remaining)
        val keyOffset = reader.position
        reader.skip(keyLength)
        val firstIndex = reader.readVarLong()
        if (parentPre < -1 || ownerObjectPre < -1 || ownerObjectPre > parentPre || firstIndex < 0 || (ownerObjectPre < 0 && keyLength != 0)) {
            throw CorruptionException(source, "invalid context ($parentPre, $ownerObjectPre, $keyLength-byte key, $firstIndex)")
        }
        val pathHash = reader.readIntLE()
        val parentPathHash = reader.readIntLE()
        val grandparentPathHash = reader.readIntLE()
        into.set(parentPre, ownerObjectPre, reader.bytes, keyOffset, keyLength, firstIndex).setPaths(pathHash, parentPathHash, grandparentPathHash)
    }
}
