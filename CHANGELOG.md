# Changelog

All notable changes to Delva — `delva`, `delva-kotlinx-serialization` and `delva-cli` — are documented in this file.
cli-kit has versions of its own and [its own changelog](cli-kit/CHANGELOG.md) from 0.2.0 on; 0.1.0 below lists it
too. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, a minor version can change the API and the
on-disk format incompatibly; see [Compatibility](project/GUIDE.md#compatibility).

## [Unreleased]

### Added

- `delva`: `QueryRejectedException.detail` is what the store found, without the message's prefix, and
  `QueryRejectedException.query` the text of the refused JSONPath query.
- `delva`: query warnings (experimental): `JsonPath.warnings`, `DocumentFilter.warnings` and those of the templates
  report a `match` or `search` whose literal pattern makes it false for every value, such as `'(?i)x'`, which isn't
  I-Regexp.
- `delva-cli`: `search path`, `get --path` and `explain` print those warnings, for the query and for `--where`, on
  stderr before anything else.
- `delva-cli`: `search kv` that finds nothing for a key that looks like a path, such as `$.a.b`, says on stderr that
  the key is taken as one member's name, and points to `search path`.
- `delva`: `maxDocuments` (experimental) on `JsonPathQuery` and `KeyValueQuery`, set with `withMaxDocuments`, ends a
  search's result, and its `count`, after the hits of that many documents; with `SearchOrder.DESCENDING`, `1` gives
  every hit of the newest matching document, and no further document is read.
- `delva-cli`: `--max-documents` (`-m`) on `search kv` and `search path`.
- `delva`: a vector field (experimental), set with `CreationOptions.withVectorField`: a `VectorField` names the path,
  dimensions and `VectorMetric` of the one vector each document of the store may hold. A store created with one is in
  format version 2, which earlier versions of Delva refuse; every other store stays in format version 1.
- `delva`: in a store with a vector field, each insert reads the document's vector as 32-bit floats, and
  `RejectReason.INVALID_VECTOR` rejects a document whose value at the field's path isn't one.
- `delva`: `verify` checks the vector index of a store with a vector field, and reports its damage as
  `VerifyIssueKind.VECTOR_INDEX`.
- `delva`: exact vector search (experimental): `search(VectorQuery)` returns the `k` documents nearest to a query
  vector, each `VectorHit` with its distance, optionally within `maxDistance` and among the documents a
  `DocumentFilter` accepts; `explain(VectorQuery)` tells how it finds its candidates.
- `delva-cli`: `info` shows the store's vector field.

### Changed

- `delva`: a `QueryRejectedException` message states what the store found and no longer names a remedy in the API;
  the KDoc of each `QueryRejectedException.Reason` gives it.
- `delva-cli`: a refused search names `delva`'s own remedy, such as `--scan`, `delva get --path` or `delva info`,
  instead of the library's API; a range too wide and a query of more than 31 segments exit with status 2 instead of
  65, as a search that needs `--scan` does.
- `delva-cli`: built on cli-kit 0.4.0, so notices, warnings and errors keep their place among the output's lines when
  stdout and stderr are merged, a line break in one starts an indented continuation line, and an option given twice
  with different values is a warning.

### Fixed

- `delva`: on Linux and macOS, a refused second open of a store in the same JVM no longer releases the first
  writer's lock, which let another process open the store as a second writer.
- `delva`: suspending writes and vector search work from a coroutine without a `Job`, such as `suspend fun main`;
  they threw `IllegalStateException`.
- `delva`: a secondary open across a reset of its store reads the documents written after it with the compression
  dictionaries trained after it; it read them with the old ones, which gave wrong bytes or reported damage.
- `delva`: closing a secondary during a read fails the read with `StoreClosedException`; it threw
  `CorruptionException`.
- `delva`: a secondary reports damage to the control log up to where a newer MANIFEST records it as durable; it judged
  damage only against the first MANIFEST of its view, and took what followed for writes in progress.
- `delva`: a long-lived secondary no longer keeps every index segment it has read in memory until it closes.
- `delva`: `verify` reports a control file that ends without its SEAL or has records after it, and zeroed bytes before
  valid records of the control log, which make the next open fail; it no longer reports invalid bytes after the
  newest control record, which the next open drops as an unsynced tail.
- `delva`: `verify` no longer reports damage on a running store when a flush or space reclamation runs during it.
- `delva`: a power loss while a store reopens after a crash no longer leaves an insert that wasn't synced yet damaged;
  with `Durability.Periodic` or `None`, the insert is now either whole or absent.
- `delva`: a store that fails reports the error that failed it to every operation it stops; one that ran into the
  failure as it happened could get an `EngineFailedException` without a cause.
- `delva`: with `mmapReads`, a read racing the store's close fails with `StoreClosedException`, and closing a result
  after its store no longer throws `IllegalStateException`. A result closed after its store no longer removes segment
  files from the store's directory, whose lock is gone; the next open removes them.

## [0.3.0] - 2026-10-31

### Added

- `delva`: `JsonStore.probe` (experimental) reads a store's format version without opening it, and tells whether
  this version of Delva reads it.
- `delva-cli`: built on cli-kit 0.3.0, so help lists `NO_COLOR`, `FORCE_COLOR`, `CLICOLOR_FORCE` and `COLUMNS` under
  Environment.

### Changed

- `delva`: secondaries are no longer experimental: `JsonStore.openSecondary`, `SecondaryJsonStore`,
  `BlockingSecondaryJsonStore` and `StoreInfo.secondary` need no opt-in.
- `delva`: a store in a format version this version of Delva doesn't read throws `UnsupportedFormatException`, with
  the version found and the versions read, instead of `CorruptionException`, and is left as it was.
- `delva-cli`: a store in a format version `delva` doesn't read exits with status 65 instead of 74.

### Fixed

- `delva`: opening a store no longer deletes a log file of another format version as one whose creation a crash cut
  off; it throws `CorruptionException` and leaves the file as it was.

## [0.2.0] - 2026-10-19

### Added

- `delva-cli`: help lists `DELVA_STORE` and `DELVA_OPTS` under Environment.

### Changed

- `cli-kit` and `cli-kit-testing` are released on their own, under the Maven group `app.oreshkov.clikit` and with
  versions of their own, so their changes are in
  [their changelog](https://github.com/aoreshkov/delva/blob/main/cli-kit/CHANGELOG.md).
- `delva-cli`: built on cli-kit 0.2.0 as released on Maven Central, rather than on cli-kit's source.
- `delva-cli`: help fits 80 columns by default, and `--width` sets the width of help as well as tables.
- `delva-cli`: `--nested` requires `--mode element`, and `--max-differences` requires `--diff`, instead of being
  ignored.
- `delva-cli`: `--line-width` implies `--pretty`, and can't be combined with `--compact` or `--json`.

### Fixed

- `delva`: after a writer was killed mid-flush, its recovery could give a new index file the number of one a secondary
  had already read, and the secondary then failed with `CorruptionException` or read the wrong index blocks.

## [0.1.0] - 2026-10-15

First public release.

### Added

- `delva`: an embedded JSON document database for JDK 22 and newer.
  - Streams JSON documents of any size in and out with bounded memory. Documents are immutable.
  - Indexes every key–value pair, for key–value search and RFC 9535 JSONPath search with cursors, either order,
    `count` and `explain`.
  - Types, compare and diff, node paths, and pretty-printed output.
  - Crash-consistent inserts, three durability modes, `verify`, `reset`, and optional NFC normalization.
- Experimental API, which needs `@OptIn(ExperimentalDelvaApi::class)` and may change in a minor release: `replace`,
  document keys, document filters, query templates, and secondaries that read beside the writer.
- `delva-kotlinx-serialization`: stores `@Serializable` values and reads results back as them.
- `delva-cli`: the `delva` command line, attached to the release with SHA-256 sums and build provenance.
- `cli-kit` and `cli-kit-testing`: a toolkit for Kotlin command-line programs, and its test kit.

[Unreleased]: https://github.com/aoreshkov/delva/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/aoreshkov/delva/releases/tag/v0.3.0
[0.2.0]: https://github.com/aoreshkov/delva/releases/tag/v0.2.0
[0.1.0]: https://github.com/aoreshkov/delva/releases/tag/v0.1.0
