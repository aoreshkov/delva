package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.lsm.VectorIndexView
import app.oreshkov.delva.internal.lsm.VectorSegments
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Vectors captured at insert (SPEC.md §11.2): the conversion of each number to float32, every shape that is rejected,
 * and vectors that stream through split documents and large lanes, then come back from the log when the store reopens.
 */
class VectorCaptureTest {
    private fun options(
        field: VectorField,
        t: Int = 4096,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        normalization: Normalization = Normalization.NONE,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t, normalization = normalization).withVectorField(field), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 23, log = TestEngines.LOG,
        backgroundFlush = false, segmentBlockBytes = 512,
    )

    @Test
    fun `each number becomes the nearest float32 of its decimal value, rounded once`() {
        val cases = listOf(
            "3.4028235e38" to Float.MAX_VALUE,
            "-3.4028235E+38" to -Float.MAX_VALUE,
            // Above MAX_VALUE, but nearer to it than to the next power of two.
            "3.4028235677973366e38" to Float.MAX_VALUE,
            "1.4e-45" to Float.MIN_VALUE,
            "1e-46" to 0f,
            "-1e-46" to -0f,
            "-0" to -0f,
            "-0.0e5" to -0f,
            "0" to 0f,
            // Ties go to the even neighbor: 2^24 + 1 isn't a float32.
            "16777217" to 16777216f,
            "16777219" to 16777220f,
            "123456789012345678901234567890" to 1.2345679e29f,
            // Just below the midpoint of 1 + 2^-23 and 1 + 2^-22. Through a double it would round to the midpoint, then
            // up to the even 1 + 2^-22: the digits must be rounded once.
            "1.000000178813934326171874" to Float.fromBits(0x3F800001),
            "1.000000178813934326171876" to Float.fromBits(0x3F800002),
        )
        for ((text, expected) in cases) assertEquals(expected, nearestFloat(text, sign = text.startsWith('-')), "oracle for $text")
        val random = Random(0x5EC7)
        val nineDigits = List(200) { randomFloat(random) }.map { BigDecimal(it.toDouble()).round(MathContext(9, RoundingMode.HALF_EVEN)).toString() to it }
        val seventeenDigits = List(200) {
            val d = random.nextDouble(-1.0, 1.0) * Math.pow(10.0, random.nextInt(-44, 38).toDouble())
            BigDecimal(d).round(MathContext(17, RoundingMode.HALF_EVEN)).toString().let { it to nearestFloat(it, d < 0) }
        }
        val all = cases + nineDigits + seventeenDigits
        val dimensions = 64
        TestEngines.open(options(VectorField("$.v", dimensions, VectorMetric.EUCLIDEAN))).use { store ->
            for (chunk in all.chunked(dimensions)) {
                val texts = chunk.map { it.first } + List(dimensions - chunk.size) { "0" }
                val docId = store.insert("""{"v":[${texts.joinToString(",")}]}""").raw
                val stored = store.vectorOf(docId)!!
                for ((i, case) in chunk.withIndex()) {
                    assertEquals(case.second.toRawBits(), stored[i].toRawBits(), "${case.first}: ${stored[i]}, expected ${case.second}")
                }
            }
        }
    }

    @Test
    fun `anything at the path but null, an array of the field's numbers or nothing is rejected where it shows`() {
        val field = VectorField("$.v", 3, VectorMetric.COSINE)
        TestEngines.open(options(field)).use { store ->
            val rejected = listOf(
                """{"v":"x"}""" to 5L,
                """{"v":{"a":1}}""" to 5L,
                """{"v":true}""" to 5L,
                """{"v":1}""" to 5L,
                """{"v":[]}""" to 5L,
                """{"v":[1,2]}""" to 5L,
                """{"v":[1,2,3,4]}""" to 12L,
                """{"v":[1,"2",3]}""" to 8L,
                """{"v":[1,[2],3]}""" to 8L,
                """{"v":[1,{},3]}""" to 8L,
                """{"v":[1,null,3]}""" to 8L,
                """{"v":[1,2,3.5e38]}""" to 10L,
                """{"v":[1,2,-3.5e38]}""" to 10L,
                // No direction under COSINE.
                """{"v":[0,-0,0.0e9]}""" to 5L,
                """{"v":[1e-46,0,0]}""" to 5L,
            )
            for ((json, offset) in rejected) {
                val e = assertFailsWith<DocumentRejectedException>(json) { store.insert(json) }
                assertEquals(listOf<Any?>(RejectReason.INVALID_VECTOR, offset, "$.v"), listOf(e.reason, e.byteOffset, e.jsonPath), "$json: ${e.message}")
            }
            assertEquals(0, store.indexes.versions.current().memtables.sumOf { it.vectors.size }, "no vector of a rejected document")
            // A repeated key is that, whatever the vectors.
            assertEquals(RejectReason.DUPLICATE_KEY, assertFailsWith<DocumentRejectedException> { store.insert("""{"v":[1,2,3],"v":[1,2,3]}""") }.reason)

            for (json in listOf("{}", """{"v":null}""", """{"w":[1,2,3]}""", """{"v2":[1,2,3]}""", """[[1,2,3]]""", "17", """{"x":{"v":[1,2,3]}}""")) {
                assertNull(store.vectorOf(store.insert(json).raw), json)
            }
            val docId = store.insert("""{"v":[1,2e-45,-3.5],"w":[1,2]}""").raw
            assertEquals(listOf(1f, 2e-45f, -3.5f), store.vectorOf(docId)?.toList())
        }

        TestEngines.open(options(VectorField("$.v", 2, VectorMetric.EUCLIDEAN, required = true))).use { store ->
            for ((json, offset) in listOf("{}" to -1L, """{"v":null}""" to 5L, """{"w":{"v":[1,2]}}""" to -1L)) {
                val e = assertFailsWith<DocumentRejectedException>(json) { store.insert(json) }
                assertEquals(listOf<Any?>(RejectReason.INVALID_VECTOR, offset, "$.v"), listOf(e.reason, e.byteOffset, e.jsonPath), "$json: ${e.message}")
            }
            // A zero vector has a length, so other metrics take it.
            assertEquals(listOf(0f, -0f), store.vectorOf(store.insert("""{"v":[0,-0]}""").raw)?.toList())
        }
    }

    @Test
    fun `a vector deep in a document is captured whatever fragments and lane it streams through, and comes back from the log`() {
        val dimensions = 64
        val field = VectorField("$.deep.a[1].embedding", dimensions, VectorMetric.DOT_PRODUCT)
        val random = Random(0x7EC)
        fun vector(): FloatArray = FloatArray(dimensions) { random.nextFloat() * 2 - 1 }
        fun doc(vector: FloatArray?, pad: Int) = buildString {
            append("""{"all":true,"pad":"""").append("x".repeat(pad)).append("""","deep":{"a":[{"skip":[1,2]},""")
            if (vector == null) append("{}") else append("""{"before":"""").append("y".repeat(pad)).append("""","embedding":[${vector.joinToString(",")}],"after":true}""")
            append("]},\"tail\":\"").append("z".repeat(pad)).append("\"}")
        }
        for (t in listOf(32, 4096)) {
            for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                val label = "T=$t threshold=$threshold"
                val fs = SimulatedFileSystemIo()
                val options = options(field, t, threshold)
                val expected = HashMap<Long, FloatArray?>()
                TestEngines.open(options, fs).use { store ->
                    for (pad in listOf(0, 10, 5000)) {
                        val v = vector()
                        expected[store.insert(doc(v, pad)).raw] = v
                    }
                    expected[store.insert(doc(null, 100)).raw] = null
                    val replaced = expected.keys.first { expected[it] != null }
                    val replacement = vector()
                    val replacing = store.replace(listOf(DocId(replaced)), doc(replacement, 300)).raw
                    expected[replacing] = replacement
                    expected.remove(replaced)
                    val batch = List(3) { if (it == 1) null else vector() }
                    val outcomes = store.insertAll(batch.map { InsertSource(ByteArrayInputStream(doc(it, 50).toByteArray())) })
                    for ((v, outcome) in batch.zip(outcomes)) expected[assertIs<InsertOutcome.Inserted>(outcome).docId.raw] = v
                    assertVectors(store, expected, label)
                    // The document read back is the one inserted: the vector stays in it as text.
                    assertEquals(doc(replacement, 300), store.documents().getValue(replacing))
                }
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    assertEquals(expected.size.toLong(), store.recoveryStats.replayedCommits - 1, "$label: every COMMIT replayed, the replaced one too")
                    assertVectors(store, expected, "$label after replay")
                    assertEquals(emptyList(), store.verify().issues.map { it.toString() }, label)
                }
            }
        }
    }

    @Test
    fun `under NFC the vector's path matches the normalized keys`() {
        val decomposed = "e${Char(0x301)}"
        TestEngines.open(options(VectorField("$['${Char(0xE9)}']", 2, VectorMetric.EUCLIDEAN), normalization = Normalization.NFC)).use { store ->
            assertEquals(listOf(1f, 2f), store.vectorOf(store.insert("""{"$decomposed":[1,2]}""").raw)?.toList())
        }
    }

    @Test
    fun `each running insert reserves room for its vector in the ingest budget`() {
        val field = VectorField("$.v", VectorField.MAX_DIMENSIONS, VectorMetric.COSINE)
        assertEquals(EngineOptions().ingestBaseBytes + 8L * VectorField.MAX_DIMENSIONS, EngineOptions(CreationOptions().withVectorField(field)).ingestBaseBytes)
    }

    private fun assertVectors(store: Engine, expected: Map<Long, FloatArray?>, label: String) {
        assertEquals(expected.keys, store.documents().keys, label)
        for ((docId, vector) in expected) assertEquals(vector?.toList(), store.vectorOf(docId)?.toList(), "$label: document $docId")
    }
}

