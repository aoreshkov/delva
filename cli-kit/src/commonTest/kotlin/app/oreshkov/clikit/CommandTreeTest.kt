package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CommandTreeTest {

    private fun List<CommandSpec>.names(): String = commandLine(this)

    @Test
    fun `paths are depth first, each command's own path before its children's`() {
        assertEquals(
            listOf(
                "demo",
                "demo session", "demo session login", "demo session list",
                "demo request", "demo request get", "demo request raw",
                "demo store", "demo store get", "demo store query", "demo store prune",
                "demo completion",
            ),
            TestCommands.root.paths().map { it.names() }.toList(),
        )
    }

    @Test
    fun `a leaf's only path is itself`() {
        assertEquals(listOf(listOf(TestCommands.raw)), TestCommands.raw.paths().toList())
    }

    @Test
    fun `runnable paths are the commands a program needs a handler for, completion left out`() {
        assertEquals(
            listOf(TestCommands.login, TestCommands.list, TestCommands.get, TestCommands.raw) +
                listOf(TestCommands.storeGet, TestCommands.query, TestCommands.prune),
            TestCommands.root.runnablePaths().map { it.last() }.toList(),
        )
        assertEquals(TestCommands.handlers().keys, TestCommands.root.runnablePaths().map { it.last() }.toSet())
    }

    @Test
    fun `pathTo walks down by name, and names the first step that is missing`() {
        assertEquals(listOf(TestCommands.root), TestCommands.root.pathTo())
        assertEquals("demo store get", TestCommands.root.pathTo("store", "get").names())
        assertEquals(TestCommands.storeGet, TestCommands.root.pathTo("store", "get").last())
        val error = assertFailsWith<IllegalArgumentException> { TestCommands.root.pathTo("store", "gte", "x") }
        assertEquals("no command 'gte' under 'demo store'", error.message)
    }

    @Test
    fun `an invocation's command line is the path's`() {
        val parsed = Parser(TestCommands.root).parse(listOf("store", "get", "k"))
        assertIs<ParseResult.Invocation>(parsed)
        assertEquals("demo store get", parsed.commandLine())
        assertEquals(parsed.commandLine(), commandLine(parsed.path))
    }
}
