package com.bugsee.android.gradle.config

import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplicitSetProbeTest {

    private enum class Mode { A, B }

    private val project: Project = ProjectBuilder.builder().build()

    @Test
    fun defaultOnly_isNotExplicit() {
        val p = project.objects.property(Boolean::class.javaObjectType).convention(false)
        assertFalse(ExplicitSetProbe.isExplicitlySet(p))
    }

    @Test
    fun conventionFromFile_isNotExplicit() {
        val p = project.objects.property(String::class.java).convention("default")
        p.convention("fromFile")
        assertFalse(ExplicitSetProbe.isExplicitlySet(p))
        assertEquals("fromFile", p.get())
    }

    @Test
    fun set_isExplicit() {
        val p = project.objects.property(String::class.java).convention("default")
        p.set("x")
        assertTrue(ExplicitSetProbe.isExplicitlySet(p))
    }

    @Test
    fun setToSameValueAsDefault_isExplicit() {
        val p = project.objects.property(Boolean::class.javaObjectType).convention(false)
        p.set(false)
        assertTrue(ExplicitSetProbe.isExplicitlySet(p))
    }

    @Test
    fun absentNoConvention_isNotExplicit() {
        val p = project.objects.property(String::class.java)
        assertFalse(ExplicitSetProbe.isExplicitlySet(p))
        assertFalse(p.isPresent)
    }

    @Test
    fun probeDoesNotChangeValue() {
        val b = project.objects.property(Boolean::class.javaObjectType).convention(true)
        val s = project.objects.property(String::class.java).convention("d")
        val e = project.objects.property(Mode::class.java).convention(Mode.A)
        val bSet = project.objects.property(Boolean::class.javaObjectType).convention(true).also { it.set(false) }
        val sSet = project.objects.property(String::class.java).convention("d").also { it.set("x") }
        val eSet = project.objects.property(Mode::class.java).convention(Mode.A).also { it.set(Mode.B) }
        for (p in listOf<Property<*>>(b, s, e, bSet, sSet, eSet)) {
            val before = p.get()
            ExplicitSetProbe.isExplicitlySet(p)
            assertEquals(before, p.get())
        }
    }

    @Test
    fun probeTwice_isStable() {
        val d = project.objects.property(String::class.java).convention("d")
        val x = project.objects.property(String::class.java).convention("d").also { it.set("x") }
        assertFalse(ExplicitSetProbe.isExplicitlySet(d))
        assertFalse(ExplicitSetProbe.isExplicitlySet(d))
        assertTrue(ExplicitSetProbe.isExplicitlySet(x))
        assertTrue(ExplicitSetProbe.isExplicitlySet(x))
        assertEquals("x", x.get())
    }

    // ── Properties that can no longer change ───────────────────────

    @Test
    fun finalizeValueOnRead_doesNotThrow_explicitIffPresent() {
        val set = project.objects.property(String::class.java).convention("d").also {
            it.set("x")
            it.finalizeValueOnRead()
        }
        assertTrue(ExplicitSetProbe.isExplicitlySet(set))
        assertEquals("x", set.get())

        // A final property's convention cannot be cleared: present counts as explicit.
        val conventionOnly = project.objects.property(String::class.java).convention("d").also { it.finalizeValue() }
        assertTrue(ExplicitSetProbe.isExplicitlySet(conventionOnly))
        assertEquals("d", conventionOnly.get())
    }

    @Test
    fun disallowChanges_doesNotThrow_explicitIffPresent() {
        val set = project.objects.property(Boolean::class.javaObjectType).convention(false).also {
            it.set(true)
            it.disallowChanges()
        }
        assertTrue(ExplicitSetProbe.isExplicitlySet(set))
        assertEquals(true, set.get())

        val absent = project.objects.property(String::class.java).also { it.disallowChanges() }
        assertFalse(ExplicitSetProbe.isExplicitlySet(absent))
    }

    @Test
    fun setToMissingProvider_isNotExplicit() {
        val p = project.objects.property(String::class.java)
        p.set(project.provider<String> { null })
        assertFalse(ExplicitSetProbe.isExplicitlySet(p))
    }
}
