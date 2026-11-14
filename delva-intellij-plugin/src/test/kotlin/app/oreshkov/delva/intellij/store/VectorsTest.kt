package app.oreshkov.delva.intellij.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorsTest {
    @Test
    fun `reads a JSON array of numbers`() {
        assertEquals(listOf(0.12f, -0.5f, 0.03f, 1f, -0f), parseVector(" [0.12, -0.5,3e-2 ,1, -0]\n"))
        assertEquals("too small for a float is zero", listOf(0f), parseVector("[1e-50]"))
        assertNull(checkVector("[1]"))
    }

    @Test
    fun `says what is wrong with anything else`() {
        val notArray = "Type a vector as a JSON array of numbers, such as [0.12, -0.5, 0.33]"
        assertEquals(notArray, checkVector("0.12, -0.5"))
        assertEquals(notArray, checkVector("["))
        assertEquals("The vector has no numbers", checkVector("[ ]"))
        assertEquals("The vector's element [1] is missing", checkVector("[1, , 2]"))
        assertEquals("The vector's element [2] is missing", checkVector("[1, 2,]"))
        assertEquals("The vector's element [0], NaN, isn't a JSON number", checkVector("[NaN]"))
        assertEquals("The vector's element [1], 1f, isn't a JSON number", checkVector("[0, 1f]"))
        assertEquals("The vector's element [0], .5, isn't a JSON number", checkVector("[.5]"))
        assertEquals("The vector's element [0], [1], isn't a JSON number", checkVector("[[1]]"))
        assertEquals("The vector's element [0], 1e39, is too large", checkVector("[1e39]"))
    }

    @Test
    fun `reads a JSON number a float holds`() {
        assertEquals(0.25f, parseJsonFloat("0.25"))
        assertEquals(-2e3f, parseJsonFloat("-2E3"))
        assertNull(parseJsonFloat("+1"))
        assertNull(parseJsonFloat("Infinity"))
        assertNull(parseJsonFloat("-1e40"))
        assertNull(parseJsonFloat(""))
    }
}
