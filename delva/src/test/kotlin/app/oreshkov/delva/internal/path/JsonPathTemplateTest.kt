package app.oreshkov.delva.internal.path

import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.DocumentFilterTemplate
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathTemplate
import app.oreshkov.delva.JsonQueryValue
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Templates (SPEC.md §10.6): where a placeholder can stand, how a value becomes a literal of the tree and of the
 * bound text, and what binding refuses. The differential tests against the oracle are in JsonPathSelectTest and
 * DocumentFilterSearchTest.
 */
class JsonPathTemplateTest {
    private fun str(s: String) = JsonQueryValue.Str(s)

    /** The right side of the one comparison of `$[?…]`. */
    private fun comparedLiteral(path: JsonPath): LiteralValue {
        val filter = path.query.segments.single().selectors.single() as Selector.Filter
        return ((filter.expr as LogicalExpr.Comparison).right as ValueExpr.Literal).value
    }

    @Test
    fun `parameters are the names in the order they first appear`() {
        val template = JsonPathTemplate.compile("\$[?@.a == \${b} && \${a} < @.n || @.c == \${b}]")
        assertEquals(listOf("b", "a"), template.parameters.toList())
        assertEquals(emptySet(), JsonPathTemplate.compile("$..x").parameters)
        assertEquals(listOf("kind"), DocumentFilterTemplate.compile(" @.kind == \${kind} ").parameters.toList())
    }

    @Test
    fun `a bound query is the query its text compiles to`() {
        val template = JsonPathTemplate.compile("\$[?@.a == \${x} && @.b > \${y} || @.c == \${x}]")
        val bound = template.bind("x" to str("it's"), "y" to JsonQueryValue.Num(BigDecimal("1E+3")))
        assertEquals("\$[?@.a == 'it\\'s' && @.b > 1E+3 || @.c == 'it\\'s']", bound.toString())
        assertEquals(JsonPath.compile(bound.toString()), bound)
        assertEquals("\$[?@.a == null && @.b > -0.5 || @.c == null]", template.bind(mapOf("x" to JsonQueryValue.Null, "y" to JsonQueryValue.Num(BigDecimal("-0.5")))).toString())
        assertEquals("\$[?@.a == true && @.b > 7 || @.c == true]", template.bind("x" to JsonQueryValue.Bool(true), "y" to JsonQueryValue.Num(7)).toString())
        assertEquals(JsonPath.compile("$..x"), JsonPathTemplate.compile("$..x").bind())

        val filter = DocumentFilterTemplate.compile("@.kind == \${kind} && @.v >= \${v}").bind("kind" to str("order"), "v" to JsonQueryValue.Num(2))
        assertEquals(DocumentFilter.compile("@.kind == 'order' && @.v >= 2"), filter)
        assertEquals("@.kind == 'order' && @.v >= 2", filter.toString())
    }

    @Test
    fun `a value stays one literal, whatever its characters`() {
        val template = JsonPathTemplate.compile("\$[?@.a == \${v}]")
        val corpus = listOf(
            "", "'", "\"", "\\", "\\'", "x' || true || 'x", "'] || \$[?@", "x\" || @.b || \"", "\${v}", "]", ")", "\u0000",
            "\n\r\t\b\u000C\u001F", "\u007F", "é", "é", "😀", "￿", "a".repeat(10_000),
        )
        for (s in corpus) {
            val bound = template.bind("v" to str(s))
            assertEquals(s, (comparedLiteral(bound) as LiteralValue.Str).value, "bound '$s'")
            // The text compiles to the same single comparison.
            val recompiled = JsonPath.compile(bound.toString())
            assertEquals(s, (comparedLiteral(recompiled) as LiteralValue.Str).value, "recompiled '$s'")
        }
    }

    @Test
    fun `numbers bind with the key a compiled literal has`() {
        val template = JsonPathTemplate.compile("\$[?@.a == \${n}]")
        for (text in listOf("0", "-1", "1.50", "1E+400", "-2.5E-7", "123456789012345678901234567890", "0E-10")) {
            val bound = template.bind("n" to JsonQueryValue.Num(BigDecimal(text)))
            val literal = comparedLiteral(bound) as LiteralValue.Num
            val compiled = comparedLiteral(JsonPath.compile(bound.toString())) as LiteralValue.Num
            assertTrue(literal.key.contentEquals(compiled.key), "key of $text")
        }
    }

    @Test
    fun `binding again leaves the template and earlier queries as they were`() {
        val template = JsonPathTemplate.compile("\$[?@.a == \${v}]")
        val first = template.bind("v" to str("one"))
        val second = template.bind("v" to str("two"))
        assertEquals("one", (comparedLiteral(first) as LiteralValue.Str).value)
        assertEquals("two", (comparedLiteral(second) as LiteralValue.Str).value)
    }

    @Test
    fun `binding copies only the nodes on the way to a placeholder`() {
        val (tree, placeholders) = JsonPathParser.parseTemplate("\$.a[?@.x == 1].b[?@.y == \${v}]")
        val bound = placeholders.bind(mapOf("v" to str("z"))).query(tree)
        assertSame(tree.segments[0], bound.segments[0])
        assertSame(tree.segments[1], bound.segments[1])
        assertSame(tree.segments[2], bound.segments[2])
        assertTrue(tree.segments[3] !== bound.segments[3])
    }

    @Test
    fun `a bound pattern is translated for match and search`() {
        val bound = JsonPathTemplate.compile("\$[?match(@.a, \${p}) || search(@.b, \${p})]").bind("p" to str("a.*"))
        val or = (bound.query.segments.single().selectors.single() as Selector.Filter).expr as LogicalExpr.Or
        for (item in or.items) assertNotNull((item as LogicalExpr.Test).call.literalPattern)
        assertEquals(JsonPath.compile("\$[?match(@.a, 'a.*') || search(@.b, 'a.*')]"), bound)
    }

