package app.oreshkov.delva.internal.compress

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.FragmentRecords
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.testkit.generate.BigDocProfile
import app.oreshkov.delva.testkit.generate.GeneratedJson
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fragment payload codecs, dictionary building, dictionary files and training. */
class CompressionTest {
    private val codecs = FragmentCodecs()

    @AfterTest
    fun close() = codecs.close()

    /** Generated catalog documents of about [bytes] each: similar keys and values, as payload stand-ins. */
    private fun documents(count: Int, bytes: Long = 1500, seed: Long = 1): List<ByteArray> =
        List(count) { GeneratedJson(seed + it, BigDocProfile.CATALOG, bytes).readAllBytes() }

    private fun record(payload: ByteArray, codecs: FragmentCodecs): ByteArray {
        val out = GrowableBytes(64)
        FragmentRecords.write(out, 1, 2, FragmentKind.SUBTREE, 0, FragmentContext(), payload, 0, payload.size, codecs)
        return out.toByteArray()
    }

    private fun decode(record: ByteArray, maxRawLength: Int = Int.MAX_VALUE): FragmentView {
        val view = FragmentView()
        val bodyLength = LittleEndian.getInt(record, 4)
        FragmentRecords.decode(record, LogFormat.RECORD_HEADER_BYTES, bodyLength, ByteReader(record), view, "test", maxRawLength)
        return view
    }

    private fun roundTrip(payload: ByteArray, codecs: FragmentCodecs): FragmentView {
        val view = decode(record(payload, codecs))
        assertEquals(payload.size, view.rawLength)
        view.inflate(codecs, GrowableBytes(0), "test")
        assertContentEquals(payload, view.bytes.copyOfRange(view.payloadOffset, view.payloadOffset + view.payloadLength))
        return view
    }

    @Test
    fun `payloads round trip as NONE, DEFLATE and DEFLATE_DICT`() {
        val random = Random(3)
        val compressible = documents(1, 20_000).single()
        val deflated = decode(record(compressible, codecs))
        assertEquals(FragmentCodecs.DEFLATE, deflated.codecId)
        assertTrue(deflated.payloadLength < compressible.size / 2)
        roundTrip(compressible, codecs)
        // Random bytes don't shrink by 10 %; tiny payloads aren't tried.
        assertEquals(FragmentCodecs.NONE, decode(record(random.nextBytes(5000), codecs)).codecId)
        assertEquals(FragmentCodecs.NONE, decode(record(ByteArray(FragmentCodecs.MIN_COMPRESSED_BYTES - 1), codecs)).codecId)
        roundTrip(random.nextBytes(5000), codecs)
        roundTrip(ByteArray(0), codecs)
        roundTrip(ByteArray(100_000), codecs)

        val dictionary = CompressionDictionary(7, documents(4, 3000, seed = 100).fold(ByteArray(0)) { a, b -> a + b }.takeLast(32 * 1024).toByteArray())
        codecs.register(dictionary, use = true)
        val small = documents(1, 800, seed = 5).single()
        val withDictionary = decode(record(small, codecs))
        assertEquals(FragmentCodecs.DEFLATE_DICT, withDictionary.codecId)
        assertEquals(7, withDictionary.dictId)
        roundTrip(small, codecs)
        FragmentCodecs(CompressionOptions.DISABLED).use { stored -> assertEquals(FragmentCodecs.NONE, decode(record(small, stored)).codecId) }
        // Without the dictionary nothing reads it: unknown, then damaged.
        FragmentCodecs().use { other ->
            val e = assertFailsWith<CorruptionException> { decode(record(small, codecs)).inflate(other, GrowableBytes(0), "test") }
            assertTrue("unknown compression dictionary 7" in e.message!!, e.message)
            other.markDamaged(7)
            assertTrue("dictionary 7 is damaged" in assertFailsWith<CorruptionException> { decode(record(small, codecs)).inflate(other, GrowableBytes(0), "test") }.message!!)
        }
        assertTrue(codecs.compressedFragments.get() >= 4 && codecs.storedFragments.get() >= 3)
    }

