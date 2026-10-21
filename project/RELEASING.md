# Releasing

Delva and cli-kit are published to Maven Central through the [Central Portal](https://central.sonatype.com). Pushing a
version tag runs [`release.yml`](../.github/workflows/release.yml), which tests, signs, attests, uploads and drafts the
GitHub release; nothing in the repository holds keys or credentials.

## Release trains

The published modules form two release trains. Each has its own Maven group, version, tag and changelog, and is
released on its own, when its own changes are ready:

| Train | Modules | Group | Version in `gradle.properties` | Tag | Changelog |
|---|---|---|---|---|---|
| Delva | delva, delva-kotlinx-serialization; delva-cli's archives | `app.oreshkov.delva` | `delva.version` | `vX.Y.Z` | [`CHANGELOG.md`](../CHANGELOG.md) |
| cli-kit | cli-kit, cli-kit-testing | `app.oreshkov.clikit` | `clikit.version` | `cli-kit-vX.Y.Z` | [`cli-kit/CHANGELOG.md`](../cli-kit/CHANGELOG.md) |

[`ReleaseTrain.kt`](../build-logic/src/main/kotlin/ReleaseTrain.kt) says which module rides which train: a
published module it doesn't list fails the build, and an unpublished one takes Delva's version. Each train's stage
task (`stageDelva`, `stageCliKit`) builds the train's bundle in `build/staging-repository/<train>`. Only Delva's
releases update the KDoc on GitHub Pages and become the repository's *Latest* release.

delva-cli builds on a released cli-kit, the `cliKit` version in
[`libs.versions.toml`](../gradle/libs.versions.toml), never on the cli-kit source beside it, so a Delva release ships
the cli-kit pinned there. `checkRuntimeDependencies` fails a build in which cli-kit doesn't come from a repository. A
cli-kit release reaches delva-cli only through a pull request that moves the pin: Dependabot's weekly run proposes
one once the release is on Maven Central, or open it yourself as the release's last step.

## One-time setup

1. **Namespace**: the `app.oreshkov` namespace has to be verified in the Central Portal (it is verified by proving
   control of `oreshkov.app`). Until it is, uploads fail with an authorization error. It covers both trains' groups.
2. **Signing key**: a PGP key used only to sign releases from this repository, with an expiry date. It signs with its
   primary key and has no signing subkey, because Maven Central checks signatures against primary keys. Its public
   half is on `keyserver.ubuntu.com`, and also on `keys.openpgp.org` with its e-mail address confirmed, since that
   server leaves out the user ID until then. Keep the revocation certificate offline.
3. **Portal token**: a user token from the Central Portal account page, as the Base64 of `username:password`. Tokens
   expire on the date chosen when they are made, so replace the secret before then.
4. **GitHub environment** `release` (Settings → Environments), with yourself as required reviewer and `v*` and
   `cli-kit-v*` as the only deployment tags, holding the secrets:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | the ASCII-armored private key |
   | `SIGNING_KEY_PASSWORD` | its passphrase |
   | `CENTRAL_TOKEN` | the Portal token |

5. **Immutable releases** on (Settings → General → Releases), so a published release's tag and assets can't change.
   This protects only releases published while the setting is on, and a tag name that belonged to a published
   release can never be used again.
6. **GitHub Pages** with *GitHub Actions* as its source (Settings → Pages), and the `github-pages` environment limited
   to `v*` tags: `release.yml` publishes the KDoc of every Delva release there.

## Cutting a release

1. `gradle.properties`: set the train's version (`delva.version` or `clikit.version`) to the release version (no
   `-SNAPSHOT`), and the installation snippets to the same version: in `README.md` and
   `delva-kotlinx-serialization/README.md` for Delva, in `cli-kit/README.md` for cli-kit.
2. The train's changelog: move the `Unreleased` entries under a new `## [X.Y.Z] - YYYY-MM-DD` heading dated the day
   you tag, leaving `## [Unreleased]` empty above it, and point the link references at the bottom at the tag
   (`[X.Y.Z]: …/releases/tag/<tag>`, `[Unreleased]: …/compare/<tag>...HEAD`).
   That section becomes the release notes; [CONTRIBUTING.md](../.github/CONTRIBUTING.md#pull-requests) says what
   belongs in it.
3. Check the published surface locally: `check` (what it runs is in [DEVELOPMENT.md](DEVELOPMENT.md)), then the
   runtime classpath of each published library:

   ```shell
   ./gradlew check
   ./gradlew :delva:dependencies --configuration runtimeClasspath
   ./gradlew :delva-kotlinx-serialization:dependencies --configuration runtimeClasspath
   ./gradlew :cli-kit:dependencies --configuration jvmRuntimeClasspath
   ```

   delva's must show only `kotlin-stdlib` and `kotlinx-coroutines-core`; delva-kotlinx-serialization's only delva's
   and `kotlinx-serialization-json` with its `kotlinx-serialization-core`; and cli-kit's only `kotlin-stdlib`.

4. Open a pull request with these changes, as for any change to `main`, and merge it once its checks pass. Then tag
   the merged commit with a signed tag and push only the tag:

   ```shell
   git switch main && git pull --ff-only
   git tag -s vX.Y.Z -m "Delva X.Y.Z"               # or: git tag -s cli-kit-vX.Y.Z -m "cli-kit X.Y.Z"
   git push origin vX.Y.Z                           # or: git push origin cli-kit-vX.Y.Z
   ```

5. `release.yml` runs `check` on Linux and Windows, and for Delva also `heapCappedTest` and `crashTest`. cli-kit's
   release runs all of `check` too, but delva-cli's tests there run against the pinned cli-kit, not the one being
   released: they meet it in the pull request that moves the pin. The `release` job then waits for your approval. Once approved, it checks that the tag matches the
   train's version, and builds and checks the train's signed bundle with its `stage` task. For Delva it also builds
   the command line's archives (`delva-cli-X.Y.Z.zip` and `.tar`, checked to hold only the start scripts, jars,
   licences and README) with their `SHA256SUMS`. It attests the build provenance of the jars, POMs, module files and
   archives, uploads the bundle to the Portal and waits until the Portal has validated it, failing with the Portal's
   errors if validation fails; the run's summary names the deployment. Only then does it draft the GitHub release,
   titled "Delva X.Y.Z" or "cli-kit X.Y.Z", with the notes from the train's changelog and the train's jars attached,
   and for Delva the archives. A version with a hyphen is drafted as a pre-release.
