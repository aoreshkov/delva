package app.oreshkov.delva

import app.oreshkov.delva.internal.engine.EngineOptions
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The validation and safe defaults of the public options, creation-time and runtime. */
class StoreOptionsTest {
    @Test
    fun `the defaults are the documented ones`() {
        val creation = CreationOptions()
        assertEquals(64 * 1024, creation.fragmentBytes)
        assertTrue(creation.hashTerms)
        assertTrue(creation.indexedKeys.isEmpty() && creation.excludedKeys.isEmpty())
        assertEquals(0, creation.minIndexedStringBytes)
        assertEquals(Int.MAX_VALUE, creation.maxIndexedStringBytes)
        assertEquals(Normalization.NONE, creation.normalization)

        val options = StoreOptions()
        assertNull(options.encoderBudgetBytes)
        assertEquals(1024, options.maxDepth)
        assertEquals(Long.MAX_VALUE, options.maxDocumentBytes)
        assertEquals(Long.MAX_VALUE, options.maxStringBytes)
        assertEquals(16, options.ioParallelism)
        assertEquals(Durability.SyncEachBatch, options.durability)
        assertTrue(options.compression)
        assertTrue(!options.mmapReads)
    }

    @Test
    fun `the default encoder budget is 8 MiB, or twice the fragment size if that is more`() {
        assertEquals(8 shl 20, EngineOptions().encoderBudgetBytes)
        assertEquals(32 shl 20, EngineOptions(CreationOptions(fragmentBytes = CreationOptions.MAX_FRAGMENT_BYTES)).encoderBudgetBytes)
        assertEquals(4096, EngineOptions(CreationOptions(fragmentBytes = 1024), StoreOptions(encoderBudgetBytes = 4096)).encoderBudgetBytes)
    }

    /** The budget is checked against the store's fragment size, which only an open store knows. */
    @Test
    fun `an encoder budget below twice the fragment size is refused when the store opens`() {
        val e = assertFailsWith<IllegalArgumentException> {
            EngineOptions(CreationOptions(fragmentBytes = 4096), StoreOptions(encoderBudgetBytes = 8191))
        }
        assertTrue("encoderBudgetBytes" in e.message!!, e.message)
        EngineOptions(CreationOptions(fragmentBytes = 4096), StoreOptions(encoderBudgetBytes = 8192))
    }

    /** The public switch is what the engine's compression follows. */
    @Test
    fun `compression follows the store option`() {
        assertTrue(EngineOptions().compression.enabled)
        assertTrue(!EngineOptions(store = StoreOptions(compression = false)).compression.enabled)
    }

