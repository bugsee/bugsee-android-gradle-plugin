# Per-variant settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the app token and every per-variant-capable setting be set per variant/flavor/build type from both `bugsee.properties` (`variant.<name>.*`) and the `bugsee { variant("<name>") { } }` DSL, with one documented lookup order and a `preferSettingsFile` precedence switch.

**Architecture:** Keep the global `BugseePluginExtension` tree. A DSL `variant { }` block is itself a full `BugseePluginExtension` instance exposed through a restricted interface. A new `SettingsResolver` layers DSL-variant, DSL-global, file-variant and file-root values (order depends on `preferSettingsFile`) into a per-variant *effective* `BugseePluginExtension`; the existing variant-level code (`register*Task`, instrumentation, build-info gates) receives that effective extension instead of the global one. One `SettingDefinitions` registry (key, scope, accessor, parser) drives the file keys, the resolver and the existing applier. Explicit-DSL-set detection uses a convention-probe on `Property`.

**Tech Stack:** Kotlin 2.1, Gradle 8.7 plugin API, AGP 8.6 (`compileOnly`), JUnit4 + `kotlin("test")` + `ProjectBuilder` for unit tests, TestKit for `src/integrationTest`.

**Spec:** `docs/superpowers/specs/2026-10-24-per-variant-settings-design.md` (read it first; this plan implements it).

## Global Constraints

