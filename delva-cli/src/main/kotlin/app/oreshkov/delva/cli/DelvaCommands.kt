package app.oreshkov.delva.cli

import app.oreshkov.clikit.Argument
import app.oreshkov.clikit.CommandSpec
import app.oreshkov.clikit.Constraint
import app.oreshkov.clikit.Converters
import app.oreshkov.clikit.EnvironmentVariable
import app.oreshkov.clikit.Example
import app.oreshkov.clikit.ExperimentalCliKitApi
import app.oreshkov.clikit.InputSource
import app.oreshkov.clikit.Option
import app.oreshkov.clikit.StandardOptions
import app.oreshkov.clikit.inputSource
import app.oreshkov.clikit.path
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchOrder
import java.nio.file.Path

/**
 * The `delva` command tree: the one declaration that parsing, help and completions all read. Handlers read their
 * values with the handles declared here.
 */
internal object DelvaCommands {

    // ------------------------------------------------------------------------------------------------- globals

    /** Every command but `completion` works on one store; it's given once, before or after the command's name. */
    val store: Option.Single<Path> = Option.Single(
        "--store",
        Converters.path,
        "-s",
        help = "The store directory",
        valueName = "dir",
        env = "DELVA_STORE",
    )

    // ----------------------------------------------------------------------------------------- shared options

    /** Queries exit 0 when they find nothing, as `sqlite3` and `kubectl get` do; this makes nothing exit 1, as `jq -e` does. */
    val exitStatus: Option.Flag = Option.Flag("--exit-status", "-e", help = "Exit 1 when nothing was found")

    val pretty: Option.Flag = Option.Flag("--pretty", help = "Indent the JSON (the default at a terminal)")

    val compact: Option.Flag = Option.Flag("--compact", help = "Write the JSON on one line (the default down a pipe)")

    val lineWidth: Option.Single<Int> = Option.Single(
        "--line-width",
        Converters.intRange(0..JsonFormat.MAX_LINE_WIDTH),
        help = "Columns an indented container may take on one line; 0 breaks every one; implies --pretty",
        valueName = "n",
        defaultDisplay = "the terminal's width",
    )

    val maxLevels: Option.Single<Int> = Option.Single(
        "--max-levels",
        Converters.intRange(0..Int.MAX_VALUE),
        help = "Write containers nested deeper than n as {...} and [...]; implies --pretty",
        valueName = "n",
    )

    private val formatOptions = listOf(pretty, compact, lineWidth, maxLevels)

    /**
     * `--json` writes one compact record per line, so it takes none of the layout flags that would say otherwise; and
     * `--line-width` lays out indented JSON, so it contradicts `--compact`.
     */
    val formatConstraints: List<Constraint> = listOf(
        Constraint.Exclusive(pretty, compact),
        Constraint.Exclusive(compact, lineWidth),
        Constraint.Exclusive(StandardOptions.json, pretty),
        Constraint.Exclusive(StandardOptions.json, maxLevels),
        Constraint.Exclusive(StandardOptions.json, lineWidth),
    )

    val mode: Option.Single<ResultMode> = Option.Single(
        "--mode",
        ValueArgs.mode,
        help = "Hits are whole documents, or the objects that contain the match",
        valueName = "mode",
        defaultDisplay = "document",
    )

    val nested: Option.Single<NestedHits> = Option.Single(
        "--nested",
        ValueArgs.nested,
        help = "Keep hits inside other hits, or only the outermost",
        valueName = "which",
        defaultDisplay = "all",
    )

    val type: Option.Single<String> =
        Option.Single("--type", Converters.string, "-t", help = "Only objects of this type", valueName = "name")

    val limit: Option.Single<Int> = Option.Single(
        "--limit",
        Converters.positiveInt,
        "-n",
        help = "At most n results; a notice says how to get the rest",
        valueName = "n",
        defaultDisplay = "all",
    )

    val order: Option.Single<SearchOrder> = Option.Single(
        "--order",
        ValueArgs.order,
        help = "Oldest documents first, or newest",
        valueName = "order",
        defaultDisplay = "asc",
    )

