# Bugsee Android Gradle Plugin

Configuration reference for the Bugsee Android Gradle plugin.

## Compatibility

Minimums: **AGP 8.6.0**, **Gradle 8.7**, Bugsee Android SDK **7.x**. The public
docs have the full
[requirements & compatibility page](https://docs.bugsee.com/sdk/android/gradle-plugin/requirements).

### Gradle 9

Gradle 9 is supported from plugin **4.0.3**.

| Plugin | Gradle 8.x | Gradle 9.x |
|---|---|---|
| 4.0.0 – 4.0.2 | ✅ | ❌ |
| **4.0.3 and newer** | ✅ | ✅ |

Plugin 4.0.0 – 4.0.2 call `ProjectDependency.getDependencyProject()`, which
Gradle 9.0 removed. On Gradle 9, the consumer's build fails during configuration:

```text
A problem occurred configuring project ':app'.
> Failed to notify project evaluation listener.
   > 'org.gradle.api.Project org.gradle.api.artifacts.ProjectDependency.getDependencyProject()'
```

`ProjectDependencyCompat` fixes this in 4.0.3 by resolving the dependency's
project reflectively (commit `2066da5`).

### Plugin order

The order of plugins in `plugins {}` doesn't matter from 4.0.9; earlier versions silently
did nothing when Bugsee was listed before the Android plugin (#46).

### Flutter

Flutter apps need no workaround. Flutter's Gradle plugin writes into AGP's
merged-assets directory from `copyFlutterAssets<Variant>`; when that task exists
the plugin makes `injectBugsee<Variant>BuildId` depend on it, so Gradle task
validation passes. Apps without Flutter are unaffected.

If a Flutter release renames that task, the build fails with "uses this output of
task ... without declaring an explicit or implicit dependency". The plugin warns
when Flutter is applied but finds no `copyFlutterAssets<Variant>` task for any
variant. Until the plugin catches up, name the task yourself. `<Variant>` is
replaced by the capitalized variant name; the same setting orders the injection
after any other plugin that writes the merged assets:

```kotlin
bugsee {
    assetsDependsOn("flutterCopyAssets<Variant>")
}
```

A name that matches no task is reported with a warning and ignored. The setting
is DSL-only (not available in `bugsee.properties`).

### Isolated Projects

The plugin does not read other projects' models, so it is not itself a source of
Isolated Projects violations (`org.gradle.unsafe.isolated-projects=true`).
Cross-module state (once-only warnings, variant names) lives in a shared build
service, `bugsee.properties` is located through the build's root directory, and
the `bugsee.instrumentation.*` Gradle properties are read from the module's own
properties (`-P`, `gradle.properties`, the module's `ext`).

Two behaviours differ when the mode is on; with it off nothing changes:

- **Gradle properties are not inherited from a parent project's `ext`.** A root
  build script's `extra["bugsee.instrumentation.log"] = false` stops reaching the
  modules; put it in `gradle.properties`, pass `-P`, or use `bugsee { ... }` /
  `bugsee.properties`.
- **Project dependencies are not inspected.** Without Isolated Projects the plugin
  follows `project(":lib")` dependencies to find the Bugsee SDK declared there.
  With it, only the module's own dependencies are seen. If `com.bugsee:bugsee-android`
  is declared only in a library module (or the SDK itself is a source project
  dependency), the application module does not see it: instrumentations that need
  the SDK are skipped when `sdkAutoLoad` is off, and with `sdkAutoLoad` on the SDK
  may be auto-added a second time (Gradle's conflict resolution picks one version).
  With `sdkAutoLoad` off the plugin logs a warning once per module when the SDK is
  not found in the module itself. Declare `com.bugsee:bugsee-android` directly in
  the application module.

### Verified combinations

Each combination below was built with plugin **4.0.7** as a minified release
build (R8), checking bytecode instrumentation, `BUILD_UUID` manifest injection
and the mapping upload. Plugin 4.0.3 – 4.0.6 were also verified on Gradle 9.8.0
with AGP 9.4.1. Verified September 2026.

| Gradle | AGP | Configuration cache | Result |
|---|---|---|---|
| 9.8.0 | 9.4.1 | ✅ | ✅ Also verified on a multi-module app (library module, product flavors, Compose, NDK) |
| 9.7.0 | 9.4.1 | ✅ | ✅ |
| 9.1.0 | 9.0.1 | Not tested | ✅ |
| 9.0.0 | 8.13.2 | ✅ | ✅ |
| 8.14.3 | 8.13.2 | Not tested | ✅ |

Known pitfalls that are not caused by this plugin:

- **AGP and Gradle must be compatible with each other.** For example, AGP 8.13
  fails inside AGP on Gradle 9.7. See Google's
  [AGP–Gradle compatibility table](https://developer.android.com/build/releases/about-agp#updating-gradle).
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android` in
  an AGP 9 module fails with `Cannot add extension with name 'kotlin'`, whether
  or not the Bugsee plugin is applied.

## Configuration sources

Plugin behavior can be configured from two sources, with the
following **precedence** (highest to lowest):

1. **`bugsee { … }` DSL** in your `build.gradle` / `build.gradle.kts`.
2. **`<rootProject>/bugsee.properties`** file — `plugin.*` keys.
3. **Built-in defaults** baked into the plugin.

Any value set in the DSL overrides the same value set in
`bugsee.properties`, and any value in `bugsee.properties` overrides
the plugin's built-in default. Internally this is implemented via
Gradle's `Property.convention(…)` semantics — the properties layer is
applied as a convention before your DSL block runs, and your `.set(…)`
calls in the DSL supersede it.

Per-variant values (`variant.<name>.*` keys and `variant("<name>") { }` blocks)
follow the same precedence, and `bugsee { preferSettingsFile.set(true) }` flips
it for the whole build. See [Per-variant settings](#per-variant-settings).

## `bugsee.properties`

A single `bugsee.properties` file at the **root project** directory
configures both the app token (used by `AppTokenResolver`) and the
plugin itself. The two surfaces share the file but live under
distinct namespaces:

| Namespace | Owner | Example |
|---|---|---|
| (unprefixed) | App-token resolution | `app_token=YOUR_APP_TOKEN` |
| `plugin.*` | Gradle plugin behavior | `plugin.debug=true` |
| `variant.<name>.*` | Per-variant `app_token` / `plugin.*` | `variant.staging.app_token=STAGING_TOKEN` |

The file is **optional**. Keys not present in the file fall through
to the next source (DSL, then defaults).

### Why `bugsee.properties` instead of the DSL?

The DSL is the right surface when configuration is the same for all
builds of an app. `bugsee.properties` is the right surface when
configuration varies by **CI environment** without touching the build
script — e.g. enabling debug logging on a per-CI-runner basis,
flipping size-analysis on for release builds only via an env-templated
file, or sharing config across multiple modules in a multi-module
build.

### File location

The plugin reads `<rootProject>/bugsee.properties` — i.e. the
top-level directory of your Gradle build, NOT each sub-project's
directory. In a multi-module build, configuration lives in one place.

### Configuration cache

The file is registered as a configuration-cache input via
`providers.fileContents(...)`. Editing `bugsee.properties` invalidates
the CC entry and triggers a re-load on the next build. No manual
`--no-configuration-cache` flag needed.

## `plugin.*` key reference

Each `plugin.<path>` key corresponds to a `Property<T>` on the
plugin's DSL extension tree. Key paths use the same `camelCase` /
dotted-path form as the DSL field names.

### Root options

| Key | Type | Default | DSL equivalent |
|---|---|---|---|
| `plugin.endpoint` | String | `https://api.bugsee.com` | `bugsee { endpoint.set(…) }` |
| `plugin.debug` | Boolean | `false` | `bugsee { debug.set(…) }` |
| `plugin.feedback` | Boolean | `false` | `bugsee { feedback.set(…) }` |
| `plugin.sdkAutoLoad` | Boolean | `true` | `bugsee { sdkAutoLoad.set(…) }` |
| `plugin.optimizeExtensionsLoading` | Boolean | `true` | `bugsee { optimizeExtensionsLoading.set(…) }` |

### NDK integration

| Key | Type | Default |
|---|---|---|
| `plugin.ndk.enabled` | Boolean | `false` |
| `plugin.ndk.forceDebugSymbolsUpload` | Boolean | `false` |
| `plugin.ndk.useMergedNativeLibs` | Boolean | `true` |

With `ndk.enabled`, native symbols are uploaded from the **unstripped libraries**
in `build/intermediates/merged_native_libs/<variant>` (keyed by GNU build-id,
needs bugsee-cli 0.8.0+), so file:line frames no longer depend on
`android.defaultConfig.ndk.debugSymbolLevel` and the app's AAB does not grow.
Unchanged prebuilt libraries are deduplicated server-side. Set
`useMergedNativeLibs` to `false` to use only AGP's `native-debug-symbols.zip`.
A library that an earlier release uploaded only as SYMBOL_TABLE (function names)
is upgraded to the unstripped copy automatically (bugsee-cli 0.8.1+); the server
never replaces debug info with a poorer file, so no `forceDebugSymbolsUpload` is needed.

### Leak detection

When `enabled`, the plugin automatically adds the `bugsee-android-leak`
module dependency (memory/thread leak detection). If the app already
declares the leak module explicitly, this is a no-op — mirroring the
`ndk.enabled` behaviour. With `sdkAutoLoad=false` nothing is added, so
`leak.enabled` (like `feedback` and the `ndk.enabled` runtime) only works if you
declare the module yourself; the plugin warns otherwise.

| Key | Type | Default |
|---|---|---|
| `plugin.leak.enabled` | Boolean | `false` |

### Build info

| Key | Type | Default |
|---|---|---|
| `plugin.buildInfo.enabled` | Boolean | `true` |
| `plugin.buildInfo.allBuildTypes` | Boolean | `false` |

### Build info — size analysis

| Key | Type | Default |
|---|---|---|
| `plugin.buildInfo.sizeAnalysis.enabled` | Boolean | `false` |
| `plugin.buildInfo.sizeAnalysis.buildConfiguration` | String | unset; falls back to the Gradle variant name |
| `plugin.buildInfo.sizeAnalysis.chunkedUpload` | Boolean | `false` |

### Build info — size check

| Key | Type | Default |
|---|---|---|
| `plugin.buildInfo.sizeCheck.enabled` | Boolean | unset — gate disabled |
| `plugin.buildInfo.sizeCheck.warningPercent` | Double | unset — threshold disabled |
| `plugin.buildInfo.sizeCheck.failPercent` | Double | unset — threshold disabled |
| `plugin.buildInfo.sizeCheck.warningBytes` | Long (bytes) | unset — threshold disabled |
| `plugin.buildInfo.sizeCheck.failBytes` | Long (bytes) | unset — threshold disabled |

### Build info — dependency collection

| Key | Type | Default |
|---|---|---|
| `plugin.buildInfo.dependencies.enabled` | Boolean | `true` |
| `plugin.buildInfo.dependencies.scope` | String | `runtime` |
| `plugin.buildInfo.dependencies.includeSelectedReason` | Boolean | `false` |
| `plugin.buildInfo.dependencies.maxCount` | Int | `5000` |

### Build info — timings

| Key | Type | Default |
|---|---|---|
| `plugin.buildInfo.timings.enabled` | Boolean | `true` |

### Bytecode instrumentation

| Key | Type | Default | Notes |
|---|---|---|---|
| `plugin.instrumentation.enabled` | Boolean | `true` | Global switch |
| `plugin.instrumentation.okhttp` | Boolean | `true` | |
| `plugin.instrumentation.httpEngine` | Boolean | `true` | Cronet |
| `plugin.instrumentation.log` | Boolean | `true` | `android.util.Log` redirect |
| `plugin.instrumentation.thread` | Boolean | `true` | |
| `plugin.instrumentation.mainThreadMisuse` | Boolean | `true` | |
| `plugin.instrumentation.operationDispatch` | Boolean | `true` | |
| `plugin.instrumentation.compose` | Boolean | `true` | Compose tag injection |
| `plugin.instrumentation.composeSecure` | Boolean | `true` | Compose secure-field auto-detect |
| `plugin.instrumentation.composeInput` | Boolean | `true` | |
| `plugin.instrumentation.ktor` | Boolean | `true` | |
| `plugin.instrumentation.cronet` | Boolean | `true` | |
| `plugin.instrumentation.startupTier` | Enum (`OFF`, `MINIMAL`, `STANDARD`, `DETAILED`, `FULL`) | `STANDARD`¹ | Case-insensitive |

¹ The defaults in this table — including every boolean instrumentation
flag and `startupTier` — are supplied at resolution time by
`InstrumentationConfigResolver`, NOT as `Property.convention(…)` on
the DSL extension. That is the load-bearing detail that makes the
chain bypass below possible: with no convention on the property,
`Property.isPresent` is `false` until something (DSL `.set(…)` OR the
properties applier OR a future binding) populates it. See the chain
note immediately below.

> **Instrumentation flags have a deeper resolution chain than other
> options.** Boolean instrumentation flags AND `startupTier` resolve
> in this order at task-configuration time:
>
> 1. DSL `.set(…)` in `bugsee { instrumentation { … } }`
> 2. `plugin.instrumentation.X` in `bugsee.properties`
> 3. Legacy Gradle property `bugsee.instrumentation.X`
> 4. Manifest `<meta-data android:name="com.bugsee.android.instrumentation.X" />`
> 5. Built-in default (`true` for booleans; `STANDARD` for startupTier)
>
> Setting an instrumentation flag in `bugsee.properties` makes the
> underlying DSL `Property` "present" (`isPresent == true`), and
> `InstrumentationConfigResolver` short-circuits at step 1 — so
> `plugin.*` BYPASSES the legacy Gradle-property and manifest-meta-data
> fallbacks for that key. The user's DSL `.set(…)` still wins over both
> sources. The simpler "DSL > properties > default" chain documented at
> the top of this file applies to every NON-instrumentation option.
>
> **CI gotcha.** If your build matrix uses
> `-Pbugsee.instrumentation.okhttp=false` (the legacy Gradle-property
> form) to disable instrumentation per-job, that flag is silently
> ignored once `plugin.instrumentation.okhttp` is set in
> `bugsee.properties` — the bypass kicks in. To keep CI overrides
> effective:
>
> - Either remove the conflicting `plugin.instrumentation.X` key from
>   `bugsee.properties` and rely on the legacy `-P` chain, OR
> - Read the `-P` flag into the DSL explicitly, e.g.
>   ```kotlin
>   bugsee { instrumentation {
>       okhttp.set(findProperty("bugsee.instrumentation.okhttp") as? Boolean ?: true)
>   } }
>   ```
>   so the CLI value flows through the highest-priority DSL slot.

> **App token** — set via the unprefixed key `app_token=…`, not
> `plugin.appToken`, or per variant via `variant.<name>.app_token=…`. The DSL
> provides additional richer forms (closure, provider) that have no
> properties-file equivalent.

## Per-variant settings

Most settings can differ per build variant, from `bugsee.properties` or from
the DSL, with the same lookup order in both. The typical case is a `staging`
flavor that uploads to its own Bugsee application.

### Key syntax

Prefix an existing key with `variant.<name>.`. Existing `app_token` and
`plugin.*` keys are unchanged.

```properties
app_token=PROD_TOKEN
plugin.endpoint=https://api.bugsee.com

variant.staging.app_token=STAGING_TOKEN
variant.staging.plugin.endpoint=https://staging.example.com
variant.release.plugin.buildInfo.sizeCheck.enabled=true
```

`<name>` is a variant name, a product flavor name or a build type name,
case-sensitive, exactly as AGP spells it (`stagingRelease`, `staging`,
`release`). A name that matches no variant is ignored.

### Lookup order

For a variant, inside each source (file or DSL) the **first match wins**, with
no merging:

1. the variant name (`stagingRelease`)
2. each product flavor, in AGP flavor-dimension order
3. the build type
4. the root / global value (`plugin.<key>` / `app_token`, or the DSL value
   outside any `variant` block)

This is the contract that wrappers (for example React Native) mirror for
settings-file tokens. It describes only the file lookup, not the plugin's overall
token precedence: when the DSL also supplies a token (and `preferSettingsFile`
is off) the plugin uses the DSL token, so a wrapper that must target the same
Bugsee application as the uploads has to use the effective token, or set
`preferSettingsFile` so the file wins. To read the file token a wrapper looks up `variant.<variant>.app_token`, then
`variant.<flavor>.app_token` for each flavor, then
`variant.<buildType>.app_token`, then `app_token` in
`<rootProject>/bugsee.properties`.

### DSL form

```kotlin
bugsee {
    endpoint.set("https://api.bugsee.com")   // global value and fallback
    appToken("PROD_TOKEN")

    variant("staging") {                      // variant, flavor or build type name
        appToken("STAGING_TOKEN")
        endpoint.set("https://staging.example.com")
        ndk { enabled.set(true) }
        buildInfo { sizeCheck { enabled.set(true) } }
    }
    variant("release") {
        instrumentation { enabled.set(false) }
    }
}
```

Calling `variant("x")` twice merges into one block. A block exposes only the
per-variant settings: `endpoint`, `debug`, `optimizeExtensionsLoading`, `ndk`,
`buildInfo`, `instrumentation` and `appToken(String)`. In the Kotlin DSL, naming
a global-only setting inside a block is a compile error. In Groovy there is no
such check, and the setting does **not** reach the global value either: Gradle
runs the closure against the block itself, which carries its own copy of every
setting, so `variant("x") { feedback true }`, `leak { … }`, `appToken { … }`
(closure form) or a nested `variant(…)` inside a block write that copy and are
**silently ignored**. Set global-only settings outside any `variant` block.
`instrumentation.excludes` is likewise ignored inside a variant block.

### Per-variant vs global-only

| Scope | Settings |
|---|---|
| Per variant | `app_token`; `endpoint`, `debug`, `optimizeExtensionsLoading`; `ndk.*`; `buildInfo.*`; `instrumentation.*` (every toggle and `startupTier`) |
| Global only | `feedback`, `leak.enabled`, `sdkAutoLoad`, `cliPath`, `cliVersion`, `cliAutoUpdate`, `uploader` |
| Global, DSL only | `instrumentation.excludes` (no `plugin.*` file key) |

`cliPath` may be relative. In the DSL (`cliPath.set("../tools/bugsee-cli")`) it is relative
to the module applying the plugin; in `bugsee.properties` (`plugin.cliPath=tools/bugsee-cli`) it
is relative to the directory of that file (the root project). It never depends on the Gradle
daemon's working directory. Absolute paths are used as written; `~` and environment variables are
not expanded. A path that is not an executable file logs a warning and falls back to the Kotlin uploader.

A `variant.<name>.plugin.<key>` file key for a global-only setting is ignored
with a warning. `instrumentation.excludes` is not a file key at all: a
`plugin.instrumentation.excludes` or `variant.<name>.plugin.instrumentation.excludes`
key is reported as unknown (at `--info`) and ignored.

### Precedence

A source is consulted as a whole (variant lookup, then its root/global value).
By default:

```text
DSL (variant lookup, then global)  >  bugsee.properties (variant lookup, then root)  >  built-in default
```

So a DSL value for a setting, global or per variant, beats a
`variant.*` file key for it. To use per-variant file values, leave that setting
out of the DSL. With the DSL-only switch

```kotlin
bugsee { preferSettingsFile.set(true) }
```

the order for the whole build (root keys included) becomes
`bugsee.properties (variant lookup, then root) > DSL (variant lookup, then global) > built-in default`.
The default is `false`.

When a `variant.<name>.*` file key is ignored because the DSL already set the
value, the plugin warns once per key (default mode only):

```text
Bugsee: variant.staging.plugin.endpoint ignored: endpoint is set in the DSL (set preferSettingsFile to let the settings file win).
```

A root key (`plugin.<key>`, `app_token`) shadowed by the DSL is the long-standing
precedence and does not warn.

### App token chain

Default order, first non-empty value wins:

1. `appTokenByVariant { variantName -> … }` closure (`null`/empty falls through)
2. `AppTokenProvider`
3. DSL `variant { appToken(…) }` per the lookup order, then the global `defaultAppToken`
4. `bugsee.properties`: `variant.*.app_token` per the lookup order, then root `app_token`
5. manifest `com.bugsee.android.APP_TOKEN` meta-data (read at execution time)

With `preferSettingsFile`, step 4 moves to the front.

The placeholder token `00000000-0000-4000-8000-000000000000` (shipped by templates and CI
projects without a real token) is treated like a missing one at every step: the chain skips it
(info message, once per source) and falls through to the next source. When no real token remains,
the upload tasks skip with a single info-level message instead of failing or warning.

### Notes and limitations

- **`sdkAutoLoad=false` disables ALL dependency auto-add.** With the default
  (`true`) the plugin adds the core SDK and the extension modules it detects
  (okhttp, compose, ktor, cronet, ndk, leak, feedback). With `false`
  (`bugsee { sdkAutoLoad.set(false) }` or `plugin.sdkAutoLoad=false`) it adds
  none of them; declare every Bugsee module yourself. Modules you declared are
  never removed, and instrumentation of them is unaffected. Global-only setting.
  `leak.enabled` and `feedback` only auto-add their module, so they are inert for
  undeclared modules; `ndk.enabled` still runs the NDK symbol upload but the NDK
  runtime is not added. A warning names each such opt-in (once per module that
  applies the plugin). The Compose compiler plugin
  (`bugsee-compose-compiler-plugin`) is still resolved when Compose is present
  and must stay resolvable from a repository (offline / local-artifact setups
  included). The decision is made when `implementation` is first resolved or
  read, so a script that reads `implementation.incoming.dependencies` before
  the `bugsee { }` block does not see the DSL value (tracked in #50).
- **Dependency auto-add is decided before variants exist.** The NDK runtime and
  the okhttp / compose / ktor / cronet modules are added when the feature is
  enabled globally or in any variant scope (DSL block or `variant.*` key). A
  variant that disabled the feature may carry an unused runtime module; its
  upload task and bytecode transform stay exact.
- **Library detection reads production configurations of the app module only.**
  The OkHttp / Compose / Ktor / Cronet detectors (including the Compose
  compiler-plugin and Compose input-capture gates) look at `implementation`,
  `api`, `runtimeOnly` and their build-type / flavor / source-set equivalents
  declared directly in the app module. Test, androidTest, testFixtures and
  compileOnly configurations are ignored, custom configurations that do not end
  in `Implementation` / `Api` / `RuntimeOnly` are not scanned, and libraries
  declared in other project modules are not followed. The match is by name, so a
  configuration whose name contains a capitalised `Test` word (a flavor or build
  type such as `latestTest` or `uiTest`) is treated as a test configuration and
  its libraries are not detected. In these cases declare the library directly in
  an app-module production configuration, or declare the Bugsee extension module
  (for example `bugsee-android-okhttp`) explicitly. For Compose, the compiler
  plugin additionally needs a direct Compose dependency in such a configuration:
  declaring `bugsee-android-compose` alone does not enable it.
- **Compose limitation.** The Compose Kotlin compiler-plugin gate is
  project-level and uses the same any-scope rule: if one variant enables
  `instrumentation.compose`, every Kotlin compilation gets the compiler plugin,
  including variants that disabled it.
- **Compose and test code.** The Compose compiler plugin is not applied to test
  compilations (`*UnitTest`, `*AndroidTest`, `*ScreenshotTest`, `testFixtures`,
  KMP `*Test` compilations associated with a main one, and every compilation of a
  `com.android.test` module), so tag wraps never add semantics nodes to the
  composables in your tests. To check: `kotlinCompilerPluginClasspath<Compilation>`
  (e.g. `kotlinCompilerPluginClasspathDebugUnitTest`) has no
  `bugsee-compose-compiler-plugin` dependency. A build type or flavor that merely
  contains "Test" in its name (`smokeTest`) is production and stays instrumented.
- **When values are fixed.** Values from `variant.*` file keys (and file root
  values when `preferSettingsFile` ranks them above the DSL) are fixed when
  variants are configured. Values resolved from the DSL stay live, so a late
  `afterEvaluate { bugsee { endpoint.set(…) } }` still reaches the tasks when no
  `variant.*` input outranks it.
- `plugin.debug` per variant only affects that variant's logging; apply-time
  reads use the global value.
- With no `variant.*` key and no `variant { }` block, nothing changes, except
  the new debug-only log line `Bugsee: variant <name> resolved: …` printed when
  `plugin.debug` is true. In that case (and without `preferSettingsFile`) the
  plugin reads the `bugsee { }` properties exactly as before: it does not
  inspect or read them early.

## Type coercion

| Property type | Accepted forms |
|---|---|
| Boolean | `true` / `false`, `yes` / `no`, `on` / `off`, `1` / `0` (case-insensitive) |
| Int / Long | Standard integer literal |
| Double | Standard decimal literal |
| String | Trimmed; empty string is rejected (warn — see below) |
| Enum | Case-insensitive match against the enum's constant names |

## Troubleshooting: "was left UNINSTRUMENTED"

```
Bugsee: a.b.e.a(Ljava/net/URL;)V was left UNINSTRUMENTED: its instrumented bytecode could not be written (...)
```

A method (typically a very large one in an obfuscated third-party library) that cannot be
written once instrumented is emitted unmodified instead of failing your build; Bugsee simply
captures nothing in that method. The build is fine and nothing needs doing. To stop instrumenting
the class on purpose, exclude it, or its whole package for obfuscated libraries whose class names
are short (an exclude disables **all** Bugsee instrumentation for what it matches):

```kotlin
bugsee { instrumentation { excludes.add("a.b") } }
```

The other instrumentations still fail the build with an attributed `InstrumentationException`
that names the class and suggests the same `excludes.add(...)`.

## Diagnostics

The plugin logs `bugsee.properties` issues at the most-appropriate
Gradle log level:

| Event | Log level | Why |
|---|---|---|
| File absent | (silent) | Most consumers don't use the file. |
| File present, no `plugin.*` keys | (silent) | Coexisting with `app_token=` is the common shape. |
| File malformed | `warn` | User error — surface always. |
| Value malformed | `warn` | User error; the key + bad value are named. |
| Empty string value (`plugin.endpoint=`) | `warn` | Likely a half-edited line; default holds. |
| Unknown `plugin.*` key | `info` | Forward-compat — `--info` to surface typos. |
| Successful apply | `warn`, only if `plugin.debug=true` | Echoes each applied key/value when verbose mode is on. |
| Unknown `variant.*` key | `info` | Same as unknown `plugin.*` keys. |
| `variant.<name>.*` key or `variant("<name>")` block matching no variant, flavor or build type | `warn` | Otherwise silently ignored, so a typo or wrong case falls back to the root/DSL token. |
| `variant.<name>.plugin.<key>` for a global-only setting | `warn` | Ignored; use the root `plugin.<key>`. |
| `variant.*` file key ignored because the DSL set the value | `warn`, once per key | See [Precedence](#precedence). |
| Per-variant resolution, only if `plugin.debug=true` for that variant | `warn` | `Bugsee: variant <name> resolved: token=<masked or none> endpoint=<url>`; the token shows the first 4 and last 4 characters (`****` when 8 or fewer). |

An empty root `app_token=` warns once, when the root token is actually consulted
(not when a DSL token already won). An empty `variant.<name>.app_token=` warns
when the file is parsed.

Symbol (mapping and native) upload failures never fail the build: with the Kotlin uploader an
unreachable endpoint, a timeout or a server error is a `warn` naming the variant, after up to 3
attempts for transient errors (I/O errors, 429, 5xx; a read timeout is not retried).

## Example

```properties
# bugsee.properties at the root project

# App token (used by AppTokenResolver — not a plugin.* key)
app_token=YOUR_APP_TOKEN_HERE

# Plugin options
plugin.debug=true
plugin.ndk.enabled=true
plugin.buildInfo.sizeAnalysis.enabled=true
plugin.buildInfo.sizeCheck.warningPercent=10.0
plugin.buildInfo.sizeCheck.failPercent=25.0
plugin.instrumentation.startupTier=DETAILED
```

```kotlin
// build.gradle.kts at app module — DSL overrides for this module
bugsee {
    // (1) Overrides plugin.endpoint from bugsee.properties (DSL > properties).
    endpoint.set("https://api.bugsee-internal.example.com")

    // (2) Sets a key that bugsee.properties did NOT touch — the two
    //     sources are additive; this becomes the effective value.
    feedback.set(true)

    // (3) Keys NOT mentioned in either source fall back to plugin
    //     defaults — `ndk.forceDebugSymbolsUpload`, every other
    //     instrumentation flag, etc. all remain at their built-in
    //     defaults documented in the tables above.
}
```

**Effective config** for the example above:

| Key | Effective value | From |
|---|---|---|
| `endpoint` | `https://api.bugsee-internal.example.com` | DSL (overrides properties) |
| `feedback` | `true` | DSL (additive — no properties value) |
| `debug` | `true` | bugsee.properties |
| `ndk.enabled` | `true` | bugsee.properties |
| `ndk.forceDebugSymbolsUpload` | `false` | built-in default |
| `buildInfo.sizeAnalysis.enabled` | `true` | bugsee.properties |
| `buildInfo.sizeCheck.warningPercent` | `10.0` | bugsee.properties |
| `instrumentation.startupTier` | `DETAILED` | bugsee.properties |
| `instrumentation.okhttp` (and others) | `true` | downstream resolver default |

For the full set of DSL options, see KDocs on `BugseePluginExtension`
and the sub-extension classes (`BugseeNdkExtension`,
`BugseeBuildInfoExtension`, `BugseeInstrumentationExtension`, etc.).

## License

MIT. See [LICENSE](LICENSE).