/** The vector the current view's memtables and segments hold for [docId], or null. */
internal fun ReadEngine.vectorOf(docId: Long): FloatArray? {
    val field = options.creation.vectorField ?: return null
    return withView { _, version -> VectorIndexView(version, field.dimensions).vectorOf(docId)?.values }
}

/** The docIds of every vector in the current view's memtables and segments, hidden or not, with repeats. */
internal fun ReadEngine.indexedVectorDocIds(): List<Long> = withView { _, version ->
    val docIds = ArrayList<Long>()
    for (m in version.memtables) m.vectors.entries().forEach { docIds += it.docId }
    for (s in version.vectorSegments) {
        val cursor = s.reader.cursor()
        var ok = cursor.first()
        while (ok) {
            docIds += VectorSegments.docIdOf(cursor, s.reader.source)
            ok = cursor.next()
        }
    }
    docIds
}

/** A random finite float32, of any magnitude. */
private fun randomFloat(random: Random): Float {
    while (true) {
        val f = Float.fromBits(random.nextInt())
        if (f.isFinite() && f != 0f) return f
    }
}

/**
 * The float32 nearest the exact value of the decimal [text], ties to the even one, with the [sign] of a zero: an oracle
 * that doesn't use the JDK's parser. A double near the value is within one float32 step of the answer.
 */
private fun nearestFloat(text: String, sign: Boolean): Float {
    val exact = BigDecimal(text)
    if (exact.signum() == 0) return if (sign) -0f else 0f
    val guess = exact.toDouble().toFloat()
    val nearest = listOf(guess.nextDown(), guess, guess.nextUp()).filter { it.isFinite() }
        .minWith(compareBy<Float> { (BigDecimal(it.toDouble()) - exact).abs() }.thenBy { it.toRawBits() and 1 })
    return if (nearest == 0f && sign) -0f else nearest
}
