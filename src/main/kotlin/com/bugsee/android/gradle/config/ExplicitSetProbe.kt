package com.bugsee.android.gradle.config

import org.gradle.api.provider.Property

/**
 * Distinguishes a Gradle [Property] that was explicitly assigned (`set`/`value`)
 * from one that only resolves through a convention (built-in default or a
 * value layered from bugsee.properties).
 *
 * Technique: clear the convention, then check presence. With no convention a
 * property is present only if it was explicitly assigned. The resolved value is
 * then restored as the convention, so the observable value is unchanged.
 *
 * Side effect: a lazily computed convention is frozen to its current value, so
 * call this only once configuration of the property is complete.
 */
internal object ExplicitSetProbe {

    fun isExplicitlySet(prop: Property<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        return probe(prop as Property<Any>)
    }

    private fun <T : Any> probe(prop: Property<T>): Boolean {
        val before = prop.orNull ?: return false
        prop.convention(null as T?)
        val explicit = prop.isPresent
        prop.convention(before)
        return explicit
    }
}