- Kotlin 2.1, Java 11 target, AGP 8.6 `compileOnly`, Gradle 8.7.
- Precedence, default: `DSL (variant lookup, then global)  >  bugsee.properties (variant lookup, then root)  >  built-in default`. With `preferSettingsFile.set(true)`: `bugsee.properties (variant lookup, then root)  >  DSL (variant lookup, then global)  >  built-in default`. A source is consulted as a whole. `preferSettingsFile` is DSL-only, default `false`, whole-file.
- Lookup order inside each source: variant name, then each product flavor (AGP dimension order), then build type, then the global/root value. Names are case-sensitive, exact AGP spelling.
- File syntax: `variant.<name>.app_token` and `variant.<name>.plugin.<existing path>`; existing `app_token` / `plugin.*` keys unchanged.
- Token chain, default: `appTokenByVariant` closure (null/empty falls through), `AppTokenProvider`, DSL `variant { appToken }` per lookup order then `defaultAppToken`, file (variant lookup then root `app_token`), manifest meta-data. With `preferSettingsFile`: file first, rest unchanged. Manifest step stays at execution time.
- Per-variant-capable: `app_token`, `plugin.endpoint`, `plugin.debug`, `plugin.optimizeExtensionsLoading`, `plugin.ndk.*`, `plugin.buildInfo.*`, `plugin.instrumentation.*` (toggles and `startupTier`). Global-only: `plugin.feedback`, `plugin.leak.enabled`, `plugin.sdkAutoLoad`, `plugin.cliPath`, `plugin.cliVersion`, `plugin.cliAutoUpdate`, `plugin.uploader` (a `variant.*` prefix on them: warn and ignore; unknown `variant.*` keys: `info`).
- Shadow warning (once per key): a file key ignored because the DSL set the value (default mode only). Precedence is a hard requirement; the warning is best-effort.
- `plugin.debug` per variant affects variant-level logging only; apply-time reads use the root/global value.
- Dependency auto-add (ndk runtime, okhttp/compose/ktor/cronet modules) is decided before variants exist: add when enabled globally OR any variant scope enables it.
- Application variants only. The file stays a configuration-cache input (`providers.fileContents`); tasks receive plain values, never the effective extension tree.
- Existing behaviour is identical when no `variant.*` key / `variant { }` block exists. All existing tests keep passing.
- Commit messages are conventional (`feat(config): …`) and end with the attribution line `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Run unit tests with `./gradlew test --tests '<fqcn>'`; integration tests with `./gradlew integrationTest --tests '<fqcn>'`. (`.claude/CLAUDE.md` says "no test suite"; that is stale, fixed in Task 10.)

## Review Focus

1. A name that is simultaneously a variant, flavor and build type (e.g. flavor `release` or variant `release` with build type `release`): the first match in the documented order wins, not a merge. Pinned in Task 5.
2. `variant.staging.app_token=` (empty or whitespace): warn and fall through to the next lookup name/root, never an empty token. Pinned in Task 3.
3. A DSL value explicitly set to the same value as the built-in default still counts as a DSL set (beats the file; triggers the shadow warning). Pinned in Task 1.
4. `variant.<name>.*` for a name that matches no variant (typo) must not affect any other variant and must not fail the build. Pinned in Task 5.
5. Editing `bugsee.properties` invalidates the configuration cache and the effective tree is never a task input (config-cache reuse works). Pinned in Task 9.

(`preferSettingsFile=true` with a file that lacks the key must still use the DSL value; pinned in Task 5.)

---

### Task 1: Explicit-set probe (spike)

**Files:**
- Create: `src/main/kotlin/com/bugsee/android/gradle/config/ExplicitSetProbe.kt`
- Test: `src/test/kotlin/com/bugsee/android/gradle/config/ExplicitSetProbeTest.kt`

**Interfaces:**
- Produces: `internal object ExplicitSetProbe { fun isExplicitlySet(prop: Property<*>): Boolean }` — true iff the value was assigned via `set`/`value`, false when the property is absent or resolves only through a convention (built-in default or file convention). Must not change the property's resolved value; safe to call repeatedly.

- [ ] **Step 1: Write failing tests** in `ExplicitSetProbeTest` (use `ProjectBuilder` objects): `defaultOnly_isNotExplicit`, `conventionFromFile_isNotExplicit` (second `.convention(...)` call), `set_isExplicit`, `setToSameValueAsDefault_isExplicit` (Boolean default `false`, `set(false)` → true), `absentNoConvention_isNotExplicit`, `probeDoesNotChangeValue` (value before == after, for Boolean, String and an enum), `probeTwice_isStable`.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.ExplicitSetProbeTest'`. Expected: FAIL (class missing).
- [ ] **Step 3: Implement `isExplicitlySet`.** Approach: read `before = prop.orNull`; if null return false; install `prop.convention(provider { consulted = true; before })`; evaluate `prop.orNull`; return `!consulted`. (An explicit `set` never consults the convention provider.) Generic helper `private fun <T : Any> probe(prop: Property<T>)` with an unchecked cast for the `Property<*>` entry point.
- [ ] **Step 4: Run the tests.** Expected: PASS. **If the approach cannot pass `setToSameValueAsDefault_isExplicit` or `probeDoesNotChangeValue`, stop and report to the user**; the spec's fallback (shadow properties for DSL values, degraded warning) changes Tasks 4-5.
- [ ] **Step 5: Commit** `feat(config): add ExplicitSetProbe to detect DSL-set Gradle properties`.

---

### Task 2: Setting definitions registry

**Files:**
- Create: `src/main/kotlin/com/bugsee/android/gradle/config/SettingDefinitions.kt`
- Modify: `src/main/kotlin/com/bugsee/android/gradle/config/PluginPropertiesApplier.kt` (derive `bindings` from the registry; keep `applyTo`/`applyText` signatures and all messages)
- Test: `src/test/kotlin/com/bugsee/android/gradle/config/SettingDefinitionsTest.kt`

**Interfaces:**
- Produces:
  - `internal enum class SettingScope { GLOBAL, VARIANT }`
  - `internal class SettingDef<T : Any>(val key: String, val scope: SettingScope, val property: (BugseePluginExtension) -> Property<T>, val parse: (String) -> T)` — `key` is the path after `plugin.` (e.g. `buildInfo.sizeCheck.enabled`); `parse` throws `IllegalArgumentException` with the same messages the applier uses today (bool aliases true/false/yes/no/on/off/1/0, non-empty trimmed string, int/long/double, case-insensitive enum).
  - `internal object SettingDefinitions { val all: List<SettingDef<*>>; fun byKey(key: String): SettingDef<*>?; fun variantScoped(): List<SettingDef<*>> }`
