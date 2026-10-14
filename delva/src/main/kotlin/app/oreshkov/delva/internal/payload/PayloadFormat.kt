package app.oreshkov.delva.internal.payload

/**
 * Fragment payload encoding. Every payload starts with the fragment's key table:
 * ```
 * payload   := keyTable | ( value | part | header )        SUBTREE and STRCHUNK | PART | HEADER
 * keyTable  := keyCount:varint { len:varint utf8 }*
 * part      := containerKind:u8 | memberCount:varint | [sortPerm] | { [keyRef:varint] value }*memberCount
 * value     := NULL | FALSE | TRUE
 *            | INT     zigzag-varint
 *            | NUM     len:varint ascii
 *            | STR     len:varint utf8
 *            | OBJ     count:varint size:varint [sortPerm] { keyRef:varint value }*count
 *            | ARR     count:varint size:varint { value }*count
 *            | REF     containerKind:u8 size:varint hash:16B encodedBytes:varint
 *            | SPLIT   containerKind:u8 count:varint size:varint hash:16B
 *            | BIGSTR  totalBytes:varint chunks:varint hash:16B prefix:16B
 * sortPerm  := count × varint, member positions ordered by unsigned key bytes; present when count ≥ 2
 * header    := seq 0:   containerKind:u8 | count:varint | size:varint | hash:16B | partCount:varint | dirEntries
 *              seq ≥ 1: dirEntries
 * dirEntries := entryCount:varint { firstPre:varint firstIndex:varint memberCount:varint }*
 * ```
 * - `pre` numbers are implicit: the first value has the fragment's `pre`, each value start adds one, a stub adds
 *   `size + 1`.
 * - A hash is 16 bytes: high 64 bits then low 64 bits, both big-endian (as in [app.oreshkov.delva.internal.canon.CanonicalHash]).
 * - A STRCHUNK payload has an empty key table and one STR value; a HEADER payload has an empty key table.
 * - `containerKind` is [PayloadTag.OBJ] or [PayloadTag.ARR].
 * - Stubs carry what the value's index term needs, so the fragment that holds a stub emits that term: REF and SPLIT
 *   carry the container kind (the hash is there anyway), BIGSTR the string's first 16 bytes. A BIGSTR string is longer
 *   than [MAX_EXACT_STRING_BYTES], so its term is always the lossy long form.
 * - REF `encodedBytes` is the payload bytes of the SUBTREE fragment plus those of every fragment reachable from its
 *   stubs.
 */
internal object PayloadFormat {
    const val FORMAT_VERSION: Int = 1

    /** Longest string with an exact index term; a BIGSTR string must be longer. */
    const val MAX_EXACT_STRING_BYTES: Int = 64

    /** Bytes of the string kept in a BIGSTR stub. */
    const val BIGSTR_PREFIX_BYTES: Int = 16
}

/** Value tags of the payload encoding. `0x00` is never a tag, so zero-filled bytes don't decode. */
internal object PayloadTag {
    const val NULL: Int = 0x01
    const val FALSE: Int = 0x02
    const val TRUE: Int = 0x03
    const val INT: Int = 0x10
    const val NUM: Int = 0x11
    const val STR: Int = 0x20
    const val OBJ: Int = 0x30
    const val ARR: Int = 0x31
    const val REF: Int = 0x40
    const val SPLIT: Int = 0x41
    const val BIGSTR: Int = 0x42
}

/**
 * Fragment kinds: `rank << 4 | subtype`. The rank orders fragments that share a `pre`.
 */
internal object FragmentKind {
    const val PART: Int = 0x00
    const val SUBTREE: Int = 0x10
    const val HEADER: Int = 0x11
    const val STRCHUNK: Int = 0x20
}
