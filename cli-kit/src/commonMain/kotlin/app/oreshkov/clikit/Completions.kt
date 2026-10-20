package app.oreshkov.clikit

/** A shell that [CompletionGenerator] writes a completion script for. */
public enum class Shell(
    /** The name `completion` takes for it, such as `zsh`. */
    public val id: String,
) {
    /** GNU Bash. */
    Bash("bash"),

    /** The Z shell. */
    Zsh("zsh"),

    /** The fish shell. */
    Fish("fish"),

    /** PowerShell. */
    PowerShell("powershell"),
    ;

    public companion object {
        /** The shell whose [Shell.id] is [id], or `null` when there is none. */
        public fun of(id: String): Shell? = entries.firstOrNull { it.id == id }

        /** The [Shell.id] of every shell, in declaration order. */
        public val ids: List<String> = entries.map { it.id }
    }
}

/**
 * `demo completion <shell>` — a script for bash, zsh, fish or PowerShell on stdout, the standard
 * `eval "$(demo completion zsh)"` shape.
 *
 * **Static values only**: subcommand names, option names, and the fixed enumerations. No dynamic
 * completion of values held in a program's own data, such as account names or keys, and that is a
 * decision rather than a limitation — dynamic completion would mean opening that data from a shell
 * callback, which is slow and, for data behind a lock, a collision at the worst possible moment,
 * every time the user presses Tab. A generated script therefore never runs the program.
 *
 * Every script works the same way. It walks the words before the cursor, skipping the value that
 * follows an option taking one — `demo --data-dir store session` is the command `session`, not
 * `store session` — and offers what the command it arrives at accepts: subcommands, options, and
 * the choices of its arguments. Right after an option that takes a value it offers that value
 * instead: the option's choices, or the shell's own file-name completion when it has none. After
 * [StandardOptions.help] it offers commands, as the parser reads its words: `demo help store` offers
 * what `demo store` does.
 *
 * An option name is read the same way wherever it appears in the tree: if it takes a value under
 * one command, the word after it is skipped under every command. A tree that used one name as a
 * flag here and a valued option there would be confusing to type as well.
 *
 * Each generator is golden-file tested against a checked-in script.
 *
 * @param programName the command the scripts complete; the root command's name when null.
 */
public class CompletionGenerator(private val programName: String? = null) {
    /** The completion script for [shell], for the command tree under [root]. */
    public fun generate(shell: Shell, root: CommandSpec): String {
        val tree = Tree(root)
        val program = programName ?: root.name
        return when (shell) {
            Shell.Bash -> bash(tree, program)
            Shell.Zsh -> zsh(tree, program)
            Shell.Fish -> fish(tree, program)
            Shell.PowerShell -> powerShell(tree, program)
        }
    }

    /** What every generator needs from the tree, worked out once. */
    private class Tree(root: CommandSpec) {
        /** Every command, root first, as the path that reaches it. */
        val paths: List<List<CommandSpec>> = buildList {
            fun walk(path: List<CommandSpec>) {
                add(path)
                path.last().subcommands.forEach { walk(path + it) }
            }
            walk(listOf(root))
        }

        private val valuedOptions: List<Option> =
            paths.flatMap { it.last().options }.filter { it.takesValue && !it.hidden }.distinctBy { it.long }

        /** The spellings of every option that takes a value: the word after one is its value. */
        val valued: List<String> = valuedOptions.flatMap { listOfNotNull(it.long, it.short) }.distinct()

        /** Options with a fixed set of values, grouped by those values: spellings to choices. */
        val choices: List<Pair<List<String>, List<String>>> = valuedOptions.filter { it.choices.isNotEmpty() }
            .groupBy { it.choices }
            .map { (choices, options) -> options.flatMap { listOfNotNull(it.long, it.short) } to choices }