- Scopes: GLOBAL = `cliPath`, `cliVersion`, `cliAutoUpdate`, `uploader`, `feedback`, `sdkAutoLoad`, `leak.enabled`. VARIANT = everything else currently bound (`endpoint`, `debug`, `optimizeExtensionsLoading`, `ndk.*`, `buildInfo.*`, `instrumentation.*`). `instrumentation.excludes` is not a setting (global, not file-bound).

- [ ] **Step 1: Write failing tests** `SettingDefinitionsTest`: `allKeysMatchTodaysBindings` (the exact key list from the current `bindings()`), `scopesMatchSpec` (global list above, everything else VARIANT), `parseBoolAliases`, `parseRejectsEmptyString`, `parseEnumCaseInsensitive`, `propertyAccessorReturnsTheLiveProperty`.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.SettingDefinitionsTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the registry in `SettingDefinitions.kt`** (move the coercers out of the applier) and rewrite `PluginPropertiesApplier.bindings` to iterate `SettingDefinitions.all` applying `prop.convention(parse(raw))`.
- [ ] **Step 4: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.*'`. Expected: PASS including the untouched `PluginPropertiesApplierTest`/`PluginPropertiesLoaderTest`.
- [ ] **Step 5: Commit** `refactor(config): single registry of settings with per-variant scope`.

---

### Task 3: Settings file model and parser

**Files:**
- Create: `src/main/kotlin/com/bugsee/android/gradle/config/SettingsFile.kt`
- Modify: `src/main/kotlin/com/bugsee/android/gradle/config/PluginPropertiesLoader.kt` (extract a shared `fun readAll(text: String?, logger: Logger): Map<String, String>` doing the BOM strip + malformed handling; `load` keeps its behaviour on top of it)
- Test: `src/test/kotlin/com/bugsee/android/gradle/config/SettingsFileParserTest.kt`

**Interfaces:**
- Consumes: `SettingDefinitions.byKey`, `SettingScope` (Task 2).
- Produces:
  - `internal data class VariantFileEntries(val appToken: String?, val plugin: Map<String, String>)`
  - `internal data class SettingsFile(val rootAppToken: String?, val rootPlugin: Map<String, String>, val variants: Map<String, VariantFileEntries>) { companion object { val EMPTY: SettingsFile } }`
  - `internal object SettingsFileParser { fun parse(text: String?, logger: Logger): SettingsFile }`
- Rules: `<name>` is the segment between `variant.` and the next `.`; remainder is `app_token` or `plugin.<path>`. Values trimmed. Empty `app_token` (root or variant) → `null` plus a `warn` ("declares app_token with an empty value — falling through"), same wording style as today's resolver. `variant.<n>.plugin.<globalOnlyKey>` → `warn` and dropped. Unknown `variant.<n>.plugin.<key>` / malformed `variant.` keys → `info` and dropped. Root unknown `plugin.*` keys are left to the applier's existing `info`.

- [ ] **Step 1: Write failing tests** (`RecordingLogger` from the existing test package): `rootTokenAndPlugin`, `variantTokenAndPlugin`, `emptyVariantToken_isNullAndWarns`, `whitespaceVariantToken_isNull`, `globalOnlyVariantKey_warnsAndDropped` (`variant.staging.plugin.feedback=true`), `unknownVariantKey_infoAndDropped`, `bomOnFirstLine`, `nullTextIsEmpty`, `malformedFileFallsBackToEmptyAndWarnsOnce`, `multipleVariantsIndependent`.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.SettingsFileParserTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `SettingsFileParser.parse`** on top of `PluginPropertiesLoader.readAll`.
- [ ] **Step 4: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.*'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(config): parse variant.* keys from bugsee.properties`.

---

### Task 4: DSL surface (`variant { }`, `preferSettingsFile`)

**Files:**
- Create: `src/main/kotlin/com/bugsee/android/gradle/BugseeVariantSettings.kt`
- Modify: `src/main/kotlin/com/bugsee/android/gradle/BugseePluginExtension.kt`
- Test: `src/test/kotlin/com/bugsee/android/gradle/BugseeVariantDslTest.kt`

