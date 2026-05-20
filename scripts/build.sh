#!/bin/bash
#
# Bugsee Android Gradle Plugin — Build Script
#
# Usage:
#   ./scripts/build.sh           # fast: unit tests only (~30s warm, ~2 min cold)
#   ./scripts/build.sh --full    # full: unit + TestKit integration tests
#                                # (~4-8 min warm, ~12-15 min cold)
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
case "${1:-}" in
    --full)
        echo "[INFO] Full build: unit + TestKit integration tests"
        GRADLE_ARGS+=("-Pbugsee.runIntegrationTests=true")
        ;;
    "")
        echo "[INFO] Fast build: unit tests only (pass --full for integration tests)"
        ;;
    *)
        echo "[ERROR] Unknown argument: $1" >&2
        echo "Usage: $0 [--full]" >&2
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
# compose-compiler-plugin subproject.
./gradlew clean build :compose-compiler-plugin:build "${GRADLE_ARGS[@]}"
