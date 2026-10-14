package app.oreshkov.delva.cli

import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.ReadableJsonStore
import app.oreshkov.delva.SecondaryJsonStore
import app.oreshkov.delva.StoreOptions
import java.nio.file.Path

/** The store directory `--store` (or `DELVA_STORE`) names; a usage error when neither does. */
internal fun CommandContext.storeDir(): Path = values[DelvaCommands.store]
    ?: throw CommandFailure(ExitStatus.USAGE, "no store given: pass ${DelvaCommands.store.long} <dir>")

/**
 * Runs [block] on the store `--store` names, as its writer, and closes it afterwards, whatever [block] does.
 *
 * The store is opened, never created: a command line that names a store names one that exists, so a mistyped path
 * is `no store at …` and exit 66, with nothing written where it pointed. Creating a store is a command of its own.
 */
internal inline fun <T> CommandContext.withStore(options: StoreOptions = StoreOptions(), block: (JsonStore, Path) -> T): T {
    val dir = storeDir()
    return JsonStore.open(dir, options).use { block(it, dir) }
}

/**
 * Runs [block] on a secondary of the store `--store` names, and closes it afterwards, whatever [block] does.
 *
 * A secondary takes no lock and writes nothing, so a command that only reads works while another process, such as a
 * long-running service, has the store open, and leaves the store exactly as it found it. It reads what the writer had
 * written when the command started. A mistyped path is `no store at …` and exit 66, as with [withStore].
 */
@OptIn(ExperimentalDelvaApi::class)
internal inline fun <T> CommandContext.withReader(options: StoreOptions = StoreOptions(), block: (ReadableJsonStore, Path) -> T): T =
    withSecondary(options, block)

/** [withReader], for a command that also asks the secondary what only a secondary knows, such as its writer. */
@OptIn(ExperimentalDelvaApi::class)
internal inline fun <T> CommandContext.withSecondary(options: StoreOptions = StoreOptions(), block: (SecondaryJsonStore, Path) -> T): T {
    val dir = storeDir()
    return JsonStore.openSecondary(dir, options).use { block(it, dir) }
}
