# Changelog

All notable changes to Delva are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, a minor version can change the API and the
on-disk format incompatibly; see [Compatibility](project/GUIDE.md#compatibility).

## [Unreleased]

## [0.1.0] - 2026-10-14

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

[Unreleased]: https://github.com/aoreshkov/delva/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aoreshkov/delva/releases/tag/v0.1.0
