package app.oreshkov.delva.intellij

import app.oreshkov.delva.JsonPath
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.jsonpath.JsonPathFileType
import com.intellij.jsonpath.inspections.JsonPathUnknownFunctionInspection
import com.intellij.jsonpath.inspections.JsonPathUnknownOperatorInspection
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * How the IDE's bundled JSONPath language, which follows the Goessner and Jayway dialects, takes RFC 9535 queries that
 * Delva runs. Each query's verdict is pinned, so a platform release that changes one fails here: the verdicts decide
 * whether Delva queries can be injected as the bundled language or need a language of their own.
 */
class JsonPathDialectTest : BasePlatformTestCase() {
    fun `test verdicts of the bundled JSONPath language on RFC 9535 queries`() {
        myFixture.enableInspections(JsonPathUnknownFunctionInspection::class.java, JsonPathUnknownOperatorInspection::class.java)
        val rows = SAMPLES.map { (query, pinned) ->
            JsonPath.compile(query) // every sample is a query Delva runs
            Triple(query, pinned, verdict(query))
        }
        val table = rows.joinToString("\n") { (query, pinned, actual) ->
            val mark = if (pinned == actual) " " else "!"
            "$mark ${query.padEnd(40)} $actual" + if (pinned == actual) "" else "   (pinned: $pinned)"
        }
        println(table)
        assertTrue("Verdicts differ from the pinned ones (marked !):\n$table", rows.all { it.second == it.third })
    }

    private fun verdict(query: String): String {
        myFixture.configureByText(JsonPathFileType.INSTANCE, query)
        if (PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).isNotEmpty()) return SYNTAX_ERROR
        val problems = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }
        return if (problems.isEmpty()) OK else problems.joinToString("; ", prefix = "warning: ", transform = HighlightInfo::getDescription)
    }

    private companion object {
        const val OK = "ok"
        const val SYNTAX_ERROR = "syntax error"

        /** RFC 9535 queries, most from its examples, each with the bundled language's verdict. */
        val SAMPLES: List<Pair<String, String>> = listOf(
            // Section 1.5, the overview's examples.
            "$.store.book[*].author" to OK,
            "$..author" to OK,
            "$.store.*" to OK,
            "$.store..price" to OK,
            "$..book[2]" to OK,
            "$..book[-1]" to OK,
            "$..book[0,1]" to OK,
            "$..book[:2]" to OK,
            "$..book[?@.isbn]" to SYNTAX_ERROR,
            "$..book[?@.price<10]" to SYNTAX_ERROR,
            "$..*" to OK,
            // Name, index, slice and wildcard selectors.
            "$['store']['book'][0]" to OK,
            "$[\"store\"]" to OK,
            "$['a','b']" to OK,
            "$['a b']" to OK,
            "$['a\\'b']" to OK,
            "$[0, -1]" to OK,
            "$[1:5:2]" to OK,
            "$[5:1:-2]" to OK,
            "$[::-1]" to SYNTAX_ERROR,
            "$ ['a'] [0]" to SYNTAX_ERROR,
            // Descendant segments with brackets.
            "$..[0]" to OK,
            "$..['a']" to OK,
            "$..[?@.b]" to SYNTAX_ERROR,
            "$..[?(@.b)]" to OK,
            // Filters, which RFC 9535 writes without parentheses; each also with them, as the older dialects require.
            "$[?@.b == 'kilo']" to SYNTAX_ERROR,
            "$[?(@.b == 'kilo')]" to OK,
            "$[?@ > 3.5]" to SYNTAX_ERROR,
            "$[?(@ > 3.5)]" to OK,
            "$[?@.a == 1e2]" to SYNTAX_ERROR,
            "$[?(@.a == 1e2)]" to OK,
            "$[?@.a == null]" to SYNTAX_ERROR,
            "$[?(@.a == null)]" to OK,
            "$[?@.a == true]" to SYNTAX_ERROR,
            "$[?(@.a == true)]" to OK,
            "$[?@.a < @.b]" to SYNTAX_ERROR,
            "$[?(@.a < @.b)]" to OK,
            "$[?@.*]" to SYNTAX_ERROR,
            "$[?(@.*)]" to OK,
            "$[?@[?@.b]]" to SYNTAX_ERROR,
            "$[?(@[?(@.b)])]" to OK,
            "$[?!@.b]" to SYNTAX_ERROR,
            "$[?(!@.b)]" to OK,
            "$[?!(@.a == 'b')]" to SYNTAX_ERROR,
            "$[?(!(@.a == 'b'))]" to OK,
            "$[?@.a && (@.b || @.c)]" to SYNTAX_ERROR,
            "$[?(@.a && (@.b || @.c))]" to OK,
            // Function extensions.
            "$[?length(@.authors) >= 5]" to SYNTAX_ERROR,
            "$[?(length(@.authors) >= 5)]" to SYNTAX_ERROR,
            "$[?count(@.*.author) >= 5]" to SYNTAX_ERROR,
            "$[?(count(@.*.author) >= 5)]" to SYNTAX_ERROR,
            "$[?match(@.date, '1974-05-..')]" to SYNTAX_ERROR,
            "$[?(match(@.date, '1974-05-..'))]" to SYNTAX_ERROR,
            "$[?search(@.author, '[BR]ob')]" to SYNTAX_ERROR,
            "$[?(search(@.author, '[BR]ob'))]" to SYNTAX_ERROR,
            "$[?value(@..color) == 'red']" to SYNTAX_ERROR,
            "$[?(value(@..color) == 'red')]" to SYNTAX_ERROR,
        )
    }
}