    @Test
    fun `a placeholder stands only where a literal can`() {
        fun invalid(text: String): String = assertFailsWith<InvalidJsonPathException>(text) { JsonPathTemplate.compile(text) }.reason
        // Valid positions: either side of a comparison, a value argument.
        JsonPathTemplate.compile("\$[?\${a} == @.x]")
        JsonPathTemplate.compile("\$[?length(\${a}) == 3]")
        JsonPathTemplate.compile("\$[?@.x == \$.y && \$.z != \${a}]")
        JsonPathTemplate.compile("\$..[?(@.x == \${a})]")
        assertContains(invalid("\$[\${a}]"), "not for a selector")
        assertContains(invalid("\$[0, \${a}]"), "not for a selector")
        assertContains(invalid("\$.\${a}"), "member name")
        assertContains(invalid("\${a}"), "not for a query")
        assertContains(invalid("\$[?\${a}]"), "literal alone")
        assertContains(invalid("\$[?!\${a}]"), "not a literal")
        assertContains(invalid("\$[?count(\${a}) == 1]"), "must be a query")
        assertContains(invalid("\$[?@.a == \${}]"), "starts with an ASCII letter")
        assertContains(invalid("\$[?@.a == \${1a}]"), "starts with an ASCII letter")
        assertContains(invalid("\$[?@.a == \${a-b}]"), "expected '}'")
        assertContains(invalid("\$[?@.a == \${a"), "expected '}'")
        assertContains(invalid("\$[?@.a == \${é}]"), "starts with an ASCII letter")
        // Inside a string it is text, as in RFC 9535.
        assertEquals(emptySet(), JsonPathTemplate.compile("\$[?@.a == '\${a}']").parameters)
        // A template's errors name the offset in its text, as compile's do.
        val e = assertFailsWith<InvalidJsonPathException> { JsonPathTemplate.compile("\$[?@.a == \${a} &&]") }
        assertEquals(17, e.offset)
    }

    @Test
    fun `compile refuses a placeholder`() {
        for (text in listOf("\$[?@.a == \${a}]", "\$[\${a}]")) {
            assertContains(assertFailsWith<InvalidJsonPathException> { JsonPath.compile(text) }.reason, "placeholder")
        }
        assertContains(assertFailsWith<InvalidJsonPathException> { DocumentFilter.compile("@.a == \${a}") }.reason, "only a template")
    }

    @Test
    fun `binding refuses missing, unknown and JSON values`() {
        val template = JsonPathTemplate.compile("\$[?@.a == \${a} && @.b == \${b}]")
        assertContains(assertFailsWith<IllegalArgumentException> { template.bind("a" to str("x")) }.message!!, "no value for \${b}")
        assertContains(
            assertFailsWith<IllegalArgumentException> { template.bind("a" to str("x"), "b" to str("y"), "c" to str("z")) }.message!!,
            "no placeholder \${c}",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { template.bind("a" to str("x"), "b" to JsonQueryValue.Json("{}")) }.message!!,
            "no literal form",
        )
    }

    @Test
    fun `a value that makes the query invalid is refused at its offset in the bound text`() {
        val template = JsonPathTemplate.compile("\$[?@.b == \${b} || @.a == \${a}]")
        val surrogate = assertFailsWith<InvalidJsonPathException> { template.bind("a" to str("x\uD800"), "b" to str("y")) }
        assertEquals("\$[?@.b == 'y' || @.a == 'x\uD800']", surrogate.text)
        assertEquals(24, surrogate.offset)
        assertContains(surrogate.reason, "unpaired surrogate")
        // The same, as compiling the text would.
        assertFailsWith<InvalidJsonPathException> { JsonPath.compile(surrogate.text) }

        val exponent = assertFailsWith<InvalidJsonPathException> {
            template.bind("a" to str("x"), "b" to JsonQueryValue.Num(BigDecimal("1E+2147483647").multiply(BigDecimal.TEN)))
        }
        assertEquals(10, exponent.offset)
        assertContains(exponent.reason, "exponent is out of range")

        val tooLong = assertFailsWith<InvalidJsonPathException> {
            template.bind("a" to str("x".repeat(JsonPathParser.MAX_LENGTH)), "b" to str("y"))
        }
        assertContains(tooLong.reason, "at most ${JsonPathParser.MAX_LENGTH} characters")
        val filterTooLong = assertFailsWith<InvalidJsonPathException> {
            DocumentFilterTemplate.compile("@.a == \${a}").bind("a" to str("x".repeat(JsonPathParser.MAX_LENGTH)))
        }
        assertContains(filterTooLong.reason, "a filter has at most")
        // A paired surrogate is a character like any other.
        assertIs<JsonPath>(template.bind("a" to str("😀"), "b" to str("y")))
    }

    @Test
    fun `templates are equal by text`() {
        assertEquals(JsonPathTemplate.compile("\$[?@.a == \${a}]"), JsonPathTemplate.compile("\$[?@.a == \${a}]"))
        assertEquals("\$[?@.a == \${a}]", JsonPathTemplate.compile("\$[?@.a == \${a}]").toString())
        assertTrue(JsonPathTemplate.compile("\$[?@.a == \${a}]") != JsonPathTemplate.compile("\$[?@.a==\${a}]"))
        assertEquals(DocumentFilterTemplate.compile("@.a == \${a}").hashCode(), DocumentFilterTemplate.compile("@.a == \${a}").hashCode())
    }
}
