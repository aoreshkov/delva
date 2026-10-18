# Development

Building needs JDK 25, which Gradle uses as a toolchain; the classes it produces run on JDK 22 and newer.

```shell
./gradlew check
```

`check` compiles everything, checks each published module's public ABI against its dump in `<module>/api/`, and runs:

| Task | Runs | Notes |
|---|---|---|
| `test` | unit and property tests | everything not below |
| `conformanceTest` | `*ConformanceTest` | JSONTestSuite |
| `simTest` | `*SimTest` | deterministic crash simulation; `-Pdelva.testProfile=long` for the long seed list |
| `checkSelfContainedComments` | source files, workflows, root build scripts and documents | comments must not cite documents outside the repository |
| `checkPublicKDoc` | published modules' main sources | every public declaration has KDoc |
| `processTest` | delva-cli's `*ProcessTest` | the installed start script, in a child process |
| `checkRuntimeDependencies` | delva-cli's runtime classpath | only delva, cli-kit and what they need; cli-kit from a repository, or from source with the switch below |
| `checkPublishedDependencies` | cli-kit-testing's published module metadata | only cli-kit, kotlin-test and the standard library |
| `:build-logic:check` | the build logic's own tests | |

These are left out of `check` because they take long:

| Task | Runs |
|---|---|
| `heapCappedTest` | `*HeapCappedTest`: huge documents under a 256 MB heap, proving memory is bounded by the options, not by document size |
| `crashTest` | `*CrashTest`: child JVMs killed mid-write, proving an insert is whole or absent after a real crash |
| `reportTest` | `*ReportTest`: the benchmark report the guide's performance numbers come from (minutes); `-Ddelva.report.megabytes=50,500` sets its loads |

Micro-benchmarks of internals: `./gradlew :delva:jmhBenchmark`.

delva-cli builds on the released cli-kit pinned in `gradle/libs.versions.toml`, not on the cli-kit source beside it
([RELEASING.md](RELEASING.md#release-trains) says why). To try a cli-kit change in delva-cli before it is released,
build on the source instead:

```shell
./gradlew :delva-cli:check -Pdelva.cliKitFromSource=true
```

delva-cli's golden files follow the pinned cli-kit, so a change to cli-kit's help rendering fails them under the
switch until the pin moves.

## How the engine stores data

- Documents are split into fragments and appended to a log with group commit and per-document lanes; payloads are
  compressed with dictionaries trained on what is inserted.
- The indexes, the document-key index among them, are LSM trees: flushed from memory, merged by size-tiered
  compaction, and recovered by replaying the log from the last flushed watermark.
- Space held by deleted documents is reclaimed.
- `verify` checks log record and segment block checksums, the fragments each COMMIT still in the log names, every
  fragment-index entry against the record it locates, and the document-key index against itself.

## Reproducing a failure

Property and simulation tests print the seed of a failing case. Rerun just that case with the command below. cli-kit's
own property tests run under `:cli-kit:jvmTest` and take only the seed.

```shell
./gradlew test --tests <Class> -Ddelva.seed=<seed> -Ddelva.size=<size>
```

## Golden files

Persisted byte formats are pinned by golden files in `delva/src/test/resources/golden`. A change that alters one
changes the on-disk format, so it needs a good reason and a CHANGELOG entry. The command line's help texts and
completion scripts are pinned the same way, in `delva-cli/src/test/resources/golden` and
`cli-kit/src/jvmTest/resources/golden`. To rewrite them after an intended change, then read the diff:

```shell
./gradlew test :cli-kit:jvmTest -Ddelva.updateGolden=true
```

## Continuous integration

| Workflow | Runs on | What it does |
|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | pushes to `main`, pull requests | `check` on Linux and Windows, and builds the published artifacts |
| [`long-tests.yml`](../.github/workflows/long-tests.yml) | Mondays, or by hand | `heapCappedTest` and `crashTest` on Linux and Windows; by hand, also the benchmark report and the long simulations on Linux |
| [`codeql.yml`](../.github/workflows/codeql.yml) | pushes to `main`, pull requests, Wednesdays | CodeQL code scanning of the Kotlin and Java sources, from a build, and of the workflows |
| [`release.yml`](../.github/workflows/release.yml) | a `v*` or `cli-kit-v*` tag | releases Delva's or cli-kit's train; see [RELEASING.md](RELEASING.md) |
| [`dependency-submission.yml`](../.github/workflows/dependency-submission.yml) | pushes to `main` | submits the Gradle dependency graph, which GitHub can't read by itself, for Dependabot alerts: the runtime classpaths only, not the build's tooling |

The long suites run on both operating systems because every durability and memory claim was first measured on one
Windows machine, and a claim proven on one operating system is a claim about that operating system. The benchmark
report and the long simulations take tens of minutes and a person reads their numbers, so they stay off the schedule;
the report's runner and table land in the run's summary:

```shell
gh workflow run long-tests.yml -f suites=report -f report-megabytes=50,500,5000
```

GitHub disables a public repository's scheduled workflows after 60 days without activity, and they stay off until
someone enables them again (`gh workflow enable long-tests.yml`, and the same for `codeql.yml`).

Every job sets up the build through [`.github/actions/setup-build`](../.github/actions/setup-build/action.yml). Actions
are pinned to full commit SHAs, the only reference that can't be moved, and Dependabot keeps them current.
