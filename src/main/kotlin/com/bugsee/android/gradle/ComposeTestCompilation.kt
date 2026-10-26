package com.bugsee.android.gradle

import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

/**
 * Decides whether a Kotlin compilation holds TEST sources, so the Compose tag/secure compiler
 * plugin can stay out of them: extra `Modifier`/semantics nodes in a test's own composables change
 * what parent/child matchers see.
 *
 * A bare name match is not enough — AGP lets a build type or flavor be called `smokeTest`, which
 * compiles as a PRODUCTION `smokeTest` compilation. So the classification uses the compilation's
 * real shape:
 *  - the names AGP reserves (`test`, `androidTest`, `unitTest`, `testFixtures`, and the same
 *    followed by an upper-case variant part: `testDebug`, `androidTestFreeDebug`) can never be a
 *    production variant, so they classify on name alone;
 *  - a test-like suffix (`debugUnitTest`, `releaseAndroidTest`, `jvmTest`, `androidInstrumentedTest`)
 *    must also be ASSOCIATED with another compilation (`associateWith`), which is how both AGP and
 *    KMP attach a test compilation to the one it tests. A production `smokeTest` build type has
 *    no such association.
 *
 * Unknown means "not a test": the cost of a wrong `false` is the pre-existing behaviour (tags in
 * test sources), whereas a wrong `true` silently drops capture from production code.
 */
internal object ComposeTestCompilation {

    private val RESERVED = Regex("^(test|androidTest|unitTest)(\\p{Lu}.*)?$")
    private val RESERVED_FIXTURES = Regex("^(testFixtures|.*TestFixtures)$")
    private val TEST_SUFFIX = Regex("^.*(Test|UnitTest|AndroidTest|InstrumentedTest)$")

    fun isTest(compilation: KotlinCompilation<*>): Boolean = isTest(
        name = compilation.compilationName,
        associated = {
            runCatching { compilation.allAssociatedCompilations.isNotEmpty() }.getOrDefault(false)
        }
    )

    internal fun isTest(name: String, associated: () -> Boolean): Boolean {
        if (name == KotlinCompilation.TEST_COMPILATION_NAME) return true
        if (RESERVED.matches(name) || RESERVED_FIXTURES.matches(name)) return true
        return TEST_SUFFIX.matches(name) && associated()
    }
}
