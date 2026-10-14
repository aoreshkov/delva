package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonShrinker
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.assertJsonEquals
import app.oreshkov.delva.testkit.property.forAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round trips through the whole encoding path: JSON text → tokenizer → pending bytes → fragment payload →
 * validator → reader → JSON output, compared with the oracle's canonical output.
 */
class PayloadRoundTripTest {
    private val validator = PayloadValidator()

    @Test
    fun `a document round trips through a SUBTREE payload`() {
        for (profile in JsonProfile.entries) {
            forAll("SUBTREE round trip of $profile", JsonGen(profile, fragmentSize = 200).value(), iterations = 300, shrinker = JsonShrinker) { value ->
                val json = JsonText.randomlyFormatted(value, Random(value.hashCode())).toByteArray()
                val payload = subtreePayload(json, window = 7)
                val nodes = preOrder(value).size.toLong()
                assertEquals(nodes, validator.validate(payload, 0, payload.size, FragmentKind.SUBTREE, pre = 0, seq = 0))
                assertJsonEquals(JsonText.minified(value), payloadJson(payload))
            }
        }
    }

    @Test
    fun `decoding at any value's offset yields that subtree`() {
        for (profile in listOf(JsonProfile.MIXED, JsonProfile.DEEP_NESTING, JsonProfile.ARRAYS_OF_OBJECTS, JsonProfile.WIDE_OBJECTS)) {
            forAll("sub-slice decode of $profile", JsonGen(profile).value(), iterations = 150, shrinker = JsonShrinker) { value ->
                val payload = subtreePayload(JsonText.minifiedBytes(value))
                val nodes = preOrder(value)
                // Walk once to learn every value's offset and pre, then decode from each.
                val reader = PayloadReader()
                reader.open(payload, 0, payload.size, FragmentKind.SUBTREE, 0)
                val starts = ArrayList<Pair<Int, Long>>()
                while (true) {
                    when (reader.next()) {
                        PayloadReader.END -> break
                        PayloadReader.END_OBJECT, PayloadReader.END_ARRAY -> {}
                        else -> starts += reader.valueOffset to reader.pre
                    }
                }
                assertEquals(nodes.indices.map { it.toLong() }, starts.map { it.second })
                for ((offset, pre) in starts) {
                    reader.openValue(payload, 0, payload.size, offset, pre)
                    assertEquals(JsonText.minified(nodes[pre.toInt()]), writeJson(reader), "value at pre $pre")
                }
            }
        }
    }

    @Test
    fun `skipValue jumps over a container and keeps pre numbers`() {
        val payload = subtreePayload("""{"a":[1,{"b":[2,3]},4],"c":"x","d":{}}""")
        val reader = PayloadReader()
        reader.open(payload, 0, payload.size, FragmentKind.SUBTREE, 100)
        assertEquals(PayloadReader.START_OBJECT, reader.next())
        assertEquals(PayloadReader.START_ARRAY, reader.next())
        reader.skipValue()
        assertEquals(PayloadReader.END_ARRAY, reader.event)
        assertEquals(PayloadReader.STR, reader.next())
        assertEquals(108L, reader.pre)
        assertEquals("c", String(payload, reader.keyOffset, reader.keyLength))
        assertEquals(PayloadReader.START_OBJECT, reader.next())
        assertEquals(109L, reader.pre)
        assertEquals(2L, reader.memberIndex)
        assertEquals(PayloadReader.END_OBJECT, reader.next())
        assertEquals(PayloadReader.END_OBJECT, reader.next())
        assertEquals(9L, reader.size)
        assertEquals(PayloadReader.END, reader.next())
        assertEquals(PayloadReader.END, reader.next())
    }

    @Test
    fun `runs of members round trip through PART payloads`() {
        val encoder = FragmentEncoder()
        for (profile in listOf(JsonProfile.WIDE_OBJECTS, JsonProfile.ARRAYS_OF_OBJECTS, JsonProfile.MIXED, JsonProfile.UNICODE)) {
            forAll("PART round trip of $profile", JsonGen(profile).value(), iterations = 150, shrinker = JsonShrinker) { root ->
                val members = when (root) {
                    is OObj -> root.members.map { it.second }
                    is OArr -> root.items
                    else -> return@forAll
                }
                if (members.isEmpty()) return@forAll
                val pending = pendingOf(JsonText.minifiedBytes(root))
                val offsets = pendingMemberOffsets(pending.array, 0)
                val random = Random(members.size * 31L + root.hashCode())
                // Cut the members into random consecutive runs, as the splitter would.
                var from = 0
                var pre = 1L
                while (from < members.size) {
                    val to = random.nextInt(from + 1, members.size + 1)
                    val out = GrowableBytes()
                    val kind = if (root is OObj) PayloadTag.OBJ else PayloadTag.ARR
                    encoder.encodePart(kind, pending.array, offsets[from], offsets[to], to - from, out)
                    val payload = out.toByteArray()
                    val values = (from until to).sumOf { preOrder(members[it]).size }.toLong()
                    assertEquals(values, validator.validate(payload, 0, payload.size, FragmentKind.PART, pre, seq = 0))
                    assertEquals(membersJson(root, from, to), payloadJson(payload, FragmentKind.PART, pre))
                    pre += values
                    from = to
                }
            }
        }
    }

    @Test
    fun `member order is kept and sortPerm orders members by unsigned key bytes`() {
        // "é" (C3 A9) sorts after "z"; "" first; "B" before "a".
        val payload = subtreePayload("""{"z":1,"é":2,"":3,"a":4,"B":5}""")
        val reader = PayloadReader()
        reader.open(payload, 0, payload.size, FragmentKind.SUBTREE, 0)
        assertEquals(PayloadReader.START_OBJECT, reader.next())
        val keys = ArrayList<String>()
        while (reader.next() != PayloadReader.END_OBJECT) keys += String(payload, reader.keyOffset, reader.keyLength)
        assertEquals(listOf("z", "é", "", "a", "B"), keys)
        // key table (first use order), then OBJ count 5 size 5, then sortPerm: "" (2), "B" (4), "a" (3), "z" (0), "é" (1).
        val keyTable = "05" + "017a" + "02c3a9" + "00" + "0161" + "0142"
        val header = "30" + "05" + "05"
        assertEquals(keyTable + header + "0204030001", payload.toHexString().take(keyTable.length + header.length + 10))
    }

    @Test
    fun `a key table entry is shared by every use in the fragment`() {
        val payload = subtreePayload("""[{"id":1,"name":"a"},{"name":"b","id":2},{"id":3}]""")
        val reader = PayloadReader()
        reader.open(payload, 0, payload.size, FragmentKind.SUBTREE, 0)
        assertEquals(2, reader.keyCount)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    @Suppress("unused")
    private fun describe(value: OValue) = JsonText.minified(value)
}
