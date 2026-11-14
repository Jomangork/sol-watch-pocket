#!/usr/bin/env bash
# Run Gradle for the Android app with a hard memory cap, low CPU and disk priority,
# so a build can never starve the SOL-Watch bridges on this machine.
# If the build needs more than the cap, the kernel stops the build, not the bridges.
#
#   tools/build.sh                  # assembleDebug
#   tools/build.sh test lint        # any Gradle tasks
set -euo pipefail
source "$(dirname "$0")/../env.sh"

MEMORY_MAX="${POCKET_MEMORY_MAX:-1800M}"
SWAP_MAX="${POCKET_SWAP_MAX:-1G}"
tasks=("$@")
[ ${#tasks[@]} -eq 0 ] && tasks=(assembleDebug)

cd "$POCKET_ROOT/android"
exec systemd-run --user --scope --quiet --collect \
    --unit="pocket-build-$$" \
    -p MemoryMax="$MEMORY_MAX" -p MemorySwapMax="$SWAP_MAX" -p CPUWeight=20 -p IOWeight=20 \
    nice -n 10 gradle --no-daemon --console=plain "${tasks[@]}"
