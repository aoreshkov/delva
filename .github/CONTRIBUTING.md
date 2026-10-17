# Contributing to Delva

Thanks for your interest. Delva is in early development and maintained by one person, so the most useful
contributions right now are bug reports with a reproducer, and discussion of the semantics in
[SPEC.md](../project/SPEC.md) before 1.0 fixes them.

- **Bugs**: open an issue with the bug form. For anything that loses or corrupts data, or that hostile input can
  trigger, follow the [security policy](SECURITY.md) instead.
- **Features and larger changes**: open an issue first, so the design can be agreed before you spend time on it. The
  [roadmap](../project/ROADMAP.md) lists what's already planned.
- **Small fixes** (typos, docs, obvious bugs): a pull request is fine.

Everyone taking part is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).

## Building

[DEVELOPMENT.md](../project/DEVELOPMENT.md) covers the JDK the build needs, the test suites, reproducing a failure
from its seed, golden files and CI.

## Pull requests

- Keep a pull request to one change, with tests that fail without it.
- **Public API**: modules use explicit API mode, and `check` fails when the ABI differs from the module's dump in
  `<module>/api/`. If the change is intended, run `./gradlew updateKotlinAbi` and commit the dump. Every public
  declaration needs KDoc, which `check` enforces.
- **Behavior**: a change to what the engine does in a way a user can observe belongs in `SPEC.md` as well,
  and a new feature in the [guide](../project/GUIDE.md) or its module's README.
- **One place per fact**: each fact is written in the document that owns it; another document gives it at most a
  clause and a link.

  | Document | Owns |
  |---|---|
  | [`README.md`](../README.md) | the landing page: what Delva is, installation, quick start, features |
  | [`project/GUIDE.md`](../project/GUIDE.md) | how to use each feature, sizing, use from Java, compatibility |
  | [`project/SPEC.md`](../project/SPEC.md) | exact semantics, limits, error reasons, byte formats |
  | a module's `README.md` | that module's installation and use |
  | [`project/DEVELOPMENT.md`](../project/DEVELOPMENT.md) | building, the test suites, CI |
  | this file | what to send and the rules for it |
  | [`project/RELEASING.md`](../project/RELEASING.md) | the release procedure |
  | [`CHANGELOG.md`](../CHANGELOG.md) | one line per notable change to Delva; each version's section becomes its release notes |
  | [`cli-kit/CHANGELOG.md`](../cli-kit/CHANGELOG.md) | the same for cli-kit and cli-kit-testing |

- **CHANGELOG.md**: for a change users can notice, add one line under `Unreleased`, in its group (`Added`,
  `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`), in `cli-kit/CHANGELOG.md` for a change to cli-kit or
  cli-kit-testing and in the root `CHANGELOG.md` for anything else. The details belong in the guide, `SPEC.md` or the
  module's README.
- **Dependencies**: the library depends only on the JDK, the Kotlin standard library and kotlinx-coroutines, and the
  optional `delva-kotlinx-serialization` module also on kotlinx-serialization-json. Tests use kotlin-test, with JUnit
  as its runner, and kotlinx-coroutines-test; benchmarks use kotlinx-benchmark; the build uses only JetBrains tooling
  and Gradle's own plugins. Prefer the JDK or a few lines of code to a new library, and open an issue before proposing
  any other dependency: pull requests that add one won't be merged.
- **Style**: [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) and `.editorconfig`.
  Comments explain why, and stand on their own: no references to issues, tickets or documents outside the repository.
- CI must pass on Linux and Windows.

## Licensing

Delva is licensed under the [Apache License 2.0](../LICENSE). By submitting a contribution you agree that it is
licensed under the same terms, as section 5 of the license describes, and that you have the right to submit it.
