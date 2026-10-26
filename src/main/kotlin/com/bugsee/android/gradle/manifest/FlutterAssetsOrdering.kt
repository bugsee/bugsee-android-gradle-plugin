package com.bugsee.android.gradle.manifest

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.Callable

/**
 * Orders the build-id asset injection task after the tasks that write into AGP's
 * merged-assets directory without AGP knowing.
 *
 * Flutter's Gradle plugin does this from `copyFlutterAssets<Variant>`. Our task
 * transforms `SingleArtifact.ASSETS` and so reads that same directory, and Gradle's
 * task validation rejects the build ("uses this output of task ... without
 * declaring an explicit or implicit dependency"). The transform cannot be replaced
 * by a generated assets source: the build id is derived from R8's mapping, and
 * routing it through `mergeAssets` closes a cycle with Flutter's own wiring.
 *
 * We declare a real `dependsOn` (not just `mustRunAfter`): we consume the
 * directory those tasks populate, so they must also run whenever we do.
 *
 * Two sources of task names:
 *  - the built-in Flutter name `copyFlutterAssets<Variant>`;
 *  - the user's `bugsee { assetsDependsOn(...) }` list, for renamed Flutter tasks
 *    or other plugins that write the merged assets. `<Variant>` in an entry is
 *    replaced by the capitalized variant name.
 *
 * Lookup is by name at task-graph time, so a task registered after our variant
 * callback is still found, nothing is realized eagerly, and apps without such
 * tasks see no change. After evaluation, [diagnostics] flags the two situations
 * that would otherwise resurface the validation error silently.
 */
internal object FlutterAssetsOrdering {

    const val VARIANT_PLACEHOLDER = "<Variant>"

    private const val VARIANTS_KEY = "bugsee.flutterAssetsVariants"

    fun builtInTaskName(capitalizedVariant: String): String = "copyFlutterAssets$capitalizedVariant"

    fun taskNames(capitalizedVariant: String, extra: List<String>): List<String> =
        (listOf(builtInTaskName(capitalizedVariant)) +
            extra.map { it.replace(VARIANT_PLACEHOLDER, capitalizedVariant) }).distinct()

    fun wire(
        project: Project,
        injectTask: TaskProvider<out Task>,
        capitalizedVariant: String,
        extraTaskNames: Provider<List<String>>,
    ) {
        injectTask.configure { task ->
            task.dependsOn(
                Callable<List<TaskProvider<Task>>> {
                    val existing = project.tasks.names
                    taskNames(capitalizedVariant, extraTaskNames.orNull.orEmpty())
                        .filter { it in existing }
                        .map { project.tasks.named(it) }
                }
            )
        }
        recordVariant(project, capitalizedVariant, extraTaskNames)
    }

    /**
     * Messages for situations where the ordering may silently be missing:
     *  - Flutter is applied but neither `copyFlutterAssets<Variant>` nor a configured
     *    name exists for ANY variant (Flutter may have renamed its task);
     *  - a configured `assetsDependsOn` name matches no task for any variant.
     */
    fun diagnostics(
        existingTaskNames: Set<String>,
        flutterApplied: Boolean,
        capitalizedVariants: Collection<String>,
        extra: List<String>,
    ): List<String> {
        val messages = mutableListOf<String>()
        // Flutter registers copyFlutterAssets<Variant> only for the variant being built, so a missing
        // task for some variants is normal. None for every variant is the signature of a rename.
        val anyOrdered = capitalizedVariants.any { v -> taskNames(v, extra).any { it in existingTaskNames } }
        if (flutterApplied && capitalizedVariants.isNotEmpty() && !anyOrdered) {
            messages += "Bugsee: the Flutter Gradle plugin is applied but no ${builtInTaskName("<Variant>")} task " +
                "was found (variants: ${capitalizedVariants.joinToString()}), so the build-id asset task is not " +
                "ordered after Flutter's asset copy. If Flutter renamed this task the build may fail with " +
                "\"uses this output of task ... without declaring an explicit or implicit dependency\". " +
                "Name it with bugsee { assetsDependsOn(\"<task name>\") }."
        }
        for (entry in extra.distinct()) {
            if (capitalizedVariants.none { v -> entry.replace(VARIANT_PLACEHOLDER, v) in existingTaskNames }) {
                messages += "Bugsee: bugsee { assetsDependsOn(\"$entry\") } matches no task in this project - ignoring it."
            }
        }
        return messages
    }

    fun isFlutterApplied(project: Project): Boolean =
        project.plugins.hasPlugin("dev.flutter.flutter-gradle-plugin") ||
            project.plugins.any { it.javaClass.simpleName == "FlutterPlugin" }

    @Suppress("UNCHECKED_CAST")
    private fun recordVariant(project: Project, capitalizedVariant: String, extra: Provider<List<String>>) {
        val ext = project.extensions.extraProperties
        if (!ext.has(VARIANTS_KEY)) {
            val variants = linkedSetOf<String>()
            ext.set(VARIANTS_KEY, variants)
            // Every module's afterEvaluate (where Flutter registers its tasks) precedes this.
            project.gradle.projectsEvaluated {
                diagnostics(
                    project.tasks.names,
                    isFlutterApplied(project),
                    variants,
                    extra.orNull.orEmpty(),
                ).forEach { project.logger.warn(it) }
            }
        }
        (ext.get(VARIANTS_KEY) as MutableSet<String>).add(capitalizedVariant)
    }
}
