# Per-variant settings in `bugsee.properties` — design

Issue: bugsee/bugsee-android-gradle-plugin#35 ("Support a per-flavor/per-variant app token for uploads").

## Intent and why

Apps with per-flavor or per-environment Bugsee applications (e.g. `staging` and
`production` flavors) cannot point each variant's uploads at its own app token
from `bugsee.properties`: the file holds one root `app_token`, so every variant
uploads to the same Bugsee application. The DSL already allows per-variant
tokens (`appTokenByVariant`, `AppTokenProvider`) but only from Gradle code.

Wrappers that upload their own artifacts (React Native source maps) must read
the same per-variant token, so the lookup order has to be simple and documented
so wrappers can mirror it.

A staging flavor that has its own Bugsee app very likely also wants a different
`endpoint`, and other settings differ by variant too. So we generalise: any
setting that is meaningful per variant can be overridden per variant from
`bugsee.properties`, with one uniform syntax.

## Goals

- Per-variant values for the app token and for every setting listed under
  "Per-variant settings" below, from both `bugsee.properties` and the
  `bugsee { }` DSL, with the same set of settings and the same lookup order in
  both.
- One uniform key syntax in the file: `variant.<name>.<existing key>`; one
  uniform DSL form: `variant("<name>") { … }`.
- A setting given without a variant applies globally, and is the fallback for a
  variant that has no value of its own.
- A documented, deterministic lookup order that external wrappers can mirror.
- One precedence rule per build, no mixed rules (see "Precedence").
- Existing builds behave identically when no `variant.*` key is present.

## Non-goals

- Per-variant values for global settings (see "Global-only settings").
- Removing or changing the existing `appTokenByVariant` / `AppTokenProvider`
  DSL forms; they stay as they are.
- Separate per-flavor files (`bugsee-<flavor>.properties`). One file only.
- Changing library-variant behaviour: the plugin still wires application
  variants only.

## Anti-goals (a failure even if it technically works)

- A user whose DSL sets a value gets surprising behaviour from a file key with
  no explanation.
- A per-variant `ndk.enabled` / instrumentation setting leaves the build
  half-configured (e.g. native-crash runtime dependency present but no upload
  task, or manifest providers stripped with no compensating bytecode).
- Configuration-cache regressions: the file must stay a configuration-cache
  input and variant resolution must not read project state at execution time.

## Key syntax

Existing keys are unchanged:

- `app_token=…` (unprefixed; the root token)
- `plugin.<path>=…` (root settings, e.g. `plugin.endpoint`)

New: prefix any supported existing key with `variant.<name>.`:

```
app_token=PROD_TOKEN
plugin.endpoint=https://api.bugsee.com

variant.staging.app_token=STAGING_TOKEN
variant.staging.plugin.endpoint=https://staging.example.com
variant.release.plugin.buildInfo.sizeCheck.enabled=true
variant.stagingDebug.plugin.instrumentation.enabled=false
```

`<name>` is a variant name, a flavor name, or a build type name, exactly as
Gradle/AGP spell them (e.g. `stagingRelease`, `staging`, `release`).

## Lookup order

For a given variant and key, the first match wins:

1. `variant.<variantName>.<key>` (e.g. `stagingRelease`)
2. `variant.<flavorName>.<key>` for each product flavor of the variant
3. `variant.<buildType>.<key>`
4. the root key (`<key>`, or `plugin.<path>` for plugin settings)
5. the built-in default (or the next source, e.g. the manifest meta-data for
   the token)

This order is the contract wrappers mirror; document it in the README.

Multi-dimension flavors: the order among several flavors of one variant is the
builder's call (suggested: AGP flavor-dimension order), but it must be
documented and deterministic.

## DSL form

```kotlin
bugsee {
    endpoint.set("https://api.bugsee.com")          // global / fallback
    appToken("PROD_TOKEN")

    variant("staging") {                             // variant, flavor or build type name
        appToken("STAGING_TOKEN")
        endpoint.set("https://staging.example.com")
        buildInfo { sizeCheck { enabled.set(true) } }
    }
    variant("release") {
        instrumentation { enabled.set(false) }
    }
}
```

- A `variant("<name>") { }` block exposes only the settings under "Per-variant
  settings". Global-only settings are not available inside it (compile error,
  stricter than the file's warn-and-ignore). This holds in the Kotlin DSL via a
  `@BugseeDsl` `@DslMarker`; in Groovy a global-only setting named inside a block
  resolves to the outer extension and silently hits the global value.
  `instrumentation.excludes` is global-only and ignored inside a block.
- `<name>` is matched like the file's `variant.<name>`: variant name, flavor
  name or build type name, case-sensitive, using the same lookup order.
- Settings set outside any `variant` block are global values and the fallback
  when a variant has no value of its own.
