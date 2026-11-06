#!/bin/bash
#
# Bugsee Android Gradle Plugin — Build Script
#
# Usage:
#   ./scripts/build.sh           # fast: unit tests only (~30s warm, ~2 min cold)
#   ./scripts/build.sh --full    # full: unit + TestKit integration tests
#                                # (~4-8 min warm, ~12-15 min cold)
#
# The pull-request workflow runs `--full` as parallel jobs, one piece each (a job that
# runs all of it takes ~35 min on a hosted runner):
#   ./scripts/build.sh --unit             # root project build (unit tests, plugin validation, docs jar)
#                                         # + buildSrc tests
#   ./scripts/build.sh --compose          # compose-compiler-plugin build (variant matrix)
#   ./scripts/build.sh --integration <n> [<total>]
#                                         # shard <n> of the TestKit integration tests (see `integrationShards`
#                                         # in build.gradle.kts); CI passes the matrix size as <total> and the
#                                         # build fails if it is not the number of shards defined there
# Together they cover exactly what `--full` does.
#
# The split mirrors the SDK's `./scripts/test.sh` two-tier convention.
# Integration tests run real Android Gradle Plugin builds via TestKit
# (`AppStartupTracingTierMatrixTest`, `AppStartupTracingConfigCacheTest`,
# `AppStartupTracingFailureModeTest`) — useful for guarding cross-repo
# regressions but too slow for the inner dev loop. Pass `--full` for CI
# pipelines or release rehearsals; omit it for normal development.

export PATH=$PATH:/usr/local/bin
export ANDROID_HOME=$ANDROID_SDK_ROOT
export PATH=$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$PATH

set -e

GRADLE_ARGS=()
# The Gradle tasks this invocation runs; `--full` and the default keep the original behaviour.
GRADLE_TASKS=(clean build :compose-compiler-plugin:build)
RUN_BUILDSRC_TESTS=true
case "${1:-}" in
    --full)
        echo "[INFO] Full build: unit + TestKit integration tests"
        GRADLE_ARGS+=("-Pbugsee.runIntegrationTests=true")
        ;;
    "")
        echo "[INFO] Fast build: unit tests only (pass --full for integration tests)"
        ;;
    --unit)
        echo "[INFO] Unit piece: root project build + buildSrc tests"
        # `:` limits `clean` / `build` to the root project (a bare `build` also builds the subproject).
        GRADLE_TASKS=(:clean :build)
        ;;
    --compose)
        echo "[INFO] Compose piece: compose-compiler-plugin build (variant matrix)"
        GRADLE_TASKS=(:compose-compiler-plugin:clean :compose-compiler-plugin:build)
        RUN_BUILDSRC_TESTS=false
        ;;
    --integration)
        shard="${2:-}"
        if [[ ! "$shard" =~ ^[0-9]+$ ]]; then
            echo "[ERROR] --integration needs a shard number: $0 --integration <n> [<total>]" >&2
            exit 1
        fi
        echo "[INFO] Integration piece: TestKit shard $shard"
        GRADLE_TASKS=(:integrationTest)
        GRADLE_ARGS+=("-Pbugsee.it.shard=$shard")
        if [ -n "${3:-}" ]; then
            GRADLE_ARGS+=("-Pbugsee.it.shardCount=$3")
        fi
        RUN_BUILDSRC_TESTS=false
        ;;
    *)
        echo "[ERROR] Unknown argument: $1" >&2
        echo "Usage: $0 [--full | --unit | --compose | --integration <n> [<total>]]" >&2
        exit 1
        ;;
esac

# Stop any running Gradle daemon before starting. Kotlin 2.1's incremental
# compiler daemon occasionally caches stale internal-visibility state across
# main/test boundaries, producing "Unresolved reference" errors on `internal`
# symbols that resolve fine on a cold daemon. Cost: ~2-3s; reward: deterministic
# builds regardless of prior daemon state.
./gradlew --stop 2>/dev/null || true

# Single Gradle invocation builds both the main plugin and the
# compose-compiler-plugin subproject (in `--full` and the default).
./gradlew "${GRADLE_TASKS[@]}" "${GRADLE_ARGS[@]}"

# buildSrc is a separate build: root `build` never runs its tests. They cover the Maven Central
# signature-checksum filter that the sonatype publish tasks call before uploading.
if [ "$RUN_BUILDSRC_TESTS" = true ]; then
    ./gradlew -p buildSrc test
fi