        /** Valued options with no fixed set: after one of these, the shell completes file names. */
        val open: List<String> = valuedOptions.filter { it.choices.isEmpty() }.flatMap { listOfNotNull(it.long, it.short) }

        /**
         * Every place the walk over the typed words can arrive at, in the order of [paths]. After
         * [StandardOptions.help], the words name a command below the group that lists it, so its place is followed by
         * one for each group down there: `help store` offers what `store` does.
         */
        val places: List<Place> = paths.flatMap { path ->
            val holder = path.dropLast(1).lastOrNull()
            if (path.last() === StandardOptions.help && holder != null) {
                holder.paths().filter { it.last().subcommands.isNotEmpty() }
                    .map { group -> Place(path + group.drop(1), path, group.last().subcommands) }
                    .toList()
            } else {
                listOf(Place(path, path, path.last().subcommands))
            }
        }

        fun options(path: List<CommandSpec>): List<Option> = optionsFor(path).filterNot { it.hidden }

        /** The fixed values of the command's arguments, which complete where an argument is due. */
        fun argumentChoices(path: List<CommandSpec>): List<String> = path.last().arguments.flatMap { it.choices }.distinct()

        /** Everything a word after [place] can be. */
        fun candidates(place: Place): List<String> =
            place.commands.map { it.name } + argumentChoices(place.command) +
                options(place.command).flatMap { listOfNotNull(it.long, it.short) } + "--help"
    }

    /**
     * Where the walk over the typed words arrives: the commands and words named, root first, [typed]; the path of the
     * [command] they run, whose options and arguments apply; and the [commands] a word can name next.
     */
    private class Place(private val typed: List<CommandSpec>, val command: List<CommandSpec>, val commands: List<CommandSpec>) {
        /** `session login`: the words typed, root excluded. */
        val words: String get() = typed.drop(1).joinToString(" ") { it.name }
    }

    private fun bash(tree: Tree, program: String): String = buildString {
        appendLine("# bash completion for $program — generated by `$program completion bash`")
        appendLine("_${program}_complete() {")
        appendLine("  local cur=\"\${COMP_WORDS[COMP_CWORD]}\" prev=\"\${COMP_WORDS[COMP_CWORD-1]}\"")
        appendLine("  COMPREPLY=()")
        // A value is due: the option's choices, or nothing, and `-o default` completes file names.
        if (tree.choices.isNotEmpty() || tree.open.isNotEmpty()) {
            appendLine("  case \"\$prev\" in")
            for ((spellings, choices) in tree.choices) {
                val words = choices.joinToString(" ") { it.escapeForDoubleQuotes() }
                appendLine("    ${spellings.joinToString("|")}) COMPREPLY=(\$(compgen -W \"$words\" -- \"\$cur\")); return ;;")
            }
            if (tree.open.isNotEmpty()) appendLine("    ${tree.open.joinToString("|")}) return ;;")
            appendLine("  esac")
        }
        appendLine("  local cmdpath=\"\" skip=0 i")
        appendLine("  for ((i = 1; i < COMP_CWORD; i++)); do")
        // bash splits `--data-dir=store` at the `=` (COMP_WORDBREAKS), so a skip steps over the `=` too.
        appendLine("    if ((skip)); then [[ \"\${COMP_WORDS[i]}\" == \"=\" ]] || skip=0; continue; fi")
        appendLine("    case \"\${COMP_WORDS[i]}\" in")
        if (tree.valued.isNotEmpty()) appendLine("      ${tree.valued.joinToString("|")}) skip=1 ;;")
        appendLine("      -*) ;;")
        appendLine("      *) cmdpath=\"\${cmdpath:+\$cmdpath }\${COMP_WORDS[i]}\" ;;")
        appendLine("    esac")
        appendLine("  done")
        appendLine("  local candidates")
        appendLine("  case \"\$cmdpath\" in")
        for (place in tree.places) {
            val words = tree.candidates(place).joinToString(" ") { it.escapeForDoubleQuotes() }
            appendLine("    \"${place.words}\") candidates=\"$words\" ;;")
        }
        appendLine("    *) candidates=\"--help\" ;;")
        appendLine("  esac")
        appendLine("  COMPREPLY=(\$(compgen -W \"\$candidates\" -- \"\$cur\"))")
        appendLine("}")
        appendLine("complete -o default -F _${program}_complete $program")
    }

