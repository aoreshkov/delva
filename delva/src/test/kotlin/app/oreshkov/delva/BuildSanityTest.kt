package app.oreshkov.delva

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildSanityTest {
    @Test
    fun `tests run on the JDK 25 toolchain`() {
        assertEquals(25, Runtime.version().feature())
    }
}
