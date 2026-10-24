# Delva Tools

An IntelliJ Platform plugin for Delva.

> Early development: nothing is published to JetBrains Marketplace yet.

<!-- Plugin description -->
Tools for [Delva](https://github.com/aoreshkov/delva), the embedded JSON document database for the JVM: list a project's
stores on disk, see what each holds, and search them with RFC 9535 JSONPath from the IDE, even while an application
has a store open.

Stores are read-only in the IDE: they are opened as Delva *secondaries*, which take no lock and never write.
The plugin reads the stores of the Delva minor its own version starts with: 0.2.x reads stores of Delva 0.2.
<!-- Plugin description end -->

## What it does

- The **Delva** tool window lists the project's stores and shows what the selected one holds: its documents, deleted
  documents, and the process that has it open as its writer. **Refresh** reads it again. The list is saved in
  `.idea/delva.xml`, with directories inside the project relative to it, so it can be shared like run configurations.
- **Search Delva Store…** asks for a JSONPath query and opens the hits in a read-only JSON editor, each with its
  document id, document key and path. It searches the store selected in the tool window, or, from the Tools menu, a
  directory it asks for.

## Compatibility

| | |
|---|---|
| IDEs | IntelliJ-based IDEs 2026.2 and newer. Delva's classes need Java 22, and 2026.2 is the first IDE line on Java 25. |
| Stores | Those of the Delva release the plugin bundles. Until Delva 1.0 a minor can change the store format, so the plugin's minor follows Delva's: 0.2.x reads stores of Delva 0.2. |

## Trying it out

```shell
./gradlew :delva-intellij-plugin:runIde
```

opens a sandbox IDE with the plugin installed. Make a store to search with the [delva command line](../delva-cli/README.md):

```shell
delva init ./sample
echo '{"order": {"items": [{"sku": "A1", "qty": 0}, {"sku": "B2", "qty": 3}]}}' | delva insert --store ./sample -
```

then add `./sample` in the **Delva** tool window, and search it with `$..[?@.qty < 1].sku`.

[DEVELOPMENT.md](../project/DEVELOPMENT.md#the-intellij-plugin) covers building and testing the plugin.
