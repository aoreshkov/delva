package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPathQueryGenTest {
    private val queries = JsonPathQueryGen(listOf(OObj("kind" to OStr("order"), "n" to ONum("3"))))

    private val placeholder = Regex("""\$\{(\w+)}""")

    @Test
    fun `a template's values are the placeholders its text has`() {
        val random = Random(2)
        repeat(500) {
            val template = queries.template(random, queries::documentFilter)
            assertEquals(placeholder.findAll(template.text).map { it.groupValues[1] }.toSet(), template.values.keys, "$template")
        }
    }

    @Test
    fun `text the generator drops leaves no values behind`() {
        val random = Random(1)
        repeat(200) {
            val template = queries.template(random) { r ->
                queries.documentFilter(r) // generated and then dropped, as an expression that grows too long is
                "@"
            }
            assertEquals("@", template.text)
            assertEquals(emptyMap(), template.values)
        }
    }
}
