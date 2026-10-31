# Delva

[![CI](https://github.com/aoreshkov/delva/actions/workflows/ci.yml/badge.svg)](https://github.com/aoreshkov/delva/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/app.oreshkov.delva/delva)](https://central.sonatype.com/artifact/app.oreshkov.delva/delva)
[![KDoc](https://img.shields.io/badge/KDoc-latest-blue.svg)](https://aoreshkov.github.io/delva/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

An embedded JSON document database engine for the JVM, written in Kotlin.

Delva stores JSON documents of any size, indexes every key–value pair in them, and answers key–value and JSONPath
searches over that index. It is a library, not a server: one process opens a directory and works with it directly.
Documents are streamed in and out, so memory is bounded by configuration and never by document size: its tests
insert, search and read back a 2 GiB document in a 256 MB heap.

*Delva* is Bulgarian — **делва**, the earthenware jar a household sinks into a cellar floor and seals for the winter.

> **Status:** early development. Until 1.0, a minor version can change the API and the on-disk format incompatibly;
> see [Compatibility](project/GUIDE.md#compatibility).

## Installation

Delva needs JDK 22 or newer, and depends only on the Kotlin standard library and kotlinx-coroutines. Its JPMS module
name is `app.oreshkov.delva`. It is published to Maven Central:

```kotlin
dependencies {
    implementation("app.oreshkov.delva:delva:0.3.0")
}
```

## Quick start

```kotlin
import app.oreshkov.delva.*
import java.nio.file.Path

JsonStore.openOrCreate(Path.of("data/store")).use { store ->
    val blocking = store.blocking()

    val id = blocking.insert("""{"name":"ada","tags":["math","engines"],"score":42}""")

    blocking.search(KeyValueQuery("name", JsonQueryValue.Str("ada"))).use { result ->
        for (hit in result) println(hit.readJsonAsString())
    }

    // An element of an array value matches the array's member too.
    blocking.search(KeyValueQuery("tags", JsonQueryValue.Str("math"), ResultMode.CONTAINING_ELEMENT)).use { result ->
        for (hit in result) println(hit.node)
    }

    blocking.delete(id)
}
```

The [guide](project/GUIDE.md) covers the suspending functions that `blocking()` wraps, and everything else; the
[KDoc](https://aoreshkov.github.io/delva/) documents every public declaration.

## Features

- **Key–value search** over every object in every document, as whole documents or the containing objects, oldest or
  newest first, paged through cursors.
- **JSONPath**: all of RFC 9535 — `select` within one value, and searches that the index narrows down by the
  query's filters.
- **Versions and keys**: documents are immutable; `replace` swaps in a new version atomically, and an optional
  document key adds `get`, `upsert` and listing in key order.
- **Types**: name objects by JSON path as they are inserted or later, and filter searches on them.
- **Readers beside the writer**: other processes open a secondary that never locks or writes.
- **Compare and diff** two stored values, or one against JSON text, streamed with bounded memory.
- **Crash consistency**: an insert is completely visible after a crash or completely absent; `verify` scrubs the whole
  store for damage.
- **Optional Unicode NFC normalization** of every string, key and query.

## Modules

| Module | What it is |
|---|---|
| `delva` | The engine; see the [guide](project/GUIDE.md). |
| [`delva-kotlinx-serialization`](delva-kotlinx-serialization/README.md) | Stores and reads `@Serializable` values. |
| [`delva-cli`](delva-cli/README.md) | The `delva` command line, shipped with each GitHub release. |
| [`delva-intellij-plugin`](delva-intellij-plugin/README.md) | Delva Tools, a plugin for IntelliJ-based IDEs that searches stores on disk. |
| [`cli-kit`, `cli-kit-testing`](cli-kit/README.md) | A standalone library for command-line programs, and its test kit, with versions of its own. |
| [`delva-examples`](delva-examples/README.md) | Runnable programs that use only the public API. |

## Documentation

- [Guide](project/GUIDE.md): every feature with examples, limits and sizing, use from Java, and compatibility.
- [Specification](project/SPEC.md): the exact semantics and canonical byte forms, a draft until 1.0.
- [Roadmap](project/ROADMAP.md) and [changelog](CHANGELOG.md); cli-kit has [its own](cli-kit/CHANGELOG.md).
- [Development](project/DEVELOPMENT.md): building, the test suites and CI; [releasing](project/RELEASING.md).

## Contributing

Bug reports and discussion of the semantics are welcome; see [CONTRIBUTING.md](.github/CONTRIBUTING.md). Report
security problems — including anything that loses or corrupts data — privately, as [SECURITY.md](.github/SECURITY.md)
describes.

## License

[Apache License 2.0](LICENSE)
