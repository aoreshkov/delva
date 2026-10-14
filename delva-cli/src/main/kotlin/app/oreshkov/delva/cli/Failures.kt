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
        QueryRejectedException::class to ExitStatus.DATA_ERR,
        CreationOptionsMismatchException::class to ExitStatus.DATA_ERR,
        ResultTooLargeException::class to ExitStatus.DATA_ERR,
        CorruptionException::class to ExitStatus.IO_ERR,
        EngineFailedException::class to ExitStatus.IO_ERR,
    )

    /**
     * The ones that can only mean a bug in `delva` itself — every command opens its store and closes it on the way
     * out — and are left to the internal-error net.
     */
    val bugs: Set<KClass<out DelvaException>> = setOf(StoreClosedException::class)

    /** The failure [error] stands for, or `null` when it is a bug. */
    fun translate(error: Exception): CommandFailure? = when (error) {
        is StoreNotFoundException -> CommandFailure(ExitStatus.NO_INPUT, "no store at ${error.directory}")
        is StoreLockedException -> CommandFailure(ExitStatus.TEMP_FAIL, "${error.directory} is open in another process" + (error.holder?.let { " ($it)" } ?: ""))
        is StoreExistsException -> CommandFailure(ExitStatus.CONFIG, "there is already a store at ${error.directory}")
        is DelvaException -> statuses[error::class]?.let { CommandFailure(it, error.message ?: error::class.simpleName!!) }
        is NoSuchFileException -> CommandFailure(ExitStatus.NO_INPUT, "no such file: ${error.file}")
        is AccessDeniedException -> CommandFailure(ExitStatus.NO_PERM, "access denied: ${error.file}")
        is IOException -> CommandFailure(ExitStatus.IO_ERR, error.message ?: error::class.simpleName!!)
        // Delva's other refusals of a request: a node that doesn't exist, or isn't an object.
        is IllegalArgumentException -> CommandFailure(ExitStatus.DATA_ERR, error.message ?: "invalid request")
        else -> null
    }
}
