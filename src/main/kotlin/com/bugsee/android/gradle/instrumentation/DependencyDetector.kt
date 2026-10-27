package com.bugsee.android.gradle.instrumentation

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency

/**
 * Checks whether a Bugsee dependency is present in a project's configurations,
 * including dependencies reached **transitively through intermediate project
 * modules**.
 *
 * Handles three scenarios:
 * - **External dependency**: matched by `com.bugsee` group and artifact name prefix
 *   (e.g. `com.bugsee:bugsee-android` or `com.bugsee:bugsee-android-beta`).
 * - **Project dependency**: matched by `GROUP=com.bugsee` Gradle property on the
 *   dependency project, optionally restricted to a specific module name.
 * - **Transitive via an intermediate module**: the SDK is frequently consumed
 *   through a wrapper/aggregator project (e.g. a Kotlin Multiplatform `:library`
 *   module that itself declares `com.bugsee:bugsee-android`). Detection recurses
 *   into project dependencies so an app that only *transitively* depends on the
 *   SDK is still recognized. Without this, the plugin's instrumentations (and
 *   the extension-init bytecode pass) silently disable themselves for such apps.
 *
 * Only **declared** dependencies are inspected (no configuration resolution), so
 * this is safe to call at configuration time. Recursion is cycle-guarded by
 * project path.
 */
internal object DependencyDetector {

    private const val BUGSEE_GROUP = "com.bugsee"

    private val PRODUCTION_SUFFIX = Regex("^(implementation|api|runtimeOnly)$|(Implementation|Api|RuntimeOnly)$")

    // A camelCase word `test`/`Test` (testImplementation, testDebugImplementation,
    // androidTestImplementation, debugUnitTestImplementation, commonTestImplementation,
    // testFixturesApi). Not a substring match: a flavor named `testing` or `latest` stays production.
    private val TEST_WORD = Regex("(^test|Test)(?=[A-Z]|$)")

    private val PRODUCTION_SUFFIX_TAIL = Regex("(Implementation|Api|RuntimeOnly)$")

    // A camelCase word `debug`/`Debug` (debugImplementation, freeDebugApi, debugCompilationImplementation).
    private val DEBUG_WORD = Regex("(^debug|Debug)(?=[A-Z]|$)")

    /** How [isProductionConfiguration] treats `debug*` configurations. */
    enum class DebugConfigurations {
        /**
         * Count them: a `debugImplementation`-only dependency is on the debug runtime classpath, and
         * the extension modules are added project-wide (the auto-add runs before variants exist),
         * exactly as for the core SDK. Used by the third-party library detectors.
         */
        INCLUDE,

        /**
         * Ignore them: used when reading a dependency PROJECT's declarations, where a debug-only
         * edge or pin is not on a release consumer's classpath and counting it would switch the
         * release auto-add off (see [BugseeSdkDeclarations]).
         */
        EXCLUDE,
    }

    /**
     * The one production-classpath predicate shared by every scan (the library detectors and
     * [BugseeSdkDeclarations]); the callers differ only in [debug].
     *
     * Whether a configuration named [name] feeds the production (app) runtime
     * classpath: `implementation`, `api`, `runtimeOnly` and their build-type,
     * flavor, variant and KMP-source-set equivalents (`debugImplementation`,
     * `freeReleaseApi`, `androidMainImplementation`, ...). Excluded: test,
     * androidTest, unitTest, testFixtures configurations, `compileOnly` /
     * `compileOnlyApi` (in any prefix position), and anything else (lint,
     * annotationProcessor, kapt, ksp, `*CompileClasspath`, ...), none of which
     * ship in the app.
     *
     * Test-ness is a camelCase `Test` word in the name, EXCEPT when [declaredNames] (the build
     * types and flavors declared in the Android DSL, see [declaredVariantNames]) explains the
     * whole prefix: a flavor `latestTest` makes `latestTestImplementation` a production
     * configuration of that flavor, while `testLatestTestImplementation` (AGP's unit-test
     * configuration of it) stays a test configuration. The auto-add runs before variants exist, so
     * the declared names are the earliest authoritative source; with none declared the plain
     * name rule applies. A KMP source set such as `uiTest` is not a declared name, so it stays test.
     */
    fun isProductionConfiguration(
        name: String,
        declaredNames: Set<String> = emptySet(),
        debug: DebugConfigurations = DebugConfigurations.INCLUDE,
    ): Boolean {
        if (!PRODUCTION_SUFFIX.containsMatchIn(name) || name.contains("compileOnly", ignoreCase = true)) {
            return false
        }
        if (debug == DebugConfigurations.EXCLUDE && DEBUG_WORD.containsMatchIn(name)) return false
        return !TEST_WORD.containsMatchIn(name) || isDeclaredVariantConfiguration(name, declaredNames)
    }

    /**
     * Names of the build types and product flavors declared in [project]'s Android DSL, or empty
     * when there is no Android DSL (yet), or when AGP is loaded by a different classloader than
     * the plugin (the DSL type then does not match and the plain name rule applies). Reads only
     * [project]'s own extension.
     */
    fun declaredVariantNames(project: Project): Set<String> = try {
        val dsl = project.extensions.findByType(CommonExtension::class.java)
        val names = HashSet<String>()
        // Read as [Named]: every DSL container element is one, and it keeps this independent of
        // the AGP DSL element interfaces (which differ between AGP versions).
        (dsl?.buildTypes as Iterable<*>?)?.forEach { (it as? Named)?.let { n -> names += n.name } }
        (dsl?.productFlavors as Iterable<*>?)?.forEach { (it as? Named)?.let { n -> names += n.name } }
        names
    } catch (t: Exception) {
        emptySet()
    } catch (t: LinkageError) {
        emptySet()
    }