    val after: Option.Single<Cursor> = Option.Single(
        "--after",
        ValueArgs.cursor,
        help = "Resume after this cursor, from a hit's JSON or a 'more:' notice",
        valueName = "cursor",
    )

    val maxBytes: Option.Single<Long> = Option.Single(
        "--max-bytes",
        Converters.byteSize,
        help = "End a page before its hits pass this size, such as 16MiB",
        valueName = "size",
    )

    val count: Option.Flag = Option.Flag("--count", "-c", help = "Print how many hits there are, not the hits; with --limit, count at most n")

    private val searchOptions = listOf(mode, nested, type, limit, order, after, maxBytes, count, exitStatus)

    private val searchConstraints: List<Constraint> = listOf(Constraint.Exclusive(count, maxBytes))

    /** `--nested` filters hits inside other hits, which only `--mode element` has; an absent `--mode` is `document`. */
    @OptIn(ExperimentalCliKitApi::class)
    private val nestedConstraint: Constraint = Constraint.RequiresValue(nested, mode, "element")

    /** For the searches that take `--mode` and `--nested`: `search key` has neither. */
    private val hitConstraints: List<Constraint> = searchConstraints + nestedConstraint

    // ------------------------------------------------------------------------------------------------ commands

    val info: CommandSpec = CommandSpec("info", "Show what a store holds and how it was created")

    val getPath: Option.Single<JsonPath> = Option.Single(
        "--path",
        ValueArgs.jsonPath,
        "-p",
        help = "Only the nodes this JSONPath query selects, one per line",
        valueName = "query",
    )

    val getDoc: Argument.Single<DocId> = Argument.Single("doc", ValueArgs.docId, "The document id; or give --key", required = false)

    val getKey: Option.Single<String> = Option.Single(
        "--key",
        Converters.string,
        "-k",
        help = "The document with this key, in a store created with --document-key",
        valueName = "key",
    )

    val get: CommandSpec = CommandSpec(
        "get",
        "Print a stored document, or nodes from it\n\nWith --path, only the nodes a JSONPath query selects from it.",
        options = listOf(getKey, getPath, exitStatus) + formatOptions,
        arguments = listOf(getDoc),
        examples = listOf(
            Example("delva -s store get 42", "Document 42, indented at a terminal and compact down a pipe"),
            Example("delva -s store get 42 --path '\$.items[*].sku'", "The SKU of every item, one per line"),
            Example("delva -s store get --key order-1042", "The document whose key is order-1042"),
        ),
        constraints = formatConstraints,
    )

    val string: Option.Flag = Option.Flag("--string", "-S", help = "Take the value as a string, not as JSON")

    val kvKey: Argument.Single<String> = Argument.Single("key", Converters.string, "The member's key")

    val kvValue: Argument.Single<String> = Argument.Single(
        "value",
        Converters.string,
        "The value it equals, as JSON ('\"ada\"', 42, true, {\"a\":1}), or any text with --string",
    )

    val searchKv: CommandSpec = CommandSpec(
        "kv",
        "Find the objects with a member equal to a value",
        options = listOf(string) + searchOptions,
        arguments = listOf(kvKey, kvValue),
        examples = listOf(
            Example("delva -s store search kv name ada --string", "Documents with a member \"name\": \"ada\""),
            Example("delva -s store search kv sku B2 -S --mode element", "The objects themselves, not their documents"),
            Example("delva -s store search kv session s-42 -S --order desc -n 1", "The newest document for a session"),
            Example("delva -s store search kv status lost -S --count", "How many documents have \"status\": \"lost\""),
        ),
        constraints = hitConstraints,
    )

    val scan: Option.Flag = Option.Flag("--scan", help = "Read every document when no index term narrows the query")

    val query: Argument.Single<JsonPath> =
        Argument.Single("query", ValueArgs.jsonPath, "An RFC 9535 JSONPath query")

    @OptIn(ExperimentalDelvaApi::class)
    val where: Option.Single<DocumentFilter> = Option.Single(
        "--where",
        ValueArgs.documentFilter,
        help = "Only the documents whose root passes this filter, such as \"@.kind == 'order'\"",
        valueName = "filter",
    )

