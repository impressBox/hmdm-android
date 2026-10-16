#!/usr/bin/env bash
# ci/runner/build-image.sh — (re)build the launcher runner image on the build server.
#
# Tags the result hmdm-runner:<UTC date-time> and moves hmdm-runner:current to it. Slots pick
# the new image up on their next job; a running job keeps the image it started with. The two newest
# dated tags are kept, so a bad image can be rolled back with
#   docker tag hmdm-runner:<previous> hmdm-runner:current
# Called by install.sh and, weekly, by maintenance.sh (--pull keeps the actions-runner base current).
# shellcheck source-path=SCRIPTDIR
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CONF="${HMDM_RUNNER_CONF:-/etc/hmdm-runner/runner.env}"
# shellcheck source=runner.env.example
if [ -r "$CONF" ]; then . "$CONF"; fi

IMAGE="${IMAGE:-hmdm-runner:current}"
REPO="${IMAGE%:*}"

TAG="$REPO:$(date -u +%Y%m%d-%H%M)"
echo "build-image: building $TAG"
docker build --pull -t "$TAG" "$HERE"
docker tag "$TAG" "$IMAGE"
echo "build-image: $IMAGE -> $TAG"

docker image ls "$REPO" --format '{{.Tag}}' \
  | grep -E '^[0-9]{8}-[0-9]{4}$' | sort -r | tail -n +3 \
  | while read -r old; do docker rmi "$REPO:$old" >/dev/null 2>&1 || true; done
