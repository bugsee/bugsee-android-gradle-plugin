package com.bugsee.android.gradle

import com.bugsee.android.gradle.config.RecordingLogger
import com.bugsee.android.gradle.config.SettingDefinitions
import com.bugsee.android.gradle.config.SettingsFileParser
import com.bugsee.android.gradle.config.SettingsResolver
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the project-level (apply-time) decisions that run before variants exist:
 * the `implementation` auto-add of Bugsee modules and the global-time reads of
 * `debug` / `sdkAutoLoad` / `feedback` / `leak.enabled`.
 *
 * The real `withDependencies` action runs: the plugin is applied to a
 * `ProjectBuilder` project and the `implementation` configuration's dependency
 * actions are triggered through its incoming dependency set.
 *
 * The compat tests compare the new resolver-based gates with a verbatim copy of
 * the OLD Property-based logic for builds with no per-variant input.
 */
class AutoAddScopeTest {

    // ── Auto-add scope ──────────────────────────────────────────────

    @Test
    fun ndkAddedWhenOnlyAVariantEnablesIt() {
        val viaFile = Harness("variant.staging.plugin.ndk.enabled=true\n")
        assertTrue("bugsee-android-ndk" in viaFile.autoAdded(), "a file variant entry enables NDK")

        val viaDsl = Harness(null) { variant("release") { it.ndk.enabled.set(true) } }
        assertTrue("bugsee-android-ndk" in viaDsl.autoAdded(), "a DSL variant block enables NDK")
    }

    @Test
    fun ndkNotAddedWhenNowhereEnabled() {
        assertFalse("bugsee-android-ndk" in Harness(null).autoAdded())

        val allFalse = Harness("variant.staging.plugin.ndk.enabled=false\n") {
            ndk.enabled.set(false)
            variant("release") { it.ndk.enabled.set(false) }
        }
        assertFalse("bugsee-android-ndk" in allFalse.autoAdded())
    }

    @Test
    fun okhttpExplicitFalseEverywhereSkipsAutoAdd() {
        val h = Harness(
            "plugin.instrumentation.okhttp=false\nvariant.staging.plugin.instrumentation.okhttp=false\n",
            gradleProperties = mapOf("bugsee.instrumentation.okhttp" to "true"),
        ) {
            instrumentation.okhttp.set(false)
            variant("release") { it.instrumentation.okhttp.set(false) }
        }
        h.declare("com.squareup.okhttp3:okhttp:4.12.0")

        assertFalse("bugsee-android-okhttp" in h.autoAdded(), "every scope says false: the Gradle property is not consulted")
    }

    @Test
    fun variantOnlyDisableDoesNotDisableAutoAddProjectWide() {
        val viaDsl = Harness(null) { variant("release") { it.instrumentation.okhttp.set(false) } }
        viaDsl.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertTrue("bugsee-android-okhttp" in viaDsl.autoAdded(), "global unset: other variants keep the default (on)")

        val viaFile = Harness("variant.release.plugin.instrumentation.okhttp=false\n")
        viaFile.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertTrue("bugsee-android-okhttp" in viaFile.autoAdded())
    }

    @Test
    fun okhttpUnsetFallsBackToGradleProperty() {
        val off = Harness(null, gradleProperties = mapOf("bugsee.instrumentation.okhttp" to "false"))
        off.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertFalse("bugsee-android-okhttp" in off.autoAdded(), "Gradle property false is honoured")

        val unset = Harness(null)
        unset.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertTrue("bugsee-android-okhttp" in unset.autoAdded(), "nothing set anywhere: default true")

        // A variant that enables it overrides the Gradle property (any scope wins).
        val variantOn = Harness(
            "variant.staging.plugin.instrumentation.okhttp=true\n",
            gradleProperties = mapOf("bugsee.instrumentation.okhttp" to "false"),
        )
        variantOn.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertTrue("bugsee-android-okhttp" in variantOn.autoAdded())
    }

