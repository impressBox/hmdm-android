#!/usr/bin/env bash
# ci/runner/check-wiring.sh — checks that the workflows and the runner pool fit together.
#
# Run by .github/workflows/build.yml on every build, and by hand before changing a label:
#   bash ci/runner/check-wiring.sh
# It fails when
#   - a job asks for a runner label no pool slot carries (a typo does not fail a job: it just
#     waits in the queue forever), or for a GitHub-hosted image (blocked by billing);
#   - the slot script gives a container more of the host than its cache volume (a Docker socket,
#     a host directory, --privileged, the host network).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="$ROOT/ci/runner/runner.env.example"
SLOT="$ROOT/ci/runner/hmdm-runner.sh"
fail=0
err() { echo "check-wiring: $*" >&2; fail=1; }

labels="self-hosted linux x64"
for key in CI_LABELS BUILD_LABELS; do
  value="$(sed -n "s/^$key=//p" "$ENV_FILE" | tr -d '"' | tr ',' ' ')"
  [ -n "$value" ] || err "$key is not set in ci/runner/runner.env.example"
  labels="$labels $value"
done

for wf in "$ROOT"/.github/workflows/*.yml; do
  while IFS= read -r line; do
    want="$(sed -E 's/^[[:space:]]*runs-on:[[:space:]]*//; s/[][,]/ /g; s/"//g' <<<"$line")"
    for label in $want; do
      case " $labels " in
        *" $label "*) ;;
        *) err "$(basename "$wf"): runs-on '$label' is not a label of the hmdm-runner pool ($labels)" ;;
      esac
    done
  done < <(grep -E '^[[:space:]]*runs-on:' "$wf")
done

# Everything the slot script passes to `docker run` is in its args=(...) list and EXTRA_DOCKER_ARGS.
args="$(sed -n '/^args=(/,/)$/p' "$SLOT")"
volumes="$(grep -o -- '--volume [^ )]*' <<<"$args" || true)"
# shellcheck disable=SC2016  # the literal text of the script, not an expansion
[ "$volumes" = '--volume "$CACHE_VOLUME:/cache"' ] || err "hmdm-runner.sh: containers may only mount their cache volume, found: ${volumes:-none}"
for bad in docker.sock '--privileged' '--network host' '--net host' '-v ' '--mount' '--cap-add'; do
  if grep -q -- "$bad" <<<"$args"; then err "hmdm-runner.sh: '$bad' in the container arguments"; fi
  if grep -E '^EXTRA_DOCKER_ARGS=' "$ENV_FILE" | grep -q -- "$bad"; then err "runner.env.example: '$bad' in EXTRA_DOCKER_ARGS"; fi
done

if [ "$fail" -eq 0 ]; then echo "check-wiring: ok (labels: $labels)"; fi
exit "$fail"
