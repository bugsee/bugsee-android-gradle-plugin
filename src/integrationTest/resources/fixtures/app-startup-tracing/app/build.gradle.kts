import com.bugsee.android.gradle.StartupTier

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.bugsee.android.gradle")
}

// Mirror the root group so this submodule is also treated as a Bugsee
// internal by the auto-install short-circuit (project.group prefix check).
group = "com.bugsee.testfixture"

android {
    namespace = "com.example.fixture"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    // `-PbugseeFixtureInitProvider=hook|nohook` compiles in a stand-in
    // com.bugsee.library.BugseeInitProvider with or without initializeExtensions(),
    // so the ExtensionsInit lane has a real class to rewrite. Absent by default.
    when (project.findProperty("bugseeFixtureInitProvider")?.toString()) {
        "hook" -> sourceSets.getByName("main").java.srcDir("src/extensionsHook/java")
        "nohook" -> sourceSets.getByName("main").java.srcDir("src/extensionsNoHook/java")
    }

    // Optional product-flavor wiring. When the
    // `bugseeFixtureMultiFlavor` property is `true` the fixture
    // exposes two flavors (`free`, `paid`) so multi-variant
    // integration tests can assert per-variant BUILD_UUID
    // discrimination + end-to-end manifest behavior. Default-off so
    // existing tier-matrix tests are unaffected.
    val multiFlavor = project.findProperty("bugseeFixtureMultiFlavor")?.toString() == "true"
    if (multiFlavor) {
        flavorDimensions += "tier"
        productFlavors {
            create("free") { dimension = "tier" }
            create("paid") { dimension = "tier" }
        }
    }
}

// Stub Bugsee SDK is published by the plugin's integrationTest task under
// the real `com.bugsee:bugsee-android:99.0.0` Maven coordinates into a
// per-build local repo. The version is a high sentinel (>= app-startup
// tracing's MIN_SDK_VERSION_WITH_DISPATCHER) so the version gate in
// AppStartupTracingInstrumentation.shouldApply does NOT skip instrumentation;
// it must stay in lockstep with the publish task in the plugin's
// build.gradle.kts. Declaring it as compileOnly here serves two purposes:
//   (1) Provides the dispatcher + annotation classes on AGP's compile-time
//       classpath so `ClassContext.loadClassData` finds them and the
//       plugin proceeds with bytecode wrapping.
//   (2) Makes `DependencyDetector.hasBugseeDependency(project, "bugsee-android")`
//       return true, triggering AppStartupTracing registration.
// compileOnly because the fixture is only assembled — never executed —
// and we don't want the stub bytecode in the dex output.
// `-PbugseeFixtureNoCoreSdk=true` hides the core SDK from dependency detection (same
// stub classes, but as a bare file rather than com.bugsee:bugsee-android) and turns
// off auto-add, so the ExtensionsInit lane cannot apply while every manifest-strip
// precondition still holds. Used by BugseeManifestOptimizeExtensionsTest.
val noCoreSdk = project.findProperty("bugseeFixtureNoCoreSdk")?.toString() == "true"

dependencies {
    if (noCoreSdk) {
        val stubRepo = project.findProperty("bugseeStubSdkRepo").toString()
        compileOnly(files("$stubRepo/com/bugsee/bugsee-android/99.0.0/bugsee-android-99.0.0.jar"))
    } else {
        compileOnly("com.bugsee:bugsee-android:99.0.0")
    }
    implementation("androidx.startup:startup-runtime:1.1.1")
    // `-PbugseeFixtureLibJar=<absolute path>` adds a prebuilt third-party-style library jar
    // (used by InstrumentationFallbackIntegrationTest, whose classes cannot come from javac).
    project.findProperty("bugseeFixtureLibJar")?.toString()?.let { implementation(files(it)) }
}

