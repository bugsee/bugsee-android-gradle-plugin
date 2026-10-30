pluginManagement {
    // Source the Bugsee plugin from its development project directory so
    // the plugin under test runs in the SAME classloader as AGP (loaded
    // via the standard `plugins { id("com.android.application") }`
    // resolution from `google()` below). TestKit's `withPluginClasspath()`
    // alternative puts the plugin under test on a separate classloader,
    // which fails when the plugin references AGP types directly
    // (`SingleArtifact`, `KotlinCompilerPluginSupportPlugin`, etc.).
    val bugseePluginDir = settings.providers
        .gradleProperty("bugseePluginProjectDir").orNull
    // Matrix legs on a newer Gradle consume the plugin as a PRE-BUILT jar from a local Maven repo
    // (`bugseePluginRepo` + `bugseePluginVersion`, written into gradle.properties by FixtureProject):
    // an included build is compiled by the Gradle under test, and the plugin's own Kotlin 2.1 compiler
    // cannot read the newest Gradle APIs. A jar is also what real consumers get.
    val bugseePluginRepo = settings.providers.gradleProperty("bugseePluginRepo").orNull
    val bugseePluginVersion = settings.providers.gradleProperty("bugseePluginVersion").orNull
    if (bugseePluginRepo != null) {
        requireNotNull(bugseePluginVersion) { "bugseePluginRepo needs bugseePluginVersion" }
        resolutionStrategy.eachPlugin {
            if (requested.id.id == "com.bugsee.android.gradle") useVersion(bugseePluginVersion)
        }
    } else if (bugseePluginDir != null) {
        includeBuild(bugseePluginDir)
    }

    repositories {
        if (bugseePluginRepo != null) {
            maven { url = uri(bugseePluginRepo) }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Local stub-SDK repo published by the integrationTest task. Provides
        // `com.bugsee:bugsee-android:99.0.0` so the fixture's compileOnly
        // declaration resolves and the plugin's dependency detector recognizes
        // the SDK as present (triggering AppStartupTracing registration).
        val stubRepo = settings.providers
            .gradleProperty("bugseeStubSdkRepo").orNull
        if (stubRepo != null) {
            maven {
                url = uri(stubRepo)
                metadataSources {
                    mavenPom()
                    artifact()
                }
            }
        }
        // The Bugsee Compose compiler-plugin artifacts (published by the integrationTest task),
        // which the Kotlin Gradle plugin resolves into the compilation's plugin classpath.
        val composeRepo = settings.providers
            .gradleProperty("bugseeComposePluginRepo").orNull
        if (composeRepo != null) {
            maven { url = uri(composeRepo) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-pipeline-fixture"
include(":app", ":stub")
// `:app17` (foundation 1.7.8, issue #158) is included only by the test that builds it (`-PbugseeFixtureApp17=true`); it needs the Kotlin 2.x Compose plugin.
if (providers.gradleProperty("bugseeFixtureApp17").orNull == "true") include(":app17")
