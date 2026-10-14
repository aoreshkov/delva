package app.oreshkov.delva.cli

import app.oreshkov.clikit.Program
import app.oreshkov.clikit.implementationVersion
import app.oreshkov.delva.cli.commands.compare
import app.oreshkov.delva.cli.commands.delete
import app.oreshkov.delva.cli.commands.explain
import app.oreshkov.delva.cli.commands.get
import app.oreshkov.delva.cli.commands.info
import app.oreshkov.delva.cli.commands.init
import app.oreshkov.delva.cli.commands.insert
import app.oreshkov.delva.cli.commands.nodePath
import app.oreshkov.delva.cli.commands.reset
import app.oreshkov.delva.cli.commands.searchKey
import app.oreshkov.delva.cli.commands.searchKv
import app.oreshkov.delva.cli.commands.searchPath
import app.oreshkov.delva.cli.commands.typesAssign
import app.oreshkov.delva.cli.commands.typesClear
import app.oreshkov.delva.cli.commands.typesCount
import app.oreshkov.delva.cli.commands.typesList
import app.oreshkov.delva.cli.commands.typesObjects
import app.oreshkov.delva.cli.commands.verify

/** `delva`: the command tree, a handler for each command, and delva's failures translated to exit statuses. */
internal object DelvaProgram {

    /** The program as a process runs it, reading `DELVA_STORE` and the like from the real environment. */
    val program: Program = create(System::getenv)

    /** The program with [environment] as its environment, so a test controls what `DELVA_STORE` says. */
    fun create(environment: (String) -> String?): Program = Program(
        DelvaCommands.root,
        "delva ${implementationVersion(DelvaProgram::class)}",
        mapOf(
            DelvaCommands.info to info,
            DelvaCommands.get to get,
            DelvaCommands.searchKv to searchKv,
            DelvaCommands.searchPath to searchPath,
            DelvaCommands.searchKey to searchKey,
            DelvaCommands.explain to explain,
            DelvaCommands.nodePath to nodePath,
            DelvaCommands.typesList to typesList,
            DelvaCommands.typesCount to typesCount,
            DelvaCommands.typesObjects to typesObjects,
            DelvaCommands.typesAssign to typesAssign,
            DelvaCommands.typesClear to typesClear,
            DelvaCommands.init to init,
            DelvaCommands.insert to insert,
            DelvaCommands.delete to delete,
            DelvaCommands.reset to reset,
            DelvaCommands.verify to verify,
            DelvaCommands.compare to compare,
        ),
        Failures::translate,
        environment,
    )
}