    private fun zsh(tree: Tree, program: String): String = buildString {
        appendLine("#compdef $program")
        appendLine("# zsh completion for $program — generated by `$program completion zsh`")
        appendLine("_${program}() {")
        appendLine("  local -a candidates")
        // Not `path`: in zsh that is the array tied to PATH.
        appendLine("  local cmdpath=\"\" word")
        appendLine("  local -i skip=0")
        appendLine("  for word in \"\${(@)words[2,CURRENT-1]}\"; do")
        appendLine("    if (( skip )); then skip=0; continue; fi")
        appendLine("    case \"\$word\" in")
        if (tree.valued.isNotEmpty()) appendLine("      ${tree.valued.joinToString("|")}) skip=1 ;;")
        appendLine("      -*) ;;")
        appendLine("      *) cmdpath=\"\${cmdpath:+\$cmdpath }\$word\" ;;")
        appendLine("    esac")
        appendLine("  done")
        if (tree.choices.isNotEmpty() || tree.open.isNotEmpty()) {
            appendLine("  case \"\${words[CURRENT-1]}\" in")
            for ((spellings, choices) in tree.choices) {
                val entries = choices.joinToString(" ") { "'${it.escapeForZsh()}'" }
                appendLine("    ${spellings.joinToString("|")}) candidates=($entries); _describe 'value' candidates; return ;;")
            }
            if (tree.open.isNotEmpty()) appendLine("    ${tree.open.joinToString("|")}) _files; return ;;")
            appendLine("  esac")
        }
        appendLine("  case \"\$cmdpath\" in")
        for (place in tree.places) {
            val entries = (
                place.commands.map { "${it.name.escapeForZsh()}:${HelpText.summary(it.help).escapeForZsh()}" } +
                    tree.argumentChoices(place.command).map { it.escapeForZsh() } +
                    tree.options(place.command).map { "${it.long}:${HelpText.summary(it.help).escapeForZsh()}" }
                ).joinToString(" ") { "'$it'" }
            appendLine("    \"${place.words}\") candidates=($entries) ;;")
        }
        appendLine("  esac")
        appendLine("  _describe 'command' candidates")
        appendLine("}")
        appendLine("compdef _${program} $program")
    }

    private fun fish(tree: Tree, program: String): String = buildString {
        val at = "__${program}_at"
        appendLine("# fish completion for $program — generated by `$program completion fish`")
        // Whether the words so far name exactly the command given, skipping option values. fish's own
        // __fish_seen_subcommand_from can't tell `store list` from `session list`, nor a value from a command.
        appendLine("function $at")
        appendLine("    set -l words (commandline -opc)")
        appendLine("    set -e words[1]")
        appendLine("    set -l cmdpath")
        appendLine("    set -l skip 0")
        appendLine("    for word in \$words")
        appendLine("        if test \$skip = 1")
        appendLine("            set skip 0")
        appendLine("            continue")
        appendLine("        end")
        appendLine("        switch \$word")
        if (tree.valued.isNotEmpty()) {
            appendLine("            case ${tree.valued.joinToString(" ")}")
            appendLine("                set skip 1")
        }
        appendLine("            case '-*'")
        appendLine("            case '*'")
        appendLine("                set -a cmdpath \$word")
        appendLine("        end")
        appendLine("    end")
        appendLine("    test \"\$cmdpath\" = \"\$argv\"")
        appendLine("end")
        appendLine("complete -c $program -f")
        for (place in tree.places) {
            val condition = "'$at${place.words.let { if (it.isEmpty()) "" else " $it" }}'"
            place.commands.forEach {
                appendLine("complete -c $program -n $condition -a '${it.name}' -d '${HelpText.summary(it.help).escapeForShell()}'")
            }
            val argumentChoices = tree.argumentChoices(place.command)
            if (argumentChoices.isNotEmpty()) {
                val words = argumentChoices.joinToString(" ") { it.escapeForShell() }
                appendLine("complete -c $program -n $condition -a '$words'")
            }
            tree.options(place.command).forEach { option ->
                val short = option.short?.removePrefix("-")?.let { " -s $it" } ?: ""
                // -x: a value from the listed choices only; -r -F: a value, completed as a file name.
                val value = when {
                    !option.takesValue -> ""
                    option.choices.isNotEmpty() -> " -x -a '${option.choices.joinToString(" ") { it.escapeForShell() }}'"
                    else -> " -r -F"
                }
                appendLine(
                    "complete -c $program -n $condition -l ${option.long.removePrefix("--")}" +
                        "$short$value -d '${HelpText.summary(option.help).escapeForShell()}'",
                )
            }
        }
    }

