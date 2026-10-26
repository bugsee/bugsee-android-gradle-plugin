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
 *  2. the stock `test` and `testFixtures` compilations are tests on every target; on an ANDROID
 *     compilation the names AGP reserves (`androidTest`, the same followed by an upper-case variant
 *     part: `testDebug`, `androidTestFreeDebug`, plus `...TestFixtures`) can never be a production
 *     variant, so they classify on name alone. Off Android they do not: a custom JVM compilation
 *     such as `testFeature` is the build author's own and may be production;
 *  3. on an ANDROID compilation the AGP-generated suffixes `UnitTest`, `AndroidTest` and
 *     `ScreenshotTest` (`debugUnitTest`, `freeReleaseAndroidTest`, `debugScreenshotTest`) classify
 *     by name, confirmed by a SIBLING compilation: the name minus the suffix must be another
 *     compilation of the same target (`debug` for `debugUnitTest`). They cannot lean on associations: AGP 9's built-in Kotlin reports NO
 *     associated compilations for them, even at execution time. The sibling check is what keeps a
 *     production `freeDeviceTest` (flavor `free`, build type `deviceTest`, which AGP accepts) from
 *     being read as a test; if the sibling list cannot be read the name alone decides;
 *  4. any other `...Test` name (in practice a custom compilation such as `integrationTest`; the
 *     stock `test` compilation is caught by rule 2) must be ASSOCIATED with another compilation (`associateWith`), which is how KMP
 *     attaches a test compilation to the one it tests. A production `smokeTest` build type has no
 *     such association.
 *
 * Unknown means "not a test": the cost of a wrong `false` is the pre-existing behaviour (tags in
 * test sources), whereas a wrong `true` silently drops capture from production code.
 */
internal object ComposeTestCompilation {

    private val RESERVED = Regex("^(test|androidTest)(\\p{Lu}.*)?$")
    private val RESERVED_FIXTURES = Regex("^.*TestFixtures$")
    private val ANDROID_GENERATED_SUFFIX =
        Regex("^.*(UnitTest|AndroidTest|ScreenshotTest)$")
    private val TEST_SUFFIX = Regex("^.*Test$")

    fun isTest(compilation: KotlinCompilation<*>): Boolean {
        if (isAndroidTestModule(compilation)) return true
        return isTest(
            name = compilation.compilationName,
            androidJvm = runCatching {
                compilation.platformType == KotlinPlatformType.androidJvm
            }.getOrDefault(false),
            siblings = { siblingNames(compilation) },
            associated = { hasAssociation(compilation) }
        )
    }

    /**
     * @param siblings names of the target's compilations, or null when they cannot be read.
     */
    internal fun isTest(
        name: String,
        androidJvm: Boolean,
        siblings: () -> Set<String>? = { null },
        associated: () -> Boolean
    ): Boolean {
        if (name == KotlinCompilation.TEST_COMPILATION_NAME || name == "testFixtures") return true
        if (androidJvm) {
            if (RESERVED.matches(name) || RESERVED_FIXTURES.matches(name)) return true
            val suffix = ANDROID_GENERATED_SUFFIX.matchEntire(name)?.groupValues?.get(1)
            if (suffix != null) {
                val known = siblings() ?: return true
                val prefix = name.removeSuffix(suffix)
                if (prefix.isNotEmpty() && prefix in known) return true
            }
        }
        return TEST_SUFFIX.matches(name) && associated()
    }

    private fun siblingNames(compilation: KotlinCompilation<*>): Set<String>? = try {
        compilation.target.compilations.map { it.compilationName }.toSet()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
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
