#!/usr/bin/env bash
# Aggregate surefire reports and print a Markdown summary (for $GITHUB_STEP_SUMMARY).
set -uo pipefail

dir="target/surefire-reports"
run=0; fail=0; err=0; skip=0; files=0

if [ -d "$dir" ]; then
  for f in "$dir"/*.txt; do
    [ -e "$f" ] || continue
    line=$(grep -m1 -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' "$f" || true)
    [ -z "$line" ] && continue
    files=$((files + 1))
    run=$((run + $(echo "$line" | sed -E 's/.*Tests run: ([0-9]+).*/\1/')))
    fail=$((fail + $(echo "$line" | sed -E 's/.*Failures: ([0-9]+).*/\1/')))
    err=$((err + $(echo "$line" | sed -E 's/.*Errors: ([0-9]+).*/\1/')))
    skip=$((skip + $(echo "$line" | sed -E 's/.*Skipped: ([0-9]+).*/\1/')))
  done
fi

echo "## Test summary"
echo ""
echo "| run | failures | errors | skipped | report files |"
echo "|---|---|---|---|---|"
echo "| $run | $fail | $err | $skip | $files |"
