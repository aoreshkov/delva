# delva-cli

`delva` works on a Delva store from a shell.

## Installation

Each release carries it as `delva-cli-X.Y.Z.zip` (and `.tar`) on the
[releases page](https://github.com/aoreshkov/delva/releases): unpack it, put its `bin` directory on the `PATH`, and
run it with Java 22 or newer (`JAVA_HOME`, or `java` on the `PATH`). JVM options, such as a larger heap for very large
documents, go in `DELVA_OPTS`.

To check a download, compare it with the release's `SHA256SUMS`, or verify its build provenance:

```shell
sha256sum --check --ignore-missing SHA256SUMS
gh attestation verify delva-cli-X.Y.Z.zip --repo aoreshkov/delva
```

## Usage

```shell
delva init ./data/store                            # create a store
export DELVA_STORE=./data/store                    # name it once for the commands after

delva insert orders/*.json                         # one document per file; prints their ids
delva search kv status open --string               # documents with "status": "open"
delva search kv sku B2 -S --mode element --json    # the matching objects, as JSON Lines
delva search path '$..[?@.qty < 1].sku'            # a JSONPath search
delva get 42 --path '$.items[*].sku'               # nodes of one document
delva verify                                       # read everything, report damage; exit 1 if any
delva compare 41 42 --diff                         # what changed between two versions
delva reset --force                                # empty the store, keeping its creation options
```

Every command has `--help`, examples first. `init` creates a store; every other command opens one that exists, named
by `--store` or `DELVA_STORE`, so a mistyped path is an error rather than a new store.

- **Streaming**: documents stream through, so `delva get` writes a document of gigabytes with the memory of a small
  one, and `delva insert -` reads one from a pipe.
- **Scripting**: `--json` makes output machine-readable (JSON Lines for results, each search hit with the cursor that
  resumes after it). `search --count` prints how many hits there are, at most `--limit`.
- **Beside a writer**: commands that only read (`info`, `get`, `search`, `explain`, `path`, the `types` reads,
  `compare`, `verify`) open a secondary, so they work beside a running writer and never change the store;
  `delva info` names the writer.
- **Document keys**: `init --document-key '$.id'` creates a keyed store; then `get --key`, `search key [prefix]` and
  `insert --upsert` work by key, and search hits carry their key.
- **Document filters**: `search path` and `explain` take one as `--where "@.kind == 'order'"`.
- **Safety**: `delete` and `reset` ask for confirmation at a terminal, and need `--force` without one.

## Exit statuses

They follow `sysexits.h`:

| Status | Meaning |
|---|---|
| 0 | success |
| 1 | `compare`: the values differ; `verify`: the store is damaged; a query run with `--exit-status`: nothing found; `delete` or `reset`: declined at the prompt |
| 2 | usage error |
| 65 | rejected data: a document, query or option value, or a store in a format version this `delva` doesn't read |
| 66 | a missing store, file or document, or a write conflict |
| 74 | an I/O error or a damaged store, or standard output that took only part of the output, as when `head` stops reading |
| 75 | a write to a store another process has open, naming that process |
| 77 | access denied |
| 78 | `init` where a store already exists |

## Shell completion

`delva completion <shell>` prints a completion script:

| Shell | Install |
|---|---|
| bash | `source <(delva completion bash)` in `~/.bashrc` |
| zsh | `delva completion zsh > "${fpath[1]}/_delva"` |
| fish | `delva completion fish > ~/.config/fish/completions/delva.fish` |
| PowerShell | `delva completion powershell \| Out-String \| Invoke-Expression` in `$PROFILE` |
