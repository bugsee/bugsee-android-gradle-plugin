package com.bugsee.android.gradle.upload

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Version-control metadata captured at upload time. Every field is
 * nullable — local-dev builds may have nothing beyond [commitSha], and
 * sometimes not even that (shallow clones, worktrees on detached HEADs).
 * The backend persists whatever is supplied and tolerates omissions.
 */
data class VcsMetadata(
    val commitSha:   String? = null,
    val baseSha:     String? = null,
    val branch:      String? = null,
    val baseBranch:  String? = null,
    val prNumber:    Int?    = null,
    val vcsProvider: String? = null,
    val vcsRepo:     String? = null
) {
    /** True if we learned anything worth sending. */
    fun isNotEmpty(): Boolean =
        commitSha != null || branch != null || vcsRepo != null
}

/**
 * Best-effort resolver for VCS metadata.
 *
 * Detection order:
 *  1. Known CI providers (GitHub Actions, GitLab CI, CircleCI, Bitbucket
 *     Pipelines) — env vars are canonical, no shell-out needed.
 *  2. Git CLI fallback for fields CI didn't expose (e.g. `base_sha` on
 *     GitHub Actions) or for local-dev builds without any CI envelope.
 *
 * The resolver never throws — any failure is silently dropped and the
 * corresponding field stays null. VCS metadata is a value-add signal,
 * not a correctness requirement, so we never want it to break a build.
 */
object VcsMetadataResolver {

    private const val GIT_TIMEOUT_SEC = 5L