**Interfaces:**
- Produces:
  - `interface BugseeVariantSettings { val endpoint: Property<String>; val debug: Property<Boolean>; val optimizeExtensionsLoading: Property<Boolean>; val ndk: BugseeNdkExtension; fun ndk(action: Action<BugseeNdkExtension>); val buildInfo: BugseeBuildInfoExtension; fun buildInfo(action: Action<BugseeBuildInfoExtension>); val instrumentation: BugseeInstrumentationExtension; fun instrumentation(action: Action<BugseeInstrumentationExtension>); fun appToken(token: String) }` — KDoc states which settings are intentionally absent (global-only) and that `instrumentation.excludes` is ignored inside a variant block.
  - `BugseePluginExtension : BugseeVariantSettings` (add `override`); new `val preferSettingsFile: Property<Boolean>` (convention `false`, not file-bound); `fun variant(name: String, action: Action<BugseeVariantSettings>)`; `internal val variantBlocks: Map<String, BugseePluginExtension>`.
- A block is `objects.newInstance(BugseePluginExtension::class.java)` created on first `variant(name)`; a second `variant(name)` call returns/merges into the same instance. Block `appToken(String)` stores in that instance's `defaultAppToken`.

- [ ] **Step 1: Write failing tests** (`ProjectBuilder`): `variantBlockHoldsOwnValues` (`ext.variant("staging") { endpoint.set("https://s"); ndk { enabled.set(true) } }`; global endpoint still default), `secondCallMerges`, `blockTokenStored` (`variantBlocks["staging"]!!.defaultAppToken == "T"`), `preferSettingsFileDefaultsFalse`, `blocksAreIndependent`.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.BugseeVariantDslTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the interface and extension changes. Keep every existing public member of `BugseePluginExtension` source-compatible.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS (whole unit suite).
- [ ] **Step 5: Commit** `feat(dsl): add variant { } blocks and preferSettingsFile`.

---

### Task 5: `SettingsResolver` — layering and effective extension

**Files:**
- Create: `src/main/kotlin/com/bugsee/android/gradle/config/SettingsResolver.kt`
- Test: `src/test/kotlin/com/bugsee/android/gradle/config/SettingsResolverTest.kt`

**Interfaces:**
- Consumes: `ExplicitSetProbe` (T1), `SettingDef`/`SettingDefinitions` (T2), `SettingsFile` (T3), `BugseePluginExtension.variantBlocks`/`preferSettingsFile` (T4).
- Produces:
  - `internal data class VariantContext(val variantName: String, val flavorNames: List<String>, val buildType: String?) { val lookupNames: List<String> }` — `variantName`, flavors in order, `buildType`, de-duplicated keeping first occurrence.
  - `internal enum class SettingSource { DSL_VARIANT, DSL_GLOBAL, FILE_VARIANT, FILE_ROOT }` and `internal data class Resolved<T>(val value: T, val source: SettingSource)`
  - `internal class SettingsResolver(global: BugseePluginExtension, file: SettingsFile, objects: ObjectFactory, logger: Logger)` with:
    - `fun <T : Any> resolve(def: SettingDef<T>, ctx: VariantContext?): Resolved<T>?` — `ctx == null` is global scope (only DSL_GLOBAL / FILE_ROOT can win). Returns null when no source sets it (caller falls to the built-in default). Reads `global.preferSettingsFile` on every call (never cached; the DSL may not have run yet at apply time).
    - `fun <T : Any> globalValue(def: SettingDef<T>): T` — `resolve(def, null)?.value` else the global property's own value.
    - `fun effectiveFor(ctx: VariantContext): BugseePluginExtension` — new instance; each VARIANT-scoped def set only when `resolve` returns a value; GLOBAL defs and `instrumentation.excludes` copied from `global`.
    - `fun anyScopeValue(def: SettingDef<Boolean>): Boolean?` — null when no scope (global, any DSL block, any file variant entry) sets it; true if any scope resolves true; else false.
  - Shadow warning: in default mode, when the winner is a DSL source and a file key for the same def exists for that ctx, `logger.warn` once per file key: `Bugsee: variant.staging.plugin.endpoint ignored: endpoint is set in the DSL (set preferSettingsFile to let the settings file win).` (root keys: `plugin.endpoint ignored: …`).

