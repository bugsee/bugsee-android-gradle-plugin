package com.bugsee.android.gradle

import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.Variant
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.testfixtures.ProjectBuilder
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [BugseePluginImpl.mayProduceMapping] gate for the build-id mapping listener (#53). */
class MayProduceMappingTest {

    /** Stand-ins for AGP 9's DSL (`buildTypes.<type>.optimization.enable`). */
    class FakeOptimization(val enable: Boolean)

    class FakeBuildType(val name: String, enable: Boolean) {
        private val optimization = FakeOptimization(enable)
        fun getOptimization(): FakeOptimization = optimization
    }

    /** A build type whose `optimization.enable` throws when read. */
    class BrokenBuildType(val name: String) {
        class Boom {
            fun getEnable(): Boolean = error("boom")
        }
        fun getOptimization(): Boom = Boom()
    }

    /** AGP 8.x shape: an `optimization` block without an `enable` property. */
    class Agp8BuildType(val name: String) {
        fun getOptimization(): Any = Any()
    }

    /** An AGP 8-style build type: no `getOptimization` at all. */
    class LegacyBuildType(val name: String)

    class FakeExtension<T : Any>(
        private val buildTypes: NamedDomainObjectContainer<T>,
        private val productFlavors: NamedDomainObjectContainer<FakeBuildType>? = null,
    ) {
        fun getBuildTypes(): NamedDomainObjectContainer<T> = buildTypes
        fun getProductFlavors(): NamedDomainObjectContainer<FakeBuildType> =
            productFlavors ?: error("no flavors")
    }

    /** Declared by a non-public class, like AGP's internal DSL impls. */
    private class HiddenOptimization(val enable: Boolean)
    private class HiddenBuildType(val name: String, enable: Boolean) {
        private val optimization = HiddenOptimization(enable)
        fun getOptimization(): HiddenOptimization = optimization
    }

    private fun variant(
        minify: Boolean,
        buildType: String? = "release",
        flavors: List<String> = emptyList(),
    ): Variant =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ApplicationVariant::class.java),
            InvocationHandler { _, m, _ ->
                when (m.name) {
                    "isMinifyEnabled" -> minify
                    "getBuildType" -> buildType
                    "getProductFlavors" -> flavors.map { "dim" to it }
                    else -> null
                }
            },
        ) as Variant

    private inline fun <reified T : Any> extensionWith(vararg items: T): FakeExtension<T> {
        val container = ProjectBuilder.builder().build().container(T::class.java) { name ->
            items.first { (it.javaClass.getMethod("getName").invoke(it) as String) == name }
        }
        items.forEach { container.add(it) }
        return FakeExtension(container)
    }

    @Test
    fun minifyEnabled_listens() {
        assertTrue(BugseePluginImpl.mayProduceMapping(variant(true), null))
    }

    @Test
    fun minifyDisabled_noAndroidExtension_doesNotListen() {
        assertFalse(BugseePluginImpl.mayProduceMapping(variant(false), null))
    }

    @Test
    fun minifyDisabled_legacyBuildType_doesNotListen() {
        assertFalse(BugseePluginImpl.mayProduceMapping(variant(false), extensionWith(LegacyBuildType("release"))))
    }

    @Test
    fun minifyDisabled_optimizationWithoutEnable_doesNotListen() {
        assertFalse(BugseePluginImpl.mayProduceMapping(variant(false), extensionWith(Agp8BuildType("release"))))
    }

    @Test
    fun minifyDisabled_optimizationEnabled_listens() {
        assertTrue(BugseePluginImpl.mayProduceMapping(variant(false), extensionWith(FakeBuildType("release", true))))
    }

    @Test
    fun minifyDisabled_optimizationDisabled_doesNotListen() {
        assertFalse(BugseePluginImpl.mayProduceMapping(variant(false), extensionWith(FakeBuildType("release", false))))
    }

    @Test
    fun optimizationEnabledOnOtherBuildType_doesNotListen() {
        val ext = extensionWith(FakeBuildType("release", false), FakeBuildType("staging", true))
        assertFalse(BugseePluginImpl.mayProduceMapping(variant(false, "release"), ext))
        assertTrue(BugseePluginImpl.mayProduceMapping(variant(false, "staging"), ext))
    }

    @Test
    fun minifyDisabled_optimizationUnreadable_keepsListener() {
        assertTrue(BugseePluginImpl.mayProduceMapping(variant(false), extensionWith(BrokenBuildType("release"))))
    }

    @Test
    fun flavorOptimizationEnabled_listens() {
        val flavors = ProjectBuilder.builder().build().container(FakeBuildType::class.java) { n -> FakeBuildType(n, true) }
        flavors.create("paid")
        val ext = FakeExtension(extensionWith(FakeBuildType("release", false)).getBuildTypes(), flavors)
        assertTrue(BugseePluginImpl.mayProduceMapping(variant(false, "release", listOf("paid")), ext))
        assertFalse(BugseePluginImpl.mayProduceMapping(variant(false, "release", listOf("free")), ext))
    }

    @Test
    fun nonPublicDeclaringClass_isStillRead() {
        assertTrue(BugseePluginImpl.mayProduceMapping(variant(false), extensionWith(HiddenBuildType("release", true))))
        assertFalse(BugseePluginImpl.mayProduceMapping(variant(false), extensionWith(HiddenBuildType("release", false))))
    }

    @Test
    fun nonMinifiableVariant_keepsListener() {
        val v = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Variant::class.java),
            InvocationHandler { _, _, _ -> null },
        ) as Variant
        assertTrue(BugseePluginImpl.mayProduceMapping(v, null))
    }
}