    @OptIn(ExperimentalDelvaApi::class)
    val searchPath: CommandSpec = CommandSpec(
        "path",
        "Find the nodes a JSONPath query selects, in every document",
        options = listOf(scan, where) + searchOptions,
        arguments = listOf(query),
        examples = listOf(
            Example("delva -s store search path '\$..[?@.qty < 1].sku'", "The SKU of every item out of stock"),
            Example("delva -s store search path '\$..[?@.qty < 1]' --mode element -c -n 10", "Whether at least ten items are out of stock"),
            Example("delva -s store search path '\$..sku' --where \"@.kind == 'order'\"", "Every SKU in the documents of kind order"),
        ),
        constraints = hitConstraints,
    )

    val keyPrefix: Argument.Single<String> =
        Argument.Single("prefix", Converters.string, "Only keys that start with this", required = false)

    val keyOrder: Option.Single<SearchOrder> = Option.Single(
        "--order",
        ValueArgs.order,
        help = "Smallest key first, or largest",
        valueName = "order",
        defaultDisplay = "asc",
    )

    val searchKey: CommandSpec = CommandSpec(
        "key",
        "List documents in the order of their keys, in a store created with --document-key",
        options = listOf(limit, keyOrder, after, maxBytes, count, exitStatus),
        arguments = listOf(keyPrefix),
        examples = listOf(
            Example("delva -s store search key order-", "Every document whose key starts with order-, by key"),
            Example("delva -s store search key --order desc -n 1", "The document with the largest key"),
        ),
        constraints = searchConstraints,
    )

    val search: CommandSpec = CommandSpec(
        "search",
        "Search every document",
        subcommands = listOf(searchKv, searchPath, searchKey),
    )

    val explainQuery: Argument.Single<JsonPath> =
        Argument.Single("query", ValueArgs.jsonPath, "An RFC 9535 JSONPath query")

    @OptIn(ExperimentalDelvaApi::class)
    val explain: CommandSpec = CommandSpec(
        "explain",
        "Show how a JSONPath search would run\n\nShows how the search would find its documents, without running it.",
        options = listOf(where),
        arguments = listOf(explainQuery),
    )

    val node: Argument.Single<NodeRef> =
        Argument.Single("node", ValueArgs.nodeRef, "The node, as doc:node")

    val nodePath: CommandSpec = CommandSpec(
        "path",
        "Print where a node is in its document, as a Normalized Path",
        arguments = listOf(node),
    )

    val typeName: Argument.Single<String> = Argument.Single("type", Converters.string, "The type's name")

    val typesAfter: Option.Single<NodeRef> = Option.Single(
        "--after",
        ValueArgs.nodeRef,
        help = "Resume after this object, as doc:node",
        valueName = "node",
    )

    val typesList: CommandSpec = CommandSpec("list", "List the types and about how many objects each has")

    val typesCount: CommandSpec = CommandSpec(
        "count",
        "Count the objects of a type exactly",
        arguments = listOf(typeName),
    )

    val typesObjects: CommandSpec = CommandSpec(
        "objects",
        "List the objects of a type, as doc:node",
        options = listOf(typesAfter, limit, exitStatus),
        arguments = listOf(typeName),
    )

    val assignType: Argument.Single<String> = Argument.Single("type", Converters.string, "The type's name")

    val typesAssign: CommandSpec = CommandSpec(
        "assign",
        "Give an object a type, replacing the one it had",
        arguments = listOf(node, assignType),
    )

    val typesClear: CommandSpec = CommandSpec(
        "clear",
        "Take an object's type away",
        arguments = listOf(node),
    )

    val types: CommandSpec = CommandSpec(
        "types",
        "Inspect the types assigned to objects",
        subcommands = listOf(typesList, typesCount, typesObjects, typesAssign, typesClear),
    )

    // ----------------------------------------------------------------------------------------- writing, checking

    val initDir: Argument.Single<Path> = Argument.Single("dir", Converters.path, "Where to create the store")