- [ ] **Step 1: Write failing tests** (`ProjectBuilder`, `RecordingLogger`; build `SettingsFile` literals; helper `ctx("stagingRelease", listOf("staging"), "release")`). Names, each with the expected winner:
  - Default mode: `dslVariantBeatsDslGlobal`, `dslGlobalBeatsFileVariant`, `fileVariantBeatsFileRoot`, `fileRootBeatsDefault`, `nothingSet_resolveIsNullAndEffectiveKeepsDefault`.
  - `preferSettingsFile`: `fileVariantBeatsDslVariant`, `fileRootBeatsDslGlobal`, `fileLacksKey_dslValueUsed`.
  - Lookup order: `variantNameBeatsFlavorBeatsBuildType` for both file and DSL; `nameThatIsFlavorAndBuildType_firstMatchWins` (Review Focus 1: `variant.release.*` and variant `release` with build type `release`; no merge); `multiFlavorUsesDimensionOrder`.
  - Review Focus 4: `unmatchedVariantName_affectsNoOtherVariant` (`variant.stagin.*`).
  - Scope: `globalOnlyDefsCopiedFromGlobal` (cliPath, feedback, excludes), `variantFileKeyOnGlobalOnlyDefIsNotApplied`.
  - Warning: `shadowWarningOncePerKey`, `noShadowWarningInPreferMode`, `noShadowWarningWhenDslNotSet`.
  - `anyScopeValue`: `nullWhenNowhere`, `trueWhenOnlyAVariantEnables`, `falseWhenAllSetFalse`.
  - `globalValueUsesFileRootInPreferMode`.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.SettingsResolverTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `SettingsResolver`.** Candidate order per def — default: DSL variant lookup (explicit-set props on `variantBlocks[name]`, in `lookupNames` order), DSL global (`ExplicitSetProbe` on `global` property), file variant lookup, file root; prefer: file first, then the DSL pair. File root values come from `file.rootPlugin` parsed with `def.parse` (a parse failure warns and skips, as the applier does). Do not read file root values back from the global property's convention.
- [ ] **Step 4: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.*'`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(config): resolve settings per variant across DSL and settings file`.

---

### Task 6: App token through the resolver

**Files:**
- Modify: `src/main/kotlin/com/bugsee/android/gradle/config/SettingsResolver.kt`
- Modify: `src/main/kotlin/com/bugsee/android/gradle/upload/AppTokenResolver.kt`
- Modify: `src/main/kotlin/com/bugsee/android/gradle/upload/MappingUploadTask.kt`, `NativeUploadTask.kt` (drop the execution-time `resolveFromPropertiesFile` fallback and the `rootProjectDirectory` input if nothing else reads it — grep first; `BundleUploadTask` already uses only `resolveFromManifest`)
- Test: `src/test/kotlin/com/bugsee/android/gradle/config/SettingsResolverTokenTest.kt`

**Interfaces:**
- Consumes: Task 5 types.
- Produces: `fun SettingsResolver.appTokenFor(ctx: VariantContext, debug: Boolean): String?` — plugin-side chain per Global Constraints (manifest excluded). Uses `AppTokenResolver.resolveFromExtension(global, ctx.variantName, logger, debug)` for the closure/provider steps (keep its signature), then DSL block tokens (`variantBlocks[name].defaultAppToken` in `lookupNames` order), then `global.defaultAppToken`, then file (`variants[name].appToken` in lookup order, then `rootAppToken`). Prefer mode: file first. Token shadow warning (once): default mode, a DSL source returned non-null and a file token exists for ctx. Debug logging keeps the masked-token style (`maskToken`).
- `AppTokenResolver.resolveFromPropertiesFile` and the back-compat `resolve(project, …)` are deleted if no caller remains.

