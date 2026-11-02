package app.oreshkov.delva.internal.path

import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.DocumentFilterTemplate
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathTemplate
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.QueryWarning
import app.oreshkov.delva.internal.canon.Nfc
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The warnings of SPEC.md §10.1: a `match` or `search` whose literal pattern makes it false for every value warns at
 * the literal, while the query still compiles. The compliance suite's own cases are checked in JsonPathSelectTest.
 */
class QueryWarningTest {
    /** The one warning of [text], which must also be the literal [token] at [offset]. */
    private fun single(text: String, token: String): QueryWarning {
        val warning = JsonPath.compile(text).warnings.single()
        assertEquals(text.indexOf(token), warning.offset, "offset in $text")
        assertEquals(token.length, warning.length, "length in $text")
        return warning
    }

    @Test
    fun `a string pattern that isn't I-Regexp warns at the literal, with the reason`() {
        val text = "\$.value.response.body.content[*].entries[?search(@.definition.id, \"(?i)dracula\")]"
        val warning = single(text, "\"(?i)dracula\"")
        assertEquals(
            "\"(?i)dracula\" isn't I-Regexp (RFC 9485), so search() is false for every value: (?i) is an inline flag, " +
                "which I-Regexp doesn't have; to match either case, list both in a class, as in [Dd]",
            warning.reason,
        )
        assertEquals("QueryWarning(offset=66, length=13, reason=${warning.reason})", warning.toString())
        // The span is the literal as written, its escapes included.
        assertContains(single("$[?match(@.a, '\\\\d+')]", "'\\\\d+'").reason, "'\\\\d+' isn't I-Regexp (RFC 9485), so match() is false for every value: \\d isn't")
        assertContains(single("$[?match(@.a, '[z-a]')]", "'[z-a]'").reason, "the range z-a runs backwards")
    }

    @Test
    fun `a pattern that isn't a string warns as well`() {
        assertEquals("the pattern 1 is a number, not a string, so match() is false for every value", single("$[?match(@.a, 1)]", "1").reason)
        assertEquals("the pattern -1.5e3 is a number, not a string, so search() is false for every value", single("$[?search(@.a, -1.5e3)]", "-1.5e3").reason)
        assertEquals("the pattern true is a boolean, not a string, so match() is false for every value", single("$[?match(@.a,true)]", "true").reason)
        assertEquals("the pattern false is a boolean, not a string, so search() is false for every value", single("$[?search(@.a , false )]", "false").reason)
        assertEquals("the pattern null isn't a string, so match() is false for every value", single("$[?match(@.a, null)]", "null").reason)
    }

    @Test
    fun `a pattern that can match, or that isn't a literal, doesn't warn`() {
        for (text in listOf(
            "$..x",
            "$[?match(@.a, 'a.*') && search(@.b, '[Dd]racula')]",
            "$[?match(@.a, '')]",
            // The pattern comes from the document, and could be valid there.
            "$[?match(@.a, @.p)]",
            "$[?search(@.a, \$.p)]",
            "$[?match(@.a, value(@..p))]",
            // A literal subject is RFC 9535; only the pattern is checked.
            "$[?match('(?i)', 'x')]",
            // Text in a string is text, whatever it looks like.
            "$[?@.a == '(?i)x']",
            "$[?length('(?i)') == 4]",
        )) {
            assertEquals(emptyList(), JsonPath.compile(text).warnings, text)
        }
    }

    @Test
    fun `every such pattern warns, in the order of the text`() {
        val text = "$[?match(@.a, '(') || @.b[?search(@.c, 2)] || match(value(@..[?search(@.d, '\\\\w')]), null)]"
        val warnings = JsonPath.compile(text).warnings
        assertEquals(listOf("'('", "2", "'\\\\w'", "null"), warnings.map { text.substring(it.offset, it.offset + it.length) })
        assertEquals(warnings.sortedBy { it.offset }, warnings)
    }

    @Test
    fun `a document filter warns at offsets in its own text`() {
        val filter = DocumentFilter.compile("  @.kind == 'x' && search(@.name, '\\\\bx')")
        val warning = filter.warnings.single()
        assertEquals(34, warning.offset)
        assertEquals(6, warning.length)
        assertContains(warning.reason, "no word boundaries")
        assertEquals(emptyList(), DocumentFilter.compile("@.kind == 'x'").warnings)
    }

    @Test
    fun `warnings follow from the text`() {
        val a = JsonPath.compile("$[?match(@.a, 1)]")
        val b = JsonPath.compile("$[?match(@.a, 1)]")
        assertEquals(a, b)
        assertEquals(a.warnings, b.warnings)
        assertEquals(a.warnings.single().hashCode(), b.warnings.single().hashCode())
        assertNotEquals(a.warnings, JsonPath.compile("$[?match(@.a, 2)]").warnings)
        assertNotEquals(a.warnings, JsonPath.compile("$[?match(@.ab, 1)]").warnings)
    }

