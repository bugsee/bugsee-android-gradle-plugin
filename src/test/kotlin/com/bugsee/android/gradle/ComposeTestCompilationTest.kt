package com.bugsee.android.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which Kotlin compilations the Compose tag/secure compiler plugin must stay out of (#56). */
class ComposeTestCompilationTest {

    private fun isTest(
        name: String,
        associated: Boolean = false,
        androidJvm: Boolean = false,
        siblings: Set<String>? = null,
    ) = ComposeTestCompilation.isTest(name, androidJvm, { siblings }) { associated }

    @Test
    fun `reserved test names are tests with or without an association`() {
        listOf("test", "androidTest", "unitTest", "testFixtures").forEach {
            assertTrue(isTest(it), it)
        }
        listOf("testDebug", "testFreeRelease", "androidTestDebug", "androidTestFreeDebug",
            "debugTestFixtures", "releaseTestFixtures").forEach {
            assertTrue(isTest(it), it)
        }
    }

    @Test
    fun `associated test-suffixed compilations are tests`() {
        listOf("debugUnitTest", "releaseUnitTest", "freeDebugAndroidTest", "releaseAndroidTest",
            "jvmTest", "androidUnitTest", "androidInstrumentedTest", "commonTest").forEach {
            assertTrue(isTest(it, associated = true), it)
        }
    }

    /**
     * AGP 9's built-in Kotlin reports NO associated compilations for its test compilations (even at
     * execution time), so on an Android compilation the generated suffixes must stand on the name.
     */
    @Test
    fun `android generated test suffixes are tests without any association`() {
        listOf("debugUnitTest", "freeReleaseUnitTest", "debugAndroidTest", "freeDebugAndroidTest",
            "debugScreenshotTest", "androidHostTest", "androidDeviceTest").forEach {
            assertTrue(isTest(it, associated = false, androidJvm = true), it)
        }
        // Off Android the same names are not trusted without an association.
        assertFalse(isTest("debugUnitTest", associated = false, androidJvm = false))
    }

    /**
     * AGP accepts a build type called `deviceTest`; with a flavor it compiles as the PRODUCTION
     * `freeDeviceTest`, which only the sibling check tells apart from a generated test compilation.
     */
    @Test
    fun `a production variant ending in a generated suffix is not a test`() {
        val siblings = setOf("freeDebug", "freeRelease", "freeDeviceTest", "freeDebugUnitTest")
        assertFalse(isTest("freeDeviceTest", androidJvm = true, siblings = siblings))
        assertFalse(isTest("freeScreenshotTest", androidJvm = true, siblings = setOf("freeScreenshotTest")))
        // ...while the generated compilations next to real variants are still tests, with no association.
        assertTrue(isTest("freeDebugUnitTest", androidJvm = true, siblings = siblings))
        assertTrue(isTest("freeDeviceTestUnitTest", androidJvm = true, siblings = siblings))
        assertTrue(isTest("androidHostTest", androidJvm = true, siblings = setOf("androidMain", "androidHostTest")))
    }

    @Test
    fun `production variants stay applicable`() {
        listOf("debug", "release", "staging", "freeDebug", "paidRelease", "main",
            "androidMain", "commonMain", "jvm", "latestTestRelease", "testimonialDebug")
            .forEach {
                assertFalse(isTest(it, associated = true, androidJvm = true), it)
                assertFalse(isTest(it, associated = true, androidJvm = false), it)
            }
    }

    /** A build type called `smokeTest` is a production compilation; only association can tell. */
    @Test
    fun `a build type merely ending in Test is production unless associated`() {
        assertFalse(isTest("smokeTest", associated = false, androidJvm = true))
        assertFalse(isTest("freeSmokeTest", associated = false, androidJvm = true))
        assertTrue(isTest("smokeTest", associated = true))
    }

    // --- against KotlinCompilation stubs (dynamic proxies: the real ones need the full KGP) ---

    private fun stub(
        project: Project,
        name: String,
        platform: KotlinPlatformType = KotlinPlatformType.androidJvm,
        siblings: List<String>? = null,
        allAssociated: () -> Set<Any> = { emptySet() },
        associateWith: () -> List<Any> = { emptyList() },
    ): KotlinCompilation<*> {
        val target = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(KotlinTarget::class.java)
        ) { _, m, _ ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "getProject" -> project
                "getCompilations" -> project.container(KotlinCompilation::class.java) { stub(project, it, platform) }.also { c ->
                    (siblings ?: throw UnsupportedOperationException(m.name)).forEach { c.create(it) }
                }
                else -> throw UnsupportedOperationException(m.name)
            }
        } as KotlinTarget
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(KotlinCompilation::class.java)
        ) { _, m, _ ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(name)
                "equals" -> false
                "toString" -> "stub($name)"
                "getCompilationName", "getName" -> name
                "getPlatformType" -> platform
                "getTarget" -> target
                "getAllAssociatedCompilations" -> allAssociated()
                "getAssociateWith" -> associateWith()
                else -> throw UnsupportedOperationException(m.name)
            }
        } as KotlinCompilation<*>
    }

    private val project: Project = ProjectBuilder.builder().build()

    @Test
    fun `stub compilations are classified by name, platform and association`() {
        assertTrue(ComposeTestCompilation.isTest(stub(project, "debugUnitTest")))
        assertFalse(ComposeTestCompilation.isTest(stub(project, "debug")))
        assertFalse(ComposeTestCompilation.isTest(stub(project, "smokeTest")))
        assertTrue(ComposeTestCompilation.isTest(stub(project, "smokeTest", allAssociated = { setOf(Any()) })))
    }

    @Test
    fun `stub compilations are checked against their sibling compilations`() {
        val names = listOf("freeDebug", "freeDeviceTest", "freeDebugUnitTest")
        assertFalse(ComposeTestCompilation.isTest(stub(project, "freeDeviceTest", siblings = names)))
        assertTrue(ComposeTestCompilation.isTest(stub(project, "freeDebugUnitTest", siblings = names)))
    }

    @Test
    fun `a failing association lookup is treated as not associated`() {
        assertFalse(ComposeTestCompilation.isTest(stub(project, "smokeTest", allAssociated = { error("boom") })))
    }

    /** KGP before 1.9.20 has no `allAssociatedCompilations`; the call then fails with a LinkageError. */
    @Test
    fun `old KGP without allAssociatedCompilations falls back to associateWith`() {
        val old = stub(
            project, "jvmTest", platform = KotlinPlatformType.jvm,
            allAssociated = { throw NoSuchMethodError("getAllAssociatedCompilations") },
            associateWith = { listOf(Any()) },
        )
        assertTrue(ComposeTestCompilation.isTest(old))
        val oldProduction = stub(
            project, "smokeTest",
            allAssociated = { throw NoSuchMethodError("getAllAssociatedCompilations") },
        )
        assertFalse(ComposeTestCompilation.isTest(oldProduction))
    }

    // --- BugseePlugin.isApplicable itself ---

    class FakeKotlinPlugin : KotlinBasePlugin {
        override val pluginVersion: String = "2.1.0"
        override fun apply(target: Project) {}
    }

    private fun appliedPlugin(): Pair<Project, BugseePlugin> {
        val p = ProjectBuilder.builder().build()
        p.configurations.create("implementation")
        p.dependencies.add("implementation", "androidx.compose.ui:ui:1.7.0")
        p.plugins.apply(FakeKotlinPlugin::class.java)
        @Suppress("UNCHECKED_CAST")
        p.extensions.add(
            AndroidComponentsExtension::class.java as Class<Any>,
            "androidComponents",
            Proxy.newProxyInstance(
                AndroidComponentsExtension::class.java.classLoader,
                arrayOf(AndroidComponentsExtension::class.java)
            ) { _, m, _ ->
                when {
                    m.name == "hashCode" -> 1
                    m.name == "equals" -> false
                    m.returnType.isInterface -> Proxy.newProxyInstance(
                        m.returnType.classLoader, arrayOf(m.returnType)
                    ) { _, _, _ -> null }
                    else -> null
                }
            }
        )
        p.plugins.apply(BugseePlugin::class.java)
        return p to p.plugins.getPlugin(BugseePlugin::class.java)
    }

    @Test
    fun `isApplicable keeps production compilations and skips test ones`() {
        val (p, plugin) = appliedPlugin()
        listOf("debug", "release", "freeDebug", "staging", "smokeTest").forEach {
            assertTrue(plugin.isApplicable(stub(p, it)), "$it must stay applicable")
        }
        listOf("debugUnitTest", "releaseAndroidTest", "debugScreenshotTest", "test", "debugTestFixtures")
            .forEach { assertFalse(plugin.isApplicable(stub(p, it)), "$it must not be applicable") }
    }

    @Test
    fun `isApplicable skips an association-less AGP 9 unit test compilation`() {
        val (p, plugin) = appliedPlugin()
        assertFalse(plugin.isApplicable(stub(p, "debugUnitTest", allAssociated = { emptySet() })))
    }
}
