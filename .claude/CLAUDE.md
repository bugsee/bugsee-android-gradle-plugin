# CLAUDE.md — Bugsee Android Gradle Plugin

This file provides guidance to Claude Code when working with this repository.

## Build Commands

```bash
./gradlew build                    # Build the plugin
./gradlew publishToMavenLocal -x signPluginMavenPublication -x signBugseePluginMarkerMavenPublication
                                   # Publish to local Maven for testing
```

Build scripts in `scripts/`:
```bash
./scripts/build.sh                 # Clean + build
./scripts/deploy.sh                # Publish to remote Maven (release)
./scripts/localPublish.sh          # Clean + build + publishToMavenLocal (skip signing)
```

Release builds: set `RELEASE=true` environment variable to drop `-SNAPSHOT` suffix.

## Testing

No test suite currently exists. After making changes:
1. `./gradlew build` — plugin compiles and `validatePlugins` passes
2. `./gradlew publishToMavenLocal` — publishes `com.bugsee:bugsee-android-gradle-plugin:4.0.0-SNAPSHOT`
3. Apply plugin in the SDK's `app/` module → `./gradlew :app:assembleDebug` — verify manifest UUID injection
4. For instrumentation testing: inspect bytecode of a consuming app via `javap` or ASM bytecode dump

## Project Overview

| Property | Value |
|----------|-------|
| Plugin ID | `com.bugsee.android.gradle` |
| Version | 4.0.0 (in `version.txt`) |
| Artifact | `com.bugsee:bugsee-android-gradle-plugin` |
| Language | Kotlin (2.1.0) |
| Java target | 11 |
| Gradle wrapper | 8.7 |
| AGP compatibility | 8.6.0 (compileOnly) |

### Dependencies

- `com.android.tools.build:gradle-api:8.6.0` (compileOnly)
- `com.android.tools.build:gradle:8.6.0` (compileOnly)
- `org.apache.httpcomponents:httpclient:4.5.14` — HTTP upload
- `org.apache.httpcomponents:httpmime:4.5.14` — multipart support
- `org.json:json:20240303` — JSON serialization/parsing

## Architecture

The plugin does four things:
1. **Manifest UUID injection** — unique BUILD_UUID per build for symbol correlation
2. **ProGuard/R8 mapping upload** — ZIP mapping + icon, hash, upload to Bugsee backend
3. **NDK symbol upload** — find native debug symbols, zip, upload
4. **Bytecode instrumentation** — ASM-based injection via `AsmClassVisitorFactory` (AGP 7.0+)

### Source Layout

```
src/main/kotlin/com/bugsee/android/gradle/
├── BugseePlugin.kt                          # Entry point: extension, variant wiring, instrumentation
├── BugseePluginExtension.kt                 # DSL: endpoint, appToken, debug, ndk, instrumentationEnabled
├── AppTokenProvider.kt                      # Interface: getAppToken(variantName: String)
├── manifest/
│   ├── ManifestModifier.kt                  # XML DOM: remove old BUILD_UUID, inject new, read meta-data
│   └── BugseeManifestTask.kt               # DefaultTask wired via SingleArtifact.MERGED_MANIFEST
├── upload/
│   ├── AppTokenResolver.kt                 # Priority: closure → provider → default → manifest meta-data
│   ├── SymbolUploader.kt                    # Two-stage HTTP: POST → presigned URL, PUT → upload
│   ├── MappingUploadTask.kt                # ZIP mapping+icon, SHA-1 hash, upload
│   ├── NativeUploadTask.kt                 # Find NDK symbols (2 locations), zip, upload
├── instrumentation/
│   ├── Instrumentation.kt                   # interface { name, shouldApply(Project), apply(Variant) }
│   ├── InstrumentationRegistrar.kt         # Iterates instrumentations, applies if dependency found
│   ├── okhttp/
│   │   ├── OkHttpInstrumentation.kt        # Gated on com.bugsee:bugsee-okhttp
│   │   ├── OkHttpClassVisitorFactory.kt    # Skips com.bugsee.* and okhttp3.*
│   │   └── OkHttpClassVisitor.kt           # Injects addInterceptor() before Builder.build()
│   ├── log/
│   │   ├── LogInstrumentation.kt            # Gated on com.bugsee:bugsee-android
│   │   ├── LogClassVisitorFactory.kt        # Skips com.bugsee.* and android.*
│   │   └── LogClassVisitor.kt              # Redirects android.util.Log → BugseeLogAdapter
│   └── thread/
│       ├── ThreadInstrumentation.kt         # Gated on com.bugsee:bugsee-android
│       ├── ThreadClassVisitorFactory.kt     # Only Runnable implementers / Thread subclasses
│       └── ThreadClassVisitor.kt            # Injects registerThread() at start of run()V
└── util/
    ├── HashUtils.kt                         # SHA-1 hex digest
    ├── ZipUtils.kt                          # zipDirectory(), createMappingZip()
    ├── IconResolver.kt                      # Resolve @mipmap/ or @drawable/, prefer xxhdpi
    └── StringResourceResolver.kt            # Resolve @string/ references from strings.xml
```

### Plugin Entry Point (`BugseePlugin.kt`)

Uses `AndroidComponentsExtension.onVariants` to register per-variant:
- **Manifest task** — `createBugsee${Variant}ManifestConfig` (all variants)
- **Mapping upload task** — `uploadBugsee${Variant}Mapping` (ApplicationVariant only, finalized by assemble/bundle)
- **Native upload task** — `uploadBugsee${Variant}Native` (ApplicationVariant only, when `ndk=true`)
- **Instrumentation** — applies all matching instrumentations via `InstrumentationRegistrar`

### Instrumentation Framework

