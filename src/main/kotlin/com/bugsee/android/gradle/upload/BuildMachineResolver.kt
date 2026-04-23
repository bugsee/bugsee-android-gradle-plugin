package com.bugsee.android.gradle.upload

import java.net.InetAddress

/**
 * Resolves a human-readable "machine" label for the host that produced
 * a given build. The label is sent with the size-analysis upload so
 * the web view can show "which runner built this" — useful when a
 * regression is tied to a specific CI node or a developer's laptop.
 *
 * Cascade — first provider with a positive signal wins:
 *   GITHUB_ACTIONS  → "github-actions:<runner-name>"
 *   GITLAB_CI       → "gitlab-ci:<runner-description>"
 *   JENKINS_URL     → "jenkins:<node-name>"
 *   CIRCLECI        → "circleci:<node-index>"
 *   BITRISE_IO      → "bitrise:<app-slug>"
 *   TEAMCITY_VERSION→ "teamcity:<agent-name>"
 *   CI=true         → "ci:<hostname>"                (generic CI)
 *   otherwise       → InetAddress.getLocalHost().hostName
 *
 * The prefix names the CI provider (so infra alerts can filter by
 * provider at a glance), the suffix names the runner (so an alert
 * can be traced back to a specific node). Either segment may be
 * empty when the expected detail var isn't set — the resolver still
 * returns the prefix so the provider information isn't lost.
 *
 * Pure: takes the env map as a parameter to mirror
 * [VcsMetadataResolver]'s testability pattern. The convenience
 * [resolve] overload reads `System.getenv()` for the real call site.
 */
internal object BuildMachineResolver {

    fun resolve(): String? = resolve(System.getenv(), hostnameProvider = ::localHostname)

    fun resolve(
        env: Map<String, String?>,
        hostnameProvider: () -> String? = ::localHostname
    ): String? {
        resolveGithubActions(env)?.let { return it }
        resolveGitlabCi(env)?.let { return it }
        resolveJenkins(env)?.let { return it }
        resolveCircleCi(env)?.let { return it }
        resolveBitrise(env)?.let { return it }
        resolveTeamCity(env)?.let { return it }
        resolveGenericCi(env, hostnameProvider)?.let { return it }
        return hostnameProvider()
    }

    // ── CI detectors — package-private for direct unit testing ─────

    internal fun resolveGithubActions(env: Map<String, String?>): String? {
        if (env["GITHUB_ACTIONS"]?.toBoolean() != true) return null
        val runner = env["RUNNER_NAME"].orBlank()
        return withDetail("github-actions", runner)
    }

    internal fun resolveGitlabCi(env: Map<String, String?>): String? {
        if (env["GITLAB_CI"]?.toBoolean() != true) return null
        // CI_RUNNER_DESCRIPTION is the configured runner label, usually
        // descriptive (e.g. "shared-runners-manager-1"). Fall back to
        // CI_RUNNER_ID if the description is absent.
        val detail = env["CI_RUNNER_DESCRIPTION"].orBlank().ifBlank {
            env["CI_RUNNER_ID"].orBlank()
        }
        return withDetail("gitlab-ci", detail)
    }

    internal fun resolveJenkins(env: Map<String, String?>): String? {
        if (env["JENKINS_URL"].isNullOrBlank()) return null
        val node = env["NODE_NAME"].orBlank()
        return withDetail("jenkins", node)
    }

    internal fun resolveCircleCi(env: Map<String, String?>): String? {
        if (env["CIRCLECI"]?.toBoolean() != true) return null
        val nodeIndex = env["CIRCLE_NODE_INDEX"].orBlank()
        return withDetail("circleci", nodeIndex)
    }

    internal fun resolveBitrise(env: Map<String, String?>): String? {
        if (env["BITRISE_IO"]?.toBoolean() != true) return null
        val slug = env["BITRISE_APP_SLUG"].orBlank()
        return withDetail("bitrise", slug)
    }

    internal fun resolveTeamCity(env: Map<String, String?>): String? {
        if (env["TEAMCITY_VERSION"].isNullOrBlank()) return null
        // TeamCity exposes the agent name as `agent.name` (dot — not an
        // environment-variable-legal shell name, so it arrives as
        // `AGENT_NAME` on most runners). We accept either.
        val agent = env["AGENT_NAME"].orBlank().ifBlank {
            env["agent.name"].orBlank()
        }
        return withDetail("teamcity", agent)
    }

    internal fun resolveGenericCi(
        env: Map<String, String?>,
        hostnameProvider: () -> String?
    ): String? {
        if (env["CI"]?.toBoolean() != true) return null
        val host = env["HOSTNAME"].orBlank().ifBlank { hostnameProvider().orEmpty() }
        return withDetail("ci", host)
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun withDetail(prefix: String, detail: String): String =
        if (detail.isBlank()) prefix else "$prefix:$detail"

    private fun String?.orBlank(): String = this?.trim().orEmpty()

    private fun localHostname(): String? = try {
        InetAddress.getLocalHost().hostName?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        // Sandboxed / networkless hosts (some Docker setups, certain
        // CI runners) throw UnknownHostException from
        // InetAddress.getLocalHost; in that case we'd rather send no
        // machine field than fail the upload.
        null
    }
}
