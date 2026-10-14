package app.oreshkov.delva.internal.io

import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** What a crash in the `pages` mode of [SimulatedFileSystemIo.crash] leaves of a file's unsynced writes. */
class SimulatedWritebackTest {
    private val dir = Path.of("d")
    private val file = dir.resolve("f")
    private val page = 4096
    private val sector = 512

    private fun FileIo.write(position: Int, length: Int, value: Int) = write(position.toLong(), ByteArray(length) { value.toByte() }, 0, length)

    /**
     * Two durable pages of 1s, then, unsynced: page 0 becomes 2s, page 1 becomes 3s, the first sector of page 0 becomes
     * 4s, and page 2 is appended as 5s.
     */
    private fun crashed(seed: Long): ByteArray {
        val fs = SimulatedFileSystemIo()
        fs.createDirectories(dir)
        fs.open(file, OpenMode.CREATE_NEW).use {
            it.write(0, 2 * page, 1)
            it.force(true)
        }
        fs.syncDirectory(dir)
        fs.open(file, OpenMode.READ_WRITE).use {
            it.write(0, page, 2)
            it.write(page, page, 3)
            it.write(0, sector, 4)
            it.write(2 * page, page, 5)
        }
        fs.crash(Random(seed), pages = true)
        return fs.open(file, OpenMode.READ).use { ByteArray(it.size().toInt()).also { bytes -> it.readFully(0, bytes, 0, bytes.size) } }
    }

    @Test
    fun `each sector holds its bytes after some of the writes, and later writes can outlive earlier ones`() {
        var laterOutlivedEarlier = false
        var tornPage = false
        var appendedKept = false
        for (seed in 0L until 300) {
            val bytes = crashed(seed)
            assertTrue(bytes.size == 2 * page || bytes.size == 3 * page, "seed $seed: size ${bytes.size}")
            val sectors = (0 until bytes.size / sector).map { s ->
                val values = (s * sector until (s + 1) * sector).map { bytes[it].toInt() }.toSet()
                assertTrue(values.size == 1, "seed $seed: sector $s holds $values")
                values.single()
            }
            val allowed = { s: Int ->
                when {
                    s == 0 -> setOf(1, 2, 4)
                    s < page / sector -> setOf(1, 2)
                    s < 2 * page / sector -> setOf(1, 3)
                    else -> setOf(0, 5)
                }
            }
            for ((s, value) in sectors.withIndex()) assertTrue(value in allowed(s), "seed $seed: sector $s holds $value")
            if (sectors[page / sector] == 3 && sectors[1] == 1) laterOutlivedEarlier = true
            if ((1 until page / sector).map { sectors[it] }.toSet().size > 1) tornPage = true
            if (bytes.size == 3 * page && sectors.last() == 5) appendedKept = true
        }
        assertTrue(laterOutlivedEarlier, "a later page write survived an earlier lost one")
        assertTrue(tornPage, "a page was torn by sector")
        assertTrue(appendedKept, "an appended page was kept")
    }
}
