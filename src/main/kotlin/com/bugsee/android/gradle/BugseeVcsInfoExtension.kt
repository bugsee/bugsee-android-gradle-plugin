package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Overrides for VCS metadata attached to a Bugsee build record.
 *
 * The plugin auto-detects commit / branch / provider / repo from CI
 * env vars and (when those are absent) from the local git checkout.
 * Set any property here to force that field; unset properties keep
 * auto-detection. Nothing in this block is required.
 *
 * Names follow the Sentry `vcsInfo` DSL so existing CI snippets
 * translate directly. They map onto the Bugsee wire fields
 * `commit_sha`, `base_sha`, `branch`, `base_branch`, `provider`,
 * `repo`, `pr_number`.
 *
 * ```kotlin
 * bugsee {
 *     vcsInfo {
 *         headSha.set("abc123def")
 *         baseSha.set("def456aaa")
 *         vcsProvider.set("github")
 *         headRepoName.set("organization/repository")
 *         headRef.set("feature-branch")
 *         baseRef.set("main")
 *         prNumber.set(42)
 *     }
 * }
 * ```
 *
 * Also settable via `plugin.vcsInfo.<name>` in
 * `<rootProject>/bugsee.properties`. DSL `.set(…)` wins. Do not put
 * author names, emails, or remote credentials in these fields — the
 * plugin never collects those from git either.
 */
abstract class BugseeVcsInfoExtension @Inject constructor(objects: ObjectFactory) {

    /** Current commit SHA (`vcs.commit_sha`). */
    val headSha: Property<String> = objects.property(String::class.java)

    /** Base commit SHA for comparison (`vcs.base_sha`). */
    val baseSha: Property<String> = objects.property(String::class.java)

    /**
     * VCS provider. Accepted by the appserver: `github`, `gitlab`,
     * `bitbucket`. Other values are dropped server-side.
     */
    val vcsProvider: Property<String> = objects.property(String::class.java)

    /** Repository in `org/repo` form (`vcs.repo`). */
    val headRepoName: Property<String> = objects.property(String::class.java)

    /** Branch or tag name (`vcs.branch`). */
    val headRef: Property<String> = objects.property(String::class.java)

    /** Base branch name (`vcs.base_branch`). */
    val baseRef: Property<String> = objects.property(String::class.java)

    /** Pull / merge request number (`vcs.pr_number`). */
    val prNumber: Property<Int> = objects.property(Int::class.javaObjectType)
}
