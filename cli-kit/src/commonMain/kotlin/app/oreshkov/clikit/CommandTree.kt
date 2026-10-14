package app.oreshkov.clikit

/**
 * Walks over a command tree, written once here so that a program, its tests and the runner itself don't each keep
 * a recursive walk of their own.
 *
 * A path is a list of commands from the root down, the form the parser, help and completions already take: the
 * command it names is the last one.
 */

/** Every path from this command down, depth first, this command's own path first: `[root]`, `[root, a]`, `[root, a, b]`… */
public fun CommandSpec.paths(): Sequence<List<CommandSpec>> = sequence {
    suspend fun SequenceScope<List<CommandSpec>>.walk(path: List<CommandSpec>) {
        yield(path)
        for (child in path.last().subcommands) walk(path + child)
    }
    walk(listOf(this@paths))
}

/**
 * The paths that end in a runnable command, [StandardOptions.completion] excluded: exactly the commands [Program]
 * needs a handler for.
 */
public fun CommandSpec.runnablePaths(): Sequence<List<CommandSpec>> =
    paths().filter { it.last().runnable && it.last() !== StandardOptions.completion }

/**
 * The path to a subcommand by name, this command first: `root.pathTo("store", "get")` is `[root, store, get]`, and
 * `pathTo()` is `[root]`. Throws [IllegalArgumentException] naming the first step that doesn't exist.
 */
public fun CommandSpec.pathTo(vararg names: String): List<CommandSpec> {
    val path = mutableListOf(this)
    for (name in names) {
        path += requireNotNull(path.last().subcommand(name)) { "no command '$name' under '${commandLine(path)}'" }
    }
    return path
}

/** `demo store index create`: the names along [path], as a user types them and as messages quote them. */
public fun commandLine(path: List<CommandSpec>): String = path.joinToString(" ") { it.name }
