package app.oreshkov.delva.internal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine's internal packages depend on each other without cycles, so each can be read, tested and changed with only
 * the packages below it in mind. A cycle is reported with the imports that make it.
 */
class PackageCyclesTest {
    private val root: Path = Path.of("src/main/kotlin/app/oreshkov/delva/internal")

    @Test
    fun `internal packages have no import cycles`() {
        val graph = graph()
        assertTrue(graph.size > 10, "found the packages: ${graph.keys}")
        val cycles = components(graph.mapValues { (_, edges) -> edges.keys }).filter { it.size > 1 }
        if (cycles.isEmpty()) return
        fail(
            cycles.joinToString("\n\n", "Internal packages that depend on each other:\n") { cycle ->
                cycle.sorted().flatMap { from ->
                    graph.getValue(from).filterKeys { it in cycle }.toSortedMap().map { (to, imports) ->
                        "$from -> $to: ${imports.sorted().joinToString()}"
                    }
                }.joinToString("\n")
            },
        )
    }

    /** Each package's imports of other internal packages: the package imported, and the names imported from it. */
    private fun graph(): Map<String, Map<String, Set<String>>> =
        root.listDirectoryEntries().filter { it.isDirectory() }.associate { dir ->
            val edges = mutableMapOf<String, MutableSet<String>>()
            Files.walk(dir).use { files ->
                files.filter { it.name.endsWith(".kt") }.forEach { file ->
                    for (line in file.readLines()) {
                        val match = import.matchEntire(line.trim()) ?: continue
                        val (to, name) = match.destructured
                        if (to != dir.name) edges.getOrPut(to) { sortedSetOf() } += "${file.name}: $name"
                    }
                }
            }
            dir.name to edges
        }

    /** The strongly connected components of [graph] (Tarjan). */
    private fun components(graph: Map<String, Set<String>>): List<Set<String>> {
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val result = mutableListOf<Set<String>>()

        fun visit(node: String) {
            index[node] = index.size
            low[node] = index.getValue(node)
            stack.addLast(node)
            for (next in graph[node].orEmpty()) {
                if (next !in index) {
                    visit(next)
                    low[node] = minOf(low.getValue(node), low.getValue(next))
                } else if (next in stack) {
                    low[node] = minOf(low.getValue(node), index.getValue(next))
                }
            }
            if (low[node] == index[node]) {
                val component = mutableSetOf<String>()
                do {
                    val member = stack.removeLast()
                    component += member
                } while (member != node)
                result += component
            }
        }

        graph.keys.sorted().forEach { if (it !in index) visit(it) }
        return result
    }

    private companion object {
        val import = Regex("""import app\.oreshkov\.delva\.internal\.(\w+)\.([\w.]+)(?: as \w+)?""")
    }
}
