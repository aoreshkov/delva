package app.oreshkov.delva.cli

import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.EngineFailedException
import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.QueryLimitException
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultTooLargeException
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.StoreExistsException
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.UnsupportedFormatException
import app.oreshkov.delva.WriteConflictException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import kotlin.reflect.KClass

/**
 * What each failure delva can report means for `delva`'s exit status: the one place the library's exceptions become
 * the command line's contract. Handlers let them escape, and the program's `translate` hook lands here.
 */
internal object Failures {

    /** The exit status of each of delva's exceptions that a command can meet in normal use. */
    val statuses: Map<KClass<out DelvaException>, Int> = mapOf(
        StoreNotFoundException::class to ExitStatus.NO_INPUT,
        DocumentNotFoundException::class to ExitStatus.NO_INPUT,
        WriteConflictException::class to ExitStatus.NO_INPUT,
        StoreLockedException::class to ExitStatus.TEMP_FAIL,
        StoreExistsException::class to ExitStatus.CONFIG,
        DocumentRejectedException::class to ExitStatus.DATA_ERR,
        InvalidTypeNameException::class to ExitStatus.DATA_ERR,
        QueryLimitException::class to ExitStatus.DATA_ERR,
        CreationOptionsMismatchException::class to ExitStatus.DATA_ERR,
        UnsupportedFormatException::class to ExitStatus.DATA_ERR,
        ResultTooLargeException::class to ExitStatus.DATA_ERR,
        CorruptionException::class to ExitStatus.IO_ERR,
        EngineFailedException::class to ExitStatus.IO_ERR,
    )

    /**
     * The ones that can only mean a bug in `delva` itself — every command opens its store and closes it on the way
     * out — and are left to the internal-error net.
     */
    val bugs: Set<KClass<out DelvaException>> = setOf(StoreClosedException::class)

    /** The ones whose exit status depends on more than their class: a refused search's on its reason ([rejected]). */
    val byReason: Set<KClass<out DelvaException>> = setOf(QueryRejectedException::class)

    /** The failure [error] stands for, or `null` when it is a bug. */
    fun translate(error: Exception): CommandFailure? = when (error) {
        is StoreNotFoundException -> CommandFailure(ExitStatus.NO_INPUT, "no store at ${error.directory}")
        is StoreLockedException -> CommandFailure(ExitStatus.TEMP_FAIL, "${error.directory} is open in another process" + (error.holder?.let { " ($it)" } ?: ""))
        is StoreExistsException -> CommandFailure(ExitStatus.CONFIG, "there is already a store at ${error.directory}")
        is QueryRejectedException -> rejected(error)
        is DelvaException -> statuses[error::class]?.let { CommandFailure(it, error.message ?: error::class.simpleName!!) }
        is NoSuchFileException -> CommandFailure(ExitStatus.NO_INPUT, "no such file: ${error.file}")
        is AccessDeniedException -> CommandFailure(ExitStatus.NO_PERM, "access denied: ${error.file}")
        is IOException -> CommandFailure(ExitStatus.IO_ERR, error.message ?: error::class.simpleName!!)
        // Delva's other refusals of a request: a node that doesn't exist, or isn't an object.
        is IllegalArgumentException -> CommandFailure(ExitStatus.DATA_ERR, error.message ?: "invalid request")
        else -> null
    }

    /**
     * A search the store refused: delva states what it found, and the remedy is worded here from the reason, in this
     * command line's options rather than the library's API. A search that another invocation answers on the same store
     * is a usage error; one that only a store created with other options answers is a data error.
     */
    private fun rejected(error: QueryRejectedException): CommandFailure = when (error.reason) {
        QueryRejectedException.Reason.NEEDS_SCAN -> CommandFailure(
            ExitStatus.USAGE,
            "${error.detail}, so it would read every document; add ${DelvaCommands.scan.long} to allow that, or filter on " +
                "a member equal to a value",
        )
        QueryRejectedException.Reason.RANGE_TOO_WIDE -> CommandFailure(
            ExitStatus.USAGE,
            "${error.detail}; narrow the range, or add ${DelvaCommands.scan.long} to read every document",
        )
        QueryRejectedException.Reason.TOO_MANY_SEGMENTS -> CommandFailure(
            ExitStatus.USAGE,
            "${error.detail}; delva get <doc> ${DelvaCommands.getPath.long} takes a query of any length, on one document",
        )
        QueryRejectedException.Reason.NOT_INDEXED -> CommandFailure(
            ExitStatus.DATA_ERR,
            "${error.detail}; what a store indexes is fixed when it is created, and delva info shows it",
        )
    }
}
