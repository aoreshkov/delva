package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonWriterTest {

    private fun json(write: JsonWriter.() -> Unit): String = buildString { JsonWriter(this).write() }

    @Test
    fun `objects and arrays get their commas and colons`() {
        val text = json {
            writeObject {
                field("doc", 7L)
                field("path", "\$['a']")
                field("type", null)
                field("deleted", false)
                name("tags").writeArray { value("x"); value(1); nullValue() }
                name("empty").writeObject { }
                name("value").rawValue("""{"k":[1,2]}""")
            }
        }
        assertEquals(
            """{"doc":7,"path":"${'$'}['a']","type":null,"deleted":false,"tags":["x",1,null],"empty":{},"value":{"k":[1,2]}}""",
            text,
        )
    }

    @Test
    fun `strings escape exactly what RFC 8259 requires`() {
        assertEquals("\"a\\\"b\\\\c\"", JsonWriter.quote("a\"b\\c"))
        assertEquals("\"\\n\\r\\t\\b\\f\"", JsonWriter.quote("\n\r\t\b\u000C"))
        assertEquals("\"\\u0000\\u001f\"", JsonWriter.quote("\u0000\u001F"))
        // Everything else is written as itself: `/`, DEL, non-ASCII and a proper surrogate pair.
        assertEquals("\"/\u007Fé→\uD83D\uDE00\"", JsonWriter.quote("/\u007Fé→\uD83D\uDE00"))
    }

    @Test
    fun `every character below 0x20 is escaped, and nothing at or above it is, bar the two`() {
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            if (c.isSurrogate()) continue
            val quoted = JsonWriter.quote(c.toString())
            val escaped = quoted != "\"$c\""
            assertEquals(code < 0x20 || c == '"' || c == '\\', escaped, "U+${code.toString(16)} -> $quoted")
        }
    }

    @Test
    fun `an unpaired surrogate is escaped, losing nothing`() {
        assertEquals("\"a\\ud83db\"", JsonWriter.quote("a\uD83Db"))
        assertEquals("\"\\ude00\"", JsonWriter.quote("\uDE00"))
        assertEquals("\"\\ud83d\"", JsonWriter.quote("\uD83D"))
    }

    @Test
    fun `misuse is a programming error, not output`() {
        assertFailsWith<IllegalStateException> { json { name("a") } }
        assertFailsWith<IllegalStateException> { json { beginObject(); value("a") } }
        assertFailsWith<IllegalStateException> { json { beginObject(); name("a"); name("b") } }
        assertFailsWith<IllegalStateException> { json { beginObject(); name("a"); endObject() } }
        assertFailsWith<IllegalStateException> { json { beginArray(); endObject() } }
        assertFailsWith<IllegalStateException> { json { value(1); value(2) } }
    }

    @Test
    fun `a raw value can write itself, with the separators still the writer's`() {
        val out = StringBuilder()
        JsonWriter(out).writeArray {
            value(1)
            rawValue { out.append("""{"big":true}""") }
            value(2)
        }
        assertEquals("""[1,{"big":true},2]""", out.toString())
    }

    private fun indented(indent: String = "  ", write: JsonWriter.() -> Unit): String =
        buildString { JsonWriter(this, indent).write() }

    /** The same record for every layout: nested objects and arrays, both kinds empty, and a raw value. */
    private val record: JsonWriter.() -> Unit = {
        writeObject {
            field("doc", 7L)
            name("tags").writeArray { value("x"); writeArray { value(1); value(2) }; writeObject { } }
            name("empty").writeArray { }
            name("nested").writeObject { name("deeper").writeObject { field("ok", true) } }
            name("raw").rawValue("""{"k":[1,2]}""")
        }
    }

    @Test
    fun `compact output is unchanged byte for byte`() {
        assertEquals(
            """{"doc":7,"tags":["x",[1,2],{}],"empty":[],"nested":{"deeper":{"ok":true}},"raw":{"k":[1,2]}}""",
            json(record),
        )
    }

    @Test
    fun `indented output puts each member and element on its own line`() {
        assertEquals(
            """
            {
              "doc": 7,
              "tags": [
                "x",
                [
                  1,
                  2
                ],
                {}
              ],
              "empty": [],
              "nested": {
                "deeper": {
                  "ok": true
                }
              },
              "raw": {"k":[1,2]}
            }
            """.trimIndent(),
            indented(write = record),
        )
    }

    @Test
    fun `empty containers and scalars at the top level, with no newline after`() {
        assertEquals("{}", indented { writeObject { } })
        assertEquals("[]", indented { writeArray { } })
        assertEquals("\"a\"", indented { value("a") })
        assertEquals("[\n\t1\n]", indented("\t") { writeArray { value(1) } })
    }

    /** What a formatter indented from column zero, as an engine hands a stored value over. */
    private val node = "{\n  \"a\": [\n    1\n  ],\n  \"s\": \"x\\ny\"\n}"

    @Test
    fun `a raw multi-line value is shifted to the depth it sits at`() {
        val text = indented {
            writeObject { name("l1").writeObject { name("l2").writeArray { rawValue(node) } } }
        }
        assertEquals(
            """
            {
              "l1": {
                "l2": [
                  {
                    "a": [
                      1
                    ],
                    "s": "x\ny"
                  }
                ]
              }
            }
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `with a tab, and without re-indenting when asked not to`() {
        val tabbed = indented("\t") { writeArray { writeArray { writeArray { rawValue("[\n\t1\n]") } } } }
        assertEquals("[\n\t[\n\t\t[\n\t\t\t[\n\t\t\t\t1\n\t\t\t]\n\t\t]\n\t]\n]", tabbed)

        val asIs = indented { writeArray { rawValue(node, reindent = false) } }
        assertEquals("[\n  $node\n]", asIs)
        // A compact writer never re-indents, even when asked to: there is no depth's indentation to add.
        assertEquals("[$node]", json { writeArray { rawValue(node, reindent = true) } })
    }

    @Test
    fun `depth counts the open containers, so a caller can work out a value's column`() {
        val out = StringBuilder()
        val writer = JsonWriter(out, "  ")
        assertEquals(0, writer.depth)
        writer.beginObject()
        assertEquals(1, writer.depth)
        writer.name("key")
        // The value starts after the indent, the quoted name and ": ".
        val column = writer.depth * writer.indent!!.length + JsonWriter.quote("key").length + 2
        assertEquals(out.toString().substringAfterLast('\n').length, column)
        writer.beginArray()
        assertEquals(2, writer.depth)
        writer.endArray().endObject()
        assertEquals(0, writer.depth)
    }

    @Test
    fun `an indent is whitespace JSON allows between tokens, and nothing else`() {
        assertFailsWith<IllegalArgumentException> { JsonWriter(StringBuilder(), "--") }
        assertFailsWith<IllegalArgumentException> { JsonWriter(StringBuilder(), "\n") }
    }

    @Test
    fun `the writer knows when its one top-level value is complete`() {
        val writer = JsonWriter(StringBuilder())
        writer.beginArray()
        assertFalse(writer.isComplete)
        writer.endArray()
        assertTrue(writer.isComplete)
    }
}
