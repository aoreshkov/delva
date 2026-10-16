# Releasing

Delva is published to Maven Central through the [Central Portal](https://central.sonatype.com). Pushing a version tag
runs [`release.yml`](../.github/workflows/release.yml), which tests, signs, attests, uploads and drafts the GitHub
release; nothing in the repository holds keys or credentials.

## One-time setup

1. **Namespace**: the `app.oreshkov` namespace has to be verified in the Central Portal (it is verified by proving
   control of `oreshkov.app`). Until it is, uploads fail with an authorization error. It covers both groups a release
   publishes: `app.oreshkov.delva` for delva and delva-kotlinx-serialization, and `app.oreshkov.clikit` for cli-kit
   and cli-kit-testing.
2. **Signing key**: a PGP key used only to sign Delva releases, with an expiry date. It signs with its primary key and has
   no signing subkey, because Maven Central checks signatures against primary keys. Its public half is on
   `keyserver.ubuntu.com`, and also on `keys.openpgp.org` with its e-mail address confirmed, since that server leaves
   out the user ID until then. Keep the revocation certificate offline.
3. **Portal token**: a user token from the Central Portal account page, as the Base64 of `username:password`. Tokens
   expire on the date chosen when they are made, so replace the secret before then.
4. **GitHub environment** `release` (Settings → Environments), with yourself as required reviewer and `v*` as the only
   deployment tag, holding the secrets:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | the ASCII-armored private key |
   | `SIGNING_KEY_PASSWORD` | its passphrase |
   | `CENTRAL_TOKEN` | the Portal token |

5. **Immutable releases** on (Settings → General → Releases), so a published release's tag and assets can't change.
   This protects only releases published while the setting is on, and a tag name that belonged to a published
   release can never be used again.
6. **GitHub Pages** with *GitHub Actions* as its source (Settings → Pages), and the `github-pages` environment limited
   to `v*` tags: `release.yml` publishes the KDoc of every release there.

## Cutting a release

1. `gradle.properties`: set `version` to the release version (no `-SNAPSHOT`).
2. `CHANGELOG.md`: move the `Unreleased` entries under a new `## [X.Y.Z] - YYYY-MM-DD` heading dated the day you
   tag, leaving `## [Unreleased]` empty above it, and point the link references at the bottom at the tag
   (`[X.Y.Z]: …/releases/tag/vX.Y.Z`, `[Unreleased]: …/compare/vX.Y.Z...HEAD`).
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
   git tag -s vX.Y.Z -m "Delva X.Y.Z"
   git push origin vX.Y.Z
   ```

5. `release.yml` runs every suite, including `heapCappedTest` and `crashTest`, on Linux and Windows. The `release` job
   then waits for your approval. Once approved, it checks that the tag matches `version`, builds the signed bundle
   of delva, delva-kotlinx-serialization, cli-kit and cli-kit-testing, builds the command line's archives
   (`delva-cli-X.Y.Z.zip` and `.tar`, checked to hold only the start scripts, jars, licences and README) with their
   `SHA256SUMS`, and attests the build provenance of the jars, POMs, module files and archives. It uploads the bundle
   to the Portal and waits until the Portal has validated it, failing with the Portal's errors if validation fails;
   the run's summary names the deployment. Only then does it draft the GitHub release, with the jars of delva and
   delva-kotlinx-serialization and the archives attached. A version with a hyphen is drafted as a pre-release.
6. In the Portal, check the validated deployment and **publish** it. Wait until it shows *Published*, so the release
   never points at coordinates that don't resolve yet.
7. On GitHub, review the draft release and **publish** it. Its tag and assets can't change after that.
8. In a second pull request, set `version` to the next `-SNAPSHOT` in `gradle.properties`.

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
throwaway branch, so `main` never holds its version:

```shell
git switch -c rc/X.Y.Z-rc.N main
# gradle.properties: version=X.Y.Z-rc.N; CHANGELOG.md: a "## [X.Y.Z-rc.N] - YYYY-MM-DD" section with one line
git commit -S -am "Release candidate X.Y.Z-rc.N"
git push -u origin rc/X.Y.Z-rc.N
git tag -s vX.Y.Z-rc.N -m "Delva X.Y.Z-rc.N" && git push origin vX.Y.Z-rc.N
```

Approve the `release` job as for a release, and check the deployment and the draft. Then remove every trace of it,
publishing neither: with immutable releases on, a published candidate would use up its tag name for good.

```shell
curl --fail --request DELETE --header "Authorization: Bearer $CENTRAL_TOKEN" \
  https://central.sonatype.com/api/v1/publisher/deployment/<deployment id>
gh release delete vX.Y.Z-rc.N --yes
git push origin :vX.Y.Z-rc.N :rc/X.Y.Z-rc.N
```

The tag ruleset allows only repository administrators to create or delete `v*` tags.

## By hand

If the workflow can't run, the same bundle can be built and uploaded locally. Put the signing key in
`~/.gradle/gradle.properties`, never in the repository:

```properties
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...
signingInMemoryKeyPassword=...
```

The staging directory keeps whatever earlier publishes put there, so the bundle starts from an empty one:

```shell
rm -rf build/staging-repository
./gradlew :delva:publishMavenPublicationToStagingRepository \
  :delva-kotlinx-serialization:publishMavenPublicationToStagingRepository \
  :cli-kit:publishAllPublicationsToStagingRepository :cli-kit-testing:publishAllPublicationsToStagingRepository
cd build/staging-repository && zip -r ../delva-bundle.zip . && cd -
curl --fail --request POST \
  --header "Authorization: Bearer $CENTRAL_TOKEN" \
  --form bundle=@build/delva-bundle.zip \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED"
```

Signing runs only for that task and only when the key is configured; without it the files are unsigned, which the
Portal rejects. A release made this way has no provenance attestation.

## Before every push

- No absolute paths from a development machine, and no e-mail address other than the public one, in the build files,
  tests, golden files or POM.
- `LICENSE` and `NOTICE` are current; both are packaged into every jar under `META-INF`.
