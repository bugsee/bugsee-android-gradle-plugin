package com.bugsee.android.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #49: the core auto-add must see core / extension declarations made by the
 * projects the app depends on, not only the app's own configurations.
 */
class CoreSdkDeclaredInProjectsTest {

    private class Build {
        val root: Project = ProjectBuilder.builder().withName("root").build()
        val app: Project = ProjectBuilder.builder().withName("app").withParent(root).build()
        val lib: Project = ProjectBuilder.builder().withName("lib").withParent(root).build()
        val lib2: Project = ProjectBuilder.builder().withName("lib2").withParent(root).build()

        init {
            for (p in listOf(app, lib, lib2)) {
                for (c in listOf("api", "implementation", "compileOnly", "debugImplementation", "testImplementation")) {
                    p.configurations.create(c)
                }
            }
        }

        fun dependOn(from: Project, config: String, to: Project) {
            from.dependencies.add(config, from.dependencies.project(mapOf("path" to to.path)))
        }

        /** Applies the plugin to :app and returns "name:version" of every dependency the auto-add contributed. */
        fun autoAdded(declaredOnApp: Set<String> = emptySet()): Set<String> {
            app.plugins.apply(BugseePlugin::class.java)
            val deps: Set<Dependency> = app.configurations.getByName("implementation").incoming.dependencies
            return deps.filter { it.group == "com.bugsee" && "${it.name}:${it.version}" !in declaredOnApp }
                .map { "${it.name}:${it.version}" }.toSet()
        }
    }

    private val range = CoreSdkAutoLoad.range(BugseePlugin.MIN_SDK_VERSION)

    @Test
    fun libraryApiPinIsHonouredNothingAddedOnApp() {
        val b = Build()
        b.lib.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(emptySet(), b.autoAdded())
    }

    @Test
    fun libraryImplementationPinIsAddedAtTheLibraryVersionNotTheRange() {
        val b = Build()
        b.lib.dependencies.add("implementation", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(setOf("bugsee-android:7.0.0"), b.autoAdded())
    }

    @Test
    fun transitivePinThroughApiChainIsHonoured() {
        val b = Build()
        b.lib2.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.lib, "api", b.lib2)
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(emptySet(), b.autoAdded())
    }