- Calling `variant("x")` twice merges into one block.
- Within a variant block, an unset property means "fall through", so these
  properties carry no built-in default convention; the built-in default is
  applied only at the end of the chain.
- Existing `appToken { variantName -> }` and `AppTokenProvider` keep working and
  sit in the DSL layer of the token chain (see "Token chain").

## Precedence (DSL vs file)

Resolution has two sources, each with the same inner lookup order:

- DSL: `variant("<variant>")`, then flavor(s), then build type, then the global
  DSL value.
- File: `variant.<variant>.*`, then flavor(s), then build type, then the root
  key.

Default, and no exceptions:

    DSL (variant lookup, then global)  >  bugsee.properties (variant lookup, then root)  >  built-in default

So a source is consulted as a whole: if the DSL has any value for the setting
(variant-specific or global), the file never touches it. A global DSL value
therefore beats a file `variant.*` key. To use per-variant values from the file,
the user omits that setting from the DSL.

Opt-in switch, DSL-only:

```kotlin
bugsee { preferSettingsFile.set(true) }
```

When true, the order for the whole build becomes:

    bugsee.properties (variant lookup, then root)  >  DSL (variant lookup, then global)  >  built-in default

It applies to the whole file (root keys and `variant.*` keys alike), so a build
has exactly one rule. It also governs the token: with `preferSettingsFile`, a
token found in the file beats `appTokenByVariant`, `AppTokenProvider` and
`defaultAppToken`. The switch is DSL-only because the file cannot carry the
setting that governs the file's own precedence.

Default is `false`.

### Shadow warning

When a `variant.<name>.*` file key (a setting or `variant.<name>.app_token`) is
ignored because the DSL already set that value, in default mode only, log a
warning once per key, e.g.:

> Bugsee: variant.staging.plugin.endpoint ignored: endpoint is set in the DSL
> (set preferSettingsFile to let the settings file win).

No warning is logged when a ROOT file key (`plugin.<key>`, `app_token`) is
shadowed by the DSL: that is the long-standing documented precedence and
warning would add noise for existing builds.

### Technical risk (settled)

Gradle's `Property<T>` cannot tell whether a value came from `.set()` or from a
convention. Both the shadow warning and the file-wins mode need that
distinction. Shipped technique (`ExplicitSetProbe`): clear the property's
convention, test `isPresent`, restore the resolved value as the convention. It
replaced the originally sketched recording-convention provider, which Gradle
rejects (`ProviderInternal` cast). It flattens conventions, so it is only valid
after DSL evaluation.

## Per-variant settings

Allowed under `variant.<name>.`:

- `app_token`
- `plugin.endpoint`, `plugin.debug`, `plugin.optimizeExtensionsLoading`
- `plugin.ndk.enabled`, `plugin.ndk.forceDebugSymbolsUpload`,
  `plugin.ndk.useMergedNativeLibs`
- `plugin.buildInfo.*` (`enabled`, `allBuildTypes`, `sizeAnalysis.*`,
  `sizeCheck.*`, `dependencies.*`, `timings.enabled`)
- `plugin.instrumentation.*` (all toggles and `startupTier`)

### Global-only settings

`plugin.feedback`, `plugin.leak.enabled`, `plugin.sdkAutoLoad`,
`plugin.cliPath`, `plugin.cliVersion`, `plugin.cliAutoUpdate`,
`plugin.uploader`. A `variant.*` prefix on these is ignored with a warning (they
add dependencies to project-wide configurations or choose one shared CLI
binary). Unknown `variant.*` keys are logged at `--info`, like unknown
`plugin.*` keys today.

## Behaviour that needs care

These come from the current code (`BugseePlugin.kt`):

1. **`ndk.enabled` does two jobs.** It adds the NDK runtime dependency to the
   project-wide `implementation` configuration (apply time, global, ~line 163)
   and registers the native-symbol upload task (per variant, ~line 312).
   Per-variant means the upload task only. Builder's call, suggested: add the
   NDK runtime dependency when NDK is enabled globally or in any `variant.*`
   overlay.
2. **`instrumentation.*` splits the same way.** Auto-adding the okhttp /
   compose / ktor / cronet modules is global; the bytecode transform is per
   variant. The same applies to the module auto-add (suggested: add the module
   if any variant needs it).
3. **Manifest/bytecode consistency.** The manifest task strips extension
   `<provider>` entries and the compensating `ExtensionsInit` bytecode
   injection must key on the same resolved per-variant
   `instrumentation.enabled` value (see ~lines 262-275). Both must read the
   same resolved view.
4. **Variant-level consumers** (manifest task, mapping/native/bundle upload
   tasks, instrumentation registration, build-info registration, size/dependency
   wiring) read the per-variant resolved view instead of the global extension.

## Token chain

