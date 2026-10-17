# Changelog

All notable changes to Delva are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, a minor version can change the API and the
on-disk format incompatibly; see [Compatibility](project/GUIDE.md#compatibility).

## [Unreleased]

### Added

- `cli-kit`: `HelpRenderer(styler, programName, width)`, a renderer that wraps to a given width.
- `cli-kit`: `ConversionResult.Unset` and `unsetWhen`, for values that mean "not given", such as an unfilled template.
  The parser treats them as absent and `Program` prints the converter's notice as a warning.

### Changed

- `cli-kit` and `cli-kit-testing` are published under the Maven group `app.oreshkov.clikit`, matching their package
  and module names. 0.1.0 was their last release under `app.oreshkov.delva`, so change the group when updating.
- `cli-kit`: help pages wrap to the terminal width, at most 100 columns. A `help` text can hold paragraphs and line
  breaks; command lists and completion scripts show its first line.
- `delva-cli`: help fits 80 columns by default, and `--width` sets the width of help as well as tables.
- `cli-kit`: `ConversionResult` has a new subtype, `Unset`, so an exhaustive `when` over it needs a branch for it.
  Recompile code that inlined `ConversionResult.map`.

### Fixed

- `cli-kit`: a run that wrote nothing to stdout no longer exits 74 when another writer on the same stream failed.

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

[Unreleased]: https://github.com/aoreshkov/delva/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aoreshkov/delva/releases/tag/v0.1.0
