// Root build script for the feature-test-modules fixture: an application with a dynamic-feature module
// and a test module (`com.android.test`, targetProjectPath = :app), each applying the Bugsee plugin like a
// convention plugin would. Used by `BugseeFeatureAndTestModulesTest` (issue #163).
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("com.android.dynamic-feature") version "8.6.0" apply false
    id("com.android.test") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.bugsee.android.gradle") apply false
}

// Prints the dependencies declared for each module's runtime classpath, at execution time. Reading
// `incoming.dependencies` resolves the parent configurations' `withDependencies` actions, which is what the
// plugin's SDK auto-add hooks into (neither `implementation.dependencies` nor the `dependencies` report
// fire it on every Gradle).
subprojects {
    tasks.register("printImplementationDeps") {
        doLast {
            val deps = project.configurations.getByName("debugRuntimeClasspath").incoming.dependencies
            println("IMPL[${project.path}]: " + deps.joinToString(",") { "${it.group}:${it.name}:${it.version}" })
        }
    }
}
