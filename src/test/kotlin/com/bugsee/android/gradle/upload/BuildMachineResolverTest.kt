package com.bugsee.android.gradle.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BuildMachineResolverTest {

    private val hostFallback: () -> String? = { "dev-laptop.local" }

    // ── GitHub Actions ─────────────────────────────────────────────

    @Test fun `github actions produces provider-prefixed runner label`() {
        val env = mapOf(
            "GITHUB_ACTIONS" to "true",
            "RUNNER_NAME"    to "ubuntu-runner-42"
        )
        assertEquals("github-actions:ubuntu-runner-42",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    @Test fun `github actions without runner name still names the provider`() {
        val env = mapOf("GITHUB_ACTIONS" to "true")
        assertEquals("github-actions", BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── GitLab CI ──────────────────────────────────────────────────

    @Test fun `gitlab prefers runner description over runner id`() {
        val env = mapOf(
            "GITLAB_CI"              to "true",
            "CI_RUNNER_DESCRIPTION"  to "shared-runners-manager-1",
            "CI_RUNNER_ID"           to "987"
        )
        assertEquals("gitlab-ci:shared-runners-manager-1",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    @Test fun `gitlab falls back to runner id when description missing`() {
        val env = mapOf(
            "GITLAB_CI"    to "true",
            "CI_RUNNER_ID" to "987"
        )
        assertEquals("gitlab-ci:987",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── Jenkins ────────────────────────────────────────────────────

    @Test fun `jenkins detected via JENKINS_URL`() {
        val env = mapOf(
            "JENKINS_URL" to "https://jenkins.example.com/",
            "NODE_NAME"   to "agent-linux-x64"
        )
        assertEquals("jenkins:agent-linux-x64",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── CircleCI ───────────────────────────────────────────────────

    @Test fun `circleci carries node index when parallel`() {
        val env = mapOf(
            "CIRCLECI"           to "true",
            "CIRCLE_NODE_INDEX"  to "3"
        )
        assertEquals("circleci:3", BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── Bitrise ────────────────────────────────────────────────────

    @Test fun `bitrise carries app slug`() {
        val env = mapOf(
            "BITRISE_IO"       to "true",
            "BITRISE_APP_SLUG" to "abc123def"
        )
        assertEquals("bitrise:abc123def", BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── TeamCity ───────────────────────────────────────────────────

    @Test fun `teamcity accepts AGENT_NAME env`() {
        val env = mapOf(
            "TEAMCITY_VERSION" to "2024.03",
            "AGENT_NAME"       to "mac-build-agent-1"
        )
        assertEquals("teamcity:mac-build-agent-1",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    @Test fun `teamcity falls back to dot-name alias`() {
        val env = mapOf(
            "TEAMCITY_VERSION" to "2024.03",
            "agent.name"       to "dotted-agent"
        )
        assertEquals("teamcity:dotted-agent",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── Generic CI ─────────────────────────────────────────────────

    @Test fun `generic CI prefers env HOSTNAME over host provider`() {
        val env = mapOf("CI" to "true", "HOSTNAME" to "ci-runner-xyz")
        assertEquals("ci:ci-runner-xyz",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    @Test fun `generic CI falls back to host provider when HOSTNAME missing`() {
        val env = mapOf("CI" to "true")
        assertEquals("ci:dev-laptop.local",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── Fallback ───────────────────────────────────────────────────

    @Test fun `no CI env returns hostname`() {
        val env = emptyMap<String, String>()
        assertEquals("dev-laptop.local",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    @Test fun `no CI env and no hostname returns null`() {
        assertNull(BuildMachineResolver.resolve(emptyMap()) { null })
    }

    // ── Precedence ─────────────────────────────────────────────────

    @Test fun `github actions precedence beats generic CI when both set`() {
        // Real GitHub Actions runs also set CI=true. We must pick the
        // more specific provider so the runner name survives.
        val env = mapOf(
            "CI"             to "true",
            "GITHUB_ACTIONS" to "true",
            "RUNNER_NAME"    to "gh-runner"
        )
        assertEquals("github-actions:gh-runner",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    // ── Falsy / empty handling ─────────────────────────────────────

    @Test fun `GITHUB_ACTIONS=false is not treated as a provider signal`() {
        val env = mapOf("GITHUB_ACTIONS" to "false")
        // Falls through to hostname fallback.
        assertEquals("dev-laptop.local",
            BuildMachineResolver.resolve(env, hostFallback))
    }

    @Test fun `blank CI detail values still name the provider`() {
        val env = mapOf("GITHUB_ACTIONS" to "true", "RUNNER_NAME" to "   ")
        val result = BuildMachineResolver.resolve(env, hostFallback)
        assertNotNull(result)
        assertEquals("github-actions", result)
    }
}
