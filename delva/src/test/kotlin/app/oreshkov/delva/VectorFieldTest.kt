package app.oreshkov.delva

import app.oreshkov.delva.internal.canon.Nfc
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorFieldTest {
    private val root: Path = Files.createTempDirectory("delva-vector-field")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val field = VectorField("$.embedding", 768, VectorMetric.COSINE)

    @Test
    fun `a vector field takes a singular path into the document and 1 to 4096 dimensions`() {
        for (path in listOf("$.embedding", "$['the vector']", "$.chunks[0].v", "$[3]")) {
            assertEquals(path, VectorField(path, 1, VectorMetric.DOT_PRODUCT).path)
        }
        assertEquals(VectorField.MAX_DIMENSIONS, VectorField("$.v", 4096, VectorMetric.EUCLIDEAN).dimensions)
        assertEquals("the vector can't be the whole document", assertFailsWith<InvalidJsonPathException> { VectorField("$", 3, VectorMetric.COSINE) }.reason)
        for (path in listOf("", "embedding", "$..v", "$.v[*]", "$.v[-1]", "$[?@.a]")) {
            assertFailsWith<InvalidJsonPathException>(path) { VectorField(path, 3, VectorMetric.COSINE) }
        }
        for (dimensions in listOf(0, -1, 4097, Int.MAX_VALUE)) {
            val e = assertFailsWith<IllegalArgumentException> { VectorField("$.v", dimensions, VectorMetric.COSINE) }
            assertEquals("dimensions $dimensions outside 1..4096", e.message)
        }
    }

    @Test
    fun `a vector field is a value`() {
        assertEquals(field, VectorField("$.embedding", 768, VectorMetric.COSINE, required = false))
        assertEquals(field.hashCode(), VectorField("$.embedding", 768, VectorMetric.COSINE).hashCode())
        for (other in listOf(
            VectorField("$.vector", 768, VectorMetric.COSINE),
            VectorField("$.embedding", 384, VectorMetric.COSINE),
            VectorField("$.embedding", 768, VectorMetric.DOT_PRODUCT),
            VectorField("$.embedding", 768, VectorMetric.COSINE, required = true),
        )) {
            assertNotEquals(field, other)
        }
        assertEquals("VectorField(path=$.embedding, dimensions=768, metric=COSINE)", field.toString())
        assertEquals(
            "VectorField(path=$.v, dimensions=3, metric=EUCLIDEAN, required=true)",
            VectorField("$.v", 3, VectorMetric.EUCLIDEAN, required = true).toString(),
        )
    }

    @Test
    fun `creation options keep their vector field through copy and withDocumentKey`() {
        val options = CreationOptions(fragmentBytes = 4096, excludedKeys = setOf("embedding")).withVectorField(field)
        assertEquals(field, options.vectorField)
        assertNull(CreationOptions().vectorField)
        assertEquals(field, options.copy(hashTerms = false).vectorField)
        assertEquals(field, options.withDocumentKey("$.id").vectorField)
        assertEquals("$.id", options.withDocumentKey("$.id").withVectorField(field).documentKey)
        assertNull(options.withVectorField(null).vectorField)
        assertEquals(CreationOptions(fragmentBytes = 4096, excludedKeys = setOf("embedding")), options.withVectorField(null))

        assertEquals(options, CreationOptions(fragmentBytes = 4096, excludedKeys = setOf("embedding")).withVectorField(VectorField("$.embedding", 768, VectorMetric.COSINE)))
        assertEquals(options.hashCode(), options.copy().hashCode())
        assertNotEquals(options, options.withVectorField(VectorField("$.embedding", 768, VectorMetric.EUCLIDEAN)))
        assertNotEquals(options.withVectorField(null), options)
        assertContains(options.toString(), ", vectorField=VectorField(path=$.embedding, dimensions=768, metric=COSINE))")
        assertTrue("vectorField" !in CreationOptions().toString())
        // copy still validates the rest.
        assertFailsWith<IllegalArgumentException> { options.copy(indexedKeys = setOf("embedding")) }
    }

    @Test
    fun `the vector field and the document key can't hold one another`() {
        val keyed = CreationOptions().withDocumentKey("$.id")
        for (path in listOf("$.id", "$['id']", "$.id.v", "$.id[0]")) {
            val e = assertFailsWith<IllegalArgumentException>(path) { keyed.withVectorField(VectorField(path, 3, VectorMetric.COSINE)) }
            assertContains(e.message!!, "overlap")
        }
        val nested = CreationOptions().withVectorField(VectorField("$.meta.v", 3, VectorMetric.COSINE))
        for (key in listOf("$.meta", "$.meta.v", "$.meta.v[1]")) {
            assertFailsWith<IllegalArgumentException>(key) { nested.withDocumentKey(key) }
        }
        // Siblings, and names that only share a prefix of their characters, are apart.
        for (path in listOf("$.v", "$.ids", "$.i", "$[0]", "$.meta.id")) {
            assertEquals(path, keyed.withVectorField(VectorField(path, 3, VectorMetric.COSINE)).vectorField?.path)
        }
        assertEquals("$.meta.w", nested.withDocumentKey("$.meta.w").documentKey)
    }

    @Test
    fun `under NFC the vector field's names are compared as the keys they match`() {
        // Built from code points, so that no editor or tool normalizes them into one another.
        val composed = Char(0xE9).toString()
        val decomposed = "e" + Char(0x301)
        assertNotEquals(composed, decomposed)
        val nfc = CreationOptions(normalization = Normalization.NFC).withDocumentKey("$['$composed']")
        assertFailsWith<IllegalArgumentException> { nfc.withVectorField(VectorField("$['$decomposed'].v", 3, VectorMetric.COSINE)) }
        // Without normalization they are different keys.
        val none = CreationOptions().withDocumentKey("$['$composed']")
        assertEquals(3, none.withVectorField(VectorField("$['$decomposed'].v", 3, VectorMetric.COSINE)).vectorField?.dimensions)

        val tooLong = "x" + Char(0x316).toString().repeat(Nfc.MAX_COMBINING_RUN + 1)
        val field = VectorField("$['$tooLong']", 3, VectorMetric.COSINE)
        assertEquals(field, CreationOptions().withVectorField(field).vectorField)
        val e = assertFailsWith<InvalidJsonPathException> { CreationOptions(normalization = Normalization.NFC).withVectorField(field) }
        assertContains(e.reason, "combining marks")
        assertFailsWith<InvalidJsonPathException> { CreationOptions().withVectorField(field).copy(normalization = Normalization.NFC) }
    }

    @Test
    fun `a store keeps the vector field it was created with, and refuses to open with another`() {
        val dir = root.resolve("store")
        val creation = CreationOptions(excludedKeys = setOf("embedding")).withVectorField(field)
        JsonStore.create(dir, creation).use { assertEquals(creation, it.creationOptions) }
        JsonStore.open(dir).use { assertEquals(field, it.creationOptions.vectorField) }
        JsonStore.openOrCreate(dir, creation).close()
        JsonStore.openSecondary(dir).use { assertEquals(creation, it.creationOptions) }

        for (other in listOf(
            creation.withVectorField(null),
            creation.withVectorField(VectorField("$.embedding", 768, VectorMetric.COSINE, required = true)),
            creation.withVectorField(VectorField("$.embedding", 767, VectorMetric.COSINE)),
        )) {
            val e = assertFailsWith<CreationOptionsMismatchException>(other.toString()) { JsonStore.openOrCreate(dir, other) }
            assertEquals(creation, e.stored)
            assertContains(e.message!!, "was created with vectorField $field, not ${other.vectorField ?: "none"}")
        }

        val plain = root.resolve("plain")
        JsonStore.create(plain).close()
        assertFailsWith<CreationOptionsMismatchException> { JsonStore.openOrCreate(plain, CreationOptions().withVectorField(field)) }
        JsonStore.open(plain).use { assertNull(it.creationOptions.vectorField) }
    }
}
