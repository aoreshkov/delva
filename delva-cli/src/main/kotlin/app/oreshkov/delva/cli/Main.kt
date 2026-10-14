package app.oreshkov.delva.cli

import app.oreshkov.clikit.runAndExit

/** The `delva` command. Everything below returns an exit status; this is the one place the process ends. */
fun main(args: Array<String>) {
    DelvaProgram.program.runAndExit(args)
}
