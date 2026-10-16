#!/usr/bin/env bash
# ci/runner/maintenance.sh — weekly upkeep of the launcher runner pool (hmdm-runner-maintenance.timer).
#
# Rebuilds the runner image on the current actions-runner release. GitHub stops sending jobs to
# runners that fall too far behind; a stale image would make every fresh container download and
# install the update before it can take its job. build-image.sh keeps the two newest dated tags.
#
# Nothing else is pruned here: the host's Docker is shared with impress-player's pool, which does
# its own pruning (its impress-runner-maintenance.timer).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

"$HERE/build-image.sh" || { echo "maintenance: image rebuild failed; slots keep the current image" >&2; exit 1; }
docker image ls hmdm-runner
