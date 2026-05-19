package com.bugsee.android.gradle.integration.harness

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Materializes a copy of the static fixture project under
 * `src/integrationTest/resources/fixtures/<name>/` into a temporary
 * directory, returns a [GradleRunner] wired to it.
 *
 * Shared between every TestKit test class — they get a fresh project
 * directory per test (via JUnit's `TemporaryFolder` rule) but reuse the
 * same Gradle user home so the Gradle daemon and dependency cache stay
 * warm across the matrix.
 */
internal class FixtureProject private constructor(
    val projectDir: File,
) {

    /**
     * Build the fixture's `app:assembleDebug` and return the
     * [BuildResult] for assertion. Forwards the test-time stub-SDK jar
     * path so the fixture's `app/build.gradle.kts` can pin it as
     * `compileOnly`, plus the JDK 17 JAVA_HOME for the daemon.
     *
     * @param tier optional [StartupTier] enum value to pass as the typed
     *   DSL property. When null, the DSL is left unset and the resolver
     *   falls through to whichever Gradle-property / manifest source the
     *   test wants to exercise.
     * @param extraArgs additional Gradle args (e.g.
     *   `--configuration-cache`, `--info`).
     */
    fun build(tier: String? = null, vararg extraArgs: String): BuildResult {
        val args = mutableListOf<String>(
            ":app:assembleDebug",
            "-PbugseeStubSdkRepo=${requireSystemProperty("bugsee.testkit.stubSdkRepo")}",
            "-PbugseePluginProjectDir=${requireSystemProperty("bugsee.testkit.pluginProjectDir")}",
            "--stacktrace",
        )
        if (tier != null) {
            args += "-PbugseeStartupTier=$tier"
        }
        args.addAll(extraArgs)

        // Not using withPluginClasspath() — the fixture's settings.gradle.kts
        // sources the Bugsee plugin via pluginManagement.includeBuild(...)
        // so it ends up in the buildscript classloader alongside AGP and the
        // Kotlin Gradle Plugin API, avoiding cross-classloader NoClassDef.
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(args)
            .withEnvironment(testKitEnvironment())
            .forwardOutput()

        return runner.build()
    }

    /**
     * Same as [build] but allows the build to fail and returns the
     * [BuildResult] for failure-path assertions.
     */
    fun buildAndFail(tier: String? = null, vararg extraArgs: String): BuildResult {
        val args = mutableListOf<String>(
            ":app:assembleDebug",
            "-PbugseeStubSdkRepo=${requireSystemProperty("bugsee.testkit.stubSdkRepo")}",
            "-PbugseePluginProjectDir=${requireSystemProperty("bugsee.testkit.pluginProjectDir")}",
            "--stacktrace",
        )
        if (tier != null) {
            args += "-PbugseeStartupTier=$tier"
        }
        args.addAll(extraArgs)

        // Not using withPluginClasspath() — the fixture's settings.gradle.kts
        // sources the Bugsee plugin via pluginManagement.includeBuild(...)
        // so it ends up in the buildscript classloader alongside AGP and the
        // Kotlin Gradle Plugin API, avoiding cross-classloader NoClassDef.
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(args)
            .withEnvironment(testKitEnvironment())
            .forwardOutput()

        return runner.buildAndFail()
    }

    /** Walks AGP's post-transform intermediates and indexes dispatcher calls. */
    fun indexBytecode(): InstrumentedBytecodeIndex.Index {
        return InstrumentedBytecodeIndex.walk(projectDir)
    }

    companion object {

        /**
         * Materializes the fixture at `fixtures/<name>` into [targetDir].
         * The target must be empty; the caller is responsible for
         * creating it (typically via JUnit `TemporaryFolder`).
         */
        fun materialize(name: String, targetDir: File): FixtureProject {
            require(targetDir.isDirectory && targetDir.listFiles().isNullOrEmpty()) {
                "target dir must be an empty directory: $targetDir"
            }
            val source = File(requireSystemProperty("bugsee.testkit.pluginProjectDir"))
                .resolve("src/integrationTest/resources/fixtures/$name")
            require(source.isDirectory) { "fixture not found: $source" }
            copyTree(source.toPath(), targetDir.toPath())

            // Append `org.gradle.java.home=<jdk17>` to gradle.properties so
            // AGP (which mandates JDK 17 since AGP 8.0) can run, regardless
            // of the JDK the test runner itself uses.
            // `GradleRunner.withEnvironment(JAVA_HOME)` is not enough — the
            // daemon JVM is selected from this property at fork time.
            val javaHome = requireSystemProperty("bugsee.testkit.javaHome")
            val props = File(targetDir, "gradle.properties")
            val javaHomeLine = "org.gradle.java.home=" + javaHome.replace("\\", "\\\\")
            val existing = if (props.isFile) props.readText() else ""
            val rewritten = buildString {
                var saw = false
                for (line in existing.lines()) {
                    if (line.startsWith("org.gradle.java.home=")) {
                        appendLine(javaHomeLine)
                        saw = true
                    } else if (line.isNotEmpty() || isNotEmpty()) {
                        appendLine(line)
                    }
                }
                if (!saw) appendLine(javaHomeLine)
            }
            props.writeText(rewritten)

            return FixtureProject(targetDir)
        }

        @Throws(IOException::class)
        private fun copyTree(src: Path, dst: Path) {
            Files.walk(src).use { stream ->
                stream.forEach { entry ->
                    val rel = src.relativize(entry)
                    val target = dst.resolve(rel.toString())
                    if (Files.isDirectory(entry)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        private fun requireSystemProperty(key: String): String {
            return System.getProperty(key)
                ?: error("missing system property $key — is the integrationTest task wired to forward it?")
        }

        /**
         * Build the env map the TestKit daemon is launched with. Must
         * forward enough state for AGP to find both its JDK and the
         * Android SDK.
         */
        private fun testKitEnvironment(): Map<String, String> {
            val env = HashMap<String, String>()
            env["JAVA_HOME"] = requireSystemProperty("bugsee.testkit.javaHome")
            // AGP requires either ANDROID_HOME (or ANDROID_SDK_ROOT) or a
            // `sdk.dir` line in `local.properties`. Forward whichever the
            // outer build has.
            val androidHome = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: System.getProperty("user.home") + "/Library/Android/sdk"
            env["ANDROID_HOME"] = androidHome
            env["ANDROID_SDK_ROOT"] = androidHome
            // Forward HOME so Gradle's user home (`~/.gradle`) and the
            // dependency cache are reused across test runs.
            System.getenv("HOME")?.let { env["HOME"] = it }
            // PATH is occasionally consulted by `cmake` / `ndk-build`
            // during AGP's preBuild — forward.
            System.getenv("PATH")?.let { env["PATH"] = it }
            return env
        }
    }
}
