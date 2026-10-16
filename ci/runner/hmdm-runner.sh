#!/usr/bin/env bash
# ci/runner/hmdm-runner.sh <class>-<n> — one hmdm-android runner slot on the build server.
#
# systemd runs one of these per slot (hmdm-runner@<class>-<n>.service, Restart=always). Each pass:
#   1. asks GitHub for a just-in-time (JIT) runner registration. A JIT runner takes one job, after
#      which GitHub deletes it by itself;
#   2. starts a fresh container from the runner image with that registration (docker run --rm);
#   3. returns when the job, or the container, has ended. systemd then starts the next pass.
# Every job therefore gets a brand-new container. Only the slot's cache volume carries over.
#
# The GitHub credential (a fine-grained PAT, or a GitHub App key) is used here, on the host, and
# never enters a container. A job, pull-request code included, sees only its own one-time
# registration.
#
# The class is the part of the instance name before the dash. Both classes get the same container:
# the image's `runner` user (it has passwordless sudo, but only inside its own throwaway
# container), NO Docker socket and NO host directories. The launcher build needs neither, so whatever
# a job does stays in its container, except for the slot's cache volume.
#   ci     pull-request builds (build.yml, unsigned, no secrets). Pull-request code runs here.
#   build  signed APKs (build.yml on pushes, tags and manual runs; it gets the signing key).
#          Separate slots with separate caches: a signed build never waits behind PR builds, and
#          PR code can never plant anything in a cache (Gradle's) that a signed build later reads.
#
# Adapted from impress-write's ci/runner/writer-runner.sh; the pools are independent.
# Configuration: /etc/hmdm-runner/runner.env (template: ci/runner/runner.env.example).
# Setup and operations: docs/ci-runners.md.
# shellcheck source-path=SCRIPTDIR
set -euo pipefail
shopt -s inherit_errexit

INSTANCE="${1:?usage: hmdm-runner.sh <class>-<n>, e.g. build-1}"
CLASS="${INSTANCE%%-*}"
CONF="${HMDM_RUNNER_CONF:-/etc/hmdm-runner/runner.env}"

log() { echo "hmdm-runner[$INSTANCE]: $*" >&2; }

[ -r "$CONF" ] || { log "cannot read $CONF (template: ci/runner/runner.env.example)"; exit 1; }
# shellcheck source=runner.env.example
. "$CONF"

GITHUB_API_URL="${GITHUB_API_URL:-https://api.github.com}"
IMAGE="${IMAGE:-hmdm-runner:current}"
RUNNER_SCOPE="${RUNNER_SCOPE:-repo}"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"
CONTAINER="hmdm-runner-$INSTANCE"
CACHE_VOLUME="hmdm-runner-cache-$INSTANCE"

case "$CLASS" in
  ci)    EXTRA_LABELS="${CI_LABELS:-hmdm-ci}" ;;
  build) EXTRA_LABELS="${BUILD_LABELS:-hmdm-build}" ;;
  *) log "unknown class '$CLASS' (expected ci-<n> or build-<n>)"; exit 1 ;;
esac

case "$RUNNER_SCOPE" in
  repo) : "${GITHUB_OWNER:?GITHUB_OWNER is not set in $CONF}" "${GITHUB_REPO:?GITHUB_REPO is not set in $CONF}"
        RUNNERS_PATH="/repos/$GITHUB_OWNER/$GITHUB_REPO/actions/runners"
        GROUP_ID=1 ;;
  org)  : "${GITHUB_OWNER:?GITHUB_OWNER is not set in $CONF}"
        RUNNERS_PATH="/orgs/$GITHUB_OWNER/actions/runners"
        GROUP_ID="${RUNNER_GROUP_ID:-1}" ;;
  *) log "RUNNER_SCOPE must be repo or org, not '$RUNNER_SCOPE'"; exit 1 ;;
esac

# A failure before a job could run must not become a tight restart loop against the GitHub API.
backoff() { log "$*; next attempt in 60 s"; sleep 60; exit 1; }

docker image inspect "$IMAGE" >/dev/null 2>&1 || backoff "image $IMAGE not found (run ci/runner/install.sh)"

# ---- GitHub API ------------------------------------------------------------------------------
# The Authorization header goes through a 0600 file, never argv: argv shows up in `ps` for every
# user on the host.
HDR="$(mktemp)"
chmod 600 "$HDR"
RUNNER_ID=""
CHILD=""

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# GitHub App: sign a JWT with the app key, then trade it for a 1-hour installation token.
app_installation_token() {
  local now header payload unsigned sig jwt_hdr out rc=0
  now="$(date +%s)"
  header="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
  # iat 60 s in the past absorbs clock drift; GitHub rejects an exp more than 10 min ahead.
  payload="$(printf '{"iat":%d,"exp":%d,"iss":"%s"}' "$((now - 60))" "$((now + 540))" "$GITHUB_APP_ID" | b64url)"
  unsigned="$header.$payload"
  sig="$(printf '%s' "$unsigned" | openssl dgst -sha256 -sign "$GITHUB_APP_KEY_FILE" -binary | b64url)"
  jwt_hdr="$(mktemp)"
  chmod 600 "$jwt_hdr"
  printf 'Authorization: Bearer %s.%s\n' "$unsigned" "$sig" > "$jwt_hdr"
  out="$(curl -fsS -X POST -H @"$jwt_hdr" \
           -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \
           "$GITHUB_API_URL/app/installations/$GITHUB_APP_INSTALLATION_ID/access_tokens" \
         | jq -er .token)" || rc=$?
  rm -f "$jwt_hdr"
  [ "$rc" -eq 0 ] || return "$rc"
  printf '%s' "$out"
}

