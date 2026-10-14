package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.JsonFormat
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
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The formatted layout of SPEC.md §5.1: golden layouts per option, and the streaming writer against its definition. */
class PrettyJsonOutputTest {
    private fun render(value: OValue, format: JsonFormat, random: Random? = null, bufferBytes: Int = 8192): String {
        val bytes = ByteArrayOutputStream()
        val out = JsonOutput(bytes, bufferBytes)
        val pretty = PrettyJsonOutput(out)
        pretty.reset(format)
        pretty.value(value, random)
        out.flush()
        return bytes.toString(Charsets.UTF_8)
    }

    private fun render(json: String, format: JsonFormat) = render(OracleJson.parseOrThrow(json), format)

    /** Sends [v]; with [random], strings go in parts at random code-point boundaries, and integers as longs. */
    private fun JsonEvents.value(v: OValue, random: Random?) {
        when (v) {
            ONull -> nullValue()
            is OBool -> booleanValue(v.value)
            is ONum -> {
                val long = v.text.toLongOrNull()
                if (random != null && long != null && long.toString() == v.text && random.nextBoolean()) {
                    number(long)
                } else {
                    v.text.toByteArray().let { number(it, 0, it.size) }
                }
            }
            is OStr -> {
                val bytes = v.value.toByteArray()
                if (random == null || random.nextBoolean()) {
                    string(bytes, 0, bytes.size)
                } else {
                    beginString()
                    var start = 0
                    var p = 0
                    while (p < bytes.size) {
                        p++
                        while (p < bytes.size && bytes[p].toInt() and 0xC0 == 0x80) p++
                        if (p == bytes.size || random.nextInt(3) == 0) {
                            stringPart(bytes, start, p - start)
                            start = p
                        }
                    }
                    endString()
                }
            }
            is OArr -> {
                beginArray()
                v.items.forEach { value(it, random) }
                endArray()
            }
            is OObj -> {
                beginObject()
                v.members.forEach { (k, m) ->
                    k.toByteArray().let { key(it, 0, it.size) }
                    value(m, random)
                }
                endObject()
            }
        }
    }

    private fun expected(value: OValue, format: JsonFormat) =
        JsonText.formatted(value, format.indent, format.lineWidth, format.maxInlineElements, format.maxInlineDepth, format.maxLevels)

