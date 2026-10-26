package com.bugsee.android.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.config.VariantContext
import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion
import com.bugsee.android.gradle.manifest.BugseeManifestTask
import com.bugsee.android.gradle.manifest.ExtensionStripGate
import com.bugsee.android.gradle.upload.AppTokenResolver
import com.bugsee.android.gradle.upload.MappingUploadTask
import com.bugsee.android.gradle.upload.NativeUploadTask
import com.bugsee.android.gradle.upload.UploaderStrategy
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how `BugseePlugin`'s `onVariants` callback wires each variant to its
 * resolved (effective) settings, and that a build with no per-variant input
 * wires exactly what the global extension says.
 *
 * The real callback runs: a fake `AndroidComponentsExtension` registered before
 * the plugin is applied captures it, and it is then invoked with fake
 * `ApplicationVariant`s (dynamic proxies, as in `ManifestTaskStripGateWiringTest`).
 */
class VariantWiringTest {

    @Test
    fun variantContextOf_flavorsInDimensionOrder() {
        val project = ProjectBuilder.builder().build()
        val variant = fakeVariant(
            project, "freeStagingRelease",
            flavors = listOf("tier" to "free", "env" to "staging"),
            buildType = "release",
        )

        assertEquals(
            VariantContext("freeStagingRelease", listOf("free", "staging"), "release"),
            BugseePlugin.variantContextOf(variant),
        )
    }

    @Test
    fun stripGateAndExtensionsInitAgreePerVariant() {
        val h = Harness("variant.staging.plugin.instrumentation.enabled=false\n")
        h.runVariant("stagingRelease", flavors = listOf("env" to "staging"))
        h.runVariant("productionRelease", flavors = listOf("env" to "production"))

        val staging = h.manifestTask("StagingRelease")
        assertEquals(false, staging.optimizeExtensionsLoading.get(), "staging disabled instrumentation: no strip")
        assertNotEquals(true, staging.extensionsInitRegistered.orNull, "staging: no ExtensionsInit injection")

        val production = h.manifestTask("ProductionRelease")
        assertEquals(true, production.optimizeExtensionsLoading.get(), "production keeps the strip armed")
        assertEquals(true, production.extensionsInitRegistered.orNull, "production: ExtensionsInit injected")
    }

    @Test
    fun taskInputsComeFromEffectiveExtension() {
        val h = Harness(
            "variant.staging.plugin.endpoint=https://staging.example.com\n" +
                "variant.staging.plugin.ndk.enabled=true\n" +
                "variant.staging.app_token=staging-token\n",
        )
        h.runVariant("stagingRelease", flavors = listOf("env" to "staging"))
        h.runVariant("productionRelease", flavors = listOf("env" to "production"))

        val stagingMapping = h.mappingTask("StagingRelease")
        assertEquals("https://staging.example.com", stagingMapping.endpoint.get())
        assertEquals("staging-token", stagingMapping.preResolvedAppToken.orNull)
        assertEquals("https://staging.example.com", h.nativeTask("StagingRelease")!!.endpoint.get())

        val productionMapping = h.mappingTask("ProductionRelease")
        assertEquals("https://api.bugsee.com", productionMapping.endpoint.get())
        assertNull(productionMapping.preResolvedAppToken.orNull)
        assertNull(h.nativeTask("ProductionRelease"), "ndk enabled for staging only")
    }

    @Test
    fun noVariantInput_taskInputsMatchGlobalExtension() {
        val h = Harness(
            "app_token=root-token\nplugin.ndk.enabled=true\nplugin.ndk.useMergedNativeLibs=false\n",
        ) {
            endpoint.set("https://dsl.example.com")
            debug.set(true)
            appToken("dsl-token")
            instrumentation.excludes.set(setOf("com.example.*"))
            // GLOBAL-scope settings set in the DSL.
            cliVersion.set("9.8.7")
            cliAutoUpdate.set(false)
            uploader.set(UploaderStrategy.KOTLIN)
            buildInfo { b ->
                b.sizeAnalysis { it.chunkedUpload.set(false); it.enabled.set(true) }
                b.sizeCheck { it.warningPercent.set(5.0); it.failBytes.set(4096L) }
                b.dependencies { it.scope.set("runtime"); it.maxCount.set(7) }
                b.timings { it.enabled.set(false) }
            }
        }
        h.runVariant("release")
        val ext = h.extension
        val root = h.project.rootProject

        val manifest = h.manifestTask("Release")
        assertEquals(ext.debug.get(), manifest.debug.get())
        assertEquals(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = ext.optimizeExtensionsLoading.get(),
                instrumentationGloballyEnabled = true,
                excludes = ext.instrumentation.excludes.get(),
                sdkVersion = BugseeSdkVersion.parse(SDK_VERSION),
            ),
            manifest.optimizeExtensionsLoading.get(),
        )