    @Test
    fun transitivePinBehindImplementationEdgeIsNotExposed() {
        val b = Build()
        b.lib2.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.lib, "implementation", b.lib2)
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(setOf("bugsee-android:7.0.0"), b.autoAdded())
    }

    @Test
    fun libraryCompileOnlyAndDebugPinsAreIgnored() {
        val b = Build()
        b.lib.dependencies.add("compileOnly", "com.bugsee:bugsee-android:7.0.0")
        b.lib.dependencies.add("debugImplementation", "com.bugsee:bugsee-android:7.0.0")
        b.lib.dependencies.add("testImplementation", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(setOf("bugsee-android:$range"), b.autoAdded())
    }

    @Test
    fun appOwnDeclarationWinsOverLibraryPin() {
        val b = Build()
        b.lib.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.app, "implementation", b.lib)
        b.app.dependencies.add("implementation", "com.bugsee:bugsee-android:7.4.2")
        assertEquals(emptySet(), b.autoAdded(setOf("bugsee-android:7.4.2")))
    }

    @Test
    fun testScopedAppExtensionPinDoesNotSetTheAutoAddedVersion() {
        // #114: no core declared anywhere; a newer extension pin the app declares only for tests
        // must not become the auto-added core's version.
        val b = Build()
        b.app.dependencies.add("testImplementation", "com.bugsee:bugsee-android-okhttp:7.5.0")
        assertEquals(setOf("bugsee-android:$range"), b.autoAdded(setOf("bugsee-android-okhttp:7.5.0")))
    }

    @Test
    fun nothingDeclaredAnywhereAddsTheRange() {
        val b = Build()
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(setOf("bugsee-android:$range"), b.autoAdded())
    }

    @Test
    fun onlyExtensionDeclaredOnAppSeedsTheCoreVersion() {
        val b = Build()
        b.app.dependencies.add("implementation", "com.bugsee:bugsee-android-okhttp:7.0.1")
        assertEquals(
            setOf("bugsee-android:7.0.1"),
            b.autoAdded(setOf("bugsee-android-okhttp:7.0.1")),
        )
    }

    @Test
    fun onlyExtensionDeclaredInLibrarySeedsTheCoreVersion() {
        val b = Build()
        b.lib.dependencies.add("api", "com.bugsee:bugsee-android-okhttp:7.0.1")
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(setOf("bugsee-android:7.0.1"), b.autoAdded())
    }

    @Test
    fun coreVersionBeatsExtensionVersion() {
        val b = Build()
        b.lib.dependencies.add("api", "com.bugsee:bugsee-android-okhttp:7.0.1")
        b.lib.dependencies.add("implementation", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.app, "implementation", b.lib)
        assertEquals(setOf("bugsee-android:7.0.0"), b.autoAdded())
    }

    @Test
    fun extensionWithoutVersionFallsBackToTheRange() {
        val b = Build()
        b.app.dependencies.add("implementation", "com.bugsee:bugsee-android-okhttp")
        assertEquals(setOf("bugsee-android:$range"), b.autoAdded(setOf("bugsee-android-okhttp:null")))
    }

    @Test
    fun gradlePluginArtifactIsNotAnSdkModule() {
        val b = Build()
        b.app.dependencies.add("implementation", "com.bugsee:bugsee-android-gradle-plugin:4.0.0")
        assertEquals(setOf("bugsee-android:$range"), b.autoAdded(setOf("bugsee-android-gradle-plugin:4.0.0")))
    }

    @Test
    fun twoDifferentPinsPickTheHighestSoExtensionsMatchTheResolvedCore() {
        val b = Build()
        b.lib.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        b.lib2.dependencies.add("api", "com.bugsee:bugsee-android:7.1.0")
        b.dependOn(b.app, "implementation", b.lib)
        b.dependOn(b.app, "implementation", b.lib2)
        b.app.dependencies.add("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
        assertEquals(
            setOf("bugsee-android-okhttp:7.1.0"),
            b.autoAdded(setOf("okhttp:4.12.0")),
        )
    }

    @Test
    fun highestVersionPrefersParseableAndFallsBackToFirst() {
        val h = com.bugsee.android.gradle.instrumentation.BugseeSdkDeclarations
        assertEquals("7.4.2", h.highestVersion(listOf("7.0.0", "[7.0.0,8.0.0)", "7.4.2")))
        assertEquals("7.+", h.highestVersion(listOf("7.+", "latest.release")))
        assertEquals(null, h.highestVersion(emptyList()))
    }

    @Test
    fun testAndCompileOnlyAndDebugEdgesToALibraryDoNotCountAsPresent() {
        for (config in listOf("testImplementation", "compileOnly", "debugImplementation")) {
            val b = Build()
            b.lib.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
            b.dependOn(b.app, config, b.lib)
            assertEquals(setOf("bugsee-android:$range"), b.autoAdded(), "edge on $config")
        }
    }

    @Test
    fun runtimeOnlyEdgeIsNotCompileExposedSoCoreIsAddedAtTheLibraryVersion() {
        val b = Build()
        b.app.configurations.create("runtimeOnly")
        b.lib.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.app, "runtimeOnly", b.lib)
        assertEquals(setOf("bugsee-android:7.0.0"), b.autoAdded())
    }

    @Test
    fun flavorPrefixedDebugEdgeAndPinAreNotProduction() {
        val edge = Build()
        edge.app.configurations.create("freeDebugImplementation")
        edge.lib.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        edge.dependOn(edge.app, "freeDebugImplementation", edge.lib)
        assertEquals(setOf("bugsee-android:$range"), edge.autoAdded())

        val pin = Build()
        pin.lib.configurations.create("freeDebugApi")
        pin.lib.dependencies.add("freeDebugApi", "com.bugsee:bugsee-android:7.0.0")
        pin.dependOn(pin.app, "implementation", pin.lib)
        assertEquals(setOf("bugsee-android:$range"), pin.autoAdded())
    }

    @Test
    fun flavorNamedLatestIsNotATestConfiguration() {
        val b = Build()
        b.app.configurations.create("latestImplementation")
        b.lib.configurations.create("latestApi")
        b.lib.dependencies.add("latestApi", "com.bugsee:bugsee-android:7.0.0")
        b.dependOn(b.app, "latestImplementation", b.lib)
        assertEquals(emptySet(), b.autoAdded())
    }

    @Test
    fun threeLevelChainRespectsApiVersusImplementation() {
        // :app -> :lib -> :lib2(core). api all the way: present. A middle implementation edge: not exposed.
        val api = Build()
        api.lib2.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        api.dependOn(api.lib, "api", api.lib2)
        api.dependOn(api.app, "api", api.lib)
        assertEquals(emptySet(), api.autoAdded())

        val impl = Build()
        impl.lib2.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        impl.dependOn(impl.lib, "implementation", impl.lib2)
        impl.dependOn(impl.app, "api", impl.lib)
        assertEquals(setOf("bugsee-android:7.0.0"), impl.autoAdded())
    }

    @Test
    fun projectDependencyCycleTerminates() {
        val b = Build()
        b.dependOn(b.app, "implementation", b.lib)
        b.dependOn(b.lib, "api", b.lib2)
        b.dependOn(b.lib2, "api", b.lib)
        b.dependOn(b.lib2, "api", b.app)
        b.lib2.dependencies.add("api", "com.bugsee:bugsee-android:7.0.0")
        assertTrue(b.autoAdded().isEmpty())
    }
}
