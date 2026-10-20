# Changelog

All notable changes to `cli-kit` and `cli-kit-testing` are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the kit adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html), with versions of its own, independent of Delva's; see
[Versioning](README.md#versioning). Version 0.1.0 came out with Delva, under the group `app.oreshkov.delva`, and is
listed in [Delva's changelog](../CHANGELOG.md#010---2026-10-15).

## [Unreleased]

### Added

- Help's Environment section lists the variables the kit reads for `--no-color` and `--width`: `NO_COLOR`,
  `FORCE_COLOR`, `CLICOLOR_FORCE` and `COLUMNS`. A command can't declare them itself.
- `StandardOptions.help`, a `help [command]...` command that shows a command's help page.
- Command aliases (`CommandSpec(aliases = …)`), and former names and deprecation for options and commands
  (`formerNames`, `deprecated`), still parsed and warned about on use.

### Changed

- A command that declares two subcommands with the same name, or that can be typed the same way through an alias or
  a former name, is refused when it's built.

## [0.2.0] - 2026-10-18

### Added

- `HelpRenderer(styler, programName, width)`, a renderer that wraps to a given width.
- `ConversionResult.Unset` and `unsetWhen`, for values that mean "not given", such as an unfilled template. The parser
  treats them as absent and `Program` prints the converter's notice as a warning.
- An Environment section in help, listing each option's variable and those a command declares with
  `CommandSpec(environment = …)` and `EnvironmentVariable`.
- Experimental API, which needs `@OptIn(ExperimentalCliKitApi::class)` and may change in a minor release:
  `Constraint.RequiresValue`, for "this option needs that one to have one of these values".

### Changed

- `cli-kit` and `cli-kit-testing` are published under the Maven group `app.oreshkov.clikit`, matching their package
  and module names, and are released on their own, with versions of their own. 0.1.0 was their last release under
  `app.oreshkov.delva`, so change the group when updating.
- Help pages wrap to the terminal width, at most 100 columns. A `help` text can hold paragraphs and line breaks;
  command lists and completion scripts show its first line.
- `ConversionResult` has a new subtype, `Unset`, so an exhaustive `when` over it needs a branch for it. Recompile code
  that inlined `ConversionResult.map`.
- `Constraint` has a new subtype, `RequiresValue`, so an exhaustive `when` over it needs a branch for it.

### Fixed

- A run that wrote nothing to stdout no longer exits 74 when another writer on the same stream failed.

[Unreleased]: https://github.com/aoreshkov/delva/compare/cli-kit-v0.2.0...HEAD
[0.2.0]: https://github.com/aoreshkov/delva/releases/tag/cli-kit-v0.2.0
