package app.oreshkov.delva.internal.path

import app.oreshkov.delva.internal.path.PathStep.Index
import app.oreshkov.delva.internal.path.PathStep.Name
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.oracle.JsonPath
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SingularPathTest {
    @Test
    fun `RFC 9535 singular queries parse into steps`() {
        assertEquals(emptyList(), SingularPath.parse("$"))
        assertEquals(listOf(Name("a"), Index(0), Name("b_1")), SingularPath.parse("$.a[0].b_1"))
        assertEquals(listOf(Name("café"), Name("名前"), Name("😀x")), SingularPath.parse("$.café.名前.😀x"))
        assertEquals(listOf(Name("@type"), Name("\$ref"), Name("b-c")), SingularPath.parse("$['@type'][\"\$ref\"]['b-c']"))
        assertEquals(listOf(Name("it's"), Name("say \"hi\"")), SingularPath.parse("""$['it\'s']['say "hi"']"""))
        assertEquals(listOf(Name("\"'"), Name("/\\")), SingularPath.parse("""$["\"'"]["\/\\"]"""))
        assertEquals(listOf(Name("\b\u000C\n\r\t"), Name("é😀"), Name("é")), SingularPath.parse("""$['\b\f\n\r\t']["\u00E9\uD83D\uDE00"]['\u00e9']"""))
        assertEquals(listOf(Name("a"), Index(1), Name("b")), SingularPath.parse("$ .a\n[1]\t\r['b']"))
        assertEquals(listOf(Index(SingularPath.MAX_INDEX)), SingularPath.parse("$[9007199254740991]"))
        assertEquals(listOf(Name("")), SingularPath.parse("$['']"))
    }

    @Test
    fun `everything else is rejected`() {
        val bad = listOf(
            "", "a", " $", "$ ", "$.a ", "$.", "$..a", "$.*", "$.1a", "$.@type", "$.\$ref", "$.b-c", "$.a\u007F",
            "$[", "$[]", "$[0", "$[01]", "$[-0]", "$[-1]", "$[+1]", "$[9007199254740992]", "$[99999999999999999999]",
            "$[ 0]", "$[0 ]", "$[ 'a']", "$[*]", "$[?@]", "$[0:1]", "$[:1]", "$[0,1]", "$['a','b']",
            "$[a]", "$['a\"]", "$['a'", "$[\"\\'\"]", "$['\\\"']", "$[\"\\x\"]", "$[\"\\u12G4\"]", "$[\"\\u٣٣٣٣\"]",
            "$[\"\\ud800\"]", "$[\"\\udc00\"]", "$[\"\\ud83d\\u0041\"]", "$[\"\\ud83dx\"]", "$[\"\u0001\"]", "$['\t']",
            "$[\"\ud800\"]", "$.\udc00", "@.a", "$\$a",
        )
        for (text in bad) assertFailsWith<IllegalArgumentException>(text) { SingularPath.parse(text) }
    }

    @Test
    fun `errors name the offset and what isn't supported`() {
        fun message(text: String) = assertFailsWith<IllegalArgumentException> { SingularPath.parse(text) }.message!!
        assertEquals("invalid JSONPath query at 2: negative indexes aren't supported: $[-1]", message("$[-1]"))
        assertEquals("invalid JSONPath query at 2: a member name can't start with '@'; use ['…']: $.@type", message("$.@type"))
        assertTrue("wildcards" in message("$.a[*]"))
        assertTrue("filters" in message("$[?@.a]"))
        assertTrue("descendant" in message("$..a"))
        assertTrue("several selectors" in message("$[0,1]"))
        assertTrue("trailing whitespace" in message("$.a "))
    }

    @Test
    fun `the engine parser agrees with the oracle on random spellings`() {
        val gen = Gen { random, size ->
            val value = JsonGen(JsonProfile.entries[random.nextInt(JsonProfile.entries.size)], fragmentSize = 64).value().generate(random, size)
            val text = StringBuilder("$")
            var node: OValue = value
            while (true) {
                node = when (val n = node) {
                    is OObj -> if (n.members.isEmpty()) break else n.members[random.nextInt(n.members.size)].let { (k, v) -> text.append(JsonPath.spellMember(k, random)); v }
                    is OArr -> if (n.items.isEmpty()) break else random.nextInt(n.items.size).let { i -> text.append(JsonPath.spellIndex(i.toLong(), random)); n.items[i] }
                    else -> break
                }
            }
            text.toString()
        }
        forAll("spellings", gen, iterations = 500) { text ->
            val expected = JsonPath.parse(text).steps.map { if (it is JsonPath.Step.Member) Name(it.key) else Index((it as JsonPath.Step.Index).index) }
            assertEquals(expected, SingularPath.parse(text))
        }
    }

    @Test
    fun `Normalized Paths are the RFC 9535 form and parse back`() {
        fun normalized(vararg steps: Any) = buildString {
            append('$')
            for (s in steps) if (s is Long) NormalizedPath.appendIndex(this, s) else NormalizedPath.appendName(this, s as String)
        }
        assertEquals("$", normalized())
        assertEquals("$['a'][0]['b c']", normalized("a", 0L, "b c"))
        assertEquals("""$['it\'s']['\\']['"']['/']""", normalized("it's", "\\", "\"", "/"))
        assertEquals("""$['\b\f\n\r\t\u0000\u000b\u001f']""", normalized("\b\u000C\n\r\t\u0000\u000B\u001F"))
        assertEquals("$['é😀\u007F']", normalized("é😀\u007F"))
        val bytes = "x\u0001😀".toByteArray()
        assertEquals("$['x\\u0001😀']", StringBuilder("$").also { NormalizedPath.appendName(it, bytes, bytes.size) }.toString())
        for (key in listOf("", "a", "it's", "\\", "\"", "\b\u000C\n\r\t\u0000\u000B\u001F", "é😀", "\u007F")) {
            assertEquals(listOf(Name(key), Index(3)), SingularPath.parse(normalized(key, 3L)))
        }
    }
}
