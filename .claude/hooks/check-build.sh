#!/usr/bin/env bash
# Claude Code Stop hook: block finishing while the build is red.
# Exit 2 sends stderr back to Claude so it keeps working on the failure.
set -uo pipefail

# A Stop hook that already blocked once is re-run with stop_hook_active=true; let Claude stop then to avoid loops.
if grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
    exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}" || exit 1

if docker info >/dev/null 2>&1; then
    args=(check --quiet)
else
    args=(check -x integrationTest --quiet)
fi

output=$(./gradlew "${args[@]}" 2>&1)
status=$?
if [ "$status" -ne 0 ]; then
    {
        echo "./gradlew ${args[*]} failed:"
        echo "$output" | tail -n 60
    } >&2
    exit 2
fi
