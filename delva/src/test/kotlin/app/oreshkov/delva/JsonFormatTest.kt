package app.oreshkov.delva

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonFormatTest {
    @Test
    fun `the indentation bounds are the ones the constructor checks`() {
        JsonFormat(indent = " ".repeat(JsonFormat.MAX_INDENT_SPACES))
        assertFailsWith<IllegalArgumentException> { JsonFormat(indent = " ".repeat(JsonFormat.MAX_INDENT_SPACES + 1)) }
        assertEquals(JsonFormat.TAB_COLUMNS, JsonFormat(indent = "\t").indentColumns)
        assertEquals(4, JsonFormat(indent = "    ").indentColumns)
        assertEquals(0, JsonFormat(indent = "").indentColumns)
    }

    @Test
    fun `only a format that cuts levels is an outline`() {
        assertTrue(JsonFormat(maxLevels = 0).isOutline)
        assertTrue(JsonFormat(maxLevels = 3).isOutline)
        assertFalse(JsonFormat.PRETTY.isOutline)
        assertFalse(JsonFormat.COMPACT.isOutline)
    }
}
