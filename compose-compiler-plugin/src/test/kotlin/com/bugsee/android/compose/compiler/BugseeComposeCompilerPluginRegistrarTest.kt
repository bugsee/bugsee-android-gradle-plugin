package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BugseeComposeCompilerPluginRegistrar] — the entry
 * point Kotlin's compiler driver discovers via
 * `META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`.
 *
 * Three concerns pinned:
 *  - **K2 support flag.** `supportsK2 == true` is REQUIRED for the
 *    plugin to load against Kotlin 2.x. A regression to `false` (or
 *    a missing override) would silently disable the plugin against
 *    the K2 backend, which is the only backend supported on Kotlin
 *    2.1+.
 *  - **Service-loader registration files.** The
 *    `META-INF/services/...` files must (a) exist on the runtime
 *    classpath, (b) name the registrar / command-line-processor
 *    classes that actually exist. A typo or a stale entry after
 *    renaming the class would silently fail compiler-driver
 *    discovery and the plugin would just not load — Kotlin's driver
 *    does not log when a registered class is missing.
 *  - **Plugin ID drift.** The registrar exposes `pluginId` as a
 *    derived value from
 *    [BugseeComposeCommandLineProcessor.PLUGIN_ID]; if either
 *    drifts the gradle-plugin-side `SubpluginOption(pluginId = ...)`
 *    wouldn't match and options handed to the compiler would land
 *    on the wrong plugin's option queue (which means our defaults
 *    would silently be in effect regardless of user DSL).
 */
@OptIn(ExperimentalCompilerApi::class)
class BugseeComposeCompilerPluginRegistrarTest {

    @Test fun `supportsK2 is true so the plugin loads on Kotlin 2x`() {
        // Kotlin 2.0+ K2 frontend requires `supportsK2` to be set.
        // Failure here means the plugin would be silently skipped
        // on Kotlin 2.x driver — the registered IR extension would
        // never fire, and consumers would see no Compose
        // instrumentation at all.
        val registrar = BugseeComposeCompilerPluginRegistrar()
        assertTrue(
            "BugseeComposeCompilerPluginRegistrar.supportsK2 MUST be true on Kotlin 2.x",
            registrar.supportsK2,
        )
    }

    @Test fun `is a CompilerPluginRegistrar (correct supertype for service-loader discovery)`() {
        // Pin the supertype: the META-INF service file declares
        // implementations of CompilerPluginRegistrar. A future
        // refactor that changed the supertype (e.g. moved to
        // ComponentRegistrar) would silently make the META-INF
        // entry invalid. Use a reflective supertype walk rather
        // than an `is` check (which the Kotlin compiler folds to
        // `true` at compile time, because the class declares the
        // supertype directly).
        val supertypes = generateSequence<Class<*>>(BugseeComposeCompilerPluginRegistrar::class.java) {
            it.superclass
        }.toList()
        assertTrue(
            "registrar must extend CompilerPluginRegistrar; supertypes were $supertypes",
            supertypes.any { it == CompilerPluginRegistrar::class.java },
        )
    }

    @Test fun `service-loader file points to the existing registrar class`() {
        // The META-INF file is the wire contract between the
        // compiler driver's service loader and our plugin entry
        // point. A drift between the file content and the class
        // name (e.g. after a class rename) is silent — the loader
        // skips missing entries without logging.
        val resourceName = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        val resource = javaClass.classLoader.getResource(resourceName)
        assertNotNull(
            "expected META-INF service file at $resourceName on the test classpath",
            resource,
        )
        val content = resource!!.readText().trim()
        assertEquals(
            "service-loader file must name the BugseeComposeCompilerPluginRegistrar class verbatim",
            "com.bugsee.android.compose.compiler.BugseeComposeCompilerPluginRegistrar",
            content,
        )
        // The named class must actually load — pin that the file
        // doesn't point at a renamed-but-not-deleted ghost.
        val loaded = javaClass.classLoader.loadClass(content)
        assertEquals(
            "service-loader-named class must be the registrar",
            BugseeComposeCompilerPluginRegistrar::class.java,
            loaded,
        )
    }

    @Test fun `service-loader file for CommandLineProcessor points to the existing processor class`() {
        val resourceName = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor"
        val resource = javaClass.classLoader.getResource(resourceName)
        assertNotNull(
            "expected META-INF service file at $resourceName on the test classpath",
            resource,
        )
        val content = resource!!.readText().trim()
        assertEquals(
            "service-loader file must name the BugseeComposeCommandLineProcessor class verbatim",
            "com.bugsee.android.compose.compiler.BugseeComposeCommandLineProcessor",
            content,
        )
        val loaded = javaClass.classLoader.loadClass(content)
        assertEquals(
            "service-loader-named class must be the command-line processor",
            BugseeComposeCommandLineProcessor::class.java,
            loaded,
        )
    }

    @Test fun `pluginId on registrar matches the command-line-processor constant`() {
        // The registrar exposes `pluginId` purely as a convenience
        // for newer Kotlin (2.3+) compiler-driver APIs that read it.
        // Pin the field's existence + value so a regression to a
        // stale string would break this test rather than silently
        // sending DSL options to the wrong plugin queue.
        val registrar = BugseeComposeCompilerPluginRegistrar()
        // Use reflection to avoid `override` warnings — the parent
        // class doesn't expose `pluginId` on Kotlin 2.1; we ship
        // the field as a plain val so it gets picked up at runtime
        // on Kotlin 2.3+ via JVM method resolution.
        val field = registrar::class.java.getDeclaredField("pluginId")
        field.isAccessible = true
        val value = field.get(registrar)
        assertEquals(
            "registrar's pluginId field must match the command-line processor's PLUGIN_ID constant",
            BugseeComposeCommandLineProcessor.PLUGIN_ID,
            value,
        )
    }
}
