package com.bugsee.android.gradle.manifest

import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.BugseePlugin
import com.bugsee.android.gradle.BugseePluginExtension
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/**
 * Pins the CALL SITE of [ExtensionStripGate] inside
 * `BugseePlugin.registerManifestTask`.
 *
 * [ExtensionStripGateTest] pins the gate function itself, but a correct
 * function fed the wrong arguments is the same shipped defect: with
 * `instrumentationGloballyEnabled` hardcoded to `true`, or the user's
 * `instrumentation.excludes` dropped on the way in, the build strips every
 * Bugsee extension `<provider>` from the merged manifest while the
 * compensating `ExtensionsInitInstrumentation` injection never runs — the
 * extensions (NDK crash reporting, feedback, compose, remoting) are silently
 * dead in the shipped APK.
 *
 * The task's `optimizeExtensionsLoading` input is the observable end of that
 * wiring, so these cases assert its RESOLVED value for each precondition.
 */
class ManifestTaskStripGateWiringTest {

    @Test
    fun `strip stays armed in the default configuration`() {
        assertEquals(
            true,
            resolveStripFlag(instrumentationGloballyEnabled = true),
            "with instrumentation on and no excludes the strip must be armed — " +
                "otherwise the other cases here prove nothing",
        )
    }

    @Test
    fun `strip is disarmed when instrumentation is globally disabled`() {
        assertEquals(
            false,
            resolveStripFlag(instrumentationGloballyEnabled = false),
            "registerManifestTask must forward the RESOLVED instrumentation flag to " +
                "ExtensionStripGate; passing a hardcoded true re-opens the " +
                "silently-dead-extensions defect for -Pbugsee.instrumentation.enabled=false builds",
        )
    }

    @Test
    fun `strip is disarmed when the user excludes the injection target`() {
        assertEquals(
            false,
            resolveStripFlag(
                instrumentationGloballyEnabled = true,
                excludes = setOf("com.bugsee.library.BugseeInitProvider"),
            ),
            "registerManifestTask must forward the user's instrumentation.excludes to " +
                "ExtensionStripGate; dropping them strips the providers for exactly the " +
                "consumer InstrumentationException tells to add this exclude",
        )
    }

    @Test
    fun `strip is disarmed when a package-glob exclude covers the injection target`() {
        assertEquals(
            false,
            resolveStripFlag(
                instrumentationGloballyEnabled = true,
                excludes = setOf("com.bugsee.*"),
            ),
            "a glob that matches the injection target must disarm the strip too",
        )
    }

    @Test
    fun `strip stays armed for unrelated excludes`() {
        assertEquals(
            true,
            resolveStripFlag(
                instrumentationGloballyEnabled = true,
                excludes = setOf("com.example.*"),
            ),
            "excludes that do not match the injection target must not disarm the strip — " +
                "pins that the excludes are really consulted rather than any non-empty set " +
                "disarming the gate",
        )
    }

    @Test
    fun `explicit optimizeExtensionsLoading=false wins regardless of instrumentation`() {
        assertEquals(
            false,
            resolveStripFlag(instrumentationGloballyEnabled = true, dslOptimize = false),
            "the DSL opt-out must still be honoured through the gate call site",
        )
    }

    /**
     * Registers the manifest task the way `BugseePlugin` does for one variant
     * and returns the resolved value of its `optimizeExtensionsLoading` input.
     */
    private fun resolveStripFlag(
        instrumentationGloballyEnabled: Boolean,
        excludes: Set<String> = emptySet(),
        dslOptimize: Boolean = true,
    ): Boolean {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(BugseePlugin::class.java)
        val plugin = project.plugins.getPlugin(BugseePlugin::class.java)
        val extension = project.extensions.getByType(BugseePluginExtension::class.java)
        extension.optimizeExtensionsLoading.set(dslOptimize)
        extension.instrumentation.excludes.set(excludes)

        val method = BugseePlugin::class.java.getDeclaredMethod(
            "registerManifestTask",
            Project::class.java,
            Variant::class.java,
            BugseePluginExtension::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val provider = method.invoke(
            plugin, project, fakeVariant("debug"), extension, "Debug", instrumentationGloballyEnabled,
        ) as TaskProvider<BugseeManifestTask>

        return provider.get().optimizeExtensionsLoading.get()
    }

    /**
     * A dynamic-proxy [Variant]. Only `getName()` carries meaning here; the
     * `artifacts.use(...).wiredWithFiles(...).toTransform(...)` chain the real
     * registration performs is satisfied by returning a further proxy for every
     * interface-typed result. AGP's `Variant` has ~40 members, so proxying is
     * far less brittle than a hand-written fake that would need updating on
     * every AGP bump.
     */
    private fun fakeVariant(name: String): Variant = proxy(Variant::class.java, name)

    private fun <T> proxy(type: Class<T>, variantName: String): T {
        val handler = InvocationHandler { _, method: Method, _ ->
            when {
                method.name == "getName" && method.returnType == String::class.java -> variantName
                method.name == "toString" -> "FakeVariant($variantName)"
                method.name == "hashCode" -> variantName.hashCode()
                method.name == "equals" -> false
                method.returnType.isInterface -> proxy(method.returnType, variantName)
                else -> null
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
    }
}
