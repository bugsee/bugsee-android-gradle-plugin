#!/bin/bash

# Bugsee Android Gradle Plugin — REAL-BINARY integration test runner.
#
# The GitHub Actions workflows (.github/workflows) do not run this lane: it needs
# a Rust toolchain and a bugsee-cli checkout, which they do not provision. A
# developer, or a CI job that has both, runs this script to exercise
# `CliUploaderRealBinaryTest` against a real, locally-built `bugsee-cli` binary.
# That test `Assume`-skips when no binary is provided, so the normal
# `./scripts/test.sh unit` run stays green offline; this script is what makes the
# real-binary path actually execute.
#
# What it does:
#   1. Resolves a `bugsee-cli` binary:
#        - honors an existing $BUGSEE_CLI_BIN if it points at an executable file;
#        - else builds the sibling CLI from $BUGSEE_CLI_SRC (default:
#          ../../bugsee-cli relative to this repo) via `cargo build` and uses
#          its `target/debug/bugsee-cli`.
#   2. Exports BUGSEE_CLI_BIN=<abs path> and runs `bash scripts/test.sh unit`,
#      so the real-binary test runs (not skipped).
#
# Usage:
#   ./scripts/integration-test.sh                 # build CLI from ../../bugsee-cli, run
#   BUGSEE_CLI_BIN=/path/to/bugsee-cli ./scripts/integration-test.sh   # use prebuilt binary
#   BUGSEE_CLI_SRC=/path/to/bugsee-cli ./scripts/integration-test.sh   # build from a custom source dir

set -euo pipefail

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

# Make an absolute path out of a possibly-relative one without requiring the
# file to exist (realpath/readlink -f aren't portable to stock macOS).
abspath() {
    local p="$1"
    if [ -d "$p" ]; then
        (cd "$p" && pwd)
    else
        local dir base
        dir=$(dirname "$p")
        base=$(basename "$p")
        if [ -d "$dir" ]; then
            echo "$(cd "$dir" && pwd)/$base"
        else
            echo "$p"
        fi
    fi
}

resolve_binary() {
    # 1. Pre-supplied binary wins — no build needed.
    if [ -n "${BUGSEE_CLI_BIN:-}" ]; then
        local bin
        bin=$(abspath "$BUGSEE_CLI_BIN")
        if [ -x "$bin" ] && [ -f "$bin" ]; then
            print_status "Using pre-supplied BUGSEE_CLI_BIN=$bin"
            echo "$bin"
            return 0
        fi
        print_warning "BUGSEE_CLI_BIN=$BUGSEE_CLI_BIN is not an executable file; falling back to a source build."
    fi

    # 2. Build from source. Default source dir is the sibling checkout
    #    (../../bugsee-cli relative to android/gradle-plugin).
    local src="${BUGSEE_CLI_SRC:-$REPO_ROOT/../../bugsee-cli}"
    if [ ! -d "$src" ]; then
        print_error "bugsee-cli source not found at: $src"
        print_error "Set BUGSEE_CLI_SRC to the CLI checkout, or BUGSEE_CLI_BIN to a prebuilt binary."
        return 2
    fi
    src=$(abspath "$src")

    if ! command -v cargo >/dev/null 2>&1; then
        print_error "cargo is not on PATH; cannot build bugsee-cli at $src."
        print_error "Install Rust (https://rustup.rs) or set BUGSEE_CLI_BIN to a prebuilt binary."
        return 2
    fi

    print_status "Building bugsee-cli (cargo build) in $src ..."
    ( cd "$src" && cargo build )

    local built="$src/target/debug/bugsee-cli"
    if [ ! -x "$built" ]; then
        print_error "cargo build completed but no executable at $built."
        return 2
    fi
    print_success "Built bugsee-cli at $built"
    echo "$built"
}

BIN=$(resolve_binary)
export BUGSEE_CLI_BIN="$BIN"
print_status "BUGSEE_CLI_BIN=$BUGSEE_CLI_BIN"
print_status "Running unit tests (real-binary integration test will execute, not skip)..."

bash "$REPO_ROOT/scripts/test.sh" unit

print_success "Integration test run complete (real-binary path exercised)."
