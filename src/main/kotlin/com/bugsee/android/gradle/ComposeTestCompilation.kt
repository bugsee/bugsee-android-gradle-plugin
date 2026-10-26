package com.bugsee.android.gradle

import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * Decides whether a Kotlin compilation holds TEST sources, so the Compose tag/secure compiler
 * plugin can stay out of them: extra `Modifier`/semantics nodes in a test's own composables change
 * what parent/child matchers see.
 *
 * A bare name match is not enough — AGP lets a build type or flavor be called `smokeTest`, which
 * compiles as a PRODUCTION `smokeTest` compilation. So the classification uses the compilation's
 * real shape, in this order:
 *  1. a `com.android.test` module is test code throughout, whatever its compilations are called;
 *  2. the names AGP reserves (`test`, `androidTest`, `unitTest`, `testFixtures`, and the same
 *     followed by an upper-case variant part: `testDebug`, `androidTestFreeDebug`, plus
 *     `...TestFixtures`) can never be a production variant, so they classify on name alone;
 *  3. on an ANDROID compilation the AGP-generated suffixes `UnitTest`, `AndroidTest`,
 *     `ScreenshotTest`, `HostTest` and `DeviceTest` (`debugUnitTest`, `freeReleaseAndroidTest`,
 *     `androidHostTest`) also classify on name alone. They cannot lean on associations: AGP 9's
 *     built-in Kotlin reports NO associated compilations for them, even at execution time;
 *  4. any other `...Test` name (`jvmTest`, `androidInstrumentedTest`, an `integrationTest`
 *     compilation) must be ASSOCIATED with another compilation (`associateWith`), which is how KMP
 *     attaches a test compilation to the one it tests. A production `smokeTest` build type has no
 *     such association.
 *
 * Unknown means "not a test": the cost of a wrong `false` is the pre-existing behaviour (tags in
 * test sources), whereas a wrong `true` silently drops capture from production code.
 */
internal object ComposeTestCompilation {

    private val RESERVED = Regex("^(test|androidTest|unitTest)(\\p{Lu}.*)?$")
    private val RESERVED_FIXTURES = Regex("^(testFixtures|.*TestFixtures)$")
    private val ANDROID_GENERATED_SUFFIX =
        Regex("^.*(UnitTest|AndroidTest|ScreenshotTest|HostTest|DeviceTest)$")
    private val TEST_SUFFIX = Regex("^.*Test$")

    fun isTest(compilation: KotlinCompilation<*>): Boolean {
        if (isAndroidTestModule(compilation)) return true
        return isTest(
            name = compilation.compilationName,
            androidJvm = runCatching {
                compilation.platformType == KotlinPlatformType.androidJvm
            }.getOrDefault(false),
            associated = { hasAssociation(compilation) }
        )
    }

    internal fun isTest(name: String, androidJvm: Boolean, associated: () -> Boolean): Boolean {
        if (name == KotlinCompilation.TEST_COMPILATION_NAME) return true
        if (RESERVED.matches(name) || RESERVED_FIXTURES.matches(name)) return true
        if (androidJvm && ANDROID_GENERATED_SUFFIX.matches(name)) return true
        return TEST_SUFFIX.matches(name) && associated()
    }

    private fun isAndroidTestModule(compilation: KotlinCompilation<*>): Boolean = try {
        compilation.target.project.plugins.hasPlugin("com.android.test")
    } catch (_: Exception) {
        false
    }

    /**
     * `allAssociatedCompilations` only exists from KGP 1.9.20; the older `associateWith` list
     * exists on every line this plugin supports. Any failure reads as "not associated".
     */
    @Suppress("DEPRECATION")
    private fun hasAssociation(compilation: KotlinCompilation<*>): Boolean {
        try {
            return compilation.allAssociatedCompilations.isNotEmpty()
        } catch (_: Exception) {
        } catch (_: LinkageError) {
        }
        return try {
            compilation.associateWith.isNotEmpty()
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}
