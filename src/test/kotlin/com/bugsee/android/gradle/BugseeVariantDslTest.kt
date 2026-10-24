package com.bugsee.android.gradle

import org.gradle.api.Action
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BugseeVariantDslTest {

    private fun newExt(): BugseePluginExtension {
        val project = ProjectBuilder.builder().build()
        return project.extensions.create("bugsee", BugseePluginExtension::class.java)
    }

    @Test
    fun variantBlockHoldsOwnValues() {
        val ext = newExt()
        ext.variant(
            "staging",
            Action {
                it.endpoint.set("https://s")
                it.ndk(Action { n -> n.enabled.set(true) })
            },
        )
        val block = ext.variantBlocks.getValue("staging")
        assertEquals("https://s", block.endpoint.get())
        assertTrue(block.ndk.enabled.get())
        assertEquals("https://api.bugsee.com", ext.endpoint.get())
        assertFalse(ext.ndk.enabled.get())
    }

    @Test
    fun secondCallMerges() {
        val ext = newExt()
        ext.variant("staging", Action { it.endpoint.set("https://s") })
        val first = ext.variantBlocks.getValue("staging")
        ext.variant("staging", Action { it.debug.set(true) })
        assertSame(first, ext.variantBlocks.getValue("staging"))
        assertEquals("https://s", first.endpoint.get())
        assertTrue(first.debug.get())
        assertEquals(1, ext.variantBlocks.size)
    }

    @Test
    fun blockTokenStored() {
        val ext = newExt()
        ext.variant("staging", Action { it.appToken("T") })
        assertEquals("T", ext.variantBlocks.getValue("staging").defaultAppToken)
        assertEquals(null, ext.defaultAppToken)
    }

    @Test
    fun preferSettingsFileDefaultsFalse() {
        assertFalse(newExt().preferSettingsFile.get())
    }

    @Test
    fun blocksAreIndependent() {
        val ext = newExt()
        ext.variant("a", Action { it.endpoint.set("https://a") })
        ext.variant("b", Action { it.debug.set(true) })
        val a = ext.variantBlocks.getValue("a")
        val b = ext.variantBlocks.getValue("b")
        assertNotSame(a, b)
        assertEquals("https://api.bugsee.com", b.endpoint.get())
        assertFalse(a.debug.get())
    }
}
