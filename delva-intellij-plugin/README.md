# Delva Tools

An IntelliJ Platform plugin for Delva.

> Early development: nothing is published to JetBrains Marketplace yet.

<!-- Plugin description -->
Tools for [Delva](https://github.com/aoreshkov/delva), the embedded JSON document database for the JVM: list a project's
stores on disk, see what each holds, browse and open their documents, and search them with RFC 9535 JSONPath from the
IDE, even while an application has a store open.

Stores are read-only in the IDE: they are opened as Delva *secondaries*, which take no lock and never write.
The plugin reads the stores of the Delva minor its own version starts with: 0.2.x reads stores of Delva 0.2.
<!-- Plugin description end -->

## What it does

- The **Delva** tool window lists the project's stores and shows what the selected one holds: its documents, deleted
  documents, and the process that has it open as its writer. **Refresh** reads it again. The list is saved in
  `.idea/delva.xml`, with directories inside the project relative to it, so it can be shared like run configurations.
- Below that come the store's **documents**, a hundred at a time: in key order in a store created with a document key,
  in id order otherwise, each with its rough size. A double click or Enter opens a document in a read-only JSON
  editor, up to the IDE's size limit for code insight (`idea.max.intellisense.filesize`, 2.5 MB by default); `delva
  get` writes out a larger one.
- A store inside the project's content would be indexed with it, which only costs time for a store's binary files: the
  tool window offers to exclude it, as **Mark Directory as | Excluded** does.
- The **Search** tab searches the selected store by an RFC 9535 JSONPath query or by a key and a value, and lists the
  hits a hundred at a time, each with its document, where in the document it is and how it starts. A double click or
  Enter opens a hit in a read-only JSON editor. A JSONPath search can be limited to the documents whose root passes a
  filter, as `--where` does on the command line, and can list whole documents instead of the nodes it selects;
  **Explain** shows which index terms narrow it down. A query that no index term narrows would read every document,
  so it runs only once that's allowed. **Search Delva Store…** in the Tools menu opens the tab.

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

then add `./sample` in the **Delva** tool window, and search it on the **Search** tab with `$..[?@.qty < 1].sku`.

[DEVELOPMENT.md](../project/DEVELOPMENT.md#the-intellij-plugin) covers building and testing the plugin.
