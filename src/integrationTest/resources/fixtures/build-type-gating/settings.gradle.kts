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
    if (bugseePluginDir != null) {
        includeBuild(bugseePluginDir)
    }

    repositories {
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
        google()
        mavenCentral()
    }
}

rootProject.name = "app-startup-tracing-fixture"
include(":app")