    @Test
    fun preferSettingsFileMakesFileFeedbackWinAtApplyTime() {
        val prefer = Harness("plugin.feedback=true\n") {
            feedback.set(false)
            preferSettingsFile.set(true)
        }
        assertTrue("bugsee-android-feedback" in prefer.autoAdded(), "file beats DSL with preferSettingsFile")

        val default = Harness("plugin.feedback=true\n") { feedback.set(false) }
        assertFalse("bugsee-android-feedback" in default.autoAdded(), "DSL beats file by default")
    }

    @Test
    fun sdkAutoLoadAndLeakFollowPreferSettingsFile() {
        val prefer = Harness("plugin.sdkAutoLoad=false\nplugin.leak.enabled=true\n", declareCore = false) {
            sdkAutoLoad.set(true)
            leak.enabled.set(false)
            preferSettingsFile.set(true)
        }
        val added = prefer.autoAdded()
        assertFalse("bugsee-android" in added, "file sdkAutoLoad=false wins")
        assertTrue("bugsee-android-leak" in added, "file leak.enabled=true wins")
    }

    // ── Detector scope: production configurations only (#47) ────────

    @Test
    fun detectorsIgnoreLibrariesOutsideProductionConfigurations() {
        val nonProduction = listOf(
            "testImplementation", "androidTestImplementation", "testFixturesApi", "compileOnly",
            "compileOnlyApi", "lintChecks", "annotationProcessor", "kapt", "ksp",
            "testDebugImplementation", "debugUnitTestImplementation", "debugAndroidTestImplementation",
            "androidTestUtil", "commonTestImplementation",
        )
        for (conf in nonProduction) {
            val h = Harness(null)
            h.project.configurations.maybeCreate(conf)
            h.declareOn(conf, "com.squareup.okhttp3:okhttp:4.12.0")
            h.declareOn(conf, "androidx.compose.ui:ui:1.6.0")
            h.declareOn(conf, "io.ktor:ktor-client-core:2.3.0")
            h.declareOn(conf, "io.ktor:ktor-client-core:3.0.0")
            h.declareOn(conf, "org.chromium.net:cronet-api:119.6045.31")
            assertEquals(emptySet(), h.autoAdded(), "$conf must not trigger any auto-add")
        }
    }

    @Test
    fun detectorsCountProductionConfigurations() {
        for (conf in listOf("api", "runtimeOnly", "debugImplementation", "freeReleaseApi", "androidMainImplementation", "stagingImplementation", "debugCompilationImplementation")) {
            val h = Harness(null)
            h.project.configurations.maybeCreate(conf)
            h.declareOn(conf, "com.squareup.okhttp3:okhttp:4.12.0")
            assertEquals(setOf("bugsee-android-okhttp"), h.autoAdded(), conf)
        }
    }

    @Test
    fun isProductionConfigurationClassifiesRealNames() {
        val production = listOf(
            "implementation", "api", "runtimeOnly", "debugImplementation", "releaseApi", "stagingImplementation",
            "freeReleaseRuntimeOnly", "androidMainImplementation", "commonMainApi", "debugCompilationImplementation",
        )
        val other = listOf(
            "releaseApiElements", "debugCompileOnlyApi", "debugRuntimeClasspath", "screenshotTestImplementation",
            "androidUnitTestImplementation", "jvmTestCompilationImplementation", "compileOnly", "testImplementation",
            "androidTestImplementation", "testFixturesImplementation", "lintChecks", "kapt", "debugAnnotationProcessor",
        )
        production.forEach { assertTrue(DependencyDetector.isProductionConfiguration(it), "$it is production") }
        other.forEach { assertFalse(DependencyDetector.isProductionConfiguration(it), "$it is not production") }
    }

