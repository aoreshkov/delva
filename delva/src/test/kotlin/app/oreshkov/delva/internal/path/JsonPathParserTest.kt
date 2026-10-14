package app.oreshkov.delva.internal.path

import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.QueryLimitException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The RFC 9535 parser and type checker, and the I-Regexp translation; the compliance suite is in JsonPathSelectTest. */
class JsonPathParserTest {
    private fun message(text: String) = assertFailsWith<IllegalArgumentException>(text) { JsonPath.compile(text) }.message!!

    @Test
    fun `segments and selectors parse into the tree`() {
        val q = JsonPathParser.parse("$.a..b[0, -1, 1:5:2, ::-1, *, 'c', ?@.d]")
        assertEquals(3, q.segments.size)
        assertFalse(q.segments[1].selectors.isEmpty())
        assertTrue(q.segments[1].descendant)
        val selectors = q.segments[2].selectors
        assertEquals(listOf(0L, -1L), selectors.take(2).map { (it as Selector.Index).index })
        val slice = selectors[2] as Selector.Slice
        assertEquals(Triple(1L, 5L, 2L), Triple(slice.start, slice.end, slice.step))
        val reverse = selectors[3] as Selector.Slice
        assertEquals(Triple(null, null, -1L), Triple(reverse.start, reverse.end, reverse.step))
        assertEquals(Selector.Wildcard, selectors[4])
        assertEquals("c", (selectors[5] as Selector.Name).key)
        assertIs<LogicalExpr.Exists>((selectors[6] as Selector.Filter).expr)
    }

    @Test
    fun `singular queries are exactly the RFC's, blank space inside brackets included`() {
        fun singular(text: String) = ((JsonPathParser.parse("$[?$text == 1]").segments[0].selectors[0] as Selector.Filter).expr as LogicalExpr.Comparison).left
        assertIs<ValueExpr.Query>(singular("@.a['b'][0][-1]"))
        assertIs<ValueExpr.Query>(singular("$ .a [0]"))
        assertTrue("singular" in message("$[?@[ 'a' ] == 1]"))
        assertTrue("singular" in message("$[?@.* == 1]"))
        assertTrue("singular" in message("$[?@..a == 1]"))
        assertTrue("singular" in message("$[?@[0,1] == 1]"))
        assertTrue("singular" in message("$[?length(@.*) == 1]") || "argument" in message("$[?length(@.*) == 1]"))
    }

    @Test
    fun `function types are checked`() {
        JsonPath.compile("$[?length(@) > 1]")
        JsonPath.compile("$[?count(@..*) == 2]")
        JsonPath.compile("$[?value(@..a) == 'x']")
        JsonPath.compile("$[?match(@.a, 'a.*') && search(@.b, \$.p)]")
        assertTrue("can't be a test" in message("$[?length(@)]"))
        assertTrue("can't be compared" in message("$[?match(@, 'a') == true]"))
        assertTrue("must be a query" in message("$[?count(1) == 1]"))
        assertTrue("unknown function" in message("$[?foo(@)]"))
        assertTrue("argument" in message("$[?length(@, @)]"))
        assertTrue("argument" in message("$[?length()]"))
        assertTrue("literal alone" in message("$[?1]"))
        message("$[?length (@) == 1]")
        message("$[?!@.a == 1]")
        message("$[?(@.a) == 1]")
    }

    @Test
    fun `errors name the offset`() {
        assertEquals("invalid JSONPath query at 4: expected ',' or ']': \$[0 1]", message("$[0 1]"))
        assertEquals("invalid JSONPath query at 1: trailing whitespace: \$ ", message("$ "))
        assertEquals("invalid JSONPath query at 2: an integer has no leading zeros: \$[01]", message("$[01]"))
        assertEquals("invalid JSONPath query at 2: invalid integer (-0 or a leading zero): \$[-0]", message("$[-0]"))
        assertTrue(message("$[9007199254740992]").startsWith("invalid JSONPath query at 2: an integer is at most"))
        assertTrue(message("$[?@ == 1e2147483648]").contains("exponent is out of range"))
    }

    @Test
    fun `the security limits are enforced when compiling`() {
        message("$" + ".a".repeat(JsonPathParser.MAX_LENGTH))
        // The filter is one level, each pair of parentheses another.
        fun nested(parens: Int) = "$[?" + "(".repeat(parens) + "@" + ")".repeat(parens) + "]"
        assertTrue("nest" in message(nested(JsonPathParser.MAX_NESTING)))
        JsonPath.compile(nested(JsonPathParser.MAX_NESTING - 1))
        assertTrue("selectors" in message("$[" + (0..JsonPathParser.MAX_SELECTORS).joinToString(",") + "]"))
    }

