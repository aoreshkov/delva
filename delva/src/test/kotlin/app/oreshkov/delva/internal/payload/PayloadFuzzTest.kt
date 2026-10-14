package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Damaged payloads: random byte flips, overwrites, insertions, deletions and truncations of valid payloads
 * either fail with [CorruptionException] or decode as some other valid payload — never another exception and never a
 * hang (a watchdog bounds every case).
 */
class PayloadFuzzTest {
    private val watchdog = Executors.newSingleThreadExecutor { r -> Thread(r, "payload-fuzz").apply { isDaemon = true } }

    @AfterTest
    fun stop() {
        watchdog.shutdownNow()
    }

    private class Case(val bytes: ByteArray, val kind: Int, val pre: Long, val seq: Int) {
        override fun toString(): String = "kind=$kind pre=$pre seq=$seq ${bytes.joinToString("") { "%02x".format(it) }}"
    }

    private val validPayloads: Gen<Case> = Gen { random, size ->
        val value = JsonGen(JsonProfile.entries[random.nextInt(JsonProfile.entries.size)], fragmentSize = 100).value().generate(random, size)
        when (random.nextInt(4)) {
            0 -> {
                // A PART over a random run of the root's members.
                val members = (value as? OObj)?.members?.size ?: (value as? OArr)?.items?.size ?: 0
                if (members == 0) return@Gen Case(subtreePayload(JsonText.minifiedBytes(value)), FragmentKind.SUBTREE, 0, 0)
                val pending = pendingOf(JsonText.minifiedBytes(value))
                val offsets = pendingMemberOffsets(pending.array, 0)
                val from = random.nextInt(members)
                val to = random.nextInt(from + 1, members + 1)
                val out = GrowableBytes()
                FragmentEncoder().encodePart(if (value is OObj) PayloadTag.OBJ else PayloadTag.ARR, pending.array, offsets[from], offsets[to], to - from, out)
                Case(out.toByteArray(), FragmentKind.PART, 1 + random.nextLong(1000), 0)
            }
            1 -> {
                val out = GrowableBytes()
                PayloadWriter.keyTableCount(out, 0)
                val entries = random.nextInt(0, 5)
                if (random.nextBoolean()) {
                    PayloadWriter.headerSummary(out, PayloadTag.ARR, 100, 300, random.nextLong(), random.nextLong(), 5)
                }
                PayloadWriter.dirEntryCount(out, entries)
                var pre = 5L
                var index = 0L
                repeat(entries) {
                    val n = random.nextLong(1, 20)
                    PayloadWriter.dirEntry(out, pre, index, n)
                    pre += n * 3
                    index += n
                }
                Case(out.toByteArray(), FragmentKind.HEADER, 4, if (out.array[1].toInt() == PayloadTag.ARR) 0 else 1)
            }
            else -> Case(subtreePayload(JsonText.minifiedBytes(value)), FragmentKind.SUBTREE, random.nextLong(0, 1L shl 40), 0)
        }
    }

    private val damaged: Gen<Case> = Gen { random, size ->
        val case = validPayloads.generate(random, size)
        var bytes = case.bytes
        repeat(random.nextInt(1, 4)) { bytes = damage(bytes, random) }
        val kind = if (random.nextInt(20) == 0) FragmentKind.entries(random) else case.kind
        Case(bytes, kind, case.pre, case.seq)
    }

    private fun FragmentKind.entries(random: Random): Int =
        intArrayOf(FragmentKind.PART, FragmentKind.SUBTREE, FragmentKind.HEADER, FragmentKind.STRCHUNK)[random.nextInt(4)]

    private fun damage(bytes: ByteArray, random: Random): ByteArray {
        if (bytes.isEmpty()) return byteArrayOf(random.nextInt(256).toByte())
        val at = random.nextInt(bytes.size)
        return when (random.nextInt(5)) {
            0 -> bytes.copyOf().also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
            1 -> bytes.copyOf().also { it[at] = SPECIAL[random.nextInt(SPECIAL.size)] }
            2 -> bytes.copyOf(at) + byteArrayOf(SPECIAL[random.nextInt(SPECIAL.size)]) + bytes.copyOfRange(at, bytes.size)
            3 -> bytes.copyOf(at) + bytes.copyOfRange(at + 1, bytes.size)
            else -> bytes.copyOf(at)
        }
    }

    @Test
    fun `damaged payloads fail with CorruptionException or decode, and never hang`() {
        val validator = PayloadValidator()
        val reader = PayloadReader()
        val sink = JsonOutput(OutputStream.nullOutputStream())
        forAll("damaged payloads", damaged, iterations = 5000, maxSize = 60) { case ->
            val future = watchdog.submit<Unit> {
                try {
                    validator.validate(case.bytes, 0, case.bytes.size, case.kind, case.pre, case.seq)
                    if (case.kind != FragmentKind.HEADER) {
                        reader.open(case.bytes, 0, case.bytes.size, case.kind, case.pre)
                        try {
                            PayloadJson.write(reader, sink)
                        } catch (_: IllegalStateException) {
                            // A stub: fine, it needs other fragments.
                        }
                    }
                } catch (_: CorruptionException) {
                    // Expected for most damage.
                }
            }
            try {
                future.get(10, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                fail("decoding didn't finish within 10 s", e)
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private companion object {
        val SPECIAL = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x20, 0x30, 0x31, 0x40, 0x41, 0x42, 0x7F, 0x80.toByte(), 0xFF.toByte())
    }
}