    // `latestTestImplementation` / `uiTestReleaseApi`: the prefix before the suffix is a camelCase
    // concatenation of declared build type / flavor names (AGP names a variant that way).
    private fun isDeclaredVariantConfiguration(name: String, declaredNames: Set<String>): Boolean {
        // Names AGP reserves for its test components (`test*`, `androidTest*`, `main`) can never
        // be flavors / build types; ignoring them stops them masking a genuine test configuration.
        val names = declaredNames.filter { it.isNotEmpty() && it != "main" && !it.startsWith("test") && !it.startsWith("androidTest") }
        if (names.isEmpty()) return false
        val prefix = name.replace(PRODUCTION_SUFFIX_TAIL, "")
        if (prefix.isEmpty()) return false
        val memo = HashMap<Int, Boolean>()
        fun explained(from: Int): Boolean {
            if (from == prefix.length) return true
            return memo.getOrPut(from) {
                names.any { declared ->
                    val word = if (from == 0) declared else declared.replaceFirstChar { it.uppercaseChar() }
                    prefix.startsWith(word, from) && explained(from + word.length)
                }
            }
        }
        return explained(0)
    }

    /**
     * Whether any dependency declared directly on a production-classpath configuration of
     * [project] (see [isProductionConfiguration]) matches [predicate]. Shared by every
     * third-party library detector so they cannot drift apart.
     */
    fun anyProductionDependency(project: Project, predicate: (Dependency) -> Boolean): Boolean {
        val declared = declaredVariantNames(project)
        return project.configurations.any { config ->
            isProductionConfiguration(config.name, declared) && config.dependencies.any(predicate)
        }
    }

    /**
     * Returns `true` if [project] — or any project it depends on, transitively —
     * declares a dependency matching the given [artifactPrefix] (external deps)
     * or [projectName] (Bugsee project deps).
     *
     * @param project        the project whose dependency graph to scan
     * @param artifactPrefix prefix for the maven artifact name (e.g. `"bugsee-android"`)
     * @param projectName    if non-null, only match Bugsee project dependencies with this module name
     * @param scope          if non-null, only inspect configurations whose name is
     *   in this set — the configurations that feed one variant's RUNTIME
     *   classpath. Callers instrumenting a specific variant MUST pass it:
     *   without it a `debugImplementation` SDK dependency answers `true` for
     *   release, and release bytecode gets instrumented against a class that is
     *   not on its runtime classpath (a launch-time `NoClassDefFoundError`).
     *   `null` preserves the legacy all-configurations scan.
     */
    fun hasBugseeDependency(
        project: Project,
        artifactPrefix: String,
        projectName: String? = null,
        scope: Set<String>? = null,
    ): Boolean = scan(project, artifactPrefix, projectName, scope, HashSet())

    private fun scan(
        project: Project,
        artifactPrefix: String,
        projectName: String?,
        scope: Set<String>?,
        visited: MutableSet<String>
    ): Boolean {
        // Cycle guard: project graphs can contain cycles in test/edge setups.
        if (!visited.add(project.path)) {
            return false
        }
        return project.configurations.any { config ->
            // A null scope means "every configuration" — the legacy behaviour
            // every non-variant caller still relies on.
            if (scope != null && config.name !in scope) return@any false
            config.dependencies.any { dep ->
                when {
                    isMatchingExternal(dep, artifactPrefix) -> true
                    isMatchingProject(project, dep, projectName) -> true
                    // Recurse through intermediate project modules: the external
                    // SDK dep may be declared by a wrapper/aggregator project that
                    // this project depends on (e.g. KMP `:composeApp` -> `:library`
                    // -> com.bugsee:bugsee-android).
                    dep is ProjectDependency ->
                        ProjectDependencyCompat.targetProject(project, dep)?.let { target ->
                            // Deliberately UNSCOPED past a module boundary: a
                            // variant of this project does not map onto any
                            // particular variant of a module it depends on.
                            // Over-detection here preserves the wrapper/KMP case
                            // that transitive detection exists for; the edge
                            // into the module was already scope-checked above.
                            scan(
                                evaluated(project, target),
                                artifactPrefix, projectName, null, visited
                            )
                        } ?: false
                    else -> false
                }
            }
        }
    }

    /**
     * Returns [dependencyProject] after ensuring it has been evaluated.
     *
     * Cross-project dependency inspection runs during the consuming project's
     * configuration, at which point an intermediate module may not have been
     * configured yet — its `configurations` container would then be empty and
     * its declared SDK dependency invisible. [Project.evaluationDependsOn] forces
     * the target project to evaluate first. Failures (evaluation cycles, test
     * fixtures without a real build) fall back to the project as-is.
     */
    internal fun evaluated(from: Project, dependencyProject: Project): Project {
        return try {
            from.evaluationDependsOn(dependencyProject.path)
        } catch (t: Throwable) {
            dependencyProject
        }
    }

    private fun isMatchingExternal(dep: Dependency, artifactPrefix: String): Boolean {
        return dep.group == BUGSEE_GROUP && dep.name.startsWith(artifactPrefix)
    }

    private fun isMatchingProject(from: Project, dep: Dependency, projectName: String?): Boolean {
        if (dep !is ProjectDependency) return false
        val depProject = ProjectDependencyCompat.targetProject(from, dep) ?: return false
        if (depProject.findProperty("GROUP") != BUGSEE_GROUP) return false
        return projectName == null || depProject.name == projectName
    }
}