- [ ] **Step 1: Write failing tests**: default mode — `closureBeatsEverything`, `closureNullFallsThrough`, `providerBeatsDslBlock`, `dslBlockBeatsDefaultAppToken`, `defaultAppTokenBeatsFile`, `fileVariantBeatsFileRoot`, `fileRootTokenWhenNoVariantEntry`, `nothingSetIsNull`; prefer mode — `fileBeatsClosure`, `fileLacksToken_closureUsed`; Review Focus 2 — `emptyVariantFileToken_fallsThroughToRoot` (using a parsed `SettingsFile`); `tokenShadowWarningOnlyWhenDslReturnedValue`.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.config.SettingsResolverTokenTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `appTokenFor`** and remove the now-dead resolver paths/task inputs.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS; if a task test constructed `rootProjectDirectory`, update it.
- [ ] **Step 5: Commit** `feat(token): per-variant app token from DSL and settings file`.

---

### Task 7: Wire variants to the effective extension

**Files:**
- Modify: `src/main/kotlin/com/bugsee/android/gradle/BugseePlugin.kt` (onVariants ~220-400, `resolveCcSafeInputs` ~680, `registerManifestTask`, `registerMappingUploadTask`, `registerNativeUploadTask`, `registerBundleUploadTask`, `shouldRegisterBuildInfoFor` ~1083)
- Test: `src/test/kotlin/com/bugsee/android/gradle/VariantWiringTest.kt`

**Interfaces:**
- Consumes: `SettingsResolver.effectiveFor`, `appTokenFor`, `VariantContext` (T5-6).
- Produces: in `BugseePlugin`, a lazily-created `SettingsResolver` (file text via `project.rootProject.providers.fileContents(rootProject.layout.projectDirectory.file(BUGSEE_PROPERTIES_FILENAME)).asText.orNull`, parsed with `SettingsFileParser`), and `internal fun variantContextOf(variant: com.android.build.api.variant.Variant): VariantContext` using `variant.name`, `variant.productFlavors.map { it.second }`, `variant.buildType`.
- Inside `onVariants`, `effective = resolver.effectiveFor(ctx)` replaces `extension` for: `isDebug`, `InstrumentationConfigResolver(effective.instrumentation, …)`, `registerManifestTask`, mapping/native/bundle registration, the `ndk.enabled` gate (~312), `shouldRegisterBuildInfoFor`, `ExtensionsInitInstrumentation(effective, …)` and the registrar's excludes. `instrumentationGloballyEnabled` (manifest provider-strip gate and the ExtensionsInit injection) must be computed from the same `effective` so both agree per variant. `sdkAutoLoad` stays global. `resolveCcSafeInputs` takes the token from `resolver.appTokenFor(ctx, isDebug)`.
- New debug log per variant (when its effective `debug` is true): `Bugsee: variant <name> resolved: token=<masked or none> endpoint=<url>` (observability for wrappers and Task 9).

- [ ] **Step 1: Write failing tests**: `variantContextOf_flavorsInDimensionOrder` (fake `Variant` via a minimal interface proxy or Mockito-free stub — follow how `VariantScopedDetectionTest` fakes variants), `stripGateAndExtensionsInitAgreePerVariant` (variant with `instrumentation.enabled=false` in file → manifest task `optimizeExtensionsLoading` resolves to false for that variant only; extend the pattern in `ManifestTaskStripGateWiringTest`), `taskInputsComeFromEffectiveExtension` (variant `staging` gets endpoint from `variant.staging.plugin.endpoint`, `production` keeps default).
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.VariantWiringTest'`. Expected: FAIL.
- [ ] **Step 3: Implement the wiring.** Do not change task classes beyond Task 6; the effective extension's properties are linked into task inputs exactly as the global ones are today.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS (all existing wiring tests included).
- [ ] **Step 5: Commit** `feat(plugin): resolve variant-level settings per variant`.

---

### Task 8: Global-time reads and dependency auto-add

**Files:**
- Modify: `src/main/kotlin/com/bugsee/android/gradle/BugseePlugin.kt` (`withDependencies` block ~96-200, `isAutoAddEnabled` ~1037, apply-time `afterEvaluate` ~202, `sdkAutoLoad` read ~340)
- Test: `src/test/kotlin/com/bugsee/android/gradle/AutoAddScopeTest.kt`

