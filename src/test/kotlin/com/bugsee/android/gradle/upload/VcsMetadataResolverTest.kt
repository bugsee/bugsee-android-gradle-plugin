package com.bugsee.android.gradle.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VcsMetadataResolverTest {

    private val anyDir = File(".")

    // ── GitHub Actions ─────────────────────────────────────────────

    @Test fun `github actions push event fills commit branch repo`() {
        val env = mapOf(
            "GITHUB_ACTIONS"   to "true",
            "GITHUB_SHA"       to "a".repeat(40),
            "GITHUB_REF"       to "refs/heads/feature-x",
            "GITHUB_REPOSITORY" to "bugsee/android-sdk",
            "GITHUB_EVENT_NAME" to "push"
        )
        val vcs = VcsMetadataResolver.resolveGithubActions(env)
        assertEquals("a".repeat(40), vcs.commitSha)
        assertEquals("feature-x", vcs.branch)
        assertEquals("bugsee/android-sdk", vcs.vcsRepo)
        assertEquals("github", vcs.vcsProvider)
        assertNull(vcs.prNumber)
        assertNull(vcs.baseBranch)
    }

    @Test fun `github actions pull_request event extracts pr number and both branches`() {
        val env = mapOf(
            "GITHUB_ACTIONS"    to "true",
            "GITHUB_SHA"        to "b".repeat(40),
            "GITHUB_REF"        to "refs/pull/42/merge",
            "GITHUB_HEAD_REF"   to "feature-x",
            "GITHUB_BASE_REF"   to "master",
            "GITHUB_REPOSITORY" to "bugsee/android-sdk",
            "GITHUB_EVENT_NAME" to "pull_request"
        )
        val vcs = VcsMetadataResolver.resolveGithubActions(env)
        assertEquals("feature-x", vcs.branch)
        assertEquals("master", vcs.baseBranch)
        assertEquals(42, vcs.prNumber)
        assertEquals("github", vcs.vcsProvider)
    }

    @Test fun `github actions extracts pr number from GITHUB_REF_NAME fallback`() {
        // On pull_request events GITHUB_REF_NAME is `N/merge`; if GITHUB_REF
        // is absent/odd we still recover the PR number from REF_NAME.
        val env = mapOf(
            "GITHUB_ACTIONS"    to "true",
            "GITHUB_SHA"        to "b".repeat(40),
            "GITHUB_REF_NAME"   to "42/merge",
            "GITHUB_HEAD_REF"   to "feature-x",
            "GITHUB_EVENT_NAME" to "pull_request"
        )
        val vcs = VcsMetadataResolver.resolveGithubActions(env)
        assertEquals(42, vcs.prNumber)
        assertEquals("feature-x", vcs.branch)
    }

    @Test fun `github actions pull_request_target sets branch but leaves pr number null`() {
        // On pull_request_target the workflow runs in the BASE-branch
        // context: GITHUB_REF/REF_NAME carry the base, not the PR number.
        // We can't recover the number from default env vars, so it's null;
        // the branch must still come from GITHUB_HEAD_REF.
        val env = mapOf(
            "GITHUB_ACTIONS"    to "true",
            "GITHUB_SHA"        to "b".repeat(40),
            "GITHUB_REF"        to "refs/heads/main",
            "GITHUB_REF_NAME"   to "main",
            "GITHUB_HEAD_REF"   to "feature-x",
            "GITHUB_BASE_REF"   to "main",
            "GITHUB_EVENT_NAME" to "pull_request_target"
        )
        val vcs = VcsMetadataResolver.resolveGithubActions(env)
        assertEquals("feature-x", vcs.branch)
        assertEquals("main", vcs.baseBranch)
        assertNull("pull_request_target can't expose PR number via default env", vcs.prNumber)
    }

    @Test fun `github actions tolerates missing envs`() {
        val vcs = VcsMetadataResolver.resolveGithubActions(mapOf("GITHUB_ACTIONS" to "true"))
        assertNull(vcs.commitSha)
        assertNull(vcs.branch)
        assertNull(vcs.vcsRepo)
        // Provider is set unconditionally when the detector runs.
        assertEquals("github", vcs.vcsProvider)
    }

    // ── GitLab CI ──────────────────────────────────────────────────

    @Test fun `gitlab ci push pipeline`() {
        val env = mapOf(
            "GITLAB_CI"        to "true",
            "CI_COMMIT_SHA"    to "c".repeat(40),
            "CI_COMMIT_BRANCH" to "main",
            "CI_PROJECT_PATH"  to "group/project"
        )
        val vcs = VcsMetadataResolver.resolveGitlabCi(env)
        assertEquals("c".repeat(40), vcs.commitSha)
        assertEquals("main", vcs.branch)
        assertEquals("group/project", vcs.vcsRepo)
        assertEquals("gitlab", vcs.vcsProvider)
        assertNull(vcs.prNumber)
    }

    @Test fun `gitlab ci merge request pipeline captures base_sha and pr number`() {
        val env = mapOf(
            "GITLAB_CI"                              to "true",
            "CI_COMMIT_SHA"                          to "c".repeat(40),
            "CI_COMMIT_REF_NAME"                     to "feature-y",
            "CI_MERGE_REQUEST_IID"                   to "17",
            "CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"    to "feature-y",
            "CI_MERGE_REQUEST_TARGET_BRANCH_NAME"    to "main",
            "CI_MERGE_REQUEST_DIFF_BASE_SHA"         to "d".repeat(40),
            "CI_PROJECT_PATH"                        to "group/project"
        )
        val vcs = VcsMetadataResolver.resolveGitlabCi(env)
        assertEquals("feature-y", vcs.branch)
        assertEquals("main", vcs.baseBranch)
        assertEquals(17, vcs.prNumber)
        assertEquals("d".repeat(40), vcs.baseSha)
    }

    @Test fun `gitlab ci tag pipeline does not mislabel the tag as a branch`() {
        // On a tag pipeline CI_COMMIT_BRANCH is unset and CI_COMMIT_REF_NAME
        // equals the TAG name. The bare-ref fallback must NOT fire here, or
        // the tag pollutes branch-keyed diffs. Branch must be null.
        val env = mapOf(
            "GITLAB_CI"          to "true",
            "CI_COMMIT_SHA"      to "c".repeat(40),
            "CI_COMMIT_TAG"      to "v1.2.3",
            "CI_COMMIT_REF_NAME" to "v1.2.3",
            "CI_PROJECT_PATH"    to "group/project"
        )
        val vcs = VcsMetadataResolver.resolveGitlabCi(env)
        assertNull("a tag pipeline must not report the tag as a branch", vcs.branch)
        assertEquals("c".repeat(40), vcs.commitSha)
        assertEquals("group/project", vcs.vcsRepo)
        assertNull(vcs.prNumber)
    }

    @Test fun `gitlab ci branch pipeline still resolves branch from CI_COMMIT_REF_NAME`() {
        // No tag → the bare-ref fallback is allowed when CI_COMMIT_BRANCH
        // is absent (e.g. older runners), so we don't lose the branch.
        val env = mapOf(
            "GITLAB_CI"          to "true",
            "CI_COMMIT_SHA"      to "c".repeat(40),
            "CI_COMMIT_REF_NAME" to "feature-y",
            "CI_PROJECT_PATH"    to "group/project"
        )
        val vcs = VcsMetadataResolver.resolveGitlabCi(env)
        assertEquals("feature-y", vcs.branch)
    }

    // ── CircleCI ───────────────────────────────────────────────────

    @Test fun `circleci reads sha branch repo`() {
        val env = mapOf(
            "CIRCLECI"                  to "true",
            "CIRCLE_SHA1"               to "e".repeat(40),
            "CIRCLE_BRANCH"             to "dev",
            "CIRCLE_PROJECT_USERNAME"   to "acme",
            "CIRCLE_PROJECT_REPONAME"   to "widgets"
        )
        val vcs = VcsMetadataResolver.resolveCircleCi(env)
        assertEquals("e".repeat(40), vcs.commitSha)
        assertEquals("dev", vcs.branch)
        assertEquals("acme/widgets", vcs.vcsRepo)
        // Provider deliberately null — CircleCI supports GitHub and Bitbucket.
        assertNull(vcs.vcsProvider)
    }

    @Test fun `circleci parses pr number from pull_request url`() {
        val env = mapOf(
            "CIRCLECI"            to "true",
            "CIRCLE_PULL_REQUEST" to "https://github.com/acme/widgets/pull/88"
        )
        val vcs = VcsMetadataResolver.resolveCircleCi(env)
        assertEquals(88, vcs.prNumber)
    }

    @Test fun `circleci prefers explicit pr number env`() {
        val env = mapOf(
            "CIRCLECI"            to "true",
            "CIRCLE_PR_NUMBER"    to "99",
            "CIRCLE_PULL_REQUEST" to "https://github.com/acme/widgets/pull/88"
        )
        val vcs = VcsMetadataResolver.resolveCircleCi(env)
        assertEquals(99, vcs.prNumber)
    }

    // ── Bitbucket Pipelines ────────────────────────────────────────

    @Test fun `bitbucket pipelines fills every field`() {
        val env = mapOf(
            "BITBUCKET_BUILD_NUMBER"            to "12",
            "BITBUCKET_COMMIT"                  to "f".repeat(40),
            "BITBUCKET_BRANCH"                  to "feature-z",
            "BITBUCKET_PR_ID"                   to "5",
            "BITBUCKET_PR_DESTINATION_BRANCH"   to "main",
            "BITBUCKET_REPO_FULL_NAME"          to "owner/repo"
        )
        val vcs = VcsMetadataResolver.resolveBitbucketPipelines(env)
        assertEquals("f".repeat(40), vcs.commitSha)
        assertEquals("feature-z", vcs.branch)
        assertEquals("main", vcs.baseBranch)
        assertEquals(5, vcs.prNumber)
        assertEquals("owner/repo", vcs.vcsRepo)
        assertEquals("bitbucket", vcs.vcsProvider)
    }

    // ── Dispatcher ─────────────────────────────────────────────────

    @Test fun `resolve with empty env returns empty metadata`() {
        val vcs = VcsMetadataResolver.resolve(File("/nonexistent-dir-bugsee-test"), emptyMap())
        // Nothing detected; git fallback won't find a .git in a nonexistent dir.
        assertFalse(vcs.isNotEmpty())
    }

    @Test fun `resolve picks github when GITHUB_ACTIONS is true`() {
        val env = mapOf(
            "GITHUB_ACTIONS"    to "true",
            "GITHUB_SHA"        to "a".repeat(40),
            "GITHUB_REF"        to "refs/heads/main",
            "GITHUB_REPOSITORY" to "x/y",
            "GITHUB_EVENT_NAME" to "push"
        )
        val vcs = VcsMetadataResolver.resolve(File("/nonexistent-dir-bugsee-test"), env)
        assertEquals("github", vcs.vcsProvider)
        assertEquals("main", vcs.branch)
    }

    @Test fun `metadata isNotEmpty reflects meaningful signal`() {
        assertFalse(VcsMetadata().isNotEmpty())
        assertTrue(VcsMetadata(commitSha = "abc").isNotEmpty())
        assertTrue(VcsMetadata(branch = "main").isNotEmpty())
        assertTrue(VcsMetadata(vcsRepo = "x/y").isNotEmpty())
        // Provider alone isn't meaningful — it's inferred from the CI envelope.
        assertFalse(VcsMetadata(vcsProvider = "github").isNotEmpty())
    }

    // ── Git CLI fallback ───────────────────────────────────────────

    @Test fun `fillFromGit leaves values untouched when present`() {
        val prefilled = VcsMetadata(
            commitSha = "keep-me",
            branch    = "keep-too"
        )
        val out = VcsMetadataResolver.fillFromGit(prefilled, File("/nonexistent-dir-bugsee-test"))
        assertEquals("keep-me", out.commitSha)
        assertEquals("keep-too", out.branch)
    }

    @Test fun `fillFromGit swallows errors when directory is missing`() {
        // Should NOT throw. All fields remain null.
        val out = VcsMetadataResolver.fillFromGit(VcsMetadata(), File("/nonexistent-dir-bugsee-test"))
        assertNotNull(out)
    }
}