    val normalization: Option.Single<Normalization> = Option.Single(
        "--normalization",
        Converters.enum(Normalization.entries),
        help = "Normalize every string and key to NFC as it is read",
        valueName = "form",
        defaultDisplay = "none",
    )

    val fragmentSize: Option.Single<Long> = Option.Single(
        "--fragment-size",
        Converters.byteSize,
        help = "Target size of the fragments documents are split into",
        valueName = "size",
        defaultDisplay = "64K",
    )

    val noHashTerms: Option.Flag = Option.Flag(
        "--no-hash-terms",
        help = "Don't index whole objects and arrays; searches for object or array values then fail",
    )

    val indexKey: Option.Repeated<String> = Option.Repeated(
        "--index-key",
        Converters.string,
        help = "Index only members with this key; repeat for more",
        valueName = "key",
    )

    val excludeKey: Option.Repeated<String> = Option.Repeated(
        "--exclude-key",
        Converters.string,
        help = "Never index members with this key; repeat for more",
        valueName = "key",
    )

    val minIndexedString: Option.Single<Int> = Option.Single(
        "--min-indexed-string",
        Converters.intRange(0..Int.MAX_VALUE),
        help = "Index only string values of at least n bytes",
        valueName = "n",
    )

    val maxIndexedString: Option.Single<Int> = Option.Single(
        "--max-indexed-string",
        Converters.intRange(0..Int.MAX_VALUE),
        help = "Index only string values of at most n bytes",
        valueName = "n",
    )

    val documentKey: Option.Single<String> = Option.Single(
        "--document-key",
        Converters.string,
        help = "The JSONPath of the string that names each document; no two documents then share one",
        valueName = "path",
    )

    val init: CommandSpec = CommandSpec(
        "init",
        "Create a store; what it is created with is fixed for its life",
        options = listOf(normalization, fragmentSize, noHashTerms, indexKey, excludeKey, minIndexedString, maxIndexedString, documentKey),
        arguments = listOf(initDir),
        examples = listOf(
            Example("delva init ./data/store", "A store that indexes everything"),
            Example("delva init ./data/store --normalization nfc --exclude-key notes", "Unicode-normalized, without the notes"),
            Example("delva init ./data/store --document-key '\$.id'", "Each document named by its id member"),
        ),
    )

    val insertTypes: Option.Repeated<Pair<String, String>> = Option.Repeated(
        "--type",
        ValueArgs.typeAssignment,
        "-t",
        help = "Give the objects a path names a type, as <jsonpath>=<Type>; repeat for more",
        valueName = "path=Type",
    )

    val insertReplaces: Option.Repeated<DocId> = Option.Repeated(
        "--replace",
        ValueArgs.docId,
        help = "Delete this document in the same change, if it still exists (one file only); repeat for more",
        valueName = "doc",
    )

    val insertUpsert: Option.Flag = Option.Flag(
        "--upsert",
        "-u",
        help = "Replace the document that has the same key, in the same change (a store created with --document-key)",
    )

    val insertFiles: Argument.Variadic<InputSource> =
        Argument.Variadic("file", Converters.inputSource, "JSON documents, or - for one from standard input")

    val insert: CommandSpec = CommandSpec(
        "insert",
        "Insert JSON documents, each file one document, and print their ids",
        options = listOf(insertTypes, insertReplaces, insertUpsert),
        arguments = listOf(insertFiles),
        examples = listOf(
            Example("delva insert orders/*.json", "One document per file; the ids are printed in order"),
            Example("curl -s \$URL | delva insert - -t '\$=Order' -t '\$.customer=Customer'", "From a pipe, with types"),
            Example("delva insert order-v2.json --replace 12", "A new version of document 12, which goes in the same change"),
            Example("delva insert orders/*.json --upsert", "New versions of the documents with these keys, or new documents"),
        ),
    )

    /** No short form: `-n` is `--limit` here, as in `head -n`, and a completion script reads a name one way everywhere. */
    val dryRun: Option.Flag = Option.Flag("--dry-run", help = "Show which documents would be deleted, and delete nothing")

    val deleteTargets: Argument.Variadic<ValueArgs.DeleteTarget> =
        Argument.Variadic("doc", ValueArgs.deleteTarget, "Document ids, or - to read them from standard input")

