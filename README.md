# Bugsee Android Gradle Plugin

Configuration reference for the Bugsee Android Gradle plugin.

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

## `bugsee.properties`

A single `bugsee.properties` file at the **root project** directory
configures both the app token (used by `AppTokenResolver`) and the
plugin itself. The two surfaces share the file but live under
distinct namespaces:

| Namespace | Owner | Example |
|---|---|---|
| (unprefixed) | App-token resolution | `app_token=YOUR_APP_TOKEN` |
| `plugin.*` | Gradle plugin behavior | `plugin.debug=true` |

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
| `plugin.optimizeExtensionsLoading` | Boolean | `true` | `bugsee { optimizeExtensionsLoading.set(…) }` |

### NDK integration

| Key | Type | Default |
|---|---|---|
| `plugin.ndk.enabled` | Boolean | `false` |
| `plugin.ndk.forceDebugSymbolsUpload` | Boolean | `false` |

### Leak detection

When `enabled`, the plugin automatically adds the `bugsee-android-leak`
module dependency (memory/thread leak detection). If the app already
declares the leak module explicitly, this is a no-op — mirroring the
`ndk.enabled` behaviour.

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
> `plugin.appToken`. The DSL provides additional richer forms
> (closure, provider, per-variant resolver) that have no
> properties-file equivalent.

## Type coercion

| Property type | Accepted forms |
|---|---|
| Boolean | `true` / `false`, `yes` / `no`, `on` / `off`, `1` / `0` (case-insensitive) |
| Int / Long | Standard integer literal |
| Double | Standard decimal literal |
| String | Trimmed; empty string is rejected (warn — see below) |
| Enum | Case-insensitive match against the enum's constant names |

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