    @Test
    fun `warnings can't be changed, from Java either`() {
        val extra = QueryWarning(0, 1, "x")
        val lists = listOf(
            JsonPath.compile("$[?match(@.a, 1) || search(@.b, 2)]").warnings,
            DocumentFilter.compile("match(@.a, 1)").warnings,
            JsonPathTemplate.compile("$[?match(@.a, 1)]").warnings,
            JsonPathTemplate.compile("\$[?match(@.a, 1) || search(@.b, \${p})]").bind("p" to JsonQueryValue.Null).warnings,
            JsonPath.compile("$..x").warnings,
        )
        for (list in lists) {
            // As a Java caller sees it: a java.util.List, whose add() Kotlin's read-only type doesn't hide.
            @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            val javaList = list as java.util.List<QueryWarning>
            assertFailsWith<UnsupportedOperationException> { javaList.add(extra) }
        }
    }

    @Test
    fun `a template warns of its own literals, and a bound one of its bound text`() {
        val template = JsonPathTemplate.compile("\$[?@.k == \${k} && match(@.a, '(?i)x') || search(@.b, \${p})]")
        val own = template.warnings.single()
        assertEquals(template.toString().indexOf("'(?i)x'"), own.offset)
        // A pattern that can match, and a long key before the template's own literal.
        val good = template.bind("k" to JsonQueryValue.Str("a much longer key"), "p" to JsonQueryValue.Str("b+"))
        assertEquals(JsonPath.compile(good.toString()).warnings, good.warnings)
        assertEquals(own.offset + "'a much longer key'".length - "\${k}".length, good.warnings.single().offset)
        assertEquals(own.reason, good.warnings.single().reason)
        // A pattern that can't: the bound literal warns at its place in the bound text.
        val bad = template.bind("k" to JsonQueryValue.Num(7), "p" to JsonQueryValue.Str("x{9,1}"))
        assertEquals(JsonPath.compile(bad.toString()).warnings, bad.warnings)
        assertEquals(listOf("'(?i)x'", "'x{9,1}'"), bad.warnings.map { bad.toString().substring(it.offset, it.offset + it.length) })
        val notString = template.bind("k" to JsonQueryValue.Null, "p" to JsonQueryValue.Bool(false))
        assertContains(notString.warnings.last().reason, "the pattern false is a boolean")

        val filter = DocumentFilterTemplate.compile("search(@.a, \${p})")
        assertEquals(emptyList(), filter.warnings)
        val bound = filter.bind("p" to JsonQueryValue.Str("\\d"))
        assertEquals(DocumentFilter.compile(bound.toString()).warnings, bound.warnings)
        assertEquals(12, bound.warnings.single().offset)
    }

    /** Patterns valid and not, with characters a string literal escapes. */
    private val patterns = listOf(
        "a.c", "", "[Dd]racula", "\\p{Lu}+", "it's", "\"q\"", "\\\\", "é{2}", "😀|x",
        "(?i)x", "\\d+", "x{2,1}", "(", "[", "a**", "\\", "(?=x)", "\\p{IsLatin}", "]", "'(?i)'",
    )

    private fun stringToken(s: String): String = StringBuilder().also { NormalizedPath.appendString(it, s) }.toString()

    private class Case(val template: String, val values: Map<String, JsonQueryValue>, val filter: Boolean) {
        override fun toString(): String = "${if (filter) "filter" else "query"} $template with $values"
    }

    private val cases = Gen { random, size ->
        fun value(): JsonQueryValue = when (random.nextInt(6)) {
            0 -> JsonQueryValue.Num(BigDecimal(random.nextInt(-1000, 1000)).movePointLeft(random.nextInt(3)))
            1 -> JsonQueryValue.Bool(random.nextBoolean())
            2 -> JsonQueryValue.Null
            else -> JsonQueryValue.Str(patterns[random.nextInt(patterns.size)])
        }
        val names = listOf("p", "q", "long_name")
        fun term(r: Random): String {
            val subject = listOf("@.a", "@", "\$.s", "@['x y']")[r.nextInt(4)]
            val function = if (r.nextBoolean()) "match" else "search"
            return when (r.nextInt(6)) {
                0, 1 -> "$function($subject, \${${names[r.nextInt(names.size)]}})"
                2 -> "$function($subject, ${stringToken(patterns[r.nextInt(patterns.size)])})"
                3 -> "$function($subject,${listOf("1", "true", "null", "-2.5")[r.nextInt(4)]})"
                4 -> "@.k == \${${names[r.nextInt(names.size)]}}"
                else -> "@.n[?$function(@, \${${names[r.nextInt(names.size)]}})]"
            }
        }
        val terms = List(1 + random.nextInt(1 + size / 10)) { term(random) }
        val expr = terms.joinToString(if (random.nextBoolean()) " && " else " || ")
        val filter = random.nextInt(3) == 0
        val text = if (filter) expr else "\$..v[?$expr]"
        val used = names.filter { "\${$it}" in text }
        Case(text, used.associateWith { value() }, filter)
    }