    @Test
    fun `the bounds of the tables are enforced`() {
        assertFailsWith<IllegalArgumentException> { CreationOptions(fragmentBytes = CreationOptions.MIN_FRAGMENT_BYTES - 1) }
        assertFailsWith<IllegalArgumentException> { CreationOptions(fragmentBytes = CreationOptions.MAX_FRAGMENT_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { CreationOptions(minIndexedStringBytes = -1) }
        assertFailsWith<IllegalArgumentException> { CreationOptions(minIndexedStringBytes = 10, maxIndexedStringBytes = 9) }
        assertFailsWith<IllegalArgumentException> { CreationOptions(indexedKeys = setOf("a"), excludedKeys = setOf("a")) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(encoderBudgetBytes = 2 * CreationOptions.MIN_FRAGMENT_BYTES - 1) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(encoderBudgetBytes = Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(maxDepth = 0) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(maxDepth = StoreOptions.MAX_DEPTH_LIMIT + 1) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(maxDocumentBytes = 0) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(maxStringBytes = -1) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(ioParallelism = 0) }
        assertFailsWith<IllegalArgumentException> { StoreOptions(ioParallelism = StoreOptions.MAX_IO_PARALLELISM + 1) }
    }

    @Test
    fun `the edges of the tables are accepted`() {
        EngineOptions(
            CreationOptions(fragmentBytes = CreationOptions.MIN_FRAGMENT_BYTES),
            StoreOptions(encoderBudgetBytes = 2 * CreationOptions.MIN_FRAGMENT_BYTES),
        )
        EngineOptions(
            CreationOptions(fragmentBytes = CreationOptions.MAX_FRAGMENT_BYTES),
            StoreOptions(encoderBudgetBytes = StoreOptions.MAX_ENCODER_BUDGET_BYTES),
        )
        StoreOptions(maxDepth = StoreOptions.MAX_DEPTH_LIMIT)
        StoreOptions(maxStringBytes = 0)
        StoreOptions(ioParallelism = StoreOptions.MAX_IO_PARALLELISM)
    }

    @Test
    fun `a message names the option that is wrong`() {
        val e = assertFailsWith<IllegalArgumentException> { StoreOptions(maxDepth = -5) }
        assertTrue(e.message!!.contains("maxDepth"), e.message)
    }

    @Test
    fun `toString leaves out the options that are at their default`() {
        val text = StoreOptions(maxDepth = 8).toString()
        assertTrue(text.contains("maxDepth=8"), text)
        assertTrue(!text.contains("maxDocumentBytes") && !text.contains("encoderBudgetBytes"), text)
        assertTrue(!CreationOptions().toString().contains("normalization"))
        assertTrue(CreationOptions(normalization = Normalization.NFC).toString().contains("normalization=NFC"))
    }

    /** SPEC.md §1.3 states these in terms of T; they follow from the fragment size, so they're checked here. */
    @Test
    fun `the parse limits are the ones SPEC section 1_3 documents`() {
        val default = EngineOptions().parseLimits
        assertEquals(1024, default.maxDepth)
        assertEquals(16 * 1024, default.maxKeyBytes, "max(T/4, 64) at T = 64 KiB")
        assertEquals(4 * 1024, default.maxNumberLength, "min(4096, max(T/4, 64)) at T = 64 KiB")
        assertEquals(Long.MAX_VALUE, default.maxDocumentBytes)
        assertEquals(Long.MAX_VALUE, default.maxStringBytes)

        // A smaller T lowers both; the floor of 64 bytes holds at the smallest fragment size.
        val small = EngineOptions(CreationOptions(fragmentBytes = 4096), StoreOptions(encoderBudgetBytes = 8192)).parseLimits
        assertEquals(1024, small.maxKeyBytes)
        assertEquals(1024, small.maxNumberLength)
        val tiny = EngineOptions(CreationOptions(fragmentBytes = 32), StoreOptions(encoderBudgetBytes = 4096)).parseLimits
        assertEquals(64, tiny.maxKeyBytes)
        assertEquals(64, tiny.maxNumberLength)
    }

    @Test
    fun `a periodic durability needs a positive interval`() {
        assertFailsWith<IllegalArgumentException> { Durability.Periodic(kotlin.time.Duration.ZERO) }
    }

    /** The factory Java callers use, since `Periodic`'s constructor takes a value class. */
    @Test
    fun `periodic can be built from a java Duration`() {
        assertEquals(kotlin.time.Duration.parse("PT2S"), Durability.periodic(java.time.Duration.ofSeconds(2)).interval)
        assertFailsWith<IllegalArgumentException> { Durability.periodic(java.time.Duration.ZERO) }
    }

    /** Options are values, and `copy` derives a variant without re-spelling the rest. */
    @Test
    fun `copy changes only what it is given and validates the result`() {
        val base = StoreOptions(maxDepth = 64, durability = Durability.None)
        val copy = base.copy(compression = false)
        assertEquals(StoreOptions(maxDepth = 64, durability = Durability.None, compression = false), copy)
        assertEquals(base, base.copy())
        assertNotEquals(base, copy)
        assertFailsWith<IllegalArgumentException> { base.copy(maxDepth = 0) }

        val creation = CreationOptions(fragmentBytes = 4096, indexedKeys = setOf("a"))
        assertEquals(CreationOptions(fragmentBytes = 4096, indexedKeys = setOf("a"), hashTerms = false), creation.copy(hashTerms = false))
        assertEquals(creation, creation.copy())
        assertFailsWith<IllegalArgumentException> { creation.copy(excludedKeys = setOf("a")) }
    }

    @Test
    fun `equal options have equal hash codes`() {
        assertEquals(StoreOptions(), StoreOptions())
        assertEquals(StoreOptions().hashCode(), StoreOptions().hashCode())
        val periodic = StoreOptions(durability = Durability.periodic(java.time.Duration.ofSeconds(1)))
        assertEquals(periodic, StoreOptions(durability = Durability.Periodic(kotlin.time.Duration.parse("1s"))))
        assertEquals(periodic.hashCode(), periodic.copy().hashCode())
        assertNotEquals(StoreOptions(), StoreOptions(encoderBudgetBytes = 1 shl 20))
        assertEquals(CreationOptions().hashCode(), CreationOptions().hashCode())
        assertNotEquals(CreationOptions(), CreationOptions(indexedKeys = setOf("a")))
    }

    @Test
    fun `creation options hold every setting in a final field`() {
        // Final fields are what make an instance shared through a data race show its document key; a field a secondary
        // constructor assigns can't be final.
        val fields = CreationOptions::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(fields.any { it.type == String::class.java }, "the document key is a field")
        assertEquals(emptyList(), fields.filterNot { Modifier.isFinal(it.modifiers) }.map { it.name })
        val keyed = CreationOptions(normalization = Normalization.NFC, documentKey = "$.id")
        assertEquals("$.id", keyed.documentKey)
        assertEquals(keyed, keyed.copy())
        assertNull(keyed.withDocumentKey(null).documentKey)
    }

    @Test
    fun `compare and diff options copy and compare by value`() {
        val compare = CompareOptions(ignoreKeys = setOf("id"))
        assertEquals(CompareOptions(includeTypes = true, ignoreKeys = setOf("id")), compare.copy(includeTypes = true))
        assertEquals(compare.hashCode(), CompareOptions(ignoreKeys = setOf("id")).hashCode())
        val diff = DiffOptions(ignorePaths = setOf("$.a"))
        assertEquals(DiffOptions(ignorePaths = setOf("$.a"), maxDifferences = 5), diff.copy(maxDifferences = 5))
        assertEquals(diff, diff.copy())
        assertNotEquals(diff, diff.copy(verifySkippedSubtrees = true))
        assertFailsWith<IllegalArgumentException> { diff.copy(maxDifferences = 0) }
    }
}
