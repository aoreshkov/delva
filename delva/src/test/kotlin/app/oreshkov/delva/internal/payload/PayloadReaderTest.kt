package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.hexToBytes
import app.oreshkov.delva.testkit.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayloadReaderTest {
    private val validator = PayloadValidator()

    private fun payload(build: GrowableBytes.() -> Unit): ByteArray = GrowableBytes().apply(build).toByteArray()

    private fun GrowableBytes.keys(vararg keys: String) {
        PayloadWriter.keyTableCount(this, keys.size)
        for (k in keys) k.toByteArray().let { PayloadWriter.key(this, it, 0, it.size) }
    }

    private fun GrowableBytes.str(s: String) = s.toByteArray().let { PayloadWriter.strValue(this, it, 0, it.size) }

    private fun GrowableBytes.num(s: String) = s.toByteArray().let { PayloadWriter.numValue(this, it, 0, it.size) }

    private fun assertCorrupt(payload: ByteArray, kind: Int = FragmentKind.SUBTREE, pre: Long = 0, seq: Int = 0, message: String) {
        val e = assertFailsWith<CorruptionException> { validator.validate(payload, 0, payload.size, kind, pre, seq) }
        assertTrue(message in (e.message ?: ""), "expected '$message' in: ${e.message}")
    }

    /** `{"a": REF(object, size 5), "b": [SPLIT(array, count 3, size 9), BIGSTR(100 bytes, 2 chunks)], "c": 1}` at pre 10. */
    private fun stubs() = payload {
        keys("a", "b", "c")
        PayloadWriter.objectHeader(this, 3, 19)
        PayloadWriter.sortPermEntry(this, 0)
        PayloadWriter.sortPermEntry(this, 1)
        PayloadWriter.sortPermEntry(this, 2)
        PayloadWriter.keyRef(this, 0)
        PayloadWriter.ref(this, PayloadTag.OBJ, size = 5, hashLow = 0x1111, hashHigh = 0x2222, encodedBytes = 40_000)
        PayloadWriter.keyRef(this, 1)
        PayloadWriter.arrayHeader(this, 2, 11)
        PayloadWriter.split(this, PayloadTag.ARR, count = 3, size = 9, hashLow = 3, hashHigh = 4)
        PayloadWriter.bigString(this, totalBytes = 100, chunks = 2, hashLow = 5, hashHigh = 6, PREFIX, 0)
        PayloadWriter.keyRef(this, 2)
        PayloadWriter.intValue(this, 1)
    }

    @Test
    fun `stubs advance pre numbers by their size`() {
        val p = stubs()
        assertEquals(6, validator.validate(p, 0, p.size, FragmentKind.SUBTREE, 10, 0))
        val r = PayloadReader()
        r.open(p, 0, p.size, FragmentKind.SUBTREE, 10)
        assertEquals(PayloadReader.START_OBJECT, r.next())
        assertEquals(PayloadReader.REF, r.next())
        assertEquals(11L, r.pre)
        assertEquals(PayloadTag.OBJ, r.containerKind)
        assertEquals(5L, r.size)
        assertEquals(0x1111L, r.hashLow)
        assertEquals(0x2222L, r.hashHigh)
        assertEquals(40_000L, r.encodedBytes)
        assertEquals(PayloadReader.START_ARRAY, r.next())
        assertEquals(17L, r.pre)
        assertEquals(PayloadReader.SPLIT, r.next())
        assertEquals(18L, r.pre)
        assertEquals(PayloadTag.ARR, r.containerKind)
        assertEquals(3L, r.count)
        assertEquals(PayloadReader.BIGSTR, r.next())
        assertEquals(28L, r.pre)
        assertEquals(100L, r.size)
        assertEquals(2L, r.count)
        assertEquals(String(PREFIX), String(p, r.offset, r.length))
        assertEquals(PayloadReader.END_ARRAY, r.next())
        assertEquals(PayloadReader.INT, r.next())
        assertEquals(29L, r.pre)
        assertEquals(PayloadReader.END_OBJECT, r.next())
        assertEquals(PayloadReader.END, r.next())
        assertFailsWith<IllegalStateException> { payloadJson(p, pre = 10) }
    }

    @Test
    fun `HEADER records carry the summary and a directory spread over seq numbers`() {
        val seq0 = payload {
            keys()
            PayloadWriter.headerSummary(this, PayloadTag.ARR, count = 5000, size = 12_000, hashLow = 7, hashHigh = 8, partCount = 3)
            PayloadWriter.dirEntryCount(this, 2)
            PayloadWriter.dirEntry(this, firstPre = 5, firstIndex = 0, memberCount = 1900)
            PayloadWriter.dirEntry(this, firstPre = 4600, firstIndex = 1900, memberCount = 2000)
        }
        val seq1 = payload {
            keys()
            PayloadWriter.dirEntryCount(this, 1)
            PayloadWriter.dirEntry(this, firstPre = 9500, firstIndex = 3900, memberCount = 1100)
        }
        assertEquals(2, validator.validate(seq0, 0, seq0.size, FragmentKind.HEADER, 4, 0))
        assertEquals(1, validator.validate(seq1, 0, seq1.size, FragmentKind.HEADER, 4, 1))

        val h = HeaderReader()
        h.open(seq0, 0, seq0.size, 0)
        assertEquals(PayloadTag.ARR, h.containerKind)
        assertEquals(5000L, h.count)
        assertEquals(12_000L, h.size)
        assertEquals(3L, h.partCount)
        assertEquals(7L, h.hashLow)
        assertEquals(8L, h.hashHigh)
        assertTrue(h.nextEntry())
        assertEquals(Triple(5L, 0L, 1900L), Triple(h.firstPre, h.firstIndex, h.memberCount))
        assertTrue(h.nextEntry())
        assertEquals(Triple(4600L, 1900L, 2000L), Triple(h.firstPre, h.firstIndex, h.memberCount))
        assertFalse(h.nextEntry())

        // Continuation records can't be read as seq 0, and entries must be consecutive and increasing.
        assertFailsWith<CorruptionException> { validator.validate(seq1, 0, seq1.size, FragmentKind.HEADER, 4, 0) }
        assertCorrupt(
            payload {
                keys()
                PayloadWriter.dirEntryCount(this, 2)
                PayloadWriter.dirEntry(this, 10, 0, 5)
                PayloadWriter.dirEntry(this, 20, 6, 5)
            },
            FragmentKind.HEADER, seq = 1, message = "consecutive",
        )
        assertCorrupt(
            payload {
                keys()
                PayloadWriter.dirEntryCount(this, 2)
                PayloadWriter.dirEntry(this, 10, 0, 5)
                PayloadWriter.dirEntry(this, 10, 5, 5)
            },
            FragmentKind.HEADER, seq = 1, message = "increasing",
        )
        assertCorrupt(payload { keys("x"); PayloadWriter.dirEntryCount(this, 0) }, FragmentKind.HEADER, seq = 1, message = "HEADER with keys")
        assertCorrupt(seq0 + byteArrayOf(0), FragmentKind.HEADER, message = "after the directory")
    }

    @Test
    fun `STRCHUNK holds one non-empty string and no keys`() {
        val chunk = payload { keys(); str("chunk of a big string") }
        assertEquals(1, validator.validate(chunk, 0, chunk.size, FragmentKind.STRCHUNK, 3, 7))
        assertCorrupt(payload { keys(); PayloadWriter.intValue(this, 1) }, FragmentKind.STRCHUNK, message = "isn't a string")
        assertCorrupt(payload { keys("k"); str("x") }, FragmentKind.STRCHUNK, message = "STRCHUNK with keys")
        assertCorrupt(payload { keys(); str("") }, FragmentKind.STRCHUNK, message = "empty STRCHUNK")
        assertCorrupt(payload { keys(); str("a"); str("b") }, FragmentKind.STRCHUNK, message = "after the last value")
    }

    @Test
    fun `structural corruption is detected`() {
        assertCorrupt(byteArrayOf(), message = "truncated varint")
        assertCorrupt(payload { keys() }, message = "need 1 bytes")
        assertCorrupt(payload { keys(); writeByte(0x00) }, message = "unknown value tag 0")
        assertCorrupt(payload { keys(); writeByte(0x7F) }, message = "unknown value tag")
        assertCorrupt(payload { keys(); PayloadWriter.nullValue(this); PayloadWriter.nullValue(this) }, message = "after the last value")
        assertCorrupt(payload { keys(); PayloadWriter.arrayHeader(this, 2, 2); PayloadWriter.nullValue(this) }, message = "container count")
        assertCorrupt(payload { keys(); PayloadWriter.arrayHeader(this, 1, 2); PayloadWriter.nullValue(this) }, message = "size 2 but its content uses 1")
        assertCorrupt(payload { keys(); PayloadWriter.arrayHeader(this, 2, 1); PayloadWriter.nullValue(this); PayloadWriter.nullValue(this) }, message = "below its count")
        assertCorrupt(payload { keys(); PayloadWriter.objectHeader(this, 1, 1); PayloadWriter.keyRef(this, 0); PayloadWriter.nullValue(this) }, message = "key table is empty")
        assertCorrupt(payload { keys("a"); PayloadWriter.objectHeader(this, 1, 1); PayloadWriter.keyRef(this, 1); PayloadWriter.nullValue(this) }, message = "exceeds 0")
        assertCorrupt(payload { keys(); PayloadWriter.ref(this, PayloadTag.OBJ, -1, 0, 0, 0) }, message = "REF size")
        assertCorrupt(payload { keys(); PayloadWriter.ref(this, PayloadTag.STR, 1, 0, 0, 0) }, message = "REF container kind")
        assertCorrupt(payload { keys(); PayloadWriter.split(this, PayloadTag.ARR, 0, 0, 0, 0) }, message = "SPLIT count")
        assertCorrupt(payload { keys(); PayloadWriter.split(this, 0, 1, 1, 0, 0) }, message = "SPLIT container kind")
        assertCorrupt(payload { keys(); PayloadWriter.bigString(this, 100, 101, 0, 0, PREFIX, 0) }, message = "BIGSTR")
        assertCorrupt(payload { keys(); PayloadWriter.bigString(this, 64, 1, 0, 0, PREFIX, 0) }, message = "BIGSTR bytes 64")
        assertCorrupt(payload { keys(); PayloadWriter.bigString(this, 65, 1, 0, 0, PREFIX, 0) }.copyOf(20), message = "need 16 bytes")
        assertCorrupt(payload { keys(); PayloadWriter.ref(this, PayloadTag.ARR, Long.MAX_VALUE - 5, 0, 0, 0) }, pre = 10, message = "pre overflow")
        assertCorrupt(payload { keys(); num("") }, message = "empty number")
        assertCorrupt(payload { keys(); str("ab") }.copyOf(4), message = "need 2 bytes")
        assertCorrupt(payload { keys(); writeByte(PayloadTag.INT); writeByte(0x80); writeByte(0x00) }, message = "non-minimal varint")
    }

    @Test
    fun `sortPerm must be a permutation ordered by key bytes`() {
        fun obj(vararg perm: Int, keyTable: Array<String> = arrayOf("a", "b")) = payload {
            keys(*keyTable)
            PayloadWriter.objectHeader(this, 2, 2)
            perm.forEach { PayloadWriter.sortPermEntry(this, it) }
            PayloadWriter.keyRef(this, 1)
            PayloadWriter.nullValue(this)
            PayloadWriter.keyRef(this, 0)
            PayloadWriter.nullValue(this)
        }
        val good = obj(1, 0)
        assertEquals(3, validator.validate(good, 0, good.size, FragmentKind.SUBTREE, 0, 0))
        assertCorrupt(obj(0, 1), message = "isn't ordered by key bytes")
        assertCorrupt(obj(1, 1), message = "repeats position 1")
        assertCorrupt(obj(1, 2), message = "exceeds 1")
        assertCorrupt(obj(1, 0, keyTable = arrayOf("a", "a")), message = "key table repeats a key")
        // Same key used twice in one object: ranks tie.
        val twice = payload {
            keys("a")
            PayloadWriter.objectHeader(this, 2, 2)
            PayloadWriter.sortPermEntry(this, 0)
            PayloadWriter.sortPermEntry(this, 1)
            repeat(2) { PayloadWriter.keyRef(this, 0); PayloadWriter.nullValue(this) }
        }
        assertCorrupt(twice, message = "isn't ordered by key bytes")
    }

    @Test
    fun `PART header and member runs`() {
        val part = payload {
            keys("k", "j")
            PayloadWriter.partHeader(this, PayloadTag.OBJ, 2)
            PayloadWriter.sortPermEntry(this, 1)
            PayloadWriter.sortPermEntry(this, 0)
            PayloadWriter.keyRef(this, 0)
            PayloadWriter.intValue(this, -3)
            PayloadWriter.keyRef(this, 1)
            str("v")
        }
        assertEquals(2, validator.validate(part, 0, part.size, FragmentKind.PART, 42, 0))
        assertEquals("\"k\":-3,\"j\":\"v\"", payloadJson(part, FragmentKind.PART, 42))
        assertCorrupt(payload { keys(); PayloadWriter.partHeader(this, PayloadTag.ARR, 1) }, FragmentKind.PART, message = "PART member count")
        assertCorrupt(payload { keys(); writeByte(PayloadTag.STR); writeVarLong(1); PayloadWriter.nullValue(this) }, FragmentKind.PART, message = "PART container kind")
        assertCorrupt(payload { keys(); PayloadWriter.partHeader(this, PayloadTag.ARR, 1); PayloadWriter.nullValue(this) }, FragmentKind.PART, seq = 1, message = "seq 1")
    }

    @Test
    fun `content checks of the validator`() {
        assertCorrupt(payload { keys(); num("12") }, message = "canonical integer stored as NUM")
        assertCorrupt(payload { keys(); num("1.") }, message = "isn't a JSON number")
        assertCorrupt(payload { keys(); num("1e2147483647") }, message = "out of range")
        assertCorrupt(payload { keys(); writeByte(PayloadTag.STR); writeVarLong(2); writeByte(0xC0); writeByte(0x80) }, message = "string isn't valid UTF-8")
        assertCorrupt(
            payload {
                PayloadWriter.keyTableCount(this, 1)
                writeVarLong(1)
                writeByte(0xFF)
                PayloadWriter.objectHeader(this, 1, 1)
                PayloadWriter.keyRef(this, 0)
                PayloadWriter.nullValue(this)
            },
            message = "key 0 isn't valid UTF-8",
        )
        val numbers = payload { keys(); PayloadWriter.arrayHeader(this, 3, 3); num("-0"); num("1.50"); num("12345678901234567890") }
        assertEquals(4, validator.validate(numbers, 0, numbers.size, FragmentKind.SUBTREE, 0, 0))
        assertEquals("[-0,1.50,12345678901234567890]", payloadJson(numbers))
    }

    @Test
    fun `numbers are INT only when canonical and within Long`() {
        val json = """[0,-1,9223372036854775807,-9223372036854775808,9223372036854775808,-0,1.0,1e2,01.5e1]"""
            .replace(",01.5e1", ",1.5e1")
        val p = subtreePayload(json)
        val r = PayloadReader()
        r.open(p, 0, p.size, FragmentKind.SUBTREE, 0)
        r.next()
        val kinds = generateSequence { r.next().takeIf { it != PayloadReader.END_ARRAY } }.toList()
        val i = PayloadReader.INT
        val n = PayloadReader.NUM
        assertEquals(listOf(i, i, i, i, n, n, n, n, n), kinds)
        assertEquals(json, payloadJson(p))
    }

    @Test
    fun `payload bytes are frozen by a golden file`() {
        val cases = linkedMapOf(
            "scalar-null" to subtreePayload("null"),
            "scalar-int" to subtreePayload("-123456789"),
            "scalar-num" to subtreePayload("1.50e-3"),
            "string-escapes" to subtreePayload("\"a\\\"b\\u00e9\\ud83d\\ude00\\n\""),
            "empty-containers" to subtreePayload("""[{},[],"",{"":[]}]"""),
            "worked-example" to subtreePayload("""{"order":{"id":7,"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":1.0}],"tags":["x","y"]}}"""),
            "sorted-keys" to subtreePayload("""{"z":1,"é":2,"":3,"a":4,"B":5}"""),
            "stubs" to stubs(),
            "part-object" to payload {
                keys("k", "j")
                PayloadWriter.partHeader(this, PayloadTag.OBJ, 2)
                PayloadWriter.sortPermEntry(this, 1)
                PayloadWriter.sortPermEntry(this, 0)
                PayloadWriter.keyRef(this, 0)
                PayloadWriter.intValue(this, -3)
                PayloadWriter.keyRef(this, 1)
                str("v")
            },
            "header-seq0" to payload {
                keys()
                PayloadWriter.headerSummary(this, PayloadTag.OBJ, count = 3, size = 40, hashLow = 0x0123456789abcdefL, hashHigh = -2L, partCount = 2)
                PayloadWriter.dirEntryCount(this, 2)
                PayloadWriter.dirEntry(this, 1, 0, 2)
                PayloadWriter.dirEntry(this, 30, 2, 1)
            },
            "strchunk" to payload { keys(); str("€uro") },
        )
        val lines = cases.map { (name, bytes) -> "$name ${bytes.toHex()}" }
        Golden.verify(
            "payloads.txt",
            "Fragment payload encodings (PayloadFormat.FORMAT_VERSION 1): <case> <payload hex>",
            lines,
        )
        // The golden bytes decode to the same JSON.
        val worked = cases.getValue("worked-example")
        assertEquals(worked.toHex(), lines[5].substringAfter(' '))
        assertEquals("""{"order":{"id":7,"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":1.0}],"tags":["x","y"]}}""", payloadJson(lines[5].substringAfter(' ').hexToBytes()))
    }

    private companion object {
        val PREFIX = "first 16 bytes!!".toByteArray()
    }
}
