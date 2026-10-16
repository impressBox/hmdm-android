#!/usr/bin/env bash
# ci/runner/install.sh — install or update the hmdm-android runner pool on the build server.
#
#   sudo bash ci/runner/install.sh               copy files + units, build the image, start the slots
#   sudo bash ci/runner/install.sh --no-build    same, without rebuilding the image
#   sudo bash ci/runner/install.sh --uninstall   stop and remove the slots, the timer and the units
#                                                (keeps /etc/hmdm-runner and the cache volumes)
#
# Run it from a checkout of hmdm-android. Safe to re-run: a slot that is running a job is not
# restarted. It picks up a new script or image on its next pass, when the job is over.
# First run creates /etc/hmdm-runner/runner.env from runner.env.example and stops, so the
# credential can be filled in. Full walkthrough: docs/ci-runners.md.
#
# Everything is named hmdm-runner (units, /opt, /etc, image, containers, volumes), so this pool
# lives next to the impress-player, manage, gateway and impress-write pools on the same host
# without touching them.
# shellcheck source-path=SCRIPTDIR
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/hmdm-runner
CONF_DIR=/etc/hmdm-runner
CONF="$CONF_DIR/runner.env"
UNIT_DIR=/etc/systemd/system

mode=install
case "${1:-}" in
  "") ;;
  --no-build) mode=no-build ;;
  --uninstall) mode=uninstall ;;
  *) echo "usage: install.sh [--no-build|--uninstall]" >&2; exit 2 ;;
esac

[ "$(id -u)" -eq 0 ] || { echo "install.sh: run as root" >&2; exit 1; }

slot_units() {  # every slot unit systemd knows about, enabled or running
  {
    systemctl list-units --all --plain --no-legend 'hmdm-runner@*.service' 2>/dev/null | awk '{print $1}'
    find "$UNIT_DIR"/*.wants -maxdepth 1 -name 'hmdm-runner@*.service' -printf '%f\n' 2>/dev/null
  } | sort -u
}

if [ "$mode" = uninstall ]; then
  for unit in $(slot_units); do systemctl disable --now "$unit" || true; done
  systemctl disable --now hmdm-runner-maintenance.timer 2>/dev/null || true
  rm -f "$UNIT_DIR/hmdm-runner@.service" \
        "$UNIT_DIR/hmdm-runner-maintenance.service" "$UNIT_DIR/hmdm-runner-maintenance.timer"
  systemctl daemon-reload
  echo "Launcher runner pool removed. Kept: $CONF_DIR, $DEST, the image and the hmdm-runner-cache-* volumes."
  exit 0
fi

for tool in docker curl jq openssl systemctl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "install.sh: '$tool' is required (apt-get install -y $tool)" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "install.sh: the Docker daemon is not reachable" >&2; exit 1; }

# ---- files -------------------------------------------------------------------------------------
install -d -m 0755 "$DEST"
install -m 0644 "$SRC/Dockerfile" "$SRC/runner.env.example" "$DEST/"
install -m 0755 "$SRC/entrypoint.sh" "$SRC/hmdm-runner.sh" "$SRC/build-image.sh" "$SRC/maintenance.sh" "$DEST/"
install -m 0644 "$SRC/systemd/hmdm-runner@.service" \
                "$SRC/systemd/hmdm-runner-maintenance.service" \
                "$SRC/systemd/hmdm-runner-maintenance.timer" "$UNIT_DIR/"

install -d -m 0700 "$CONF_DIR"
if [ ! -f "$CONF" ]; then
  install -m 0600 "$SRC/runner.env.example" "$CONF"
  echo "Created $CONF from the template."
  echo "Put the GitHub token in ${CONF_DIR}/github-pat (docs/ci-runners.md, One-time setup, step 1), then run install.sh again."
  exit 0
fi
# shellcheck source=runner.env.example
. "$CONF"

if [ -z "${GITHUB_APP_ID:-}" ] && { [ -z "${GITHUB_PAT_FILE:-}" ] || [ ! -s "$GITHUB_PAT_FILE" ]; }; then
  echo "install.sh: no GitHub credential yet. Put the token in ${GITHUB_PAT_FILE:-$CONF_DIR/github-pat}" >&2
  echo "  (chmod 600), or configure the GitHub App in $CONF. See docs/ci-runners.md, One-time setup, step 1." >&2
  exit 1
fi

# ---- image -------------------------------------------------------------------------------------
if [ "$mode" = install ]; then
  "$DEST/build-image.sh"
fi
docker image inspect "${IMAGE:-hmdm-runner:current}" >/dev/null 2>&1 \
  || { echo "install.sh: image ${IMAGE:-hmdm-runner:current} missing; run without --no-build" >&2; exit 1; }

# ---- units and slots ---------------------------------------------------------------------------
systemctl daemon-reload
systemctl enable --now hmdm-runner-maintenance.timer

want=()
for i in $(seq 1 "${CI_RUNNERS:-1}");    do want+=("hmdm-runner@ci-$i.service"); done
for i in $(seq 1 "${BUILD_RUNNERS:-1}"); do want+=("hmdm-runner@build-$i.service"); done

for unit in $(slot_units); do
  case " ${want[*]} " in
    *" $unit "*) ;;
    *) echo "install.sh: removing surplus slot $unit"; systemctl disable --now "$unit" || true ;;
  esac
done
for unit in "${want[@]}"; do
  systemctl enable --now "$unit"   # starts a stopped slot; leaves a running one (and its job) alone
done

echo
systemctl list-units --all --no-pager 'hmdm-runner@*.service'
echo
echo "Slots register as GitHub runners named $(hostname -s 2>/dev/null || hostname)-hmdm-<slot>-<timestamp>;"
echo "check hmdm-android -> Settings -> Actions -> Runners. Logs: journalctl -u 'hmdm-runner@*' -f"