    @Test
    fun `a bound query's warnings are those of compiling its bound text`() {
        var warned = 0
        var fromPlaceholders = 0
        forAll("template warnings", cases, iterations = 400) { case ->
            val (bound, recompiled, own) = if (case.filter) {
                val template = DocumentFilterTemplate.compile(case.template)
                val bound = template.bind(case.values)
                Triple(bound.warnings, DocumentFilter.compile(bound.toString()).warnings, template.warnings)
            } else {
                val template = JsonPathTemplate.compile(case.template)
                val bound = template.bind(case.values)
                Triple(bound.warnings, JsonPath.compile(bound.toString()).warnings, template.warnings)
            }
            assertEquals(recompiled, bound, "$case")
            // The template's own warnings are among the bound ones, with the same reasons.
            assertTrue(bound.map { it.reason }.containsAll(own.map { it.reason }), "$case")
            if (bound.isNotEmpty()) warned++
            if (bound.size > own.size) fromPlaceholders++
        }
        assertTrue(warned > 100 && fromPlaceholders > 50, "$warned warned, $fromPlaceholders through a placeholder")
    }

    @Test
    fun `a pattern that NFC makes valid or invalid says which store it is false in`() {
        // In NFC, y + U+0303 is U+1EF9, so the range starts above its end.
        val broken = single("$[?match(@, '[y\u0303-\u0304]')]", "'[y\u0303-\u0304]'")
        assertEquals(
            "'[y\u0303-\u0304]' is '[\u1EF9-\u0304]' in NFC, which isn't I-Regexp (RFC 9485), so match() is false for every " +
                "value in a store with normalization: the range \u1EF9-\u0304 runs backwards",
            broken.reason,
        )
        // The other way round: U+00E9 to e is backwards as written, U+00E9 to e + U+0301, composed to U+00E9, isn't.
        val mended = single("$[?search(@, '[\u00E9-e\u0301]')]", "'[\u00E9-e\u0301]'")
        assertContains(mended.reason, "so search() is false for every value in a store without normalization, though valid in NFC: the range \u00E9-e runs backwards")
    }

    @Test
    fun `a pattern warns exactly when it is invalid as written or in NFC, which is what each store runs`() {
        // A store with normalization runs the pattern in NFC (SPEC.md §10.2), one without runs it as written.
        val alphabet = listOf(
            "a", "e", "y", "\u00E9", "\u0301", "\u0303", "\u0304", "\u0327", "c", "\u00E7", "-", "[", "]", "(", ")", "{2}", "*", "|",
            "\\.", "\\p{L}", "\uAC00", "\u1100", "\u1161",
        )
        // Character classes with ranges, where composing moves a range's end: a composed pair is one code point.
        val classChars = alphabet.filter { it.length == 1 && it !in setOf("-", "[", "]", "(", ")", "*", "|") }
        val gen = Gen { random, size ->
            fun piece(): String = if (random.nextBoolean()) {
                alphabet[random.nextInt(alphabet.size)]
            } else {
                val items = List(1 + random.nextInt(4)) {
                    val c = classChars[random.nextInt(classChars.size)]
                    if (random.nextBoolean()) "$c-${classChars[random.nextInt(classChars.size)]}" else c
                }
                "[" + items.joinToString("") + "]"
            }
            List(random.nextInt(1 + size / 10 + 1)) { piece() }.joinToString("")
        }
        var valid = 0
        var changedByNfc = 0
        forAll("pattern warnings and NFC", gen, iterations = 2000) { pattern ->
            val compiled = JsonPath.compile("\$[?match(@, ${stringToken(pattern)})]")
            val call = ((compiled.query.segments[0].selectors[0] as Selector.Filter).expr as LogicalExpr.Test).call
            val normalized = (compiled.query.normalized().segments[0].selectors[0] as Selector.Filter).expr as LogicalExpr.Test
            assertEquals(Nfc.normalize(pattern, "pattern"), (normalized.call.patternLiteral as LiteralValue.Str).value)
            val validAsWritten = call.literalRegexp is IRegexp.Compiled
            val validInNfc = normalized.call.literalRegexp is IRegexp.Compiled
            assertEquals(!validAsWritten || !validInNfc, compiled.warnings.isNotEmpty(), "warnings of '$pattern'")
            if (validAsWritten) valid++
            if (validAsWritten != validInNfc) {
                changedByNfc++
                val store = if (validAsWritten) "in a store with normalization" else "in a store without normalization"
                assertContains(compiled.warnings.single().reason, store, message = "'$pattern'")
            }
        }
        assertTrue(valid > 200 && changedByNfc > 5, "$valid valid patterns, $changedByNfc changed by NFC")
    }
}