bugsee {
    if (noCoreSdk) sdkAutoLoad.set(false)
    // Upload tasks (build-info + size analysis) are OFF by default in
    // this fixture — they need a Bugsee app token and are otherwise
    // unrelated to app-startup tracing. BundleUploadConfigCacheTest
    // flips `bugseeFixtureBuildInfoCc=true` to turn build-info ON for
    // ALL build types, so the dependency-collection task wiring is
    // realized and its configuration-cache compatibility can be
    // asserted. No app token is configured, so the upload task skips the
    // network at execution (BundleUploadTask returns early on an
    // unresolved token) — the test only cares that CONFIGURATION
    // serializes cleanly under CC.
    val buildInfoCc = project.findProperty("bugseeFixtureBuildInfoCc")?.toString() == "true"
    buildInfo {
        enabled.set(buildInfoCc)
        if (buildInfoCc) {
            allBuildTypes.set(true)
        }
    }

    // Tier comes from the bugseeStartupTier project property — when
    // missing the typed DSL property is left unset and the plugin's
    // resolver falls through to the Gradle-property / manifest source.
    // Passing the property as `bugseeStartupTier=DETAILED` exercises
    // the typed DSL path.
    val tier = project.findProperty("bugseeStartupTier")?.toString()
    if (!tier.isNullOrBlank()) {
        instrumentation {
            startupTier.set(StartupTier.valueOf(tier))
        }
    }

    // Instrumentation excludes — exercised by InstrumentationExcludesIntegrationTest.
    // `-PbugseeFixtureExcludes=<comma-separated patterns, dotted or slash form>` opts those
    // classes out of ALL Bugsee bytecode instrumentation.
    val excludePatterns = project.findProperty("bugseeFixtureExcludes")?.toString()
    if (!excludePatterns.isNullOrBlank()) {
        instrumentation {
            excludePatterns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { excludes.add(it) }
        }
    }

    // `-PbugseeFixtureAssetsDependsOn=<name>` exercises the opt-in `assetsDependsOn` setting.
    project.findProperty("bugseeFixtureAssetsDependsOn")?.toString()?.let { assetsDependsOn(it) }

    // Optional opt-out for the extension-stripping path. Defaults to
    // the plugin's default (`true`) when the property is absent. Used
    // by the manifest-stripping integration test to exercise BOTH
    // sides of the `optimizeExtensionsLoading` switch from the same
    // fixture.
    val optimizeExt = project.findProperty("bugseeFixtureOptimizeExtensions")?.toString()
    if (optimizeExt != null) {
        optimizeExtensionsLoading.set(optimizeExt == "true")
    }
}

// Opt-in: applies a plugin class named `FlutterPlugin`, which is how the Bugsee plugin recognises
// Flutter's Gradle plugin, so the "Flutter applied but its copy task is missing" warning can be exercised.
if (project.findProperty("bugseeFixtureFlutterPluginStandIn")?.toString() == "true") {
    class FlutterPlugin : Plugin<Project> {
        override fun apply(target: Project) = Unit
    }
    apply<FlutterPlugin>()
}

// Opt-in: simulates Flutter's Gradle plugin, whose `copyFlutterAssets<Variant>`
// task writes into AGP's merged-assets directory without AGP knowing
// (issue #33). Off by default so other tests are unaffected.
if (project.findProperty("bugseeFixtureFakeFlutter")?.toString() == "true") {
    val mergedAssets = layout.buildDirectory.dir("intermediates/assets/debug/mergeDebugAssets")
    val flutterSrc = layout.projectDirectory.file("fake-flutter/flutter_marker.txt")
    // Mirrors Flutter: the copy depends on mergeAssets and writes into its
    // output dir; processResources depends on the copy.
    // `-PbugseeFixtureFakeFlutterTaskName=<name>` simulates a renamed Flutter task.
    val flutterTaskName = project.findProperty("bugseeFixtureFakeFlutterTaskName")?.toString()
        ?: "copyFlutterAssetsDebug"
    val copyFlutter = tasks.register<Copy>(flutterTaskName) {
        from(flutterSrc)
        into(mergedAssets)
        dependsOn("mergeDebugAssets")
    }
    // Flutter also declares the copy as a dependency of the tasks that read the
    // merged dir (FlutterPlugin.kt `tasksToCheck`: compress<Variant>Assets, ...).
    tasks.matching { it.name == "processDebugResources" || it.name == "compressDebugAssets" }.configureEach {
        dependsOn(copyFlutter)
    }
}

// Explicit, to match compileOptions above. The `kotlinOptions { jvmTarget }` it replaces is a hard
// error from Kotlin 2.3; the `compilerOptions` DSL exists from Kotlin 2.0.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