        val oldToken = AppTokenResolver.resolveFromExtension(ext, "release", h.project.logger, false)
            ?: AppTokenResolver.resolveFromPropertiesFile(root.projectDir, h.project.logger, false)
        for (task in listOf(h.mappingTask("Release"), h.nativeTask("Release")!!)) {
            assertEquals(ext.endpoint.get(), task.inputValue("endpoint"))
            assertEquals(ext.debug.get(), task.inputValue("debug"))
            assertEquals("9.8.7", task.inputValue("cliVersion"))
            assertEquals(false, task.inputValue("cliAutoUpdate"))
            assertEquals(UploaderStrategy.KOTLIN, task.inputValue("uploader"))
            assertEquals(oldToken, task.inputValue("preResolvedAppToken"))
        }
        val native = h.nativeTask("Release")!!
        assertEquals(ext.ndk.forceDebugSymbolsUpload.get(), native.forceUpload.get())
        assertEquals(false, native.useMergedNativeLibs.get())

        val info = ext.buildInfo
        for (cap in listOf("Bundle", "Apk")) {
            val bundle = h.project.tasks.getByName("uploadBugseeRelease$cap")
            assertEquals(ext.endpoint.get(), bundle.inputValue("endpoint"))
            assertEquals(ext.debug.get(), bundle.inputValue("debug"))
            assertEquals("9.8.7", bundle.inputValue("cliVersion"))
            assertEquals(oldToken, bundle.inputValue("preResolvedAppToken"))
            assertEquals(info.sizeAnalysis.chunkedUpload.get(), bundle.inputValue("chunkedUpload"))
            assertEquals(info.sizeAnalysis.enabled.get(), bundle.inputValue("requestArtifactUpload"))
            assertEquals(info.sizeCheck.warningPercent.get(), bundle.inputValue("sizeCheckWarningPercent"))
            assertEquals(info.sizeCheck.failBytes.get(), bundle.inputValue("sizeCheckFailBytes"))
            assertEquals(info.dependencies.enabled.get(), bundle.inputValue("requestDependenciesUpload"))
            assertEquals(info.dependencies.scope.get(), bundle.inputValue("dependenciesScope"))
            assertEquals(info.dependencies.includeSelectedReason.get(), bundle.inputValue("dependenciesIncludeReason"))
            assertEquals(info.dependencies.maxCount.get(), bundle.inputValue("dependenciesMaxCount"))
            assertEquals(info.timings.enabled.get(), bundle.inputValue("timingsEnabled"))
        }
    }

    @Test
    fun lateDslValuesReachTaskInputs() {
        // AGP runs onVariants from its own afterEvaluate, so a user's later afterEvaluate { bugsee { … } }
        // used to reach the tasks through the live global properties. It still must.
        val h = Harness("plugin.ndk.enabled=true\nplugin.ndk.forceDebugSymbolsUpload=false\n")
        h.extension.endpoint.set("https://early.example.com")
        h.runVariant("release")

        h.extension.endpoint.set("https://late.example.com")
        h.extension.cliVersion.set("9.9.9")
        h.extension.ndk.forceDebugSymbolsUpload.set(true) // overrides the root-file convention late
        h.extension.optimizeExtensionsLoading.set(false)

        val mapping = h.mappingTask("Release")
        assertEquals("https://late.example.com", mapping.endpoint.get())
        assertEquals("9.9.9", mapping.cliVersion.get())
        assertEquals(true, h.nativeTask("Release")!!.forceUpload.get())
        assertEquals(false, h.manifestTask("Release").optimizeExtensionsLoading.get())
    }

    @Test
    fun noVariantInput_wiringDoesNotQueryLazyDslValues() {
        // e.g. cliPath.set(buildCli.map { … }): querying it before the producing task runs fails.
        var queries = 0
        val h = Harness("plugin.ndk.enabled=true\n")
        h.extension.cliPath.set(h.project.provider { queries++; "/opt/bugsee-cli" })
        h.extension.endpoint.set(h.project.provider { queries++; "https://lazy.example.com" })
        h.extension.ndk.forceDebugSymbolsUpload.set(h.project.provider { queries++; true })
        h.runVariant("release")

        assertEquals(0, queries, "onVariants wiring must not read lazy DSL values")
        assertEquals("/opt/bugsee-cli", h.mappingTask("Release").cliPath.get())
        assertEquals(true, h.nativeTask("Release")!!.forceUpload.get())
    }

    @Test
    fun relativeDslCliPathResolvesAgainstProjectDir() {
        val h = Harness(null)
        h.extension.cliPath.set("tools/fake-cli.sh")
        h.runVariant("release")

        assertEquals(
            File(h.project.projectDir, "tools/fake-cli.sh").path,
            h.mappingTask("Release").cliPath.get(),
        )
    }

    @Test
    fun relativeFileCliPathResolvesAgainstSettingsFileDirNotModule() {
        val h = Harness("plugin.cliPath=tools/fake-cli.sh\n", moduleName = "app")
        h.runVariant("release")

        assertEquals(
            File(h.root.projectDir, "tools/fake-cli.sh").path,
            h.mappingTask("Release").cliPath.get(),
        )
    }

    @Test
    fun twoModulesOfOneBuildShareTheRootFileValue() {
        val root = ProjectBuilder.builder().build()
        File(root.projectDir, "bugsee.properties").writeText("plugin.cliPath=tools/cli.sh\n")
        val paths = listOf("app", "app2").map { name ->
            val child = ProjectBuilder.builder().withParent(root).withName(name).build()
            val ext = child.extensions.create("bugsee", BugseePluginExtension::class.java)
            com.bugsee.android.gradle.config.PluginPropertiesApplier.applyTo(child, ext)
            BugseePlugin.projectRelativeCliPath(child, ext).get()
        }
        assertEquals(File(root.projectDir, "tools/cli.sh").path, paths[0])
        assertEquals(paths[0], paths[1])
    }

    @Test
    fun relativeDslCliPathStaysModuleRelativeWithRootFileValueSet() {
        val h = Harness("plugin.cliPath=tools/from-file.sh\n", moduleName = "app")
        h.extension.cliPath.set("tools/from-dsl.sh")
        h.runVariant("release")

        assertEquals(
            File(h.project.projectDir, "tools/from-dsl.sh").path,
            h.mappingTask("Release").cliPath.get(),
        )
    }

    @Test
    fun absoluteAndBlankCliPathPassThroughByteForByte() {
        val tmp = System.getProperty("java.io.tmpdir")
        for (raw in listOf(
            File(tmp, "bugsee-cli").absolutePath,
            File(tmp, "linkdir/../bugsee-cli").absolutePath,
            "  ",
            "C:\\tools\\bugsee-cli.exe",
        )) {
            val h = Harness(null)
            h.extension.cliPath.set(raw)
            h.runVariant("release")
            // `C:\\...` is relative on non-Windows: must not throw (no URI parsing); on Windows it is absolute.
            val expected = if (raw.startsWith("C:")) {
                if (File(raw).isAbsolute) raw else File(h.project.projectDir, raw).path
            } else {
                raw
            }
            assertEquals(expected, h.mappingTask("Release").cliPath.get(), "cliPath '$raw'")
        }
    }

    @Test
    fun rootFileTokenUsedWhenDslHasNone() {
        val h = Harness("app_token=root-file-token\n")
        h.runVariant("release")

        assertEquals("root-file-token", h.mappingTask("Release").preResolvedAppToken.orNull)
    }

    @Test
    fun debugSummaryLine_formatAndMasking() {
        assertEquals(
            "Bugsee: variant stagingRelease resolved: token=${AppTokenResolver.maskToken("secret-token-value")} " +
                "endpoint=https://x.example.com",
            BugseePlugin.variantResolvedMessage("stagingRelease", "secret-token-value", "https://x.example.com"),
        )
        assertEquals(
            "Bugsee: variant release resolved: token=none endpoint=https://api.bugsee.com",
            BugseePlugin.variantResolvedMessage("release", null, "https://api.bugsee.com"),
        )

        // The real wiring logs it (only under debug) and never prints the raw token.
        val quiet = Harness("app_token=secret-token-value\n")
        assertTrue(quiet.captureOutput { quiet.runVariant("release") }.none { "resolved:" in it })

        val h = Harness("app_token=secret-token-value\nplugin.debug=true\n")
        val output = h.captureOutput { h.runVariant("release") }
        assertTrue(output.none { "secret-token-value" in it }, "raw token leaked: $output")
        assertTrue(
            BugseePlugin.variantResolvedMessage("release", "secret-token-value", "https://api.bugsee.com") in output,
            "summary line missing from: $output",
        )
    }

    @Test
    fun noVariantInput_gradlePropertyStillDisablesInstrumentation() {
        val h = Harness(null)
        h.project.extensions.extraProperties["bugsee.instrumentation.enabled"] = "false"
        h.runVariant("release")

        val manifest = h.manifestTask("Release")
        assertEquals(false, manifest.optimizeExtensionsLoading.get(), "-P disables instrumentation, so no strip")
        assertNotEquals(true, manifest.extensionsInitRegistered.orNull)
    }

    @Test
    fun noVariantInput_rootFileInstrumentationValueBeatsGradleProperty() {
        val h = Harness("plugin.instrumentation.enabled=true\n")
        h.project.extensions.extraProperties["bugsee.instrumentation.enabled"] = "false"
        h.runVariant("release")

        assertEquals(true, h.manifestTask("Release").optimizeExtensionsLoading.get())
    }

    // ── Harness ─────────────────────────────────────────────────────

    private class Harness(
        propertiesText: String?,
        moduleName: String? = null,
        configure: BugseePluginExtension.() -> Unit = {},
    ) {
        val root: Project = ProjectBuilder.builder().build()
        val project: Project =
            moduleName?.let { ProjectBuilder.builder().withParent(root).withName(it).build() } ?: root
        private val callbacks = mutableListOf<(Variant) -> Unit>()
        val extension: BugseePluginExtension

        init {
            propertiesText?.let { File(root.projectDir, "bugsee.properties").writeText(it) }
            project.configurations.create("implementation")
            project.dependencies.add("implementation", "com.bugsee:bugsee-android:$SDK_VERSION")
            @Suppress("UNCHECKED_CAST")
            project.extensions.add(
                AndroidComponentsExtension::class.java as Class<Any>,
                "androidComponents",
                fakeComponents(),
            )
            project.plugins.apply(BugseePlugin::class.java)
            extension = project.extensions.getByType(BugseePluginExtension::class.java)
            extension.configure()
        }

        fun runVariant(name: String, flavors: List<Pair<String, String>> = emptyList(), buildType: String = "release") {
            val variant = fakeVariant(project, name, flavors, buildType)
            callbacks.forEach { it(variant) }
        }

        fun manifestTask(cap: String) =
            project.tasks.getByName("createBugsee${cap}ManifestConfig") as BugseeManifestTask

        fun mappingTask(cap: String) = project.tasks.getByName("uploadBugsee${cap}Mapping") as MappingUploadTask

        fun nativeTask(cap: String) = project.tasks.findByName("uploadBugsee${cap}Native") as NativeUploadTask?

        /**
         * Messages logged while [block] runs. ProjectBuilder's loggers write through Gradle's
         * slf4j logger context, which has no public hook, so the test swaps its output listener.
         */
        fun captureOutput(block: () -> Unit): List<String> {
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

        private fun fakeComponents(): Any {
            val handler = InvocationHandler { proxy, method, args ->
                when {
                    method.name == "onVariants" -> {
                        @Suppress("UNCHECKED_CAST")
                        when (val cb = args!!.last()) {
                            is Function1<*, *> -> callbacks += cb as (Variant) -> Unit
                            is Action<*> -> callbacks += { v -> (cb as Action<Variant>).execute(v) }
                        }
                        null
                    }
                    method.name == "toString" -> "FakeAndroidComponents"
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "equals" -> proxy === args?.get(0)
                    method.returnType.isInterface -> nullObject(method.returnType, project)
                    else -> null
                }
            }
            return Proxy.newProxyInstance(
                AndroidComponentsExtension::class.java.classLoader,
                arrayOf(AndroidComponentsExtension::class.java),
                handler,
            )
        }
    }

    private companion object {
        const val SDK_VERSION = "7.1.3"

        fun Any.inputValue(name: String): Any? {
            val getter = javaClass.methods.first { it.name == "get" + name.replaceFirstChar { c -> c.uppercase() } }
            return (getter.invoke(this) as Provider<*>).orNull
        }

        /**
         * A fake [ApplicationVariant]. `getName`/`getBuildType`/`getProductFlavors`
         * carry meaning; any other interface result is a [nullObject].
         */
        fun fakeVariant(
            project: Project,
            name: String,
            flavors: List<Pair<String, String>>,
            buildType: String,
        ): ApplicationVariant {
            val handler = InvocationHandler { _, method, _ ->
                when {
                    method.name == "getName" && method.returnType == String::class.java -> name
                    method.name == "getBuildType" -> buildType
                    method.name == "getProductFlavors" -> flavors
                    method.name == "toString" -> "FakeVariant($name)"
                    method.name == "hashCode" -> name.hashCode()
                    method.name == "equals" -> false
                    method.returnType.isInterface -> nullObject(method.returnType, project)
                    else -> defaultFor(method.returnType)
                }
            }
            return Proxy.newProxyInstance(
                ApplicationVariant::class.java.classLoader,
                arrayOf(ApplicationVariant::class.java),
                handler,
            ) as ApplicationVariant
        }

        /** A no-op proxy; `Provider` results are absent providers so AGP-artifact task inputs can be realized. */
        fun nullObject(type: Class<*>, project: Project): Any =
            if (Provider::class.java.isAssignableFrom(type)) project.provider { null } else
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { p, m, a ->
            when {
                m.name == "toString" -> "NullObject(${type.simpleName})"
                m.name == "hashCode" -> System.identityHashCode(p)
                m.name == "equals" -> p === a?.get(0)
                m.returnType.isInterface -> nullObject(m.returnType, project)
                else -> defaultFor(m.returnType)
            }
        }

        fun defaultFor(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    }
}
