package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.canon.Utf8

/**
 * Full validation of an untrusted fragment payload: everything [PayloadReader] and [HeaderReader] check, plus
 * content rules — keys and strings are valid UTF-8, numbers follow the JSON grammar and range, a NUM isn't a canonical
 * `Long` (that must be INT), and the key table has no repeated key. Always terminates; problems throw
 * [CorruptionException]. Reusable; not thread-safe.
 */
internal class PayloadValidator {
    private val reader = PayloadReader()
    private val header = HeaderReader()
    private val numberKey = GrowableBytes(64)
    private val longSlot = LongArray(1)

    /**
     * Validates the payload of a fragment with key `(pre, kind, seq)`. Returns the number of values it holds, or for a
     * HEADER the number of directory entries.
     */
    fun validate(payload: ByteArray, offset: Int, length: Int, kind: Int, pre: Long, seq: Int, source: String = "payload"): Long {
        if (kind == FragmentKind.HEADER) {
            header.open(payload, offset, length, seq, source)
            var entries = 0L
            while (header.nextEntry()) entries++
            return entries
        }
        if (kind != FragmentKind.SUBTREE && kind != FragmentKind.PART && kind != FragmentKind.STRCHUNK) {
            throw CorruptionException(source, "unknown fragment kind $kind")
        }
        if (kind != FragmentKind.STRCHUNK && seq != 0) throw CorruptionException(source, "seq $seq for kind $kind")
        reader.open(payload, offset, length, kind, pre, source)
        reader.checkKeyTable()
        for (k in 0 until reader.keyCount) {
            val at = Utf8.firstInvalid(payload, reader.keyOffsetOf(k), reader.keyLengthOf(k))
            if (at >= 0) throw CorruptionException("$source@$at", "key $k isn't valid UTF-8")
        }
        var values = 0L
        while (true) {
            when (reader.next()) {
                PayloadReader.END -> return values
                PayloadReader.END_OBJECT, PayloadReader.END_ARRAY -> continue
                PayloadReader.STR -> {
                    val at = Utf8.firstInvalid(payload, reader.offset, reader.length)
                    if (at >= 0) throw CorruptionException("$source@$at", "string isn't valid UTF-8")
                    if (kind == FragmentKind.STRCHUNK && reader.length == 0) throw CorruptionException("$source@${reader.valueOffset}", "empty STRCHUNK")
                }
                PayloadReader.NUM -> checkNumber(payload, source)
            }
            values++
        }
    }

    private fun checkNumber(payload: ByteArray, source: String) {
        numberKey.clear()
        when (CanonicalNumber.writeKey(payload, reader.offset, reader.length, numberKey)) {
            CanonicalNumber.MALFORMED -> throw CorruptionException("$source@${reader.valueOffset}", "NUM isn't a JSON number")
            CanonicalNumber.OUT_OF_RANGE -> throw CorruptionException("$source@${reader.valueOffset}", "NUM exponent out of range")
        }
        if (CanonicalNumber.parseCanonicalLong(payload, reader.offset, reader.length, longSlot, 0)) {
            throw CorruptionException("$source@${reader.valueOffset}", "canonical integer stored as NUM")
        }
    }
}
