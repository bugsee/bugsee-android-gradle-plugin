#!/bin/bash

# Bugsee Android Gradle Plugin — Test Runner
#
# Modes:
#   ./scripts/test.sh                              # All tests (unit + integration)
#   ./scripts/test.sh unit                         # Unit tests only (~10s)
#   ./scripts/test.sh integration                  # TestKit integration tests (~30s warm, ~3 min cold)
#   ./scripts/test.sh specific <class-name>        # Run a single unit test class
#                                                  # e.g. ./scripts/test.sh specific StartupTierTest
#   ./scripts/test.sh specific integration <class> # Run a single integration test class
#
# Notes:
#  - Unit tests run in-process against ASM in-memory bytecode fixtures
#    (see src/test/kotlin/.../fixtures/). Fast, deterministic, no
#    daemon involvement.
#  - Integration tests use Gradle TestKit and require a working
#    Android SDK + JDK 17 toolchain. The plugin's build.gradle.kts
#    resolves a JDK 17 launcher automatically via
#    `javaToolchains.launcherFor { languageVersion = 17 }` — if no
#    JDK 17 is on this machine, Gradle's foojay resolver will
#    auto-download one (~30s first run).
#  - Integration tests source the plugin under test via
#    `pluginManagement.includeBuild(...)` from the fixture, NOT via
#    TestKit's `withPluginClasspath()`. That keeps the plugin loaded
#    into the same classloader as AGP at fixture build time.
#  - This script does NOT bring up an Android emulator. The plugin
#    has no on-device test surface — connected tests for the
#    bugsee-android-gradle-plugin live in the consuming SDK repo
#    (see android/sdk/app/src/androidTest/).

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status()  { echo -e "${BLUE}[INFO]${NC} $1" >&2; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1" >&2; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1" >&2; }
print_error()   { echo -e "${RED}[ERROR]${NC} $1" >&2; }

# Resolve repo root so the script runs from any cwd.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

# Forward an Android SDK path. AGP fixture builds need ANDROID_HOME or
# ANDROID_SDK_ROOT. The integrationTest task's harness reads these from
# the test runner's environment.
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
    DEFAULT_SDK_DIRS=(
        "$HOME/Library/Android/sdk"
        "$HOME/Android/Sdk"
        "$HOME/.android/sdk"
    )
    for sdk in "${DEFAULT_SDK_DIRS[@]}"; do
        if [ -d "$sdk" ]; then
            export ANDROID_HOME="$sdk"
            export ANDROID_SDK_ROOT="$sdk"
            print_status "ANDROID_HOME=$ANDROID_HOME"
            break
        fi
    done
    if [ -z "$ANDROID_HOME" ]; then
        print_warning "ANDROID_HOME not set and no SDK found in common locations. \
Integration tests will fail; unit tests will still run."
    fi
fi

MODE="${1:-all}"

run_unit() {
    print_status "Running unit tests (./gradlew test)..."
    ./gradlew test --console=plain
    print_success "Unit tests passed."
}

run_integration() {
    print_status "Running integration tests (./gradlew integrationTest)..."
    ./gradlew integrationTest --console=plain
    print_success "Integration tests passed."
}

run_specific_unit() {
    local class_name="$1"
    if [ -z "$class_name" ]; then
        print_error "specific mode requires a test class name"
        exit 1
    fi
    print_status "Running unit test class: $class_name"
    ./gradlew test --tests "*${class_name}*" --console=plain
    print_success "Test $class_name passed."
}

run_specific_integration() {
    local class_name="$1"
    if [ -z "$class_name" ]; then
        print_error "specific integration mode requires a test class name"
        exit 1
    fi
    print_status "Running integration test class: $class_name"
    ./gradlew integrationTest --tests "*${class_name}*" --console=plain
    print_success "Integration test $class_name passed."
}

case "$MODE" in
    unit)
        run_unit
        ;;
    integration|integ)
        run_integration
        ;;
    specific)
        if [ "$2" = "integration" ] || [ "$2" = "integ" ]; then
            run_specific_integration "$3"
        else
            run_specific_unit "$2"
        fi
        ;;
    all|"")
        run_unit
        run_integration
        ;;
    -h|--help|help)
        sed -n '3,30p' "$0"
        ;;
    *)
        print_error "Unknown mode: $MODE"
        print_error "Run './scripts/test.sh --help' for usage."
        exit 1
        ;;
esac
