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
 * - A property set to a provider that currently resolves to no value is
 *   reported as NOT explicitly set.
 * - A property whose value can no longer change (`finalizeValue`,
 *   `finalizeValueOnRead` after a read, `disallowChanges`) cannot have its
 *   convention cleared: it is reported as explicitly set iff it is present,
 *   and it is not mutated.
 *
 * Side effect: the probe reads the property, and a lazily computed convention is
 * frozen to its current value, so call this only once configuration of the
 * property is complete, and only when the answer is needed ([SettingsResolver]
 * probes only when per-variant input exists or `preferSettingsFile` is on).
 */
internal object ExplicitSetProbe {

    fun isExplicitlySet(prop: Property<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        return probe(prop as Property<Any>)
    }

    private fun <T : Any> probe(prop: Property<T>): Boolean {
        val before = prop.orNull ?: return false
        try {
            prop.convention(null as T?)
        } catch (e: IllegalStateException) {
            // Value is final or changes are disallowed: nothing was changed.
            return prop.isPresent
        }
        val explicit = prop.isPresent
        prop.convention(before)
        return explicit
    }
}
