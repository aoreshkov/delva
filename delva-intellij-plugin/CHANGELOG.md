# Changelog

All notable changes to Delva Tools, the IntelliJ plugin, are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). A version's minor is that of the Delva release it bundles.
Each version's section becomes the plugin's change notes on JetBrains Marketplace.

## [Unreleased]

### Added

- The **Delva** tool window lists a project's stores, saved in `.idea/delva.xml`, and shows what the selected one
  holds: its documents, deleted documents and the process that has it open as its writer.
- **Search Delva Store…**, in the tool window and the Tools menu: opens a store read-only, as a Delva 0.2 secondary,
  and shows the hits of an RFC 9535 JSONPath query in a read-only JSON editor.
