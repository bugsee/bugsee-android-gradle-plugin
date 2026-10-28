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
        assertFalse("bugsee-android-leak" in added, "file sdkAutoLoad=false also stops the leak extension (#48)")

        val leakOnly = Harness("plugin.leak.enabled=true\n", declareCore = false) {
            leak.enabled.set(false)
            preferSettingsFile.set(true)
        }
        assertTrue("bugsee-android-leak" in leakOnly.autoAdded(), "file leak.enabled=true wins")
    }

    // ── sdkAutoLoad gates every auto-add (issue #48) ────────────────

    // Must trigger every extension computeAutoAdds can add (with enableOptInModules): the equality
    // test below is what keeps AutoAddedExtensions.ALL in step with the code.
    private val allTriggers = listOf(
        "com.squareup.okhttp3:okhttp:4.12.0",
        "androidx.compose.ui:ui:1.6.0",
        "io.ktor:ktor-client-core:2.3.0",
        "io.ktor:ktor-client-core:3.0.0",
        "org.chromium.net:cronet-api:119.6045.31",
    )

    private fun BugseePluginExtension.enableOptInModules() {
        ndk.enabled.set(true)
        leak.enabled.set(true)
        feedback.set(true)
    }

    private fun Harness.declareAllTriggers() = allTriggers.forEach { declare(it) }

    @Test
    fun sdkAutoLoadFalseAddsNothingForAnyTrigger() {
        val h = Harness(null, declareCore = false) {
            sdkAutoLoad.set(false)
            enableOptInModules()
        }
        h.declareAllTriggers()
        assertEquals(emptySet(), h.autoAdded())
    }

    @Test
    fun sdkAutoLoadTrueByDefaultAddsCoreAndAllExtensions() {
        val h = Harness(null, declareCore = false) { enableOptInModules() }
        h.declareAllTriggers()
        assertEquals(
            // AutoAddedExtensions.ALL is the list the ExtensionFirstRelease guard test checks, so it
            // must be exactly what computeAutoAdds can add.
            setOf("bugsee-android") + AutoAddedExtensions.ALL,
            h.autoAdded(),
        )
    }

    @Test
    fun sdkAutoLoadFalseViaPropertiesFileAddsNothing() {
        val h = Harness(
            "plugin.sdkAutoLoad=false\nplugin.ndk.enabled=true\nplugin.feedback=true\nplugin.leak.enabled=true\n",
            declareCore = false,
        )
        h.declareAllTriggers()
        assertEquals(emptySet(), h.autoAdded())
    }

    @Test
    fun sdkAutoLoadFalseKeepsExplicitlyDeclaredModules() {
        val h = Harness(null, declareCore = false) {
            sdkAutoLoad.set(false)
            ndk.enabled.set(true)
        }
        h.declare("com.bugsee:bugsee-android:$SDK_VERSION")
        h.declare("com.bugsee:bugsee-android-okhttp:$SDK_VERSION")
        h.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(emptySet(), h.autoAdded())
        val names = h.project.configurations.getByName("implementation").dependencies.map { it.name }
        assertTrue("bugsee-android-okhttp" in names && "bugsee-android" in names)
        assertFalse("bugsee-android-ndk" in names, "ndk.enabled must not auto-add under sdkAutoLoad=false")
    }

    @Test
    fun sdkAutoLoadFalseLogsOneWarningPerInertOptInFromTheRealAction() {
        val h = Harness(null, declareCore = false) {
            sdkAutoLoad.set(false)
            ndk.enabled.set(true)
            leak.enabled.set(true)
            feedback.set(true)
        }
        h.declare("com.bugsee:bugsee-android-leak:$SDK_VERSION")
        h.project.extensions.add("android", Any()) // lets the plugin's afterEvaluate run
        val bugsee = captureOutput {
            h.autoAdded()
            h.autoAdded() // a second read must not repeat the warnings
            // The harness reads before the DSL is final, so the decision is provisional and its
            // warnings are logged once, when the DSL is declared final (#50).
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(
            listOf(
                BugseePlugin.sdkAutoLoadOffWarning("ndk.enabled", "bugsee-android-ndk"),
                BugseePlugin.sdkAutoLoadOffWarning("feedback", "bugsee-android-feedback"),
            ),
            bugsee,
            "one warning each for the undeclared ndk and feedback; none for the declared leak module",
        )
    }

    @Test
    fun inertOptInsAreReportedUnlessModuleDeclared() {
        val h = Harness(null, declareCore = false) {
            sdkAutoLoad.set(false)
            ndk.enabled.set(true)
            leak.enabled.set(true)
            feedback.set(true)
        }
        h.declare("com.bugsee:bugsee-android-leak:$SDK_VERSION")
        assertEquals(
            listOf("ndk.enabled" to "bugsee-android-ndk", "feedback" to "bugsee-android-feedback"),
            BugseePlugin.inertOptInModules(h.project, h.resolver()),
        )
        assertEquals(
            "Bugsee: ndk.enabled=true but sdkAutoLoad=false and com.bugsee:bugsee-android-ndk is not declared; " +
                "declare it or the runtime is not added",
            BugseePlugin.sdkAutoLoadOffWarning("ndk.enabled", "bugsee-android-ndk"),
        )
        val none = Harness(null)
        assertEquals(emptyList(), BugseePlugin.inertOptInModules(none.project, none.resolver()))
    }

    @Test
    fun sdkAutoLoadDslBeatsFileUnlessPreferSettingsFile() {
        // DSL false, file true -> DSL wins: nothing added.
        val dslOff = Harness("plugin.sdkAutoLoad=true\n", declareCore = false) { sdkAutoLoad.set(false) }
        dslOff.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(emptySet(), dslOff.autoAdded())

        // DSL true, file false -> DSL wins: core and okhttp added.
        val dslOn = Harness("plugin.sdkAutoLoad=false\n", declareCore = false) { sdkAutoLoad.set(true) }
        dslOn.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(setOf("bugsee-android", "bugsee-android-okhttp"), dslOn.autoAdded())

        // preferSettingsFile flips it: the file's false stops the extension too.
        val prefer = Harness("plugin.sdkAutoLoad=false\n", declareCore = false) {
            sdkAutoLoad.set(true)
            preferSettingsFile.set(true)
        }
        prefer.declare("com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(emptySet(), prefer.autoAdded())
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
     * A flavor or build type whose name ends in a capitalised `Test` word is a production
     * configuration when the Android DSL declares that name (the auto-add reads the declared names;
     * the harness has no Android plugin, so the predicate is driven directly with them).
     */
    @Test
    fun declaredFlavorNamedLikeATestWordIsProduction() {
        val declared = setOf("latestTest", "uiTest", "release", "debug", "free")
        val production = listOf(
            "latestTestImplementation", "latestTestReleaseApi", "freeUiTestDebugRuntimeOnly",
            "releaseLatestTestImplementation", "uiTestImplementation",
        )
        val test = listOf(
            "latestTestUnitTestImplementation", "testLatestTestImplementation", "testLatestTestDebugImplementation",
            "androidTestLatestTestDebugImplementation", "testFixturesLatestTestApi",
            "testImplementation", "jvmTestImplementation", "latestTestFixturesApi",
        )
        production.forEach {
            assertTrue(DependencyDetector.isProductionConfiguration(it, declared), "$it is production")
        }
        test.forEach {
            assertFalse(DependencyDetector.isProductionConfiguration(it, declared), "$it is still test")
        }
        // Without declared names the plain name rule applies (no Android DSL yet / KMP source set).
        assertFalse(DependencyDetector.isProductionConfiguration("latestTestImplementation"))
    }

    @Test
    fun declaredVariantNamesAreEmptyWithoutAndroidDsl() {
        assertTrue(DependencyDetector.declaredVariantNames(ProjectBuilder.builder().build()).isEmpty())
    }

    /** End to end: the names are read from a (stubbed) Android DSL and drive the auto-add. */
    @Test
    fun okHttpOnDeclaredLatestTestFlavorConfigurationIsAutoAdded() {
        val h = Harness(null)
        h.project.extensions.add(
            com.android.build.api.dsl.CommonExtension::class.java, "android",
            fakeDsl(flavors = listOf("latestTest"), buildTypes = listOf("debug", "release")),
        )
        h.project.configurations.maybeCreate("latestTestImplementation")
        h.declareOn("latestTestImplementation", "com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(setOf("bugsee-android-okhttp"), h.autoAdded())

        // The same name WITHOUT a declaration stays a test configuration.
        val other = Harness(null)
        other.project.configurations.maybeCreate("latestTestImplementation")
        other.declareOn("latestTestImplementation", "com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(emptySet(), other.autoAdded())
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeDsl(flavors: List<String>, buildTypes: List<String>): com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *> {
        fun named(type: Class<*>, n: String): Any = java.lang.reflect.Proxy.newProxyInstance(
            type.classLoader, arrayOf(type),
        ) { _, m, _ -> if (m.name == "getName") n else null }
        fun container(type: Class<*>, names: List<String>): Any {
            val items = names.map { named(type, it) }
            return java.lang.reflect.Proxy.newProxyInstance(
                org.gradle.api.NamedDomainObjectContainer::class.java.classLoader,
                arrayOf(org.gradle.api.NamedDomainObjectContainer::class.java),
            ) { _, m, _ -> if (m.name == "iterator") items.iterator() else null }
        }
        val dsl = java.lang.reflect.Proxy.newProxyInstance(
            com.android.build.api.dsl.CommonExtension::class.java.classLoader,
            arrayOf(com.android.build.api.dsl.CommonExtension::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getBuildTypes" -> container(org.gradle.api.Named::class.java, buildTypes)
                "getProductFlavors" -> container(org.gradle.api.Named::class.java, flavors)
                else -> null
            }
        }
        return dsl as com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *>
    }

    @Test
    fun reservedTestNamesDoNotMaskTestConfigurations() {
        // AGP rejects these as flavor / build type names; were they declared they must not turn
        // `testImplementation` into a production configuration.
        val names = setOf("test", "androidTest", "main")
        assertFalse(DependencyDetector.isProductionConfiguration("testImplementation", names))
    }

    @Test
    fun debugConfigurationsAreIncludedOrExcludedPerCaller() {
        val include = DependencyDetector.DebugConfigurations.INCLUDE
        val exclude = DependencyDetector.DebugConfigurations.EXCLUDE
        for (name in listOf("debugImplementation", "freeDebugApi", "debugRuntimeOnly")) {
            assertTrue(DependencyDetector.isProductionConfiguration(name, debug = include), name)
            assertFalse(DependencyDetector.isProductionConfiguration(name, debug = exclude), name)
        }
        for (name in listOf("implementation", "releaseApi", "debuggerImplementation", "freeImplementation")) {
            assertTrue(DependencyDetector.isProductionConfiguration(name, debug = exclude), name)
        }
        // compileOnly is excluded under both, whatever the prefix.
        for (d in listOf(include, exclude)) {
            assertFalse(DependencyDetector.isProductionConfiguration("releaseCompileOnlyApi", debug = d))
        }
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

    // ── Core / extension presence is judged from production configurations (#124) ──

    /** #141: an extension absent at the declared core version is skipped with one warning, not left to fail resolution. */
    @Test
    fun extensionsBelowTheirFirstReleaseAreSkippedWithOneWarning() {
        val h = Harness(null, declareCore = false) {
            ndk.enabled.set(true)
            leak.enabled.set(true)
            feedback.set(true)
        }
        h.declare("com.bugsee:bugsee-android:7.0.0-beta8")
        h.project.extensions.add("android", Any())
        val lines = captureOutput {
            val added = h.autoAdded()
            assertTrue("bugsee-android-ndk" in added, "ndk exists from beta6")
            assertTrue("bugsee-android-feedback" in added, "feedback exists from beta1")
            assertFalse("bugsee-android-leak" in added, "leak starts at beta13")
            h.autoAdded()
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(
            listOf(ExtensionFirstRelease.skipWarning("bugsee-android-leak", "7.0.0-beta8", "7.0.0-beta13")),
            lines,
        )
    }

    /** #143: the early-read auto-add (core range, so ndk is added) is corrected once the DSL is final and a pin below the first release is declared. */
    @Test
    fun reconcileSkipsExtensionBelowFirstReleaseWithOneWarning() {
        val h = Harness(null, declareCore = false) {
            ndk.enabled.set(true)
            feedback.set(true)
        }
        h.project.extensions.add("android", Any())
        val early = h.autoAdded() // first read: DSL not final, core is auto-added at the range
        assertTrue("bugsee-android-ndk" in early, "early pass adds ndk at the range: $early")
        h.declare("com.bugsee:bugsee-android:7.0.0-beta1")
        val lines = captureOutput {
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        val after = h.autoAdded()
        assertFalse("bugsee-android-ndk" in after, "reconcile must remove ndk: $after")
        assertTrue("bugsee-android-feedback" in after, "feedback exists from beta1: $after")
        assertEquals(
            listOf(ExtensionFirstRelease.skipWarning("bugsee-android-ndk", "7.0.0-beta1", "7.0.0-beta6")),
            lines,
        )
    }

    @Test
    fun compileOnlyCorePinBelowFirstReleaseSkipsExtensionWithWarning() {
        for (conf in listOf("compileOnly", "compileOnlyApi", "debugCompileOnly")) {
            val h = Harness(null, declareCore = false) {
                ndk.enabled.set(true)
                feedback.set(true)
            }
            h.declareOnCreating(conf, "com.bugsee:bugsee-android:7.0.0-beta1")
            h.project.extensions.add("android", Any())
            val lines = captureOutput {
                val added = h.autoAdded()
                assertFalse("bugsee-android-ndk" in added, conf)
                assertTrue("bugsee-android-feedback" in added, conf)
                (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
            }.filter { it.startsWith("Bugsee:") && "not auto-added" in it }
            assertEquals(
                listOf(ExtensionFirstRelease.skipWarning("bugsee-android-ndk", "7.0.0-beta1", "7.0.0-beta6")),
                lines,
                conf,
            )
        }
    }

    @Test
    fun extensionBelowFirstReleaseIsNotWarnedWhenDeclaredByTheUser() {
        val h = Harness(null, declareCore = false) { ndk.enabled.set(true) }
        h.declare("com.bugsee:bugsee-android:7.0.0-beta1")
        h.declare("com.bugsee:bugsee-android-ndk:7.0.0-beta1")
        h.project.extensions.add("android", Any())
        val lines = captureOutput {
            h.autoAdded()
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(emptyList<String>(), lines)
    }

    private val core = "com.bugsee:bugsee-android:$SDK_VERSION"

    private fun Harness.declareOnCreating(configuration: String, coordinate: String) {
        project.configurations.maybeCreate(configuration)
        declareOn(configuration, coordinate)
    }

    /** Versions of every `bugsee-android` core dependency on `implementation`, auto-added or declared. */
    private fun Harness.coreVersionsOnImplementation(): List<String?> =
        project.configurations.getByName("implementation").incoming.dependencies
            .filter { it.group == "com.bugsee" && it.name == "bugsee-android" }.map { it.version }

    @Test
    fun testOnlyCorePinDoesNotSuppressCoreAutoAdd() {
        for (conf in listOf("testImplementation", "androidTestImplementation", "testCompileOnly", "debugUnitTestImplementation")) {
            val h = Harness(null, declareCore = false)
            h.declareOnCreating(conf, core)
            assertEquals(setOf("bugsee-android"), h.autoAdded(), conf)
            // The test pin does not leak into the version: the floor range is used.
            assertEquals(listOf<String?>(CoreSdkAutoLoad.range(BugseePlugin.MIN_SDK_VERSION)), h.coreVersionsOnImplementation(), conf)
        }
    }

    @Test
    fun customConfigurationExtendedByImplementationSuppressesAutoAdd() {
        val h = Harness(null, declareCore = false)
        val custom = h.project.configurations.create("bugseeSdk")
        h.project.configurations.getByName("implementation").extendsFrom(custom)
        h.declareOn("bugseeSdk", core)
        // The inherited pin is the only core dependency; nothing is added on top of it.
        assertEquals(listOf<String?>(SDK_VERSION), h.coreVersionsOnImplementation())
    }

    @Test
    fun customConfigurationExtendedOnlyByTestsDoesNotSuppressAutoAdd() {
        val h = Harness(null, declareCore = false)
        val custom = h.project.configurations.create("bugseeSdk")
        h.project.configurations.maybeCreate("testImplementation").extendsFrom(custom)
        h.declareOn("bugseeSdk", core)
        assertEquals(setOf("bugsee-android"), h.autoAdded())
    }

    @Test
    fun inheritedCorePinSetsTheExtensionVersion() {
        val h = Harness(null, declareCore = false) { feedback.set(true) }
        val custom = h.project.configurations.create("bugseeSdk")
        h.project.configurations.getByName("implementation").extendsFrom(custom)
        h.declareOn("bugseeSdk", "com.bugsee:bugsee-android:7.2.0")
        val feedback = h.project.configurations.getByName("implementation").incoming.dependencies
            .single { it.name == "bugsee-android-feedback" }
        assertEquals("7.2.0", feedback.version)
    }

    @Test
    fun coreProjectDependencyOnImplementationSuppressesAutoAdd() {
        val h = Harness(null, declareCore = false)
        val stub = org.gradle.testfixtures.ProjectBuilder.builder().withName("stub").withParent(h.project).build()
        stub.extensions.extraProperties.set("GROUP", "com.bugsee")
        h.project.dependencies.add("implementation", h.project.dependencies.project(mapOf("path" to ":stub")))
        assertEquals(setOf("stub"), h.autoAdded())
    }

    @Test
    fun testOnlyCoreProjectDependencyDoesNotSuppressCoreAutoAdd() {
        val h = Harness(null, declareCore = false)
        val stub = org.gradle.testfixtures.ProjectBuilder.builder().withName("stub").withParent(h.project).build()
        stub.extensions.extraProperties.set("GROUP", "com.bugsee")
        h.project.configurations.maybeCreate("testImplementation")
        h.project.dependencies.add("testImplementation", h.project.dependencies.project(mapOf("path" to ":stub")))
        assertEquals(setOf("bugsee-android"), h.autoAdded())
    }

    @Test
    fun productionCorePinIsNotAutoAddedAgain() {
        for (conf in listOf("implementation", "api", "debugImplementation", "freeReleaseApi")) {
            val h = Harness(null, declareCore = false)
            h.declareOnCreating(conf, core)
            assertEquals(emptySet<String>(), h.autoAdded(), conf)
            // `autoAdded()` hides the name of a pre-declared pin, so also assert no second dependency.
            if (conf == "implementation") assertEquals(listOf<String?>(SDK_VERSION), h.coreVersionsOnImplementation(), conf)
        }
    }

    /** #129: a debug-only core pin keeps suppressing the auto-add (deliberate debug-only SDK) but is warned about. */
    @Test
    fun debugOnlyCorePinWarnsOnceAndStaysDeferred() {
        for (conf in listOf("debugImplementation", "freeDebugApi", "debugCompileOnly")) {
            val h = Harness(null, declareCore = false)
            h.declareOnCreating(conf, core)
            h.project.extensions.add("android", Any())
            val lines = captureOutput {
                assertEquals(emptySet<String>(), h.autoAdded(), conf)
                (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
            }.filter { it.startsWith("Bugsee:") }
            assertEquals(listOf(BugseePlugin.debugOnlyCoreWarning(h.project.path)), lines, conf)
        }
    }

    /** The Bugsee lines for a debug-only core pin, plus what was auto-added (must stay empty: the setting only gates the warning). */
    private fun debugOnlyCoreRun(h: Harness): Pair<Set<String>, List<String>> {
        h.declareOnCreating("debugImplementation", core)
        h.project.extensions.add("android", Any())
        var added = emptySet<String>()
        val lines = captureOutput {
            added = h.autoAdded()
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        return added to lines
    }

    @Test
    fun warnDebugOnlySdkFalseInTheDslSilencesTheWarningWithoutChangingAutoAdd() {
        val (added, lines) = debugOnlyCoreRun(Harness(null, declareCore = false) { warnDebugOnlySdk.set(false) })
        assertEquals(emptySet<String>(), added)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun warnDebugOnlySdkFalseInBugseePropertiesSilencesTheWarning() {
        val (added, lines) = debugOnlyCoreRun(Harness("plugin.warnDebugOnlySdk=false\n", declareCore = false))
        assertEquals(emptySet<String>(), added)
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun warnDebugOnlySdkTrueStillWarns() {
        val h = Harness("plugin.warnDebugOnlySdk=true\n", declareCore = false) { warnDebugOnlySdk.set(true) }
        val (added, lines) = debugOnlyCoreRun(h)
        assertEquals(emptySet<String>(), added)
        assertEquals(listOf(BugseePlugin.debugOnlyCoreWarning(h.project.path)), lines)
    }

    @Test
    fun warnDebugOnlySdkDslWinsOverTheFile() {
        val h = Harness("plugin.warnDebugOnlySdk=false\n", declareCore = false) { warnDebugOnlySdk.set(true) }
        val (added, lines) = debugOnlyCoreRun(h)
        assertEquals(emptySet<String>(), added)
        assertEquals(listOf(BugseePlugin.debugOnlyCoreWarning(h.project.path)), lines)
    }

    /** Set after `implementation` was already read: the warning is decided in the final recompute. */
    @Test
    fun warnDebugOnlySdkSetAfterAnEarlyReadStillSilences() {
        val h = Harness(null, declareCore = false)
        h.declareOnCreating("debugImplementation", core)
        h.project.extensions.add("android", Any())
        val lines = captureOutput {
            h.autoAdded()
            h.extension.warnDebugOnlySdk.set(false)
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun warnDebugOnlySdkFollowsPreferSettingsFile() {
        val (_, lines) = debugOnlyCoreRun(
            Harness("plugin.warnDebugOnlySdk=false\n", declareCore = false) {
                warnDebugOnlySdk.set(true)
                preferSettingsFile.set(true)
            },
        )
        assertEquals(emptyList<String>(), lines)
    }

    /** The setting gates only the debug-only warning: the sdkAutoLoad=false opt-in warning still logs. */
    @Test
    fun warnDebugOnlySdkFalseLeavesOtherAutoAddWarningsAlone() {
        val h = Harness(null, declareCore = false) {
            warnDebugOnlySdk.set(false)
            sdkAutoLoad.set(false)
            feedback.set(true)
        }
        h.project.extensions.add("android", Any())
        val lines = captureOutput {
            h.autoAdded()
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertTrue(lines.contains(BugseePlugin.sdkAutoLoadOffWarning("feedback", "bugsee-android-feedback")), lines.toString())
    }

    @Test
    fun warnDebugOnlySdkFalseDoesNotSuppressTheCoreAutoAddWhenNothingIsDeclared() {
        val h = Harness(null, declareCore = false) { warnDebugOnlySdk.set(false) }
        assertEquals(setOf("bugsee-android"), h.autoAdded())
    }

    @Test
    fun corePinOnANonDebugConfigurationAlongsideDebugDoesNotWarn() {
        for (other in listOf("implementation", "releaseImplementation", "compileOnly")) {
            val h = Harness(null, declareCore = false)
            h.declareOnCreating("debugImplementation", core)
            h.declareOnCreating(other, core)
            h.project.extensions.add("android", Any())
            val lines = captureOutput {
                h.autoAdded()
                (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
            }.filter { it.startsWith("Bugsee:") }
            assertEquals(emptyList<String>(), lines, other)
        }
    }

    @Test
    fun debugOnlyCorePinDoesNotWarnWhenAnExtensionIsAutoAdded() {
        val h = Harness(null, declareCore = false)
        h.declareOnCreating("debugImplementation", core)
        h.declareOnCreating("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
        h.project.extensions.add("android", Any())
        val lines = captureOutput {
            assertEquals(setOf("bugsee-android-okhttp"), h.autoAdded())
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun debugOnlyCorePinDoesNotWarnWithUserExtensionOrSdkAutoLoadOff() {
        val ext = Harness(null, declareCore = false)
        ext.declareOnCreating("debugImplementation", core)
        ext.declareOnCreating("implementation", "com.bugsee:bugsee-android-okhttp:$SDK_VERSION")
        ext.project.extensions.add("android", Any())
        val extLines = captureOutput {
            ext.autoAdded()
            (ext.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(emptyList<String>(), extLines)

        val off = Harness(null, declareCore = false) { sdkAutoLoad.set(false) }
        off.declareOnCreating("debugImplementation", core)
        off.project.extensions.add("android", Any())
        val offLines = captureOutput {
            off.autoAdded()
            (off.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(emptyList<String>(), offLines)
    }

    @Test
    fun debugOnlyCorePinStillWarnsWithAnUnrelatedNonDebugProjectDependency() {
        val h = Harness(null, declareCore = false)
        h.declareOnCreating("debugImplementation", core)
        org.gradle.testfixtures.ProjectBuilder.builder().withName("lib").withParent(h.project).build()
        h.project.dependencies.add("implementation", h.project.dependencies.project(mapOf("path" to ":lib")))
        h.project.extensions.add("android", Any())
        val lines = captureOutput {
            h.autoAdded()
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
        assertEquals(listOf(BugseePlugin.debugOnlyCoreWarning(h.project.path)), lines)
    }

    /** The app pins the core on debug only and depends on `:lib`, which declares [libCoordinate] on [libConfig]. */
    private fun debugOnlyCoreLinesWithLib(libConfig: String, libCoordinate: String): List<String> {
        val h = Harness(null, declareCore = false)
        h.declareOnCreating("debugImplementation", core)
        val lib = org.gradle.testfixtures.ProjectBuilder.builder().withName("lib").withParent(h.project).build()
        lib.configurations.create("implementation")
        lib.configurations.maybeCreate(libConfig)
        lib.dependencies.add(libConfig, libCoordinate)
        h.project.dependencies.add("implementation", h.project.dependencies.project(mapOf("path" to ":lib")))
        h.project.extensions.add("android", Any())
        return captureOutput {
            h.autoAdded()
            (h.project as org.gradle.api.internal.project.ProjectInternal).evaluate()
        }.filter { it.startsWith("Bugsee:") }
    }

    @Test
    fun debugOnlyCorePinIsSilentWhenADependencyProjectSuppliesTheSdk() {
        assertEquals(emptyList<String>(), debugOnlyCoreLinesWithLib("implementation", core))
        assertEquals(
            emptyList<String>(),
            debugOnlyCoreLinesWithLib("implementation", "com.bugsee:bugsee-android-okhttp:$SDK_VERSION"),
        )
    }

    @Test
    fun debugOnlyCorePinStillWarnsWhenTheDependencyProjectDeclaresTheCoreOnlyOnDebug() {
        val h = debugOnlyCoreLinesWithLib("debugImplementation", core)
        assertEquals(1, h.size, h.toString())
    }

    @Test
    fun isDebugConfigurationMatchesCamelCaseWordOnly() {
        for (n in listOf("debugImplementation", "freeDebugApi")) assertTrue(DependencyDetector.isDebugConfiguration(n), n)
        for (n in listOf("implementation", "debuggerImplementation", "releaseApi")) assertFalse(DependencyDetector.isDebugConfiguration(n), n)
    }

    @Test
    fun testPinAlongsideProductionPinStillDefersToProduction() {
        val h = Harness(null, declareCore = false)
        h.declareOn("implementation", core)
        h.declareOnCreating("testImplementation", "com.bugsee:bugsee-android:6.0.0")
        // Only the production pin is on implementation; no auto-added range on top.
        assertEquals(listOf<String?>(SDK_VERSION), h.coreVersionsOnImplementation())
    }

    /** compileOnly is the documented deferral: the app supplies the SDK at runtime. */
    @Test
    fun compileOnlyCorePinStillDefersTheAutoAdd() {
        for (conf in listOf("compileOnly", "compileOnlyApi", "debugCompileOnly")) {
            val h = Harness(null, declareCore = false)
            h.declareOnCreating(conf, core)
            assertEquals(emptySet<String>(), h.autoAdded(), conf)
        }
    }

    /** #130: the deferral must not let an auto-added extension pull a newer core transitively. */
    @Test
    fun compileOnlyCorePinSetsTheExtensionVersion() {
        // 7.9.0 is at or above and 7.0.0 below the MIN_SDK_VERSION floor: both are used as declared.
        for ((conf, version) in listOf("compileOnly" to "7.9.0", "compileOnlyApi" to "7.0.0", "debugCompileOnly" to "7.0.0")) {
            val h = Harness(null, declareCore = false) { feedback.set(true) }
            h.declareOnCreating(conf, "com.bugsee:bugsee-android:$version")
            val feedback = h.project.configurations.getByName("implementation").incoming.dependencies
                .single { it.name == "bugsee-android-feedback" }
            assertEquals(version, feedback.version, conf)
            assertEquals(emptyList<String?>(), h.coreVersionsOnImplementation(), conf)
        }
    }

    @Test
    fun compileOnlyCorePinSetsTheOkHttpExtensionVersion() {
        val h = Harness(null, declareCore = false)
        h.declare("com.squareup.okhttp3:okhttp:4.12.0")
        h.declareOnCreating("compileOnly", "com.bugsee:bugsee-android:7.0.0")
        val okhttp = h.project.configurations.getByName("implementation").incoming.dependencies
            .single { it.name == "bugsee-android-okhttp" }
        assertEquals("7.0.0", okhttp.version)
    }

    @Test
    fun productionCorePinWinsOverCompileOnlyForTheExtensionVersion() {
        val h = Harness(null, declareCore = false) { feedback.set(true) }
        h.declareOnCreating("compileOnly", "com.bugsee:bugsee-android:7.9.0")
        h.declareOn("implementation", "com.bugsee:bugsee-android:7.2.0")
        val feedback = h.project.configurations.getByName("implementation").incoming.dependencies
            .single { it.name == "bugsee-android-feedback" }
        assertEquals("7.2.0", feedback.version)
    }

    @Test
    fun testOnlyExtensionPinDoesNotSuppressExtensionAutoAdd() {
        val h = Harness(null) { feedback.set(true) }
        h.declareOnCreating("androidTestImplementation", "com.bugsee:bugsee-android-feedback:$SDK_VERSION")
        assertEquals(setOf("bugsee-android-feedback"), h.autoAdded())
    }

    @Test
    fun isSdkDeclarationConfigurationClassifiesNames() {
        for (n in listOf("implementation", "api", "debugImplementation", "compileOnly", "compileOnlyApi", "debugCompileOnly", "runtimeOnly")) {
            assertTrue(DependencyDetector.isSdkDeclarationConfiguration(n), n)
        }
        for (n in listOf("testImplementation", "androidTestImplementation", "testCompileOnly", "debugUnitTestCompileOnly", "androidTestCompileOnly", "testFixturesCompileOnly", "lintChecks", "kapt")) {
            assertFalse(DependencyDetector.isSdkDeclarationConfiguration(n), n)
        }
        // A declared flavor named like a test word is production / compileOnly of that flavor.
        assertTrue(DependencyDetector.isSdkDeclarationConfiguration("latestTestCompileOnly", setOf("latestTest")))
        assertFalse(DependencyDetector.isSdkDeclarationConfiguration("testLatestTestCompileOnly", setOf("latestTest")))
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

    /**
     * Messages logged while [block] runs. ProjectBuilder's loggers write through Gradle's slf4j
     * logger context, which has no public hook, so the test swaps its output listener (same
     * technique as VariantWiringTest).
     */
    private fun captureOutput(block: () -> Unit): List<String> {
        val context = org.slf4j.LoggerFactory.getILoggerFactory()
        val type = context.javaClass
        val listenerType = org.gradle.internal.logging.events.OutputEventListener::class.java
        val original = type.getMethod("getOutputEventListener").invoke(context) as
            org.gradle.internal.logging.events.OutputEventListener
        val setter = type.getMethod("setOutputEventListener", listenerType)
        val lines = mutableListOf<String>()
        setter.invoke(context, org.gradle.internal.logging.events.OutputEventListener { event ->
            if (event is org.gradle.internal.logging.events.LogEvent) lines += event.message
            original.onOutput(event)
        })
        try {
            block()
        } finally {
            setter.invoke(context, original)
        }
        return lines
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
            null,
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