    @Test
    fun `a document filter is a logical expression on its own`() {
        fun filterMessage(text: String) = assertFailsWith<InvalidJsonPathException>(text) { DocumentFilter.compile(text) }
        assertIs<LogicalExpr.And>(JsonPathParser.parseFilter(" @.a == 1 && \$.b[?@.c] \n"))
        assertIs<LogicalExpr.Exists>(JsonPathParser.parseFilter("$.a"))
        assertIs<LogicalExpr.Not>(JsonPathParser.parseFilter("!(@.a == 1 || match(@.b, 'x.*'))"))
        assertEquals(6, filterMessage("@.a ==").offset)
        assertEquals(9, filterMessage("@.a == 1 )").offset)
        assertEquals(0, filterMessage("").offset)
        assertEquals(0, filterMessage("1").offset)
        assertTrue("singular" in filterMessage("@.* == 1").reason)
        assertTrue("can't be a test" in filterMessage("length(@)").reason)
        assertEquals("@.a ==", filterMessage("@.a ==").text)
        // The filter is one level of nesting, as the selector it stands for would be.
        fun nested(parens: Int) = "(".repeat(parens) + "@" + ")".repeat(parens)
        assertTrue("nest" in filterMessage(nested(JsonPathParser.MAX_NESTING)).reason)
        DocumentFilter.compile(nested(JsonPathParser.MAX_NESTING - 1))
        assertTrue("characters" in filterMessage("@.a".repeat(JsonPathParser.MAX_LENGTH)).reason)
    }

    @Test
    fun `queries are equal by their text`() {
        assertEquals(JsonPath.compile("$.a"), JsonPath.compile("$.a"))
        assertEquals("$ .a", JsonPath.compile("$ .a").toString())
        assertFalse(JsonPath.compile("$.a") == JsonPath.compile("$['a']"))
    }

    @Test
    fun `I-Regexp is checked and translated`() {
        fun matches(re: String, s: String) = assertNotNull(IRegexp.compile(re), re).matches(s)
        assertTrue(matches("a.c", "abc"))
        assertFalse(matches("a.c", "a\nc"))
        assertFalse(matches("a.c", "a\rc"))
        assertTrue(matches("[a-c]+\\p{Lu}", "abcX"))
        assertTrue(matches("\\P{L}+", "12"))
        assertTrue(matches("[^\\n]{2,3}", "ab"))
        assertTrue(matches("(ab|cd)*", "abcdab"))
        assertTrue(matches("[-a]", "-"))
        assertTrue(matches("[a-]", "-"))
        assertTrue(matches("\\.\\*\\{\\}\\|", ".*{}|"))
        assertTrue(matches("[&]", "&"))
        assertTrue(matches("😀.", "😀😀"))
        assertTrue(assertNotNull(IRegexp.compile("b")).find("abc"))
        assertFalse(assertNotNull(IRegexp.compile("b$")).find("ab\n"))
        for (invalid in listOf("(", ")", "a**", "*", "a{", "a{1,2", "[", "[]", "[a", "\\d", "\\w", "\\1", "(?:a)", "a{2,1}", "\\p{Xx}", "[a-\\p{L}]", "a|*", "]", "}")) {
            assertNull(IRegexp.compile(invalid), invalid)
        }
    }

    @Test
    fun `a regular expression that backtracks too much fails the query`() {
        // Recent JDKs memoize `(a+)+b`; a bounded repetition of `.*` still backtracks exponentially.
        val evil = assertNotNull(IRegexp.compile("(.*a){12}b"))
        assertFailsWith<QueryLimitException> { evil.matches("a".repeat(30) + "c") }
    }

    @Test
    fun `groups nest at most MAX_GROUP_DEPTH deep, and deeper patterns aren't valid`() {
        fun nested(depth: Int) = "(".repeat(depth) + "a" + ")".repeat(depth)
        assertTrue(assertNotNull(IRegexp.compile(nested(IRegexp.MAX_GROUP_DEPTH))).matches("a"))
        assertNull(IRegexp.compile(nested(IRegexp.MAX_GROUP_DEPTH + 1)))
        assertNull(IRegexp.compile(nested(32_000)))
        assertNull(IRegexp.compile("(".repeat(32_000)))
    }

    @Test
    fun `a match that runs out of stack fails the query, not the thread`() {
        // The JDK's matcher recurses once per repetition of a group.
        val pattern = assertNotNull(IRegexp.compile("(a|b)*"))
        val subject = "ab".repeat(500_000)
        assertContains(assertFailsWith<QueryLimitException> { pattern.matches(subject) }.message!!, "stack")
        assertContains(assertFailsWith<QueryLimitException> { assertNotNull(IRegexp.compile("(a|b)*c")).find(subject) }.message!!, "stack")
    }
}