6. In the Portal, check the validated deployment and **publish** it. Wait until it shows *Published*, so the release
   never points at coordinates that don't resolve yet.
7. On GitHub, review the draft release and **publish** it. Its tag and assets can't change after that. A Delva
   release becomes *Latest*. Publish a cli-kit release from the command line instead, because GitHub would otherwise
   make it *Latest* in place of Delva's:

   ```shell
   gh release edit cli-kit-vX.Y.Z --draft=false --latest=false
   ```

8. In a second pull request, set the train's version to the next `-SNAPSHOT` in `gradle.properties`.
9. For cli-kit, once its files resolve from `repo.maven.apache.org`, move delva-cli's pin if delva-cli should take the
   release now: `cliKit` in `libs.versions.toml`, with delva-cli's golden files rewritten if the help rendering
   changed, and a line in the root `CHANGELOG.md` if users of the command line can see the difference.

Anyone can check a released jar against its provenance, and the command line's archives as its
[README](../delva-cli/README.md#installation) shows. GitHub also attests every published immutable release, and its
assets can be checked against that attestation:

```shell
gh attestation verify delva-X.Y.Z.jar --repo aoreshkov/delva
gh release verify vX.Y.Z --repo aoreshkov/delva
gh release verify-asset vX.Y.Z delva-cli-X.Y.Z.zip --repo aoreshkov/delva
```

## Release candidates

A release candidate proves the whole path, Portal validation included, without publishing anything. It is cut from a
throwaway branch, so `main` never holds its version. For Delva:

```shell
git switch -c rc/delva-X.Y.Z-rc.N main
# gradle.properties: delva.version=X.Y.Z-rc.N; CHANGELOG.md: a "## [X.Y.Z-rc.N] - YYYY-MM-DD" section with one line
git commit -S -am "Release candidate Delva X.Y.Z-rc.N"
git push -u origin rc/delva-X.Y.Z-rc.N
git tag -s vX.Y.Z-rc.N -m "Delva X.Y.Z-rc.N" && git push origin vX.Y.Z-rc.N
```

For cli-kit, the branch is `rc/cli-kit-X.Y.Z-rc.N`, the version `clikit.version`, the section goes in
`cli-kit/CHANGELOG.md`, and the tag is `cli-kit-vX.Y.Z-rc.N`.

Approve the `release` job as for a release, and check the deployment and the draft. Then remove every trace of it,
publishing neither: with immutable releases on, a published candidate would use up its tag name for good.

```shell
curl --fail --request DELETE --header "Authorization: Bearer $CENTRAL_TOKEN" \
  https://central.sonatype.com/api/v1/publisher/deployment/<deployment id>
gh release delete vX.Y.Z-rc.N --yes
git push origin :vX.Y.Z-rc.N :rc/delva-X.Y.Z-rc.N
```

The tag ruleset allows only repository administrators to create or delete `v*` and `cli-kit-v*` tags.

## By hand

If the workflow can't run, the same bundle can be built and uploaded locally. Put the signing key in
`~/.gradle/gradle.properties`, never in the repository:

```properties
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...
signingInMemoryKeyPassword=...
```

The staging directory keeps whatever earlier publishes put there, so the bundle starts from an empty one. For Delva
(for cli-kit, `stageCliKit` and `build/staging-repository/cli-kit`):

```shell
rm -rf build/staging-repository
./gradlew stageDelva
cd build/staging-repository/delva && zip -r ../../delva-bundle.zip . && cd -
curl --fail --request POST \
  --header "Authorization: Bearer $CENTRAL_TOKEN" \
  --form bundle=@build/delva-bundle.zip \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED"
```

Signing runs only for the stage task and only when the key is configured; without it the files are unsigned, which
the Portal rejects. A release made this way has no provenance attestation.

## Before every push

- No absolute paths from a development machine, and no e-mail address other than the public one, in the build files,
  tests, golden files or POM.
- `LICENSE` and `NOTICE` are current; both are packaged into every jar under `META-INF`.