    /** The text without whitespace outside strings. */
    private fun stripped(text: String): String {
        val sb = StringBuilder()
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                if (c == '\\') sb.append(text[++i]) else if (c == '"') inString = false
            } else if (c == '"') {
                inString = true
                sb.append(c)
            } else if (c != ' ' && c != '\n' && c != '\t') {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun `the default format puts containers that fit on one line`() {
        val json = """{"name":"ada","tags":["math","engines"],"scores":[{"round":1,"points":40},{"round":2,"points":44}],""" +
            """"notes":"a line that is long enough to push the whole object over eighty columns"}"""
        assertEquals(
            """
            {
              "name": "ada",
              "tags": ["math", "engines"],
              "scores": [{"round": 1, "points": 40}, {"round": 2, "points": 44}],
              "notes": "a line that is long enough to push the whole object over eighty columns"
            }
            """.trimIndent(),
            render(json, JsonFormat.PRETTY),
        )
        assertEquals("""{"a": [1, 2], "b": {}}""", render("""{"a":[1,2],"b":{}}""", JsonFormat.PRETTY))
    }

    @Test
    fun `the example of SPEC section 5_1 and the JsonFormat KDoc`() {
        assertEquals(
            """
            {
              "name": "ada",
              "tags": ["math", "engines"],
              "scores": [
                {"round": 1, "points": 40},
                {"round": 2, "points": 44}
              ]
            }
            """.trimIndent(),
            render("""{"name":"ada","tags":["math","engines"],"scores":[{"round":1,"points":40},{"round":2,"points":44}]}""", JsonFormat(lineWidth = 32)),
        )
    }

    @Test
    fun `width 0 puts every non-empty container on lines of its own`() {
        assertEquals(
            """
            {
                "a": [
                    1,
                    {}
                ],
                "b": [],
                "c": {
                    "d": null
                }
            }
            """.trimIndent(),
            render("""{"a":[1,{}],"b":[],"c":{"d":null}}""", JsonFormat(indent = "    ", lineWidth = 0)),
        )
    }

    @Test
    fun `scalars and empty containers are written as they are`() {
        for (json in listOf("1", "-0.5e3", "\"x\\n\"", "true", "null", "{}", "[]")) {
            assertEquals(json, render(json, JsonFormat(lineWidth = 0)))
        }
    }

    @Test
    fun `maxInlineElements breaks a container above the limit only`() {
        val json = """[[1,2,3]]"""
        assertEquals("[[1, 2, 3]]", render(json, JsonFormat(maxInlineElements = 3)))
        assertEquals("[[1, 2, 3]]", render(json, JsonFormat(maxInlineElements = 4)))
        assertEquals("[\n  [\n    1,\n    2,\n    3\n  ]\n]", render(json, JsonFormat(maxInlineElements = 2)))
        assertEquals("[\n  [\n    1\n  ]\n]", render("[[1]]", JsonFormat(maxInlineElements = 0)))
        assertEquals("[[], {}]", render("[[],{}]", JsonFormat(maxInlineElements = 2)))
    }

    @Test
    fun `maxInlineDepth limits how deep a one-line container nests`() {
        val json = """{"a":[[1],[2,[3]]],"b":[]}"""
        assertEquals("""{"a": [[1], [2, [3]]], "b": []}""", render(json, JsonFormat(maxInlineDepth = 3)))
        assertEquals("{\n  \"a\": [[1], [2, [3]]],\n  \"b\": []\n}", render(json, JsonFormat(maxInlineDepth = 2)))
        assertEquals("{\n  \"a\": [\n    [1],\n    [2, [3]]\n  ],\n  \"b\": []\n}", render(json, JsonFormat(maxInlineDepth = 1)))
        assertEquals(
            "{\n  \"a\": [\n    [1],\n    [\n      2,\n      [3]\n    ]\n  ],\n  \"b\": []\n}",
            render(json, JsonFormat(maxInlineDepth = 0)),
        )
        // Empty containers don't count as nesting.
        assertEquals("[[], {}]", render("[[],{}]", JsonFormat(maxInlineDepth = 0)))
    }

    @Test
    fun `maxLevels cuts out the containers nested deeper`() {
        val json = """{"name":"ada","tags":["math"],"address":{"city":"london","geo":[51,0]},"none":[],"empty":{}}"""
        assertEquals(
            """{"name": "ada", "tags": [...], "address": {...}, "none": [], "empty": {}}""",
            render(json, JsonFormat(maxLevels = 0)),
        )
        assertEquals(
            """{"name": "ada", "tags": ["math"], "address": {"city": "london", "geo": [...]}, "none": [], "empty": {}}""",
            render(json, JsonFormat(lineWidth = 120, maxLevels = 1)),
        )
        assertEquals(render(json, JsonFormat(lineWidth = 120)), render(json, JsonFormat(lineWidth = 120, maxLevels = 2)))
        // The node's own elements are written broken as usual; a cut-out container is laid out like a scalar.
        assertEquals(
            "[\n  1,\n  [...],\n  {...},\n  \"x\"\n]",
            render("""[1,[2,[3]],{"a":{"b":null}},"x"]""", JsonFormat(lineWidth = 0, maxLevels = 0)),
        )
        assertEquals("[[...]]", render("[[1]]", JsonFormat(maxInlineDepth = 0, maxLevels = 0)))
        // `{...}` is 5 columns: `[{...}]` fits 7.
        assertEquals("[{...}]", render("""[{"a":1}]""", JsonFormat(lineWidth = 7, maxLevels = 0)))
        assertEquals("[\n  {...}\n]", render("""[{"a":1}]""", JsonFormat(lineWidth = 6, maxLevels = 0)))
        // Streamed strings and scalars at the node itself are untouched.
        assertEquals("\"s\"", render("\"s\"", JsonFormat(maxLevels = 0)))
    }

    @Test
    fun `a line that exactly fits stays, and one column more breaks`() {
        // `[1, 2, 3]` is 9 columns.
        assertEquals("[1, 2, 3]", render("[1,2,3]", JsonFormat(lineWidth = 9)))
        assertEquals("[\n  1,\n  2,\n  3\n]", render("[1,2,3]", JsonFormat(lineWidth = 8)))
        // Inside a broken parent: `  "k": [1, 2]` is 13 columns, 14 with the comma a sibling needs.
        val last = """{"x":0,"k":[1,2]}"""
        assertEquals("{\n  \"x\": 0,\n  \"k\": [1, 2]\n}", render(last, JsonFormat(lineWidth = 13)))
        val notLast = """{"k":[1,2],"x":0}"""
        assertEquals("{\n  \"k\": [\n    1,\n    2\n  ],\n  \"x\": 0\n}", render(notLast, JsonFormat(lineWidth = 13)))
        assertEquals("{\n  \"k\": [1, 2],\n  \"x\": 0\n}", render(notLast, JsonFormat(lineWidth = 14)))
        assertEquals("{\n  \"x\": 0,\n  \"k\": [\n    1,\n    2\n  ]\n}", render(last, JsonFormat(lineWidth = 12)))
    }

    @Test
    fun `columns are code points of the escaped text`() {
        // `["é😀\n"]`: brackets 2, quotes 2, two code points, and a two-character escape: 8 columns.
        val json = "[\"é😀\\n\"]"
        assertEquals("[\"é😀\\n\"]", render(json, JsonFormat(lineWidth = 8)))
        assertEquals("[\n  \"é😀\\n\"\n]", render(json, JsonFormat(lineWidth = 7)))
        // `\u0001` is six columns.
        assertEquals("[\"\\u0001\"]", render("[\"\\u0001\"]", JsonFormat(lineWidth = 10)))
        assertEquals("[\n  \"\\u0001\"\n]", render("[\"\\u0001\"]", JsonFormat(lineWidth = 9)))
    }

    @Test
    fun `a key longer than the line still gets its value`() {
        val key = "k".repeat(30)
        assertEquals("{\n  \"$key\": [\n    1\n  ],\n  \"b\": {}\n}", render("""{"$key":[1],"b":{}}""", JsonFormat(lineWidth = 20)))
        assertEquals("{\n  \"$key\": \"${"v".repeat(40)}\"\n}", render("""{"$key":"${"v".repeat(40)}"}""", JsonFormat(lineWidth = 20)))
    }

    @Test
    fun `tabs count eight columns and an empty indent writes bare lines`() {
        // `\t"a": [1]` is 8 + 8 = 16 columns; at 15 the array breaks.
        val json = """{"b":"${"x".repeat(16)}","a":[1]}"""
        val b = "\t\"b\": \"${"x".repeat(16)}\""
        assertEquals("{\n$b,\n\t\"a\": [1]\n}", render(json, JsonFormat(indent = "\t", lineWidth = 16)))
        assertEquals("{\n$b,\n\t\"a\": [\n\t\t1\n\t]\n}", render(json, JsonFormat(indent = "\t", lineWidth = 15)))
        assertEquals("{\n\"a\": [\n1\n]\n}", render("""{"a":[1]}""", JsonFormat(indent = "", lineWidth = 0)))
    }

    @Test
    fun `invalid formats are rejected`() {
        assertFailsWith<IllegalArgumentException> { JsonFormat(indent = " \t") }
        assertFailsWith<IllegalArgumentException> { JsonFormat(indent = " ".repeat(17)) }
        assertFailsWith<IllegalArgumentException> { JsonFormat(lineWidth = -1) }
        assertFailsWith<IllegalArgumentException> { JsonFormat(lineWidth = JsonFormat.MAX_LINE_WIDTH + 1) }
        assertFailsWith<IllegalArgumentException> { JsonFormat(maxInlineElements = -1) }
        assertFailsWith<IllegalArgumentException> { JsonFormat(maxInlineDepth = -1) }
        assertFailsWith<IllegalArgumentException> { JsonFormat(maxLevels = -1) }
        assertEquals(JsonFormat(), JsonFormat.PRETTY)
        assertEquals(JsonFormat(maxLevels = 1), JsonFormat(maxLevels = 1))
        assertEquals(false, JsonFormat(maxLevels = 1) == JsonFormat(maxLevels = 2))
        assertEquals("JsonFormat(indent=tab, lineWidth=0, maxInlineDepth=2)", JsonFormat(indent = "\t", lineWidth = 0, maxInlineDepth = 2).toString())
        assertEquals("JsonFormat(indent=2 spaces, lineWidth=80, maxLevels=0)", JsonFormat(maxLevels = 0).toString())
    }

    private val formats: Gen<JsonFormat> = Gen { random, _ ->
        JsonFormat(
            indent = when (random.nextInt(4)) {
                0 -> "\t"
                1 -> ""
                else -> " ".repeat(random.nextInt(1, 5))
            },
            lineWidth = when (random.nextInt(4)) {
                0 -> 0
                1 -> random.nextInt(1, 20)
                2 -> random.nextInt(20, 120)
                else -> random.nextInt(120, 400)
            },
            maxInlineElements = if (random.nextBoolean()) Int.MAX_VALUE else random.nextInt(0, 6),
            maxInlineDepth = if (random.nextBoolean()) Int.MAX_VALUE else random.nextInt(0, 4),
        )
    }

    @Test
    fun `the streaming layout matches its definition for generated documents and formats`() {
        for (profile in listOf(JsonProfile.MIXED, JsonProfile.UNICODE, JsonProfile.STRING_EDGES, JsonProfile.EMPTY_CONTAINERS)) {
            val cases = Gen { random, size -> Triple(JsonGen(profile, fragmentSize = 100).value().generate(random, size), formats.generate(random, size), random.nextLong()) }
            forAll("PrettyJsonOutput of $profile", cases, iterations = 300) { (value, format, seed) ->
                val text = render(value, format, Random(seed), bufferBytes = 16)
                assertEquals(expected(value, format), text)
                assertEquals(JsonText.minified(value), stripped(text))
                assertEquals(value, OracleJson.parseOrThrow(text))
            }
        }
    }

    @Test
    fun `the streaming layout matches its definition with nested levels cut out`() {
        for (profile in listOf(JsonProfile.MIXED, JsonProfile.EMPTY_CONTAINERS)) {
            val cases = Gen { random, size ->
                val format = formats.generate(random, size).let {
                    JsonFormat(it.indent, it.lineWidth, it.maxInlineElements, it.maxInlineDepth, maxLevels = random.nextInt(0, 4))
                }
                Triple(JsonGen(profile, fragmentSize = 100).value().generate(random, size), format, random.nextLong())
            }
            forAll("PrettyJsonOutput of $profile with maxLevels", cases, iterations = 300) { (value, format, seed) ->
                assertEquals(expected(value, format), render(value, format, Random(seed), bufferBytes = 16))
            }
        }
    }

    @Test
    fun `the writer is reusable across values and formats`() {
        val bytes = ByteArrayOutputStream()
        val out = JsonOutput(bytes)
        val pretty = PrettyJsonOutput(out)
        val value = OracleJson.parseOrThrow("""{"a":[1,2,{"b":[3]}],"c":"d"}""")
        for (format in listOf(JsonFormat(lineWidth = 0), JsonFormat.PRETTY, JsonFormat(lineWidth = 12, maxInlineDepth = 0), JsonFormat(maxLevels = 0))) {
            bytes.reset()
            out.reset(bytes)
            pretty.reset(format)
            pretty.value(value, null)
            out.flush()
            assertEquals(expected(value, format), bytes.toString(Charsets.UTF_8))
        }
    }
}
