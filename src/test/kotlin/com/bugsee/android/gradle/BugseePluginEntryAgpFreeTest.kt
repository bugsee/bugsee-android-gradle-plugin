package com.bugsee.android.gradle

import org.junit.Test
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the invariant behind #161: Gradle decorates the plugin entry class by loading every type in its
 * signature, so [BugseePlugin] must never mention an Android Gradle Plugin type, directly or through a
 * supertype, field or method. Anything AGP-aware belongs in [BugseePluginImpl].
 */
class BugseePluginEntryAgpFreeTest {

    private fun typesOf(m: Method): List<Class<*>> =
        listOf(m.returnType) + m.parameterTypes + m.exceptionTypes

    @Test
    fun entry_class_signature_mentions_no_agp_type() {
        val c = BugseePlugin::class.java
        val referenced = buildList<Class<*>> {
            add(c.superclass)
            addAll(c.interfaces)
            c.declaredFields.forEach { add(it.type) }
            c.declaredMethods.forEach { addAll(typesOf(it)) }
            c.declaredConstructors.forEach { addAll(it.parameterTypes) }
            c.declaredClasses.forEach { add(it) }
        }
        // Gradle's decoration also loads BugseePluginImpl (via the implementation() accessor), so its
        // supertypes must be AGP-free too.
        val impl = BugseePluginImpl::class.java
        val offendersImpl = (listOf(impl.superclass) + impl.interfaces).map { it.name }.filter { it.startsWith("com.android.") }
        assertTrue(offendersImpl.isEmpty(), "AGP supertypes on BugseePluginImpl: $offendersImpl")
        val offenders = referenced.map { it.name }.filter { it.startsWith("com.android.") }
        assertTrue(offenders.isEmpty(), "AGP types in BugseePlugin's signature: $offenders")
    }

    @Test
    fun metadata_the_entry_needs_is_not_on_the_impl() {
        assertEquals("com.bugsee.compose.compiler", PluginMetadata.COMPILER_PLUGIN_ID)
        assertEquals(PluginMetadata.VERSION, BugseePluginImpl.PLUGIN_VERSION)
    }

    @Test
    fun agp_probe_is_true_on_a_classpath_with_agp_and_messages_name_the_project() {
        assertTrue(BugseePlugin.isAgpLoadable()) // gradle-api is on the test classpath
        assertTrue(BugseePlugin.noAgpMessage(":m").contains("project :m"))
        assertTrue(BugseePlugin.agpNotVisibleMessage(":app").contains("project :app"))
        assertFalse(BugseePlugin.noAgpMessage(":m").isEmpty())
    }
}
