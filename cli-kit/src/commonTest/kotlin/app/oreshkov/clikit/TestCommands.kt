package app.oreshkov.clikit

/**
 * A small command tree for this module's own tests, with one of every shape the parser, help and
 * completions handle: globals, a group, a three-level leaf, a required option, a fixed enumeration,
 * a repeated option with a converter, a flag, a typed option, and a positional with choices.
 *
 * Deliberately not any program's real surface. That one lives with its handlers and is golden-file
 * tested there; what is tested here is the machinery, which knows no command of anyone's.
 */
internal object TestCommands {

    val account = Option.Single("--account", Converters.string, "-a", help = "Account to act as", valueName = "name")

    val site = Option.Single("--site", Converters.string, "-s", help = "Site id", valueName = "id")

    val dataDir = Option.Single(
        "--data-dir",
        Converters.string,
        help = "Data directory",
        valueName = "dir",
        defaultDisplay = "./store",
        env = "DEMO_DATA_DIR",
    )

    val force = Option.Flag("--force", help = "Log in again")

    val limit = Option.Single("--limit", Converters.positiveInt, "-n", help = "Most sessions to list", valueName = "n")

    val param = Option.Repeated(
        "--param",
        Converters.keyValue,
        "-p",
        help = "Query parameter, repeatable",
        valueName = "k=v",
    )

    val method = Option.Single(
        "--method",
        Converters.choice("GET", "POST", "DELETE"),
        help = "HTTP method",
        valueName = "verb",
        required = true,
    )

    val url = Option.Single("--url", Converters.string, help = "Absolute URL", valueName = "url", required = true)

    val endpoint = Argument.Single("endpoint", Converters.string, "Endpoint id")

    val key = Argument.Single("key", Converters.string, "Store key")

    val paths = Argument.Variadic("path", Converters.string, "A path")

    val before = Option.Single("--before", Converters.string, help = "Cutoff", valueName = "ts", required = true)

    val confirm = Option.Flag("--confirm", help = "Actually delete")

    val login = CommandSpec("login", "Log an account in", options = listOf(account, site, force))

    val list = CommandSpec("list", "One row per login", options = listOf(site, limit))

    val get = CommandSpec(
        "get",
        "Issue a read",
        options = listOf(account, param),
        arguments = listOf(endpoint),
        examples = listOf(
            Example("demo request get -a alice game.startup", "Read game.startup as alice"),
            Example("demo request get -a alice -p page=2 -p limit=50 game.list", "The second page of 50"),
        ),
        constraints = listOf(Constraint.Requires(param, account)),
    )

    val raw = CommandSpec("raw", "Issue an arbitrary URL", options = listOf(method, url))

    val storeGet = CommandSpec("get", "Read one entry", arguments = listOf(key))

    val query = CommandSpec("query", "Search the store", arguments = listOf(paths))

    val prune = CommandSpec("prune", "Delete old entries", options = listOf(before, confirm))

    val root: CommandSpec = CommandSpec(
        name = "demo",
        help = "A fixture tree for cli-kit's tests.",
        epilogue = "Documentation: https://example.invalid/demo",
        options = listOf(dataDir, StandardOptions.json, StandardOptions.verbose),
        subcommands = listOf(
            CommandSpec("session", "Sessions", subcommands = listOf(login, list)),
            CommandSpec("request", "Requests", subcommands = listOf(get, raw)),
            CommandSpec("store", "The store", subcommands = listOf(storeGet, query, prune)),
            StandardOptions.completion,
        ),
    )

    /** A handler for every runnable command, answering [status]. */
    fun handlers(status: Int = ExitStatus.OK): Map<CommandSpec, CommandHandler> =
        listOf(login, list, get, raw, storeGet, query, prune).associateWith { CommandHandler { status } }
}
