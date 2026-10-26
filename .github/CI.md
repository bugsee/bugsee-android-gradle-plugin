# CI/CD

All workflows run on GitHub-hosted `ubuntu-latest` runners, except the `windows` job of `pr.yml`
(`windows-latest`). Environments,
secrets and the release gate follow the same conventions as
`bugsee/bugsee-android`.

| Workflow | Trigger | Environment | `RELEASE` | What it does |
|---|---|---|---|---|
| `pr.yml` | PR → `main` / `release` | — | `false` | `scripts/build.sh --full` — unit + TestKit integration tests + Compose variant matrix |
| `codeql.yml` | PR / push `main`, `release`; weekly | — | — | CodeQL advanced setup: `java-kotlin` (manual `gradlew assemble -x test`), `actions`, `c-cpp`. Replaces the default setup, which must be turned off |
| `pr.yml` (`integration (gradle …, agp …, kotlin …)`) | PR → `main` / `release` | — | `false` | The TestKit suite on newer toolchains, one job per row of the `toolchain` matrix (Gradle 9.0.0 / AGP 8.13.2 / Kotlin 2.2.20, Gradle 9.8.0 / AGP 9.4.1 / Kotlin 2.3.20) |
| `pr.yml` (`windows`) | PR → `main` / `release` | — | `false` | On `windows-latest`: `CliBinaryResolverTest`, `CliUploaderTest`, and `BuildTypeGatingTest` + `ToolchainMatrixTest` as the TestKit smoke (pre-built plugin jar, no `clean`). Not run off Windows, so do not make it a required check until it has been seen green |
| `scheduled.yml` | weekly + manual | — | `false` | Compose variant matrix on the newest stable patch of each claimed Kotlin line (2.1 - 2.4) and the newest stable overall (`-Pbugsee.matrix.extraKotlin`); fails for a Kotlin line no variant claims, or when a lookup finds nothing |
| `deploy-staging.yml` | push to `main` | `staging` | `false` | `build.sh` + `deploy.sh` — `<version>-SNAPSHOT` to the Central snapshot repository, unsigned |
| `deploy-production.yml` | manual dispatch | `production` | `true` | `build.sh --full` + `deploy.sh` — signed release into a closed Central staging deployment |
| `claude.yml` | `@claude` mention | — | — | Claude answers on the issue or PR |

## Toolchain matrix

`integrationTest` takes three optional Gradle properties, all forwarded to the fixtures by
`FixtureProject` (unset = the declared minimums, Gradle 8.7 / AGP 8.6.0 / Kotlin 2.1.0):

```
./gradlew integrationTest -Pbugsee.it.gradleVersion=9.8.0 -Pbugsee.it.agpVersion=9.4.1 -Pbugsee.it.kotlinVersion=2.3.20
```

- `gradleVersion` runs the fixtures with `GradleRunner.withGradleVersion`. A non-default Gradle also
  switches the fixtures from the included-build plugin to a pre-built jar published into
  `build/integrationTest/plugin-repo`: the plugin's own Kotlin 2.1 compiler cannot compile it under
  the newest Gradle APIs, and the jar is what consumers get anyway.
- `agpVersion` / `kotlinVersion` rewrite the plugin versions in the fixture's root `build.gradle.kts`.
  AGP 9 compiles Kotlin itself, so for AGP >= 9 the fixtures set `android.builtInKotlin=false` and
  `android.newDsl=false` (temporary AGP opt-outs; they go away in AGP 10 and the fixtures then need a
  Kotlin-free variant).
- `ToolchainMatrixTest` asserts the build really ran on the requested versions (exact resolved
  version, so a request bumped by conflict resolution, `2.2.0 -> 2.2.10`, fails).
- The legs fetch the pinned `bugsee-cli` through `.github/actions/fetch-bugsee-cli` (shared with the
  `test` job) and fail if `NativeMergedLibsE2ETest` was skipped.
- `.gitattributes` pins `gradlew` and `*.sh` to LF (and `*.bat` to CRLF): Git for Windows would
  otherwise check them out with CRLF and bash would fail on `gradlew`.

**Cost.** Each leg is a full TestKit suite plus its own dependency downloads: the TestKit Gradle home
lives under `build/tmp/` (about 3.5 GB of caches), outside what `setup-gradle` caches, so it is
re-downloaded on every run and too large to cache usefully. The two legs run in parallel with the
`test` job, so wall time barely moves, but the minutes are billed. There is deliberately no
`paths-ignore` on `pr.yml`: a workflow skipped by path filters leaves its required checks pending
forever.

To add a row, add one line to `matrix.include` of the `toolchain` job in `pr.yml` and run that line
locally first; incompatible pairs (AGP 8.13 on Gradle 9.7+) fail inside AGP. The Compose compiler-plugin
rows live in `compose-compiler-plugin/build.gradle.kts` (`matrixKotlinVersions`) and
`ComposeVariantMatrixTest`.

## Releasing

1. Bump `version.txt` on `release` (and `sdk-min-version.txt` if the SDK floor moves).
2. Actions → **Deploy (production)** → *Run workflow* on `release`, typing the
   version from `version.txt` to confirm.
3. The run uploads and **closes** the Central deployment but does not release
   it. Release it at <https://central.sonatype.com> → Deployments.

## What is uploaded to Central

