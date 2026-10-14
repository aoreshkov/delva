package app.oreshkov.delva.internal.split

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.path.SingularPath
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.oracle.JsonPath
import app.oreshkov.delva.testkit.oracle.ONodeRef
import app.oreshkov.delva.testkit.oracle.OracleStore
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypePathsTest {
    @Test
    fun `conflicting paths and invalid type names fail before reading`() {
        assertFailsWith<IllegalArgumentException> { TypePaths.compile(mapOf("$.a" to "A", "$[\"a\"]" to "B")) }
        assertFailsWith<IllegalArgumentException> { TypePaths.compile(mapOf("$['a'][0]" to "A", "$ .a [0]" to "B")) }
        TypePaths.compile(mapOf("$.a" to "A", "$[\"a\"]" to "A", "$['a']" to "A"))
        assertFailsWith<IllegalArgumentException> { TypePaths.compile(mapOf("$.@type" to "A")) }
        assertFailsWith<InvalidTypeNameException> { TypePaths.compile(mapOf("$" to "")) }
        assertFailsWith<InvalidTypeNameException> { TypePaths.compile(mapOf("$" to "x".repeat(257))) }
        assertFailsWith<InvalidTypeNameException> { TypePaths.compile(mapOf("$" to "a\u0007")) }
    }

    @Test
    fun `the splitter assigns types exactly like the oracle`() {
        val options = SplitOptions(64, 256, sorterBytes = 4096)
        val gen = Gen { random, size ->
            val value = JsonGen(JsonProfile.entries[random.nextInt(JsonProfile.entries.size)], fragmentSize = 64).value().generate(random, size)
            value to randomPaths(value, random)
        }
        forAll("types at insert", gen, iterations = 400) { (value, paths) ->
            val oracle = OracleStore()
            val expected = try {
                val docId = oracle.insert(value, paths)
                Result.success(oracle.nodes(docId).map { it.first }.mapNotNull { pre -> oracle.typeOf(ONodeRef(docId, pre))?.let { pre to it } }.toMap())
            } catch (e: DocumentRejectedException) {
                Result.failure(e)
            }
            val actual = try {
                Result.success(split(JsonText.minifiedBytes(value), options, paths).types.assignments.toMap())
            } catch (e: DocumentRejectedException) {
                Result.failure(e)
            }
            if (expected.isSuccess) {
                assertEquals(expected.getOrThrow(), actual.getOrThrow())
            } else {
                assertEquals(RejectReason.TYPE_PATH_UNMATCHED, (actual.exceptionOrNull() as? DocumentRejectedException)?.reason, "expected rejection, got $actual")
            }
        }
    }

    /** Paths to random existing nodes (objects mostly), plus now and then a path to nothing. */
    private fun randomPaths(root: OValue, random: Random): Map<String, String> {
        val paths = LinkedHashMap<String, String>()
        repeat(random.nextInt(0, 4)) {
            var node = root
            val text = StringBuilder("$")
            while (random.nextInt(3) != 0) {
                when (node) {
                    is OObj -> {
                        if (node.members.isEmpty()) break
                        val (key, child) = node.members[random.nextInt(node.members.size)]
                        text.append(JsonPath.spellMember(key, random))
                        node = child
                    }
                    is OArr -> {
                        if (node.items.isEmpty()) break
                        val index = random.nextInt(node.items.size)
                        text.append(JsonPath.spellIndex(index.toLong(), random))
                        node = node.items[index]
                    }
                    else -> break
                }
            }
            if (random.nextInt(8) == 0) text.append("[\"missing\"]")
            val path = text.toString()
            if (path !in paths) paths[path] = TYPES[random.nextInt(TYPES.size)]
        }
        // Paths that name the same node must agree on the type (else the insert fails before reading).
        val canonical = paths.entries.groupBy { SingularPath.parse(it.key) }
        return canonical.values.flatMap { same -> same.map { it.key to same.first().value } }.toMap()
    }

    private companion object {
        val TYPES = listOf("Order", "Item", "type.googleapis.com/x")
    }
}
