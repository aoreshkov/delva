# Changelog

All notable changes to Delva Tools, the IntelliJ plugin, are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). A version's minor is that of the Delva release it bundles.
Each version's section becomes the plugin's change notes on JetBrains Marketplace.

## [Unreleased]

### Added

- **Add Store…** warns when a directory holds no store, or one in a format this version doesn't read, and suggests
  **Verify Store** for a damaged one.

### Changed

- Built on Delva 0.3.0.
- A store in another format names its format and the formats the plugin reads, rather than a Delva version.

## [0.2.0] - 2026-10-30

### Added

- The **Delva** tool window lists a project's stores, saved in `.idea/delva.xml`, and shows what the selected one
  holds: its documents, deleted documents and the process that has it open as its writer.
- The tool window lists the selected store's documents a page at a time, in key order or id order, and opens one in a
  read-only JSON editor; it offers to exclude a store inside the project from indexing.
- The tool window's **Search** tab searches a store read-only, as a Delva 0.2 secondary, by RFC 9535 JSONPath, with a
  filter on each document's root, or by a key and value; it lists the hits a page at a time and opens each in a
  read-only JSON editor, and **Explain** shows how the store would find a JSONPath search's documents.
  **Search Delva Store…** in the Tools menu opens it.
- The tool window's **Types** tab lists the types assigned in a store and the objects of each; **Compare** shows two
  selected documents or nodes in the IDE's diff viewer; **Verify Store** checks every byte of a store in the background.
- The plugin has its own logo, and the IDE's error reporter sends an exception in the plugin to its maintainer
  through JetBrains Marketplace.

[Unreleased]: https://github.com/aoreshkov/delva/compare/delva-tools-v0.2.0...HEAD
[0.2.0]: https://github.com/aoreshkov/delva/releases/tag/delva-tools-v0.2.0