Central requires only `.md5` and `.sha1` beside each file, plus the `.asc` signature. Gradle 8.7
uploads four checksums for every file *and* for every `.asc`, which made a release 210 files.
Two measures bring it to **84** (plus 15 module-level `maven-metadata.xml` files):

- `systemProp.org.gradle.internal.publish.checksums.insecure=true` in `gradle.properties` drops
  the optional `.sha256` / `.sha512`.
- The sonatype publish tasks (`allprojects` hook in `build.gradle.kts`) stage into a local Maven
  directory, delete the `.asc.*` checksums and upload the rest with `buildSrc`'s
  `MavenSignatureChecksums`. Snapshot publishes first copy the remote `maven-metadata.xml` into
  the staging directory (otherwise Gradle would reset the shared snapshot metadata) and replace
  the metadata's `.sha256` / `.sha512`; release staging never uploads those.

Both mirror the Bugsee Android SDK. The hook needs `NEXUS_USERNAME` and fails the task rather than
fall back to Gradle's own upload, and it is incompatible with the configuration cache (so is the
nexus plugin). Gradle 9.7 stops emitting the signature checksums, after which the staging can be
removed. `MavenCentralChecksumPolicyTest` and `scripts/build.sh` (`-p buildSrc test`) guard it.

`deploy.sh` publishes the Gradle plugin, its marker and all three Compose
compiler-plugin artifacts together, in one deployment. They must ship together:
see the header of `scripts/deploy.sh`.

## Runners

`runs-on: ubuntu-latest` — GitHub-hosted, a clean VM per job, so no state
carries over between runs.

- **Android SDK** comes with the image (`ANDROID_SDK_ROOT` is preset, licenses
  accepted). The TestKit integration tests build fixture apps against it.
- **JDKs**: `actions/setup-java` installs Temurin 11 (`jvmToolchain(11)`) and 17
  (the TestKit launcher, and `JAVA_HOME`). The build has no toolchain resolver,
  so both must be installed up front.
- **Gradle** is set up by `gradle/actions/setup-gradle` with
  `cache-provider: basic` (open source, on the GitHub Actions cache; the default
  provider is a commercial service). Caches are written by `main` runs and
  restored everywhere else. The action also validates
  `gradle/wrapper/gradle-wrapper.jar` against Gradle's published checksums; a
  wrapper upgrade must commit the official jar or every run fails.
- **Test reports** are uploaded only when a PR run fails, kept 3 days. The org's
  Actions artifact storage is shared across every repo and capped.
- **Minutes**: this repository is private, so jobs spend the org's included
  Actions minutes. A full PR run is the expensive one.

## Environments

- `staging` — deployable only from `main`
- `production` — deployable only from `release`

That branch policy is the real production guard: required-reviewer approval is
not available on the current (free) plan. `deploy-production.yml` additionally
makes you type the version to confirm.

## Required configuration

Per-environment **secrets**:

| Name | `staging` | `production` | Purpose |
|---|---|---|---|
| `NEXUS_USERNAME` / `NEXUS_PASSWORD` | ✓ | ✓ | Central Portal user token. Reach Gradle as `ORG_GRADLE_PROJECT_*`, which `build.gradle.kts` already reads. |
| `SIGNING_KEY` | — | ✓ | ASCII-armored PGP **private** key block |
| `SIGNING_PASSWORD` | — | ✓ | passphrase for that key (`signing.password`) |

Staging has no signing secrets because SNAPSHOT builds are never signed.

**Organization** secret:

| Name | Purpose |
|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | Used by `claude.yml`. Inherited from the `bugsee` org secret (visibility: all repositories). Do not add a repository-level copy: it would shadow the org value and go stale when the org token is rotated. |

### What goes in `SIGNING_KEY`

The ASCII-armored form of the keyring that `signing.secretKeyRingFile` points at
— not the path, and not the raw `.gpg` bytes. Armoring is only an encoding
(base64 + CRC24), so it needs no passphrase and no `gpg`. With `gpg`:

```
gpg --armor --export-secret-keys F9AB1FCF | gh secret set SIGNING_KEY --env production --repo bugsee/bugsee-android-gradle-plugin
```

The keyring holds the master key `F9AB1FCF` and subkey `4CFA136C`.
`useInMemoryPgpKeys` takes the first secret key in the block, the master, which
is the key `signing.keyId` selects — the same key `bugsee-android` signs with.

### Why signing needed a build change

Sonatype credentials are plain Gradle properties, so environment variables carry
them as-is. Signing is different: `signing.secretKeyRingFile` is a filesystem
path. Both `build.gradle.kts` and `compose-compiler-plugin/build.gradle.kts`
therefore call `useInMemoryPgpKeys` when `SIGNING_KEY` is set, and otherwise fall
back to the on-disk keyring, so local `./scripts/deploy.sh` runs are unchanged.
Sign tasks are enabled for non-SNAPSHOT builds when either source of a key is
present.

## Known gaps

- **The real-binary uploader lane is not in CI.** `scripts/integration-test.sh`
  builds `bugsee-cli` from a sibling checkout with `cargo`, and the workflows
  provision neither. `CliUploaderRealBinaryTest` `Assume`-skips without a
  binary, so CI is green but does not exercise it.
- **`release-3`** (the 3.x line, last at 3.6) has no workflows. Run
  `scripts/deploy.sh` locally if it ever needs another release.