Default mode (DSL wins):

1. `appTokenByVariant` closure (a `null`/empty return falls through)
2. `AppTokenProvider`
3. DSL `variant("…") { appToken(…) }` per the lookup order, then the global
   `defaultAppToken`
4. `bugsee.properties`: `variant.*.app_token` per the lookup order, then the
   root `app_token`
5. manifest `com.bugsee.android.APP_TOKEN` meta-data

With `preferSettingsFile`: the file (step 4) moves to the front; the rest keep
their order.

The token shadow warning fires only when a DSL source returned a non-null value
for that variant and a `variant.<name>.app_token` file key exists for it; a
shadowed root `app_token` does not warn. The `appTokenByVariant` / `AppTokenProvider`
chain is unchanged.

## Calls made while writing this (author's decisions, not the user's)

- `plugin.buildInfo.*` means every leaf currently bound under `buildInfo` in the
  applier's binding registry, with the variant-level consumers
  (`BugseePlugin.kt` ~787/857) reading the resolved view. `allBuildTypes` is a
  variant gate (~1104) and reads the resolved view too. The build-timings
  listener registration is single-flight and build-wide: it keeps collecting
  regardless; `timings.enabled` only controls whether a variant's task uploads
  the timings. The builder should verify this against
  `BuildTimingListenerRegistration.kt` and, if the listener is itself gated,
  make `timings.enabled` global-only.
- `plugin.debug`: a per-variant value affects variant-level logging only.
  Apply-time and global reads use the root value.
- Dependency auto-add for ndk/okhttp/compose/ktor/cronet is decided before
  variants exist, so use the conservative rule: add the module if it is enabled
  globally or any `variant.*` key enables it. A variant that disabled the
  feature may then carry an unused runtime module; that is acceptable. The
  per-variant upload task and bytecode transform stay exact. Known limitation:
  the Compose Kotlin compiler-plugin gate is project-level and uses the same
  any-scope rule, so if one variant enables compose every Kotlin compilation
  gets the compiler plugin, including variants that disabled it
  (variant-exactness would need a compilation-to-variant mapping; not done).
- Value fixing: values from `variant.*` file keys (and file root values when
  `preferSettingsFile` ranks them above the DSL) are fixed when variants are
  configured; values resolved from the DSL stay live, so a late
  `afterEvaluate { bugsee { endpoint.set(..) } }` still reaches the tasks when
  no `variant.*` input outranks it.
- With `plugin.debug` true for a variant the plugin logs
  `Bugsee: variant <name> resolved: token=<masked or none> endpoint=<url>`.
- The empty `app_token=` warning appears once when the file is parsed.
- Precedence is a hard requirement; the shadow warning is best-effort. If the
  spike shows explicit-DSL-set detection is infeasible on the existing
  `Property` objects, the builder may route DSL values through a shadow
  mechanism to keep precedence exact, and may degrade the warning (e.g. warn
  only when detection is possible) rather than weaken precedence.
- `variant.<name>` matches case-sensitively against AGP's exact spelling. A name
  shared by a flavor and a build type is covered by the documented order.

## Constraints

- Kotlin 2.1, Java 11 target, AGP 8.6 `compileOnly`, Gradle 8.7.
- The file stays a configuration-cache input (`providers.fileContents`).
- Reuse the existing type coercers (bool/int/long/double/string/enum) and error
  handling (malformed value: warn, fall through).
- Application variants only (library variants are skipped as today).
- Verification: unit tests (`./gradlew :test`) for the pure resolver and file
  logic, TestKit integration tests (`./gradlew :integrationTest`) with a
  two-flavor app, plus build and publishToMavenLocal.

## Mechanism (the author's guess, not agreed)

Keep the global extension tree. Add a per-variant DSL type holding the
per-variant-capable subset (properties without default conventions), collected
by name in the extension. Add a per-variant resolved view built from the
extension (global + variant DSL blocks), the parsed file, and the variant's
name/flavors/build type. Wire the variant-level code to it. Reuse the applier's
binding registry so the set of per-variant keys is declared once and drives the
DSL type, the file keys and the resolved view.

## Documentation

- README: new "Per-variant settings" section with the key syntax, the DSL
  `variant { }` form, lookup order,
  per-variant vs global-only tables, the precedence rule, `preferSettingsFile`,
  and a wrapper-facing summary of the token lookup (the part wrappers mirror).
- Update `.claude/CLAUDE.md` / `AGENTS.md` (App Token Resolution and DSL
  sections).
- Bump nothing else; this is a plugin-only change.

## Left to the builder

- Multi-dimension flavor ordering (documented, deterministic).
- Exact shape of the resolved-view type and where it is built.
- How to detect explicit DSL sets (the spike).
- Warning wording and throttling.
- Test strategy beyond what is listed above.
