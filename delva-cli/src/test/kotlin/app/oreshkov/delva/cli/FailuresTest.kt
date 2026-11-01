package app.oreshkov.delva.cli

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.UnsupportedFormatException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailuresTest {

    /**
     * Every public exception in delva's ABI dump — not a hand-kept list, which is how a new exception would slip past
     * — has an exit status or is named a bug, never both.
     */
    @Test
    fun `every exception delva publishes is accounted for`() {
        val declared = Path.of("..", "delva", "api", "delva.api").readLines()
            .mapNotNull { EXCEPTION.find(it)?.groupValues?.get(1) }
            .toSet()
        assertTrue(declared.size >= 10, "found only $declared; has the ABI dump's format changed?")
        val mapped = Failures.statuses.keys.map { it.simpleName!! }.toSet()
        val bugs = Failures.bugs.map { it.simpleName!! }.toSet()
        val byReason = Failures.byReason.map { it.simpleName!! }.toSet()
        assertEquals(emptySet(), mapped intersect bugs)
        assertEquals(emptySet(), (mapped + bugs) intersect byReason)
        assertEquals(declared, mapped + bugs + byReason)
    }

    @Test
    fun `IO failures map to the sysexits codes`() {
        assertEquals(ExitStatus.NO_INPUT, Failures.translate(NoSuchFileException("a.json"))?.status)
        assertEquals(ExitStatus.NO_PERM, Failures.translate(AccessDeniedException("a.json"))?.status)
        assertEquals(ExitStatus.IO_ERR, Failures.translate(IOException("disk"))?.status)
        assertEquals(ExitStatus.IO_ERR, Failures.translate(CorruptionException("f", "bad"))?.status)
        assertEquals(ExitStatus.DATA_ERR, Failures.translate(IllegalArgumentException("not indexed"))?.status)
    }

    @Test
    fun `a refused search is worded in the command line's options, with the status its remedy calls for`() {
        fun failure(reason: QueryRejectedException.Reason, detail: String) =
            Failures.translate(QueryRejectedException(reason, detail, "$..a"))!!
        // Another invocation on the same store answers these: usage errors.
        val scan = failure(QueryRejectedException.Reason.NEEDS_SCAN, "no index term narrows the search for $..a")
        assertEquals(ExitStatus.USAGE, scan.status)
        assertEquals(
            "no index term narrows the search for $..a, so it would read every document; add --scan to allow that, " +
                "or filter on a member equal to a value",
            scan.message,
        )
        val range = failure(QueryRejectedException.Reason.RANGE_TOO_WIDE, "the range 'a' > 1 of $..a matches more than 3 documents")
        assertEquals(ExitStatus.USAGE, range.status)
        assertEquals("the range 'a' > 1 of $..a matches more than 3 documents; narrow the range, or add --scan to read every document", range.message)
        val long = failure(QueryRejectedException.Reason.TOO_MANY_SEGMENTS, "$..a has 32 segments; a search takes at most 31")
        assertEquals(ExitStatus.USAGE, long.status)
        assertEquals("$..a has 32 segments; a search takes at most 31; delva get <doc> --path takes a query of any length, on one document", long.message)
        // Only a store created with other options answers this one: a data error.
        val notIndexed = failure(QueryRejectedException.Reason.NOT_INDEXED, "the key \"k\" isn't indexed by this store")
        assertEquals(ExitStatus.DATA_ERR, notIndexed.status)
        assertEquals("the key \"k\" isn't indexed by this store; what a store indexes is fixed when it is created, and delva info shows it", notIndexed.message)
        // Every reason is worded, none with the library's API or its message prefix.
        for (reason in QueryRejectedException.Reason.entries) {
            val message = failure(reason, "fact").message!!
            assertTrue(message.startsWith("fact"), message)
            assertTrue(API_NAMES.find(message) == null, message)
        }
    }

    @Test
    fun `mismatched creation options are a data error, as they were as IllegalArgumentException`() {
        val mismatch = CreationOptionsMismatchException(Path.of("db"), CreationOptions(fragmentBytes = 4096), CreationOptions())
        val failure = Failures.translate(mismatch)
        assertEquals(ExitStatus.DATA_ERR, failure?.status)
        assertEquals(mismatch.message, failure?.message)
    }

    @Test
    fun `a store of another format version is a data error, not a damaged store`() {
        val unsupported = UnsupportedFormatException(Path.of("db"), 2, listOf(1))
        val failure = Failures.translate(unsupported)
        assertEquals(ExitStatus.DATA_ERR, failure?.status)
        assertEquals(unsupported.message, failure?.message)
    }

    @Test
    fun `a store open elsewhere is a temporary failure that names the holder when it is known`() {
        val unknown = Failures.translate(StoreLockedException(Path.of("db")))
        assertEquals(ExitStatus.TEMP_FAIL, unknown?.status)
        assertEquals("db is open in another process", unknown?.message)
        val known = Failures.translate(StoreLockedException(Path.of("db"), LockHolder(4711, null, "build-7")))
        assertEquals("db is open in another process (process 4711 on build-7)", known?.message)
    }

    @Test
    fun `anything else is left to the bug net`() {
        assertNull(Failures.translate(IllegalStateException("broken")))
        assertNull(Failures.translate(app.oreshkov.delva.StoreClosedException()))
    }

    private companion object {
        /** Identifiers of delva's API, which mean nothing to a command-line user. */
        val API_NAMES = Regex("""ScanPolicy|ALLOW_SCAN|CreationOptions|JsonStore|\bselect\b""")

        val EXCEPTION = Regex("""^public (?:final )?class app/oreshkov/delva/(\w+) : app/oreshkov/delva/${DelvaException::class.simpleName}""")
    }
}
