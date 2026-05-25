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
     * Variant of [build] that runs an arbitrary list of Gradle tasks
     * instead of the hardcoded `:app:assembleDebug`. Used by
     * integration tests that need to drive `:app:bundleDebug` (AAB),
     * multiple flavor variants, etc. — anything beyond the standard
     * APK assembly path.
     */
    fun buildTasks(
        tasks: List<String>,
        tier: String? = null,
        vararg extraArgs: String,
    ): BuildResult {
        require(tasks.isNotEmpty()) { "must run at least one task" }
        val args = mutableListOf<String>().apply {
            addAll(tasks)
            add("-PbugseeStubSdkRepo=${requireSystemProperty("bugsee.testkit.stubSdkRepo")}")
            add("-PbugseePluginProjectDir=${requireSystemProperty("bugsee.testkit.pluginProjectDir")}")
            add("--stacktrace")
            if (tier != null) add("-PbugseeStartupTier=$tier")
            addAll(extraArgs)
        }
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(args)
            .withEnvironment(testKitEnvironment())
            .forwardOutput()
        return runner.build()
    }

    /**
     * Read the POST-TRANSFORM merged manifest for the given variant
     * — i.e. the [com.bugsee.android.gradle.manifest.BugseeManifestTask]
     * output, NOT AGP's pre-transform `processDebugMainManifest`
     * input. Returns `null` if no such manifest can be located.
     *
     * The plugin wires the manifest task via
     * `artifacts.use(...).wiredWithFiles(...).toTransform(SingleArtifact.MERGED_MANIFEST)`.
     * AGP picks the on-disk output location for the transform; it
     * doesn't necessarily overwrite the pre-transform input. So we
     * scan every `AndroidManifest.xml` under
     * `app/build/intermediates/` (and `app/build/outputs/`) and return
     * the first one that carries the `com.bugsee.android.BUILD_UUID`
     * meta-data we know the transform injects. If no such file is
     * present, return whichever AGP wrote pre-transform — that
     * answers "did the transform run at all?" with a `null`
     * BUILD_UUID, which is itself a useful failure mode for tests
     * to assert against.
     *
     * The variant name is used to disambiguate when multiple
     * variants build into the same project (multi-flavor matrix):
     * we restrict the search to manifests whose intermediate path
     * contains the variant name. Falls back to "any manifest with
     * the marker" when no variant-tagged match is found.
     */
    fun readMergedManifest(variant: String): String? {
        val variantLower = variant.lowercase()
        val buildRoots = listOf(
            projectDir.resolve("app/build/intermediates"),
            projectDir.resolve("app/build/outputs"),
        ).filter { it.isDirectory }

        val candidates = buildRoots.asSequence()
            .flatMap { it.walk() }
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .toList()

        // Prefer transform outputs that match the variant AND carry
        // the BUILD_UUID marker (post-transform).
        val markered = candidates.filter { f ->
            f.path.lowercase().contains("/$variantLower/") &&
                f.readText().contains("com.bugsee.android.BUILD_UUID")
        }
        if (markered.isNotEmpty()) return markered.first().readText()

        // Fallback 1: any manifest under the variant subtree
        // (pre-transform — the transform didn't run, or wrote
        // somewhere we don't search).
        val variantTagged = candidates.firstOrNull { f ->
            f.path.lowercase().contains("/$variantLower/")
        }
        if (variantTagged != null) return variantTagged.readText()

        // Fallback 2: any AndroidManifest under intermediates with
        // BUILD_UUID — better than nothing for tests that don't
        // care about the variant tag.
        val anyMarkered = candidates.firstOrNull { f ->
            f.readText().contains("com.bugsee.android.BUILD_UUID")
        }
        return anyMarkered?.readText()
    }

    /**
     * Extract the BUILD_UUID `<meta-data>` value out of a merged
     * manifest text. Returns `null` if the meta-data is absent — the
     * task didn't run, or the optimization-options short-circuit
     * fired before injection. Mirrors the parser used in the
     * unit-level determinism tests.
     */
    fun extractBuildUuid(manifestText: String): String? {
        val re = Regex(
            "<meta-data\\s+android:name=\"com\\.bugsee\\.android\\.BUILD_UUID\"\\s+android:value=\"([^\"]+)\""
        )
        return re.find(manifestText)?.groupValues?.get(1)
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