    private fun powerShell(tree: Tree, program: String): String = buildString {
        fun list(values: List<String>) = values.joinToString(", ", "@(", ")") { "'${it.escapeForPowerShell()}'" }

        appendLine("# PowerShell completion for $program — generated by `$program completion powershell`")
        appendLine("Register-ArgumentCompleter -Native -CommandName $program -ScriptBlock {")
        appendLine("  param(\$wordToComplete, \$commandAst, \$cursorPosition)")
        appendLine("  \$valued = ${list(tree.valued)}")
        // The words before the one under the cursor, which is still being typed.
        appendLine("  \$before = @(\$commandAst.CommandElements | Select-Object -Skip 1 |")
        appendLine("    Where-Object { \$_.Extent.EndOffset -lt \$cursorPosition } | ForEach-Object { \$_.ToString() })")
        appendLine("  \$words = @()")
        appendLine("  \$skip = \$false")
        appendLine("  foreach (\$word in \$before) {")
        appendLine("    if (\$skip) { \$skip = \$false; continue }")
        appendLine("    if (\$valued -ccontains \$word) { \$skip = \$true } elseif (\$word -notlike '-*') { \$words += \$word }")
        appendLine("  }")
        appendLine("  \$previous = if (\$before.Count -gt 0) { \$before[-1] } else { '' }")
        // After an option with no fixed values, return nothing: PowerShell then completes file names.
        appendLine("  if ((\$valued -ccontains \$previous) -and -not (${list(tree.choices.flatMap { it.first })} -ccontains \$previous)) { return }")
        appendLine("  \$candidates = switch -CaseSensitive -Exact (\$previous) {")
        for ((spellings, choices) in tree.choices) {
            spellings.forEach { appendLine("    '$it' { ${list(choices)} }") }
        }
        appendLine("    default {")
        appendLine("      switch -CaseSensitive -Exact (\$words -join ' ') {")
        for (place in tree.places) {
            appendLine("        '${place.words.escapeForPowerShell()}' { ${list(tree.candidates(place))} }")
        }
        appendLine("        default { @('--help') }")
        appendLine("      }")
        appendLine("    }")
        appendLine("  }")
        appendLine("  \$candidates | Where-Object { \$_ -like \"\$wordToComplete*\" } | ForEach-Object {")
        appendLine("    [System.Management.Automation.CompletionResult]::new(\$_, \$_, 'ParameterValue', \$_)")
        appendLine("  }")
        appendLine("}")
    }

    private fun String.escapeForShell(): String = replace("'", "'\\''")

    private fun String.escapeForZsh(): String = replace("'", "'\\''").replace(":", "\\:")

    private fun String.escapeForDoubleQuotes(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")

    private fun String.escapeForPowerShell(): String = replace("'", "''")
}