    val delete: CommandSpec = CommandSpec(
        "delete",
        "Delete documents\n\nAsks first at a terminal, and needs --force without one.",
        options = listOf(dryRun, StandardOptions.force, StandardOptions.noInput),
        arguments = listOf(deleteTargets),
        examples = listOf(
            Example("delva delete 12 13 --dry-run", "Which of them exist, deleting nothing"),
            Example("delva search kv status lost -S --json | jq .doc | delva delete - --force", "Delete every match"),
        ),
    )

    val reset: CommandSpec = CommandSpec(
        "reset",
        "Empty a store, keeping its creation options\n\n" +
            "Deletes every document and type, leaving the store as init created it. Asks first at a terminal, and " +
            "needs --force without one.",
        options = listOf(StandardOptions.force, StandardOptions.noInput),
        examples = listOf(
            Example("delva -s ./data/store reset", "Asks, then empties the store; its creation options stay"),
            Example("delva -s ./data/store reset --force", "In a script: no terminal to ask at"),
        ),
    )

    val verify: CommandSpec = CommandSpec(
        "verify",
        "Check a store for damage\n\nReads the whole store and reports any damage, changing nothing; exits 1 when there is some.",
    )

    val left: Argument.Single<ValueArgs.Operand> =
        Argument.Single("a", ValueArgs.operand, "A document (42), a node (42:7), a JSON file, or - for standard input")

    val right: Argument.Single<ValueArgs.Operand> = Argument.Single("b", ValueArgs.operand, "The same, for the other side")

    val diff: Option.Flag = Option.Flag("--diff", "-d", help = "List the differences, not just whether there are any")

    val ignoreKey: Option.Repeated<String> =
        Option.Repeated("--ignore-key", Converters.string, help = "Leave out members with this key, at any depth", valueName = "key")

    val ignorePath: Option.Repeated<String> = Option.Repeated(
        "--ignore-path",
        Converters.string,
        help = "Leave out the value at this path, such as \$.updatedAt",
        valueName = "path",
    )

    val compareTypes: Option.Flag = Option.Flag("--types", help = "Objects differ when their assigned types do")

    val maxDifferences: Option.Single<Int> = Option.Single(
        "--max-differences",
        Converters.positiveInt,
        help = "Stop after n differences",
        valueName = "n",
        defaultDisplay = "1000",
    )

    val compare: CommandSpec = CommandSpec(
        "compare",
        "Compare two JSON values, stored or not\n\nExits 0 when they are equal and 1 when not.",
        options = listOf(diff, ignoreKey, ignorePath, compareTypes, maxDifferences),
        arguments = listOf(left, right),
        examples = listOf(
            Example("delva compare 41 42 --diff", "What changed between two versions of a document"),
            Example("delva compare 42 expected.json --ignore-path '\$.updatedAt'", "A stored document against a file"),
        ),
        constraints = listOf(Constraint.Requires(maxDifferences, diff)),
    )

    val root: CommandSpec = CommandSpec(
        "delva",
        "Inspect, search and check Delva JSON stores.",
        options = listOf(
            store,
            StandardOptions.json,
            StandardOptions.verbose,
            StandardOptions.quiet,
            StandardOptions.noColor,
            StandardOptions.width,
        ),
        subcommands = listOf(
            info, get, search, explain, nodePath, types, init, insert, delete, reset, verify, compare, StandardOptions.completion,
        ),
        examples = listOf(
            Example("export DELVA_STORE=./data/store", "Name the store once for every command after"),
            Example("delva search kv name ada --string --json | jq .doc", "Pipe the hits on as JSON Lines"),
        ),
        epilogue = "Documentation and issues: https://github.com/aoreshkov/delva",
        // The start script reads it, not the program, and `java -jar` doesn't. JAVA_OPTS, which the script reads too,
        // shares its row: a variable declared here is listed on every command's page.
        environment = listOf(EnvironmentVariable("DELVA_OPTS", "JVM options for the start script, such as -Xmx4g (or JAVA_OPTS)")),
    )
}