write_auth_header() {
  local token=""
  if [ -n "${GITHUB_APP_ID:-}" ]; then
    : "${GITHUB_APP_INSTALLATION_ID:?GITHUB_APP_INSTALLATION_ID is not set in $CONF}"
    : "${GITHUB_APP_KEY_FILE:?GITHUB_APP_KEY_FILE is not set in $CONF}"
    token="$(app_installation_token)" || { log "GitHub App installation token request failed"; return 1; }
  elif [ -n "${GITHUB_PAT_FILE:-}" ] && [ -r "$GITHUB_PAT_FILE" ]; then
    token="$(tr -d '[:space:]' < "$GITHUB_PAT_FILE")"
  else
    log "no GitHub credential: set GITHUB_PAT_FILE, or GITHUB_APP_ID + GITHUB_APP_INSTALLATION_ID + GITHUB_APP_KEY_FILE, in $CONF"
    return 1
  fi
  [ -n "$token" ] || { log "the GitHub credential is empty"; return 1; }
  printf 'Authorization: Bearer %s\n' "$token" > "$HDR"
}

# gh_api METHOD PATH [JSON] -> response body on stdout; GitHub's error message in the log on failure.
gh_api() {
  local method="$1" path="$2" body="${3:-}" out code
  local args=(-sS -X "$method" -H @"$HDR" -H 'Accept: application/vnd.github+json'
              -H 'X-GitHub-Api-Version: 2022-11-28' -w '\n%{http_code}')
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  out="$(curl "${args[@]}" "$GITHUB_API_URL$path")" || return 1
  code="${out##*$'\n'}"
  out="${out%$'\n'*}"
  if [ "${code:0:1}" != 2 ]; then
    log "GitHub API $method $path -> HTTP $code: $(jq -r '.message? // empty' <<<"$out" 2>/dev/null || true)"
    return 1
  fi
  printf '%s' "$out"
}

# A JIT runner that ran its job is already gone (the DELETE then gets a 404, which is fine). One
# that never got a job would otherwise sit in the runner list as offline until GitHub expires it.
deregister() {
  [ -n "$RUNNER_ID" ] || return 0
  { write_auth_header && gh_api DELETE "$RUNNERS_PATH/$RUNNER_ID" >/dev/null; } 2>/dev/null || true
}

# shellcheck disable=SC2329  # invoked through the INT/TERM trap
on_stop() {
  trap - INT TERM
  log "stop requested; stopping $CONTAINER (grace ${STOP_TIMEOUT}s)"
  docker stop -t "$STOP_TIMEOUT" "$CONTAINER" >/dev/null 2>&1 || true
  [ -n "$CHILD" ] && wait "$CHILD" 2>/dev/null || true
  deregister
  exit 143
}

trap 'rm -f "$HDR"' EXIT
trap on_stop INT TERM

# ---- register ----------------------------------------------------------------------------------
# JIT registrations get no default labels, so self-hosted/linux/x64 are added explicitly.
LABELS_JSON="$(jq -cn --arg extra "$EXTRA_LABELS" \
  '["self-hosted", "linux", "x64"] + ($extra | split(",") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0))) | unique')"
NAME="$(hostname -s 2>/dev/null || hostname)-hmdm-$INSTANCE-$(date +%s)"
BODY="$(jq -cn --arg name "$NAME" --argjson group "$GROUP_ID" --argjson labels "$LABELS_JSON" \
  '{name: $name, runner_group_id: $group, labels: $labels, work_folder: "_work"}')"

write_auth_header || backoff "no usable GitHub credential"
RESP="$(gh_api POST "$RUNNERS_PATH/generate-jitconfig" "$BODY")" || backoff "JIT runner registration failed"
RUNNER_ID="$(jq -r '.runner.id // empty' <<<"$RESP")"
JIT="$(jq -r '.encoded_jit_config // empty' <<<"$RESP")"
[ -n "$JIT" ] || backoff "GitHub returned no encoded_jit_config"
: > "$HDR"   # the credential is not needed again unless we have to deregister
log "registered $NAME (runner id $RUNNER_ID, labels $(jq -r 'join(",")' <<<"$LABELS_JSON"))"

# ---- run one job -------------------------------------------------------------------------------
# Same container shape for both classes: the image's unprivileged `runner` user, the slot's cache
# volume, nothing else from the host. No Docker socket, no host paths (ci/runner/check-wiring.sh
# keeps it that way).
args=(--rm --init --pull never
      --name "$CONTAINER" --hostname "hmdm-$INSTANCE"
      --label "hmdm-runner.instance=$INSTANCE"
      --env RUNNER_JITCONFIG
      --volume "$CACHE_VOLUME:/cache")

if [ -n "${EXTRA_DOCKER_ARGS:-}" ]; then
  # shellcheck disable=SC2206  # splitting "--memory 8g --cpus 4" into words is the point
  args+=($EXTRA_DOCKER_ARGS)
fi

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true   # left over from a crash or a reboot

# Background + wait, so a stop signal reaches on_stop at once instead of after the job.
started="$(date +%s)"
RUNNER_JITCONFIG="$JIT" docker run "${args[@]}" "$IMAGE" &
CHILD=$!
rc=0
wait "$CHILD" || rc=$?
CHILD=""
log "container exited with status $rc"
deregister
# A container that dies straight away (bad image, Docker trouble) must not turn into a
# register-and-fail loop every RestartSec.
if [ "$rc" -ne 0 ] && [ "$(( $(date +%s) - started ))" -lt 60 ]; then
  backoff "container failed within a minute of starting"
fi
exit "$rc"
