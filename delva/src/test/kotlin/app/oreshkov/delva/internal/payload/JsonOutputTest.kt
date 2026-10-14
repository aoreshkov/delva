package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.property.forAll
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonOutputTest {
    private fun render(bufferBytes: Int = 8192, write: JsonOutput.() -> Unit): String {
        val bytes = ByteArrayOutputStream()
        val out = JsonOutput(bytes, bufferBytes)
        out.write()
        out.flush()
        assertEquals(bytes.size().toLong(), out.bytesWritten)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun JsonOutput.value(v: OValue) {
        when (v) {
            ONull -> nullValue()
            is OBool -> booleanValue(v.value)
            is ONum -> v.text.toByteArray().let { number(it, 0, it.size) }
            is OStr -> v.value.toByteArray().let { string(it, 0, it.size) }
            is OArr -> {
                beginArray()
                v.items.forEach { value(it) }
                endArray()
            }
            is OObj -> {
                beginObject()
                v.members.forEach { (k, m) ->
                    k.toByteArray().let { key(it, 0, it.size) }
                    value(m)
                }
                endObject()
            }
        }
    }

    @Test
    fun `output matches the canonical form for generated documents at any buffer size`() {
        for (profile in listOf(JsonProfile.MIXED, JsonProfile.UNICODE, JsonProfile.STRING_EDGES, JsonProfile.EMPTY_CONTAINERS)) {
            forAll("JsonOutput of $profile", JsonGen(profile, fragmentSize = 100).value(), iterations = 200) { v ->
                val expected = JsonText.minified(v)
                assertEquals(expected, render(16) { value(v) })
                assertEquals(expected, render(4096) { value(v) })
            }
        }
    }

    @Test
    fun `escapes follow SPEC section 5`() {
        val all = (0 until 0x20).map { it.toChar() }.joinToString("") + "\"\\/€😀"
        val bytes = all.toByteArray()
        assertEquals(
            "\"\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007\\b\\t\\n\\u000b\\f\\r\\u000e\\u000f" +
                "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f" +
                "\\\"\\\\/€😀\"",
            render { string(bytes, 0, bytes.size) },
        )
    }

    @Test
    fun `integers, parts and separators`() {
        val text = render(16) {
            beginArray()
            number(0)
            number(-7)
            number(Long.MAX_VALUE)
            number(Long.MIN_VALUE)
            beginString()
            "abc".toByteArray().let { stringPart(it, 0, it.size) }
            "\n😀".toByteArray().let { stringPart(it, 0, it.size) }
            endString()
            beginObject()
            "k".toByteArray().let { key(it, 0, it.size) }
            beginArray()
            endArray()
            "".toByteArray().let { key(it, 0, it.size) }
            nullValue()
            endObject()
            val long = ByteArray(100) { 'x'.code.toByte() }
            string(long, 0, long.size)
            endArray()
        }
        assertEquals("""[0,-7,9223372036854775807,-9223372036854775808,"abc\n😀",{"k":[],"":null},"${"x".repeat(100)}"]""", text)
    }
}
