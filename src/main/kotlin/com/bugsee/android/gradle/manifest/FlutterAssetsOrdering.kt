package com.bugsee.android.gradle.manifest

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

/**
 * Orders the build-id asset injection task after Flutter's own asset copy.
 *
 * Flutter's Gradle plugin writes into AGP's merged-assets directory from
 * `copyFlutterAssets<Variant>` without AGP knowing. Our task transforms
 * `SingleArtifact.ASSETS` and so reads that same directory, and Gradle's task
 * validation rejects the build ("uses this output of task ... without
 * declaring an explicit or implicit dependency").
 *
 * We declare a real `dependsOn` (not just `mustRunAfter`): we consume the
 * directory Flutter populates, so Flutter's copy must also run whenever we do.
 *
 * Lookup is by name at task-graph time, so a Flutter task registered after
 * our variant callback is still found, nothing is realized eagerly, and apps
 * without Flutter see no change.
 */
internal object FlutterAssetsOrdering {

    fun candidateTaskNames(capitalizedVariant: String): List<String> =
        listOf("copyFlutterAssets$capitalizedVariant")

    fun wire(
        project: Project,
        injectTask: TaskProvider<out Task>,
        capitalizedVariant: String,
    ) {
        val names = candidateTaskNames(capitalizedVariant)
        injectTask.configure { task ->
            task.dependsOn(
                java.util.concurrent.Callable<List<TaskProvider<Task>>> {
                    val existing = project.tasks.names
                    names.filter { it in existing }.map { project.tasks.named(it) }
                }
            )
        }
    }
}
