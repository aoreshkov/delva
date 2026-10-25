package app.oreshkov.delva.internal.log

import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.LittleEndian

/**
 * The MANIFEST [bytes] as another version of Delva could have written it: format version [version], with a checksum
 * that holds, so it reads as another format rather than as damage. The layout past the version is left as it was.
 */
internal fun withFormatVersion(bytes: ByteArray, version: Int): ByteArray = bytes.copyOf().also {
    LittleEndian.putShort(it, 4, version.toShort())
    LittleEndian.putInt(it, it.size - 4, Crc32c.of(it, 0, it.size - 4))
}