Each instrumentation implements `Instrumentation`:
```kotlin
internal interface Instrumentation {
    val name: String
    fun shouldApply(project: Project): Boolean  // dependency detection
    fun apply(variant: Variant)                 // register AsmClassVisitorFactory
}
```

`InstrumentationRegistrar` holds the list of all instrumentations. Adding a new one requires:
1. New `XxxClassVisitorFactory` + `XxxClassVisitor` in `instrumentation/xxx/`
2. New `XxxInstrumentation` implementing the interface
3. Add to the list in `InstrumentationRegistrar`

No changes needed to `BugseePlugin`, extension, or other instrumentations.

#### OkHttp Instrumentation
- **Gate:** `com.bugsee:bugsee-okhttp` dependency present
- **What:** Injects `BugseeOkHttpInterceptor` before every `OkHttpClient.Builder.build()` call
- **How:** Inserts NEW + DUP + INVOKESPECIAL `<init>` + INVOKEVIRTUAL `addInterceptor` before the `build()` INVOKEVIRTUAL
- **Scope:** `InstrumentationScope.ALL` — OkHttp clients may be in libraries

#### Log Instrumentation
- **Gate:** `com.bugsee:bugsee-android` dependency present
- **What:** Redirects all `android.util.Log` static calls to `BugseeLogAdapter`
- **How:** Owner replacement in `visitMethodInsn` — same method name, same descriptor, different owner
- **Coverage:** 14 overloads: v(2), d(2), i(2), w(3), e(2), wtf(3)
- **Exclusions:** `com.bugsee.*`, `android.*` packages (prevents recursion — BugseeLogAdapter itself calls Log)

#### Thread Instrumentation
- **Gate:** `com.bugsee:bugsee-android` dependency present
- **What:** Injects `BugseeThreadAdapter.registerThread()` as the first instruction of `run()V`
- **How:** Overrides `visitCode()` to emit a single `INVOKESTATIC` before any other instructions
- **Filter:** `isInstrumentable` checks `classData.interfaces` for `java.lang.Runnable` or `classData.superClasses` for `java.lang.Thread`
- **Purpose:** Builds Java-to-native thread ID mapping for native crash reporting

### Upload Protocol

Two-stage upload via `SymbolUploader`:
1. **POST** JSON metadata to `{endpoint}/apps/{appToken}/symbols` → receives presigned URL
2. **PUT** file to presigned URL

Error handling:
- Code `16004` → `SymbolAlreadyExistsError` (mapping already uploaded, skip)
- `ApplicationNotFoundError` → invalid app token
- Uses `StandardHttpRequestRetryHandler` for transient failures

### App Token Resolution (`AppTokenResolver`)

Priority order:
1. `appTokenByVariant` closure: `(variantName: String) -> String?`
2. `AppTokenProvider` interface: `getAppToken(variantName: String): String?`
3. `defaultAppToken` property
4. `com.bugsee.android.APP_TOKEN` meta-data in manifest (supports `@string/` references)

### DSL Extension (`BugseePluginExtension`)

```kotlin
bugsee {
    endpoint.set("https://api.bugsee.com")  // API endpoint
    debug.set(false)                         // Enable debug logging
    ndk.set(false)                           // Enable NDK symbol upload
    instrumentationEnabled.set(true)         // Enable bytecode instrumentation

    appToken("your-token")                   // Default token
    appToken { variantName -> "token" }      // Per-variant token
    appToken(myAppTokenProvider)             // Strong-typed provider
}
```

All properties use `Property<T>` for lazy evaluation and Gradle configuration avoidance.

## Publishing

Maven publishing is configured inline in `build.gradle.kts`:
- Release repo: Sonatype OSS staging (`RELEASE_REPOSITORY_URL` or default)
- Snapshot repo: Sonatype OSS snapshots (`SNAPSHOT_REPOSITORY_URL` or default)
- Credentials: `NEXUS_USERNAME` / `NEXUS_PASSWORD` properties
- Signing: conditional — only required for release builds with `publish` task

## Code Review

After every code change (adding new files, modifying existing code, or refactoring), run the **review** agent to perform a code review before considering the task complete. Invoke it with:

```
@review
```

This agent reviews the diff for correctness, security, Kotlin/Gradle conventions, ASM bytecode safety, and compatibility. Address any "Needs Changes" findings before finalizing.

## Important Development Notes

1. **AGP compileOnly** — AGP is on the classpath at runtime; `compileOnly` avoids version locking the consuming project.
2. **No FeatureVariant** — removed in AGP 5.0; dynamic features use standard applicationVariants.
3. **SingleArtifact.MERGED_MANIFEST** — modern AGP 8.x manifest transformation API.
4. **Reflection for sourceSets** — `IconResolver` and `StringResourceResolver` use reflection to access Android extension APIs since they're compileOnly.
5. **ASM 9** — all visitors use `Opcodes.ASM9` for maximum compatibility.
6. **COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS** — safe: recomputes stack map frames only for methods we touch.
7. **isInstrumentable exclusions** — always exclude `com.bugsee.*` to prevent recursion; each instrumentation also excludes its target framework package.
8. **`sdk-min-version.txt` tracks the SDK release** — this file at the plugin root declares the minimum-compatible Bugsee Android SDK version. `processResources` packages it as `bugsee-sdk-min-version.txt`; it is read at runtime as `BugseePlugin.MIN_SDK_VERSION` and used as the floor (`[MIN_SDK_VERSION,)`) when auto-adding the core SDK to apps that don't declare it. It is **separate** from the plugin's own `version.txt`. **When the Bugsee Android SDK version changes — especially a new major/release — bump `sdk-min-version.txt` to match; never leave it on a `-beta` after the SDK ships a stable release** (e.g. the 7.0.0 release required bumping it from `7.0.0-beta13` → `7.0.0`).
