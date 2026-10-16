#!/usr/bin/env bash
# ci/runner/entrypoint.sh — the main process inside every launcher runner container (ci/runner/Dockerfile).
#
# ci/runner/hmdm-runner.sh on the host has already registered a just-in-time runner and hands
# its one-time configuration over in RUNNER_JITCONFIG. This script only starts the runner with it.
# A JIT runner takes exactly one job and then exits, which ends the container (started with --rm).
# The GitHub credential that created the registration never reaches this container.
set -euo pipefail

: "${RUNNER_JITCONFIG:?RUNNER_JITCONFIG is not set - start this image through ci/runner/hmdm-runner.sh}"
jit="$RUNNER_JITCONFIG"
unset RUNNER_JITCONFIG   # the runner passes its environment on to every job step

cd /home/runner
exec ./run.sh --jitconfig "$jit"