**Interfaces:**
- Consumes: `SettingsResolver.globalValue`, `anyScopeValue` (T5).
- Produces: `internal fun isAutoAddEnabled(project: Project, resolver: SettingsResolver, def: SettingDef<Boolean>, key: String): Boolean` — `resolver.anyScopeValue(def)` when non-null; otherwise the existing `bugsee.instrumentation.<key>` Gradle-property fallback, then `true`. Used for `ndk.enabled` (replacing the `extension.ndk.enabled.get()` check at ~163, with default `false` when `anyScopeValue` is null), compose/composeSecure/composeInput, okhttp, ktor, cronet. Global-time reads of `debug`, `sdkAutoLoad`, `feedback`, `leak.enabled` use `resolver.globalValue(def)`, so `preferSettingsFile` applies to them too.

- [ ] **Step 1: Write failing tests**: `ndkAddedWhenOnlyAVariantEnablesIt`, `ndkNotAddedWhenNowhereEnabled`, `okhttpExplicitFalseEverywhereSkipsAutoAdd`, `okhttpUnsetFallsBackToGradleProperty`, `preferSettingsFileMakesFileFeedbackWinAtApplyTime`.
- [ ] **Step 2: Run** `./gradlew test --tests 'com.bugsee.android.gradle.AutoAddScopeTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** the signature above and update the call sites.
- [ ] **Step 4: Run** `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(plugin): auto-add modules when any variant scope enables them`.

---

### Task 9: Integration tests (TestKit)

**Files:**
- Create: `src/integrationTest/kotlin/com/bugsee/android/gradle/integration/PerVariantSettingsIntegrationTest.kt`
- Reuse the harness/fixtures used by `BugseeMultiVariantUuidTest` and `BundleUploadConfigCacheTest` (add a fixture only if flavors are missing).

**Interfaces:** consumes the debug line from Task 7.

- [ ] **Step 1: Write failing tests** on a two-flavor app (`staging`, `production`) with `plugin.debug=true`: `perFlavorTokenAndEndpointReachEachVariant` (assert the Task 7 log line per variant with the staging token/endpoint vs root), `dslVariantBlockAndFilePrecedence` (default mode: DSL block wins; with `preferSettingsFile` the file wins), `configCacheReusedThenInvalidatedByFileEdit` (Review Focus 5: run twice, second reports cache reuse; edit `bugsee.properties`, third run recomputes and shows the new value).
- [ ] **Step 2: Run** `./gradlew integrationTest --tests 'com.bugsee.android.gradle.integration.PerVariantSettingsIntegrationTest'`. Expected: FAIL before Tasks 6-8 are complete, PASS now.
- [ ] **Step 3: Fix what the run exposes** (only in files from earlier tasks).
- [ ] **Step 4: Run** `./gradlew build integrationTest`. Expected: PASS.
- [ ] **Step 5: Commit** `test(integration): per-variant settings end to end`.

---

### Task 10: Documentation

**Files:**
- Modify: `README.md` (configuration sources, `bugsee.properties`, key reference sections)
- Modify: `.claude/CLAUDE.md` and `AGENTS.md` (App Token Resolution, DSL Extension, Testing — replace "no test suite")
- Modify: `docs/superpowers/specs/2026-10-24-per-variant-settings-design.md` (remove the stale "no test suite exists" line under Constraints)

- [ ] **Step 1: README "Per-variant settings" section**: key syntax, DSL `variant("<name>") { }` form, the lookup order (marked as the contract wrappers such as React Native mirror), per-variant vs global-only tables, precedence rule in both modes, `preferSettingsFile`, the shadow warning, and the token chain.
- [ ] **Step 2: Update `CLAUDE.md` / `AGENTS.md`** (token chain, new files in the source layout, `variant { }`, `preferSettingsFile`, correct testing instructions: `./gradlew test`, `./gradlew integrationTest`).
- [ ] **Step 3: Verify** `./gradlew build` still passes and the README examples match the integration test fixtures.
- [ ] **Step 4: Commit** `docs: per-variant settings and lookup order`.
- [ ] **Step 5: Reply on issue #35** is left to the user (do not post).