    /**
     * Conservative whitelist for branch names used as `git merge-base`
     * arguments. A hostile env var like `BITBUCKET_PR_DESTINATION_BRANCH=--upload-pack=evil`
     * is neutralised by the argv (no shell), but git itself would still
     * parse it as a flag. Refs are `[A-Za-z0-9/_.-]+` in practice and
     * the rejection is safe — a weird branch name just means no
     * `base_sha`, which the backend tolerates.
     */
    private val SAFE_BRANCH_NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9/._-]*$")

    fun resolve(
        projectDir: File,
        env: Map<String, String> = System.getenv()
    ): VcsMetadata {
        // Pick the most specific CI provider first, then fill gaps from git.
        val ci = when {
            env["GITHUB_ACTIONS"] == "true"      -> resolveGithubActions(env)
            env["GITLAB_CI"]      == "true"      -> resolveGitlabCi(env)
            env["CIRCLECI"]       == "true"      -> resolveCircleCi(env)
            env.containsKey("BITBUCKET_BUILD_NUMBER") -> resolveBitbucketPipelines(env)
            else -> VcsMetadata()
        }

        return fillFromGit(ci, projectDir)
    }

    // ── CI providers ───────────────────────────────────────────────

    internal fun resolveGithubActions(env: Map<String, String>): VcsMetadata {
        val isPr = env["GITHUB_EVENT_NAME"] == "pull_request" ||
                   env["GITHUB_EVENT_NAME"] == "pull_request_target"
        val headRef = env["GITHUB_HEAD_REF"]
        val ref = env["GITHUB_REF"]
        val branch = if (isPr && !headRef.isNullOrBlank()) {
            headRef
        } else {
            ref?.removePrefix("refs/heads/")?.takeIf { it != ref }
        }
        // GITHUB_REF is `refs/pull/N/merge` on PR events — extract N.
        val prNumber = ref
            ?.let { Regex("""^refs/pull/(\d+)/(?:merge|head)$""").find(it) }
            ?.groupValues?.get(1)
            ?.toIntOrNull()
        return VcsMetadata(
            commitSha   = env["GITHUB_SHA"].nullIfBlank(),
            branch      = branch.nullIfBlank(),
            baseBranch  = env["GITHUB_BASE_REF"].nullIfBlank(),
            prNumber    = prNumber,
            vcsProvider = "github",
            vcsRepo     = env["GITHUB_REPOSITORY"].nullIfBlank()
        )
    }

    internal fun resolveGitlabCi(env: Map<String, String>): VcsMetadata {
        val isMr = !env["CI_MERGE_REQUEST_IID"].isNullOrBlank()
        val branch = if (isMr) {
            env["CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"].nullIfBlank()
                ?: env["CI_COMMIT_REF_NAME"].nullIfBlank()
        } else {
            env["CI_COMMIT_BRANCH"].nullIfBlank()
                ?: env["CI_COMMIT_REF_NAME"].nullIfBlank()
        }
        return VcsMetadata(
            commitSha   = env["CI_COMMIT_SHA"].nullIfBlank(),
            baseSha     = env["CI_MERGE_REQUEST_DIFF_BASE_SHA"].nullIfBlank(),
            branch      = branch,
            baseBranch  = env["CI_MERGE_REQUEST_TARGET_BRANCH_NAME"].nullIfBlank(),
            prNumber    = env["CI_MERGE_REQUEST_IID"]?.toIntOrNull(),
            vcsProvider = "gitlab",
            vcsRepo     = env["CI_PROJECT_PATH"].nullIfBlank()
        )
    }

    internal fun resolveCircleCi(env: Map<String, String>): VcsMetadata {
        // CircleCI exposes pr number via CIRCLE_PR_NUMBER, or as a URL
        // suffix in CIRCLE_PULL_REQUEST (https://.../pull/123).
        val prNumber = env["CIRCLE_PR_NUMBER"]?.toIntOrNull()
            ?: env["CIRCLE_PULL_REQUEST"]
                ?.let { Regex("""/pull/(\d+)/?$""").find(it) }
                ?.groupValues?.get(1)?.toIntOrNull()
        val repo = listOfNotNull(
            env["CIRCLE_PROJECT_USERNAME"].nullIfBlank(),
            env["CIRCLE_PROJECT_REPONAME"].nullIfBlank()
        ).takeIf { it.size == 2 }?.joinToString("/")
        // CircleCI does not reliably disclose whether the repo is GitHub or
        // Bitbucket without parsing CIRCLE_REPOSITORY_URL. Leave provider
        // null rather than guessing incorrectly.
        return VcsMetadata(
            commitSha   = env["CIRCLE_SHA1"].nullIfBlank(),
            branch      = env["CIRCLE_BRANCH"].nullIfBlank(),
            prNumber    = prNumber,
            vcsProvider = null,
            vcsRepo     = repo
        )
    }

    internal fun resolveBitbucketPipelines(env: Map<String, String>): VcsMetadata {
        return VcsMetadata(
            commitSha   = env["BITBUCKET_COMMIT"].nullIfBlank(),
            branch      = env["BITBUCKET_BRANCH"].nullIfBlank(),
            baseBranch  = env["BITBUCKET_PR_DESTINATION_BRANCH"].nullIfBlank(),
            prNumber    = env["BITBUCKET_PR_ID"]?.toIntOrNull(),
            vcsProvider = "bitbucket",
            vcsRepo     = env["BITBUCKET_REPO_FULL_NAME"].nullIfBlank()
        )
    }

    // ── Git CLI fallback ───────────────────────────────────────────

    internal fun fillFromGit(partial: VcsMetadata, projectDir: File): VcsMetadata {
        var result = partial

        if (result.commitSha == null) {
            runGit(projectDir, "rev-parse", "HEAD")?.let {
                result = result.copy(commitSha = it)
            }
        }

        if (result.branch == null) {
            // Returns "HEAD" for detached worktrees — treat as unknown.
            val out = runGit(projectDir, "rev-parse", "--abbrev-ref", "HEAD")
            if (out != null && out != "HEAD") {
                result = result.copy(branch = out)
            }
        }

        if (result.baseSha == null && result.baseBranch != null) {
            val base = result.baseBranch!!
            if (SAFE_BRANCH_NAME_RE.matches(base)) {
                // Try origin/<branch> first (what CI checkouts typically have),
                // then bare <branch>. Branch names failing the regex are
                // skipped — a `base_sha` isn't worth a potential argv-
                // injection class of surprise against `git`.
                val mergeBase = runGit(projectDir, "merge-base", "HEAD", "origin/$base")
                    ?: runGit(projectDir, "merge-base", "HEAD", base)
                if (mergeBase != null) {
                    result = result.copy(baseSha = mergeBase)
                }
            }
        }

        return result
    }

    private fun runGit(cwd: File, vararg args: String): String? {
        return try {
            // redirectErrorStream merges stderr into stdout so a single
            // drain thread serves both — avoids the classic deadlock
            // where the child fills one pipe while waitFor() blocks on
            // the other. Every field we parse is a single line; any
            // stderr chatter ("not a git repository" etc.) gets mixed
            // in but we simply ignore it on non-zero exit.
            val process = ProcessBuilder(listOf("git", *args))
                .directory(cwd)
                .redirectErrorStream(true)
                .start()

            val captured = StringBuilder()
            val drain = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(1024)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            captured.append(buf, 0, n)
                        }
                    }
                } catch (_: Exception) {
                    /* stream closed — expected during destroyForcibly */
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                // Timeout path: destroy and bound the join so we don't
                // wait for a stream that might never close.
                process.destroyForcibly()
                drain.join(500)
                return null
            }
            // Happy path: the process exited cleanly, so stdout has
            // hit EOF (or will, very shortly). Unbounded join is safe
            // and guarantees we see the complete output.
            drain.join()
            if (process.exitValue() != 0) return null
            captured.toString().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.nullIfBlank(): String? =
        this?.takeIf { it.isNotBlank() }
}