    /**
     * KNOWN LIMITATION (documented in the README): the rule is name-based, so a flavor or build type
     * whose name ends in a capitalised `Test` word (`latestTest`, `uiTest`) has its configurations
     * (`latestTestImplementation`) classified as test configurations and its libraries are not
     * detected. A name-only rule cannot tell it from `testImplementation`-style names; the fix is to
     * use the variants' configuration hierarchy once variants exist (tracked with #50).
     */
    @Test
    fun knownLimitationFlavorNameEndingInTestIsNotDetected() {
        val h = Harness(null)
        h.project.configurations.maybeCreate("latestTestImplementation")
        h.declareOn("latestTestImplementation", "com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(emptySet(), h.autoAdded())
    }

    /**
     * `hasComposeDependency` is the first gate of `isApplicable` (the Kotlin compiler plugin), so
     * test-only Compose must not apply it. `isApplicable` itself cannot be driven end to end here:
     * it also needs a real Kotlin Gradle plugin to report the consumer's Kotlin version, and
     * returns false without one whatever the dependencies are.
     */
    @Test
    fun composeGateOfCompilerPluginIgnoresTestOnlyCompose() {
        val h = Harness(null)
        h.project.configurations.maybeCreate("testImplementation")
        h.declareOn("testImplementation", "androidx.compose.ui:ui:1.6.0")
        val plugin = h.project.plugins.getPlugin(BugseePlugin::class.java)
        assertFalse(plugin.hasComposeDependency(h.project), "test-only Compose")

        h.declare("androidx.compose.ui:ui:1.6.0")
        assertTrue(plugin.hasComposeDependency(h.project), "Compose in implementation")
    }

    @Test
    fun okhttpInTestAndImplementationStillAdds() {
        val h = Harness(null)
        h.project.configurations.maybeCreate("testImplementation")
        h.declareOn("testImplementation", "com.squareup.okhttp3:okhttp:4.12.0")
        h.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(setOf("bugsee-android-okhttp"), h.autoAdded())
    }

    // ── Backward compatibility (no per-variant input) ───────────────

    /**
     * For every combination of DSL value × root file value × Gradle property, with no
     * `variant` block and no `variant.*` key, the new gate returns exactly what the
     * old Property-based gate returned on the same (applier-configured) extension.
     */
    @Test
    fun isAutoAddEnabledMatchesOldBehaviourWithoutVariantInput() {
        val dslValues = listOf(true, false, null)
        val fileValues = listOf("true", "false", "yes", "bogus", null)
        val gradleValues = listOf("true", "false", "invalid", null)
        for (key in listOf("okhttp", "compose", "ktor")) {
            for (dsl in dslValues) for (fileRaw in fileValues) for (gradle in gradleValues) {
                val case = "key=$key dsl=$dsl file=$fileRaw gradle=$gradle"
                val h = Harness(
                    fileRaw?.let { "plugin.instrumentation.$key=$it\n" },
                    gradleProperties = gradle?.let { mapOf("bugsee.instrumentation.$key" to it) }.orEmpty(),
                ) { dsl?.let { instrumentation.propertyForKey(key)!!.set(it) } }
                val property = h.extension.instrumentation.propertyForKey(key)!!

                val old = oldIsAutoAddEnabled(h.project, property, key)
                val new = BugseePlugin.isAutoAddEnabled(
                    h.project, h.resolver(), SettingDefinitions.booleanByKey("instrumentation.$key"), key,
                )

                assertEquals(old, new, case)
            }
        }
    }

    @Test
    fun composeGateMatchesOldBehaviourWithoutVariantInput() {
        for (key in listOf("compose", "composeSecure")) {
            for (dsl in listOf(true, false, null)) for (fileRaw in listOf("true", "false", "bogus", null)) {
                val h = Harness(fileRaw?.let { "plugin.instrumentation.$key=$it\n" }) {
                    dsl?.let { instrumentation.propertyForKey(key)!!.set(it) }
                }
                val property = h.extension.instrumentation.propertyForKey(key)!!
                val old = !property.isPresent || property.get()

                val new = BugseePlugin.isCompilerFeatureEnabled(
                    h.resolver(), SettingDefinitions.booleanByKey("instrumentation.$key"),
                )

                assertEquals(old, new, "key=$key dsl=$dsl file=$fileRaw")
            }
        }
    }

    /** In default mode the global-time reads equal the raw extension values the old code read. */
    @Test
    fun globalTimeReadsMatchRawExtensionWithoutVariantInput() {
        val keys = listOf("debug", "sdkAutoLoad", "feedback", "leak.enabled", "ndk.enabled")
        for (key in keys) {
            for (dsl in listOf(true, false, null)) for (fileRaw in listOf("true", "false", "bogus", null)) {
                val def = SettingDefinitions.booleanByKey(key)
                val h = Harness(fileRaw?.let { "plugin.$key=$it\n" }) { dsl?.let { def.property(this).set(it) } }
                val raw = def.property(h.extension).get()
                val case = "key=$key dsl=$dsl file=$fileRaw"

                assertEquals(raw, h.resolver().globalValue(def), case)
                assertEquals(raw, h.resolver().anyScopeValue(def) ?: def.property(h.extension).get(), case)
            }
        }
    }

    /** Same modules auto-added as before for a build with no per-variant input. */
    @Test
    fun autoAddedModulesUnchangedWithoutVariantInput() {
        val h = Harness("plugin.ndk.enabled=true\nplugin.instrumentation.ktor=false\n") {
            feedback(true)
            instrumentation.okhttp.set(true)
        }
        h.declare("com.squareup.okhttp3:okhttp:4.12.0")
        h.declare("io.ktor:ktor-client-core:2.3.0")
        h.declare("org.chromium.net:cronet-api:119.6045.31")

        assertEquals(
            setOf("bugsee-android-ndk", "bugsee-android-feedback", "bugsee-android-okhttp", "bugsee-android-cronet"),
            h.autoAdded(),
        )
    }

    // ── Reference: the pre-change logic, verbatim ───────────────────

    private fun oldIsAutoAddEnabled(project: Project, dslProperty: Property<Boolean>, key: String): Boolean {
        if (dslProperty.isPresent) {
            return dslProperty.get()
        }
        val gradleValue = project.findProperty("bugsee.instrumentation.$key")?.toString()
        if (gradleValue != null) {
            val parsed = gradleValue.toBooleanStrictOrNull()
            if (parsed == null) {
                project.logger.warn(
                    "Bugsee: Invalid boolean value '$gradleValue' for Gradle property " +
                        "'bugsee.instrumentation.$key', defaulting to true"
                )
            }
            return parsed ?: true
        }
        return true
    }

    // ── Harness ─────────────────────────────────────────────────────

    private class Harness(
        private val propertiesText: String?,
        gradleProperties: Map<String, String> = emptyMap(),
        declareCore: Boolean = true,
        configure: BugseePluginExtension.() -> Unit = {},
    ) {
        val project: Project = ProjectBuilder.builder().build()
        val extension: BugseePluginExtension
        private val before = mutableSetOf<String>()

        init {
            propertiesText?.let { File(project.projectDir, "bugsee.properties").writeText(it) }
            gradleProperties.forEach { (k, v) -> project.extensions.extraProperties.set(k, v) }
            project.configurations.create("implementation")
            if (declareCore) declare("com.bugsee:bugsee-android:$SDK_VERSION")
            project.plugins.apply(BugseePlugin::class.java)
            extension = project.extensions.getByType(BugseePluginExtension::class.java)
            extension.configure()
        }

        fun declare(coordinate: String) {
            project.dependencies.add("implementation", coordinate)
            before += coordinate.split(":")[1]
        }

        /** Declares [coordinate] on [configuration] without counting it as pre-existing. */
        fun declareOn(configuration: String, coordinate: String) {
            project.dependencies.add(configuration, coordinate)
            if (configuration == "implementation") before += coordinate.split(":")[1]
        }

        /** A resolver over the same extension and file, as the plugin builds it. */
        fun resolver(): SettingsResolver = SettingsResolver(
            extension,
            SettingsFileParser.parse(propertiesText, RecordingLogger(), reportMalformedFile = false),
            project.objects,
            RecordingLogger(),
        )

        /** Runs the `implementation` dependency actions; returns the module names they added. */
        fun autoAdded(): Set<String> {
            val deps: Set<Dependency> = project.configurations.getByName("implementation").incoming.dependencies
            return deps.map { it.name }.filter { it !in before }.toSet()
        }
    }

    private companion object {
        const val SDK_VERSION = "7.0.0"
    }
}
