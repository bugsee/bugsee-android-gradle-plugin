package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency

/**
 * What the build already DECLARES about the Bugsee SDK in the projects the
 * consuming app depends on (transitively), as opposed to on the app itself.
 *
 * The core auto-add decision (is `bugsee-android` already declared? at which
 * version?) used to look at the app's own configurations only, so a library
 * module's `api("com.bugsee:bugsee-android:7.0.0")` was invisible and the app
 * got an additional `[7.3.0,8.0.0)` on top — Gradle's conflict resolution then
 * silently raised the library's pin. This scan reuses the recursion model of
 * [DependencyDetector] (declared deps only, cycle-guarded by project path,
 * dependency projects evaluated first) to expose those declarations.
 *
 * Only configurations that feed a consumer's RUNTIME classpath are read from a
 * dependency project (see [isProductionConfiguration]); the app's own
 * configurations are handled by the caller exactly as before.
 */
internal object BugseeSdkDeclarations {

    private const val BUGSEE_GROUP = "com.bugsee"
    private const val CORE_ARTIFACT = "bugsee-android"

    /**
     * One declaration found in a dependency project.
     *
     * @property version the explicit version string, or `null` (BOM-managed / project dep)
     * @property isCore `true` for `bugsee-android` itself (or the SDK's own `:library`/`:stub`
     *   project), `false` for an extension module such as `bugsee-android-okhttp`
     * @property exposed `true` when the consuming app also sees it at COMPILE time — every
     *   edge down to the declaring project is an `api` edge. An `implementation`-only
     *   declaration is on the app's runtime classpath but not its compile classpath
     */
    data class Declaration(val version: String?, val isCore: Boolean, val exposed: Boolean)

    /**
     * All Bugsee core/extension declarations reachable through [project]'s
     * project dependencies, in depth-first discovery order (NOT nearest-first; use
     * [highestVersion] to choose among them). [project]'s own configurations are NOT
     * inspected for SDK modules (the caller does that itself), but the project edges
     * leaving it are followed.
     */
    fun inDependencyProjects(project: Project): List<Declaration> {
        val out = ArrayList<Declaration>()
        // A cycle back to the app must not revisit it as a dependency project.
        val visited = HashSet<String>().also {
            it.add(visitKey(project.path, true))
            it.add(visitKey(project.path, false))
        }
        // Edges leaving the app are "exposed" (the lib's `api` surface is on the app's
        // COMPILE classpath) unless declared `runtimeOnly`, which is runtime-only.
        for (config in project.configurations) {
            if (!isProductionConfiguration(config.name)) continue
            val exposed = !(config.name == "runtimeOnly" || config.name.endsWith("RuntimeOnly"))
            for (dep in config.dependencies) {
                if (dep is ProjectDependency) visit(project, dep, exposed, visited, out)
            }
        }
        return out
    }

    private fun visit(
        from: Project,
        edge: ProjectDependency,
        edgeExposed: Boolean,
        visited: MutableSet<String>,
        out: MutableList<Declaration>,
    ) {
        val target = ProjectDependencyCompat.targetProject(from, edge) ?: return
        // Keyed by exposure too: a module first reached through an `implementation`
        // edge must still be revisited when another path reaches it through `api`.
        // The key space is finite, so project cycles still terminate.
        if (!visited.add(visitKey(target.path, edgeExposed))) return
        val lib = DependencyDetector.evaluated(from, target)
        if (lib.findProperty("GROUP") == BUGSEE_GROUP && (lib.name == "library" || lib.name == "stub")) {
            // The SDK's own module, consumed as a local project (SDK development):
            // always "present" — never layer a range on top of a local project.
            out.add(Declaration(null, isCore = true, exposed = true))
            return
        }
        for (config in lib.configurations) {
            if (!isProductionConfiguration(config.name)) continue
            val api = isApi(config.name)
            for (dep in config.dependencies) {
                when {
                    dep is ProjectDependency -> visit(lib, dep, edgeExposed && api, visited, out)
                    isSdkModule(dep.group, dep.name) ->
                        out.add(
                            Declaration(
                                dep.versionOrNull(),
                                isCore = dep.name == CORE_ARTIFACT,
                                exposed = edgeExposed && api,
                            )
                        )
                }
            }
        }
    }

    private val TEST_WORD = Regex("(^test|Test)(?=[A-Z]|$)")
    private val DEBUG_WORD = Regex("(^debug|Debug)(?=[A-Z]|$)")

    /**
     * Declaration configurations (of the app, or of a dependency project) that put
     * a dependency on a RELEASE consumer's runtime classpath: `api` / `implementation`
     * / `runtimeOnly`, optionally with a source-set or variant prefix (KMP:
     * `commonMainApi`, `androidMainImplementation`; flavors: `latestImplementation`).
     * Excluded: `compileOnly*` (not shipped), test configurations (camelCase `test` /
     * `Test` words only — a flavor named `latest` or `attestation` is NOT a test config),
     * and `debug*`: a debug-only edge or pin is not on the release classpath, and
     * counting it would switch the release auto-add (and instrumentation) off. The cost
     * is the opposite error on debug builds — a debug-only pin is ignored there and the
     * default range is added as before this analysis existed; that is the established,
     * safe fallback. Note the app's OWN declarations are still scanned across every
     * configuration by the caller (unchanged legacy behaviour).
     *
     * TODO: unify with `DependencyDetector.isProductionConfiguration` once #68 merges.
     */
    private fun isProductionConfiguration(name: String): Boolean {
        if (name.startsWith("compileOnly") || TEST_WORD.containsMatchIn(name) || DEBUG_WORD.containsMatchIn(name)) {
            return false
        }
        return name == "api" || name == "implementation" || name == "runtimeOnly" ||
            name.endsWith("Api") || name.endsWith("Implementation") || name.endsWith("RuntimeOnly")
    }

    /**
     * The version Gradle's conflict resolution would pick among exact versions: the
     * HIGHEST one [BugseeSdkVersion.parse] can read; the first entry when none parse
     * (ranges, `+`, `latest.release`). Note `preferImpl`: a library's soft
     * `prefer("7.0.0")` is reported by `Dependency.version` as `7.0.0`, so copying it
     * onto the app makes it a hard requirement there — harmless, the result is the same.
     */
    fun highestVersion(versions: List<String>): String? =
        versions.mapNotNull { v -> BugseeSdkVersion.parse(v)?.let { it to v } }
            .maxByOrNull { it.first }?.second
            ?: versions.firstOrNull()

    private fun visitKey(path: String, exposed: Boolean) = "$path|$exposed"

    /**
     * `true` for `com.bugsee:bugsee-android` and its extension modules
     * (`bugsee-android-okhttp`, …), which are published under the SDK's version line.
     * The Gradle plugin's own artifact (`bugsee-android-gradle-plugin`) is not one.
     */
    fun isSdkModule(group: String?, name: String): Boolean =
        group == BUGSEE_GROUP && name.startsWith(CORE_ARTIFACT) && !name.endsWith("-gradle-plugin")

    private fun isApi(name: String): Boolean = name == "api" || name.endsWith("Api")

    private fun Dependency.versionOrNull(): String? = version?.takeIf { it.isNotBlank() }
}
