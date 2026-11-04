# Guide

How to use the Delva library, one task at a time. The [README](../README.md#quick-start) has the quick start,
[SPEC.md](SPEC.md) defines the exact semantics, and the KDoc documents every class and option.

The snippets build on each other: `store` is an open `JsonStore`, `blocking` is `store.blocking()`, and `order` is the
document inserted under [JSONPath](#jsonpath).

## Opening a store

`JsonStore` offers every operation as a suspending function; `blocking()` is a view of the same store without
coroutines. The view has no `close` of its own: closing the `JsonStore` releases both. Both are thread-safe, and one
process at a time opens a store as its `JsonStore`, its writer.

`openOrCreate` suits a program that owns its store. The other two say which one is meant: `JsonStore.create` makes a
new store and fails if the directory already has one, and `JsonStore.open` opens a store that exists, with whatever
`CreationOptions` it was created with, and fails with `StoreNotFoundException` — changing nothing — where there is
none, so a mistyped path is an error rather than a new, empty store. `JsonStore.reset` empties a store that isn't
open, leaving it as `create` left it, with the same `CreationOptions`.

## Reading beside the writer

Other processes can read the store meanwhile with `JsonStore.openSecondary`: a `SecondaryJsonStore` offers every read,
takes no lock and never writes. It reads what the writer had written when it opened, and `refresh()` catches up with
what it wrote since; results already open keep the state they started on. Code that only reads can take a
`ReadableJsonStore`, which both kinds of store are:

```kotlin
JsonStore.openSecondary(Path.of("data/store")).use { reader ->
    val db = reader.blocking()
    println(db.count(KeyValueQuery("name", JsonQueryValue.Str("ada"))))
    db.refresh() // true when the writer wrote something since
}
```

## Large documents

Large documents take the same calls — pass an `InputStream` or a `Path` instead of a string, and the document is
split and indexed as it is read:

```kotlin
val id = store.blocking().insert(Path.of("catalog-2GB.json"))
```

## Versions

Documents are immutable, so a new version of one is a new insert, and docIds grow with each insert. `replace`
(experimental) inserts the new version and deletes the old one in one atomic change, and throws
`WriteConflictException` without changing anything when the old one is gone, as when another writer replaced it first:

```kotlin
@OptIn(ExperimentalDelvaApi::class)
val next = store.blocking().replace(listOf(id), """{"session":"s-42","state":"closed"}""")
```

In `insertAll`, `InsertSource.replaces` names the documents a source replaces, and a conflict is that source's outcome,
`InsertOutcome.Conflicted`, rather than an exception.

A search in `SearchOrder.DESCENDING` returns the newest document first, so the current version of a key is a single
hit:

```kotlin
val current = store.blocking().search(KeyValueQuery("session", JsonQueryValue.Str("s-42"), limit = 1, order = SearchOrder.DESCENDING)).use { result ->
    result.nextPage()?.hits?.single()?.readJsonAsString()
}
```

When the answer is every hit inside the current version — each item of the newest order for a session, say —
`maxDocuments` (experimental) ends the result after the hits of that many documents, and the search reads no document
past them. Here a [document filter](#document-filters) picks the session's versions:

```kotlin
@OptIn(ExperimentalDelvaApi::class)
val newestItems = JsonPathQuery(JsonPath.compile("$.items[*].sku"), order = SearchOrder.DESCENDING)
    .withDocumentFilter(DocumentFilter.compile("@.session == 's-42'"))
    .withMaxDocuments(1)
val skus = store.blocking().search(newestItems).use { result -> result.iterator().asSequence().map { it.readJsonAsString() }.toList() }
```

Without it, read the descending result's hits until `docId` changes: a result reads one snapshot, so no write in between
can change which document is the newest.

## Document keys

When every document has a name of its own, create the store with a **document key** (experimental): the path of a
string member that no two visible documents share. An insert of a key that is taken throws `WriteConflictException`;
`upsert` replaces the document that has it in one atomic change; `get` finds a document by its key, and a
`DocumentKeyQuery` lists documents in key order:

```kotlin
@OptIn(ExperimentalDelvaApi::class)
JsonStore.create(dir, CreationOptions().withDocumentKey("$.session")).use { sessions ->
    val db = sessions.blocking()
    db.upsert("""{"session":"s-42","state":"open"}""")
    db.upsert("""{"session":"s-42","state":"closed"}""") // replaces the first
    val id: DocId? = db.get("s-42")
    val keys = db.search(DocumentKeyQuery(prefix = "s-")).use { result -> result.iterator().asSequence().map { it.documentKey }.toList() }
}
```

In `insertAll`, `InsertSource.onExistingKey = KeyConflict.REPLACE` upserts. A document without a string at the key's
path is rejected with `RejectReason.KEY_MISSING`.

## Types

Objects can be given a type as they are inserted, by JSON path: an RFC 9535 singular query such as `$.customer`,
or `$['@type']` for a key that needs quotes. Each document of an `insertAll` carries its own paths. The objects of a
type can then be listed, in pages or as a `Flow` that reads one snapshot:

```kotlin
store.blocking().insertAll(
    listOf(
        InsertSource("""{"order":1,"customer":{"name":"ada"}}""".byteInputStream(), mapOf("$" to "Order", "$.customer" to "Customer")),
        InsertSource("""{"invoice":7}""".byteInputStream(), mapOf("$" to "Invoice")),
    ),
)

// In a coroutine:
store.objectsOfTypeFlow("Customer").collect { node -> println(node) }
```

`objectsOfTypePage` pages them with a cursor that says when the last page was reached, `countOfType` counts them, and
`clearType` removes a type from every object in one atomic change. Types are metadata and never change the stored
JSON.

## JSONPath

JSONPath queries (RFC 9535, filters and functions included) are compiled once. `select` returns the nodelist of one
stored value, exactly as the RFC defines it; a JSONPath search returns the distinct selected nodes of every document,
and the index finds the documents from the query's filters:

```kotlin
val order = blocking.insert("""{"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":0}]}""")

blocking.select(NodeRef(order, 0), JsonPath.compile("$.items[*].sku")).use { result ->
    for (node in result) println("${node.path} = ${node.readJsonAsString()}")   // $['items'][0]['sku'] = "A1" …
}

val lowStock = JsonPathQuery(JsonPath.compile("$..[?@.qty < 1].sku"))
println(blocking.explain(lowStock))                                           // QueryPlan('qty' < 1)
blocking.search(lowStock).use { result ->
    for (hit in result) println("${hit.path} = ${hit.readJsonAsString()}")    // $['items'][1]['sku'] = "B2"
}
```

A query whose filters don't narrow the documents down — `$..sku`, say — is rejected with `QueryRejectedException`
unless the query allows a full scan with `ScanPolicy.ALLOW_SCAN`. `count(query, atMost)` tells how many hits a
search has without reading them, and stops at `atMost`. To find where a value lives in a large document, read one
document as an outline with `JsonFormat(maxLevels = …)` ([below](#formatting-output)) rather than scanning every
document with `$..`.

The patterns of `match` and `search` are I-Regexp (RFC 9485), which has no inline flags, no `\d` and no lookaround.
A pattern written into the query that can never match is still valid RFC 9535, so the query compiles and finds
nothing, but its `warnings` (experimental) say why; show them to whoever wrote the query. To match either case, list
both in a class: `'[Aa]1'`.

```kotlin
@OptIn(ExperimentalDelvaApi::class)
val warnings = JsonPath.compile("$..[?match(@.sku, '(?i)a1')]").warnings.map { "offset ${it.offset}: ${it.reason}" }
println(warnings.single())   // offset 18: '(?i)a1' isn't I-Regexp (RFC 9485), so match() is false for every value: …
```

## Document filters

A **document filter** (experimental) limits a search to the documents whose root meets a condition: an RFC 9535 filter
expression in which `@` is the document itself, which a path alone can't test. Its comparisons of root members with
values are index terms like the query's own, so `$..sku` needs no scan once the filter names one:

```kotlin
blocking.insert("""{"kind":"order","items":[{"sku":"C3","qty":5}]}""")
@OptIn(ExperimentalDelvaApi::class)
val orderSkus = JsonPathQuery(JsonPath.compile("$..sku")).withDocumentFilter(DocumentFilter.compile("@.kind == 'order'"))
println(blocking.explain(orderSkus))                                          // QueryPlan('kind' == 'order')
blocking.search(orderSkus).use { result ->
    for (hit in result) println("${hit.path} = ${hit.readJsonAsString()}")    // $['items'][0]['sku'] = "C3"
}
```

## Storing embeddings

An embedding, the vector of numbers a model computes from a text so that similar texts get nearby vectors, is stored
as a JSON array. Delva has no vector index yet ([roadmap](ROADMAP.md#engine)), so the nearest ones are found in your
own code, and four habits keep the store small and ready for one.

**Leave the vector's key out of the index.** Each number of an indexed array is a posting, and nobody searches for one
of them, so create the store with `CreationOptions(excludedKeys = setOf("embedding"))`. The option is fixed when the
store is created, so a store that exists has to be copied into a new one. It applies to the key at any depth, and a
key–value search on that key fails with `NOT_INDEXED` ([SPEC.md](SPEC.md#41-query)).

**Write the numbers short.** Numbers are stored as they are spelled. Nine significant digits keep any float32 exactly,
and Java's `Float.toString` writes the shortest text that reads back as the same float32, which is all the vectors of
a float32 model need. A serializer that widens float32 to float64 first writes up to 17 digits. Per document, measured
on one Windows machine with 10,000 chunks of 768 dimensions:

| Numbers | Vector key | JSON | Stored | Index | Inserts/s | Top 10 of all |
|---|---|---|---|---|---|---|
| float32, shortest | excluded | 9.5 KB | 4.0 KB | 58 B | 1,370 | 1.3 s |
| float64, 17 digits | excluded | 16.2 KB | 8.0 KB | 59 B | 1,520 | 1.9 s |
| float32, shortest | indexed | 9.5 KB | 4.0 KB | 22.8 KB | 750 | 1.4 s |

**One document per chunk.** Store each chunk of a text as a document of its own, with a reference to its source such
as `"source": "doc-17"`. A document filter then finds a source's chunks, and `replace` swaps one chunk without
rewriting the others. One vector per document is also what the roadmap's vector search takes.

**Record the model.** Vectors from two models, or from two versions of one, can't be compared. Keep one model per
store, or put `"model": "…"` in each chunk and filter on it. A new model means new vectors, and so new documents.

The nearest chunks are a search for the embedding's path, whose hits are the candidates' arrays, and a heap of the
best k:

```kotlin
JsonStore.create(dir, CreationOptions(excludedKeys = setOf("embedding"))).use { chunks ->
    val db = chunks.blocking()
    db.insert("""{"source":"doc-17","chunk":3,"model":"embed-v1","text":"…","embedding":[0.0123,-0.441,0.2087]}""")
    @OptIn(ExperimentalDelvaApi::class)
    val hits = nearest(db, queryVector, k = 10, DocumentFilter.compile("@.model == 'embed-v1'"))
}

@OptIn(ExperimentalDelvaApi::class)
fun nearest(db: BlockingReadableJsonStore, query: FloatArray, k: Int, filter: DocumentFilter): List<Pair<Double, DocId>> {
    val nearestFirst = compareBy<Pair<Double, DocId>>({ it.first }, { it.second.raw })
    val best = PriorityQueue(k, nearestFirst.reversed())                      // the farthest of the best k on top
    db.search(JsonPathQuery(JsonPath.compile("$.embedding")).withDocumentFilter(filter)).use { result ->
        for (hit in result) {
            val numbers = hit.readJsonAsString().removeSurrounding("[", "]").split(',')
            val candidate = cosineDistance(query, FloatArray(numbers.size) { numbers[it].toFloat() }) to hit.docId
            if (best.size < k) {
                best += candidate
            } else if (nearestFirst.compare(candidate, best.peek()) < 0) {
                best.poll()
                best += candidate
            }
        }
    }
    return best.sortedWith(nearestFirst)
}
```

`cosineDistance` is `1 − cos θ`, summed in double precision. Comparing the distance and then the docId puts ties in
docId order, so a store and a query always give the same answer. The filter's comparison with a literal is an index
term, so a more selective filter means fewer chunks read. Every candidate's embedding is still read and parsed from
JSON, about 0.13 ms each at 768 dimensions: the last column of the table above is this search over all 10,000 chunks.
It suits filters that leave hundreds of candidates, or a few thousand where a second is acceptable.
[`EmbeddingSearch`](../delva-examples/README.md) in delva-examples is the whole program.

## Templates

A **template** (experimental) is a query or a document filter with `${name}` placeholders where literals go. It is
compiled once, and each `bind` puts values in as literals, never as text, so no value can change what the query asks:

```kotlin
@OptIn(ExperimentalDelvaApi::class)
val qtyOf = JsonPathTemplate.compile("$..[?@.sku == \${sku}].qty")
@OptIn(ExperimentalDelvaApi::class)
val a1 = JsonPathQuery(qtyOf.bind("sku" to JsonQueryValue.Str("A1")))
println(a1.path)                                                              // $..[?@.sku == 'A1'].qty
blocking.search(a1).use { result -> for (hit in result) println(hit.readJsonAsString()) }   // 2
```

## Node paths

Every search hit knows where it is: `path` is its Normalized Path from the document root, for key–value hits too,
and `pathOf` names any node:

```kotlin
blocking.search(KeyValueQuery("sku", JsonQueryValue.Str("B2"), ResultMode.CONTAINING_ELEMENT)).use { result ->
    for (hit in result) println(hit.path)                                     // $['items'][1]
}
println(blocking.pathOf(NodeRef(order, 6)))                                   // $['items'][1]['sku']
```

## Formatting output

Hits and selected nodes are read as compact JSON by default. A `JsonFormat` pretty-prints them instead, putting a
container on one line when it fits the line width and within the element and depth limits you set, and breaking it
one element per line otherwise — still streamed, with memory independent of the node's size:

```kotlin
blocking.select(NodeRef(order, 0), JsonPath.compile("$")).use { result ->
    println(result.iterator().next().readJsonAsString(JsonFormat(lineWidth = 32)))
}
// {
//   "items": [
//     {"sku": "A1", "qty": 2},
//     {"sku": "B2", "qty": 0}
//   ]
// }
```

`JsonFormat(lineWidth = 0)` breaks every container, the layout of kotlinx.serialization's `prettyPrint`;
`maxInlineElements` and `maxInlineDepth` keep long or deeply nested containers off one line whatever the width.
`maxLevels` gives an outline instead: containers nested deeper are written as `{...}` and `[...]`, so
`JsonFormat(maxLevels = 0)` shows just the node's own elements — `{"items": [...]}` for the order above.

## Limits and sizing

Defaults are set so a store works on any heap. The limits a document must meet, and their defaults, are in
[SPEC.md](SPEC.md#13-limits), and those of a JSONPath query in its [section 10.1](SPEC.md#101-queries). Every option is
documented, with its range, in `CreationOptions`, for what is fixed when a store is created, or in `StoreOptions`, for
what may change at every open.

**Index size depends on your data, and can exceed the size of the documents.** The index holds a posting per indexed
key–value pair, so data made of many small records with mostly distinct values indexes far more heavily than data
with repetition: measured on one 46 MB document the index is about 37 % of the raw JSON, while on generated catalogs
of ~2 KiB records it is 120–155 %. If that matters for your data, the index controls — `indexedKeys`, `excludedKeys`,
`minIndexedStringBytes`, `maxIndexedStringBytes` and `hashTerms` — restrict what is indexed, and are fixed when the
store is created; [SPEC.md](SPEC.md#41-query) says what each one leaves searchable.

Fragment payloads are compressed by default (deflate, with dictionaries trained on what is inserted), which is what
keeps stored bytes well below the raw JSON. Compression costs between nothing and 40 % of ingest throughput,
depending on the data and the heap, and is close to free on reads; `StoreOptions.compression` turns it off. Each
payload records how it was stored, so a store written with compression reads back either way and the option only
chooses what new writes do.

Strings compare by exact code points, so the two spellings of `"é"` are two strings. Where your data or your users
mix spellings, create the store with `CreationOptions(normalization = Normalization.NFC)` to make them one; documents
then read back normalized rather than as inserted, and [SPEC.md](SPEC.md#21-strings) has the rest of the rules. ASCII
and text that is already normalized cost only a scan.

`StoreOptions.mmapReads` reads sealed segment files through memory mappings instead of positional reads, which saves
a copy per block and leaves the pages to the OS. It is off by default: on Windows a mapped file cannot be deleted, so
a compaction that retires a mapped segment waits for its readers to let go. It is worth trying on read-heavy stores
whose segments fit in memory.

## Using it from Java

Delva is a Kotlin API. `DocId` is a value class, so `insert`, `delete` and everything else that takes or returns one
has a JVM name Java cannot spell — the write path is effectively Kotlin-only. The one place that is fixed is
`Durability`, whose static `periodic(java.time.Duration)` factory stands in for a constructor Java cannot call either.
A Java facade is on the [roadmap](ROADMAP.md).

## Compatibility

This covers Delva's modules: delva, delva-kotlinx-serialization and delva-cli, which share one version. cli-kit has
versions of its own, under the [policy in its README](../cli-kit/README.md#versioning).

Until 1.0, the public API and the on-disk format can change in incompatible ways between minor versions. A store
written by one version is not guaranteed to be readable by another; the format version in the store's metadata makes
such a mismatch an `UnsupportedFormatException` rather than a misread, and `JsonStore.probe` (experimental) tells
whether a store opens without opening it ([SPEC §8](SPEC.md#8-consistency)).

Delva can be embedded in an IntelliJ Platform plugin for IDEs from 2026.2, the first that run on a JDK Delva needs.
The plugin must use the IDE's Kotlin standard library and kotlinx-coroutines rather than bundle its own, since a
second kotlinx-coroutines breaks the platform's: set `kotlin.stdlib.default.dependency=false` and exclude both from
the Delva dependency, as [Delva Tools](../delva-intellij-plugin/README.md) does. The IDE's kotlinx-coroutines can be
older than the one Delva is built with, so the IntelliJ Plugin Verifier, which checks the bundled Delva jar against
each IDE, is what says whether a Delva release runs in it.

Two opt-in markers qualify the API within a release:

- `@ExperimentalDelvaApi` marks what is new and may still change, in signature or behaviour, in a minor release. Using
  it needs `@OptIn(ExperimentalDelvaApi::class)`. The mark is lifted once a release has shipped a declaration
  unchanged, and the [changelog](../CHANGELOG.md) says when.
- `@DelvaImplementationApi` marks the interfaces that are Delva's to implement: `ReadableJsonStore`, `JsonStore`,
  `SecondaryJsonStore`, their blocking faces, `SearchResult`, `SearchHit`, `SelectResult` and `SelectedNode`. Calling
  them needs no opt-in. Implementing one, as a test fake does, needs `@OptIn(DelvaImplementationApi::class)`, because
  minor releases add members to them.
