#!/usr/bin/env bash
#
# Archive this run's Gradle test reports on the self-hosted runner's disk and
# say where in the job summary.
#
# Test reports are the only way to diagnose a red run after the fact, but
# uploading them spends the org's shared Actions storage: 0.5 GB across every
# repo on the Free plan, metered as a MONTHLY integral. Once a month is over the
# cap GitHub refuses uploads in EVERY repo until the cycle resets, and deleting
# artifacts does not undo it. The runner is a single self-hosted Mac, so the
# reports stay on its disk instead. Nothing here consumes GitHub storage.
#
# Inputs (environment):
#   LABEL         archive directory prefix, e.g. `pr12` or `staging`
#   SOURCE_URL    link recorded in PROVENANCE
#   COMMIT_SHA    commit recorded in PROVENANCE
#   ARCHIVE_ROOT  optional; defaults to ~/ci-test-results/bugsee-android-gradle-plugin
#   KEEP_DAYS     optional; prune archives older than this (default 14)
#   KEEP_RUNS     optional; keep at most this many archives (default 60)

set -euo pipefail

: "${LABEL:?}" "${SOURCE_URL:?}" "${COMMIT_SHA:?}"
KEEP_DAYS="${KEEP_DAYS:-14}"
KEEP_RUNS="${KEEP_RUNS:-60}"
root="${ARCHIVE_ROOT:-$HOME/ci-test-results/bugsee-android-gradle-plugin}"

list=$(mktemp)
find . -type d \( -path '*/build/reports/tests' \
               -o -path '*/build/test-results' \) -print0 > "$list"

if [ ! -s "$list" ]; then
  echo "No test reports were produced; nothing to archive."
  rm -f "$list"
  exit 0
fi

# Prune BEFORE writing, not after: on a tight disk this frees the space the tar
# is about to need, and it still runs when the tar later fails. Keep
# KEEP_RUNS-1 so this run brings the total back to KEEP_RUNS. Both prunes are
# best-effort.
mkdir -p "$root"
find "$root" -mindepth 1 -maxdepth 1 -type d -mtime "+${KEEP_DAYS}" \
     -exec rm -rf {} + || true
find "$root" -mindepth 1 -maxdepth 1 -type d -exec stat -f '%m %N' {} + \
  | sort -rn | tail -n "+${KEEP_RUNS}" | cut -d' ' -f2- \
  | while IFS= read -r old; do rm -rf "$old"; done || true

dest="$root/${LABEL}-run${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}"
mkdir -p "$dest"

if ! tar -czf "$dest/reports.tar.gz" --null -T "$list"; then
  echo "::warning::could not archive test reports (disk full?); dropping the partial archive"
  rm -rf "$dest"; rm -f "$list"
  exit 1
fi
rm -f "$list"

printf '%s\n%s\n' "$SOURCE_URL" "$COMMIT_SHA" > "$dest/PROVENANCE"

{
  echo "### Test reports"
  echo
  echo "Kept on the runner rather than uploaded: the org's Actions"
  echo "storage is shared across every repo and capped."
  echo
  echo '```'
  echo "runner: ${RUNNER_NAME}"
  echo "path:   ${dest}"
  echo '```'
  echo
  ( cd "$dest" && du -sh -- reports.tar.gz ) 2>/dev/null | sed 's/^/    /' || true
  echo
  runs=$(find "$root" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
  echo "Archive: ${runs} run(s), $(du -sh "$root" | cut -f1) total, pruned at ${KEEP_DAYS} days / ${KEEP_RUNS} runs."
} >> "$GITHUB_STEP_SUMMARY"