    @Test
    fun `damaged compressed payloads and codec headers are corruption`() {
        val payload = documents(1, 8000).single()
        val good = record(payload, codecs)
        val view = decode(good)
        val payloadStart = view.payloadOffset
        val codecAt = payloadStart - FragmentCodecs.CODEC_HEADER_BYTES
        val random = Random(9)
        repeat(2000) {
            val bytes = good.copyOf()
            when (it % 3) {
                0 -> bytes[payloadStart + random.nextInt(view.payloadLength)] = random.nextInt(256).toByte()
                1 -> LittleEndian.putInt(bytes, codecAt + 3, payload.size + random.nextInt(-3, 4))
                else -> bytes[payloadStart + random.nextInt(view.payloadLength)] = (bytes[payloadStart + random.nextInt(view.payloadLength)].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
            // Raw deflate has no checksum: a changed literal inflates to other bytes (the record CRC32C catches that on
            // disk). Anything else must be a CorruptionException, never another exception or a hang.
            try {
                decode(bytes).inflate(codecs, GrowableBytes(0), "test")
            } catch (_: CorruptionException) {
            }
        }
        // Truncated stream, unknown codec, NONE with a dictionary or a wrong length, DEFLATE with a dictionary id, too long.
        val truncated = good.copyOf()
        LittleEndian.putInt(truncated, 4, LittleEndian.getInt(good, 4) - 5)
        assertFailsWith<CorruptionException> { decode(truncated).inflate(codecs, GrowableBytes(0), "test") }
        for ((codec, dictId) in listOf(3 to 0, 0 to 1, 1 to 1, 2 to 0)) {
            val bytes = good.copyOf()
            bytes[codecAt] = codec.toByte()
            bytes[codecAt + 1] = dictId.toByte()
            assertFailsWith<CorruptionException>("codec $codec dict $dictId") { decode(bytes) }
        }
        assertFailsWith<CorruptionException> { decode(good, maxRawLength = payload.size - 1) }
        val stored = FragmentCodecs(CompressionOptions.DISABLED).use { record(payload, it) }
        LittleEndian.putInt(stored, decode(stored).payloadOffset - 4, payload.size + 1)
        assertFailsWith<CorruptionException> { decode(stored) }
    }

    @Test
    fun `a built dictionary holds shared content and compresses unseen documents better`() {
        val builder = DictionaryBuilder(64 * 1024)
        assertEquals(0, builder.build(32 * 1024).size)
        val training = documents(80)
        for (d in training) builder.add(d, 0, d.size)
        assertTrue(builder.sampleBytes <= 64 * 1024)
        val dictionary = builder.build(8 * 1024)
        assertTrue(dictionary.size in 1024..8 * 1024, "${dictionary.size}")
        assertContentEquals(dictionary, builder.build(8 * 1024), "deterministic")
        // Common keys of the catalog profile made it in.
        assertTrue(String(dictionary, Charsets.ISO_8859_1).contains("\"sku\""), String(dictionary, Charsets.ISO_8859_1).take(300))
        val unseen = documents(40, seed = 10_000)
        val scratch = GrowableBytes(0)
        val plain = unseen.sumOf { codecs.deflatedSize(it, 0, it.size, null, scratch) }
        val primed = unseen.sumOf { codecs.deflatedSize(it, 0, it.size, dictionary, scratch) }
        assertTrue(primed < plain * 0.85, "with dictionary $primed, without $plain")
        // Samples that share nothing give nothing; a held-out sample is ignored.
        val random = Random(1)
        val noise = DictionaryBuilder(64 * 1024)
        repeat(20) { noise.add(random.nextBytes(2000), 0, 2000) }
        assertEquals(0, noise.build(8 * 1024).size)
        val held = DictionaryBuilder(64 * 1024)
        held.add(training[0], 0, training[0].size)
        held.add(training[1], 0, training[1].size)
        assertEquals(0, held.build(8 * 1024, holdoutEvery = 2).size)
    }

    @Test
    fun `dictionary files load valid ones and tell damage from a cut-off write`() {
        val io = SimulatedFileSystemIo()
        val dir = Path.of("store", DictionaryFiles.DIR)
        io.createDirectories(dir)
        val bytes = "{\"name\":\"x\"}".repeat(50).toByteArray()
        for (id in 1..4) DictionaryFiles.write(io, dir, CompressionDictionary(id, bytes.copyOf(bytes.size - id)))
        val decoded = DictionaryFiles.decode(DictionaryFiles.encode(CompressionDictionary(3, bytes)), 3, "test")
        assertContentEquals(bytes, decoded.bytes)
        assertFailsWith<CorruptionException> { DictionaryFiles.decode(DictionaryFiles.encode(CompressionDictionary(3, bytes)), 4, "test") }
        assertFailsWith<CorruptionException> { DictionaryFiles.decode(DictionaryFiles.encode(CompressionDictionary(3, bytes)).also { it[20]++ }, 3, "test") }
        // 2 is damaged and listed; 4 is damaged and unlisted (a cut-off write); 3 is valid and unlisted; a tmp file is left over.
        for (id in listOf(2, 4)) io.open(dir.resolve(DictionaryFiles.name(id)), OpenMode.READ_WRITE).use { it.write(20, byteArrayOf(0), 0, 1) }
        io.open(dir.resolve("deflate-00005.dict.tmp"), OpenMode.CREATE_NEW).use { }
        FragmentCodecs().use { loaded ->
            assertEquals(listOf(1, 3), DictionaryFiles.load(io, dir, listOf(1, 2), loaded, dir.resolveSibling("tmp")))
            assertSame(loaded.dictionary(1), loaded.current)
            assertNull(loaded.dictionary(2))
            assertEquals(3, loaded.dictionary(3)!!.id)
            assertEquals(3, loaded.maxId)
            assertTrue(io.exists(dir.resolve(DictionaryFiles.name(2))))
            assertTrue(!io.exists(dir.resolve(DictionaryFiles.name(4))) && !io.exists(dir.resolve("deflate-00005.dict.tmp")))
        }
    }

    @Test
    fun `the trainer builds from a spread sample and adopts only a better dictionary`() {
        val options = CompressionOptions(sampleBytes = 32 * 1024, sampleChunkBytes = 2048, firstTrainingBytes = 200_000, retrainingBytes = 400_000, maxDictionaryBytes = 8 * 1024)
        FragmentCodecs(options).use { codecs ->
            val installed = ArrayList<ByteArray>()
            val trainer = DictionaryTrainer(codecs) { bytes ->
                installed += bytes
                codecs.register(CompressionDictionary(installed.size, bytes), use = true)
            }
            val docs = documents(600)
            var offered = 0L
            for (d in docs) {
                trainer.offer(d, 0, d.size)
                offered += d.size
                if (offered < options.firstTrainingBytes) assertEquals(0, trainer.builds)
            }
            assertTrue(trainer.builds >= 2, "builds ${trainer.builds}")
            // The first dictionary beats none; later ones from the same kind of data don't beat it by 5 %.
            assertEquals(1, installed.size)
            assertEquals(1, trainer.adopted)
            assertTrue(installed[0].size in 1024..8 * 1024)
        }
        FragmentCodecs(CompressionOptions(maxDictionaries = 0, firstTrainingBytes = 1000)).use { codecs ->
            val trainer = DictionaryTrainer(codecs) { error("no dictionaries") }
            for (d in documents(10)) trainer.offer(d, 0, d.size)
            assertEquals(0, trainer.builds)
        }
    }

    @Test
    fun `the dictionary is used only for payloads it pays off for`() {
        // zlib indexes the whole dictionary per fragment, so large payloads deflate without it.
        FragmentCodecs(CompressionOptions(dictionaryMaxPayloadBytes = 4096)).use { codecs ->
            codecs.register(CompressionDictionary(2, documents(4, 3000, seed = 200).fold(ByteArray(0)) { a, b -> a + b }), use = true)
            val small = documents(1, 1000, seed = 7).single()
            val large = documents(1, 20_000, seed = 8).single()
            assertEquals(FragmentCodecs.DEFLATE_DICT, decode(record(small, codecs)).codecId)
            assertEquals(FragmentCodecs.DEFLATE, decode(record(large, codecs)).codecId)
            roundTrip(large, codecs)
        }
    }
}
