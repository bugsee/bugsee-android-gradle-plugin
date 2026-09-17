# CI/CD

All workflows run on GitHub-hosted `ubuntu-latest` runners. Environments,
secrets and the release gate follow the same conventions as
`bugsee/bugsee-android`.

| Workflow | Trigger | Environment | `RELEASE` | What it does |
|---|---|---|---|---|
| `pr.yml` | PR → `main` / `release` | — | `false` | `scripts/build.sh --full` — unit + TestKit integration tests + Compose variant matrix |
| `deploy-staging.yml` | push to `main` | `staging` | `false` | `build.sh` + `deploy.sh` — `<version>-SNAPSHOT` to the Central snapshot repository, unsigned |
| `deploy-production.yml` | manual dispatch | `production` | `true` | `build.sh --full` + `deploy.sh` — signed release into a closed Central staging deployment |
| `claude-code-review.yml` | every PR | — | — | Claude reviews the diff and posts inline findings plus one summary comment |
| `claude.yml` | `@claude` mention | — | — | Claude answers on the issue or PR |

## Releasing

1. Bump `version.txt` on `release` (and `sdk-min-version.txt` if the SDK floor moves).
2. Actions → **Deploy (production)** → *Run workflow* on `release`, typing the
   version from `version.txt` to confirm.
3. The run uploads and **closes** the Central deployment but does not release
   it. Release it at <https://central.sonatype.com> → Deployments.

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

Repository **secret**:

| Name | Purpose |
|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | Used by the two Claude workflows. The org-level secret of the same name does not reach private repositories on the free plan, so it must be set here. Create one with `claude setup-token`. |

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
