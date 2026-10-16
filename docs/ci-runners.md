# CI: building the launcher APK

`.github/workflows/build.yml` builds the impressbox launcher (`assembleImpressboxRelease`), signs
it with the impressbox release key, and keeps the APK:

| Trigger                           | Result                                                                  |
| --------------------------------- | ----------------------------------------------------------------------- |
| push to any branch                | signed APK as a **workflow artifact** (30 days)                         |
| Actions → Build APK → Run workflow | the same, for the branch you pick                                       |
| tag `v*`                          | the same, and attached to the **GitHub Release** of the tag             |
| pull request from a fork          | unsigned build without secrets (a compile check only)                   |

Pull requests from branches of this repository are not built a second time: their pushes already
are. The run's summary shows the version, the commit, the signing certificate and the APK's
SHA-256. Install over the current launcher with `adb install -r hmdm-….apk`.

The APK is named `hmdm-<versionName>-impressbox-<branch or tag>-<commit>.apk`.

## Where it runs

GitHub-hosted runners are blocked by the organisation's billing (since 2026-09-25), so the build
runs on **this repository's own runner pool on the build server (10.0.0.200)**, defined in
[`ci/runner/`](../ci/runner). It sits next to the impress-player, manage, gateway and impress-write
pools on that host and shares nothing with them: its own image, config, systemd units, containers,
cache volumes (all named `hmdm-runner`) and its own GitHub token, scoped to this repository only.

systemd keeps a fixed number of **slots**. Each slot registers a GitHub _just-in-time_ runner, runs
**one** job in a fresh container, removes the container and starts again. Nothing a job leaves
behind reaches the next job, except the slot's own cache volume (Gradle and its dependencies).

```
systemd: hmdm-runner@build-1.service (Restart=always)
  └─ /opt/hmdm-runner/hmdm-runner.sh build-1
       1. POST /repos/impressBox/hmdm-android/actions/runners/generate-jitconfig
          (the GitHub token is used here, on the host, and never enters a container)
       2. docker run --rm hmdm-runner:current   (one-time registration only)
            └─ run.sh --jitconfig …   takes exactly one job, then exits
       3. container gone → script exits → systemd starts the slot again
```

A container gets **no Docker socket and no host directory**, only its cache volume
(`hmdm-runner-cache-<slot>:/cache`), and at most 6 GB of memory and 4 CPUs (`EXTRA_DOCKER_ARGS`).
`ci/runner/check-wiring.sh` (run by every build) fails if the slot script ever mounts more.

The image (`ci/runner/Dockerfile`) is built on the build server and never pushed anywhere: the
GitHub actions-runner base plus JDK 17 and the Android SDK the build uses (android-34, build-tools
34.0.0 and 35.0.0, platform-tools). Gradle itself and the dependencies are downloaded by the job
into the cache volume, so only a slot's first job fetches them. A timer rebuilds the image every
Sunday on the current actions-runner release.

| Label        | Carried by              | Runs                                                       |
| ------------ | ----------------------- | ---------------------------------------------------------- |
| `hmdm-build` | pool slots `build-1..M` | signed builds (pushes, tags, manual runs): gets the key    |
| `hmdm-ci`    | pool slots `ci-1..N`    | builds of pull requests from forks: no secrets             |

The labels are set in `ci/runner/runner.env.example` and declared for actionlint in
`.github/actionlint.yaml`. `check-wiring.sh` checks that every `runs-on` is a label of this pool,
because a typo does not fail a job: it waits in the queue forever.

## Signing

Repository → Settings → Secrets and variables → Actions.

**Secrets** (all required except the VPN one):

| Secret                         | Value                                                                  |
| ------------------------------ | ---------------------------------------------------------------------- |
| `IMPRESSBOX_KEYSTORE_BASE64`   | the release keystore, base64: `base64 -w0 impressbox.keystore`         |
| `IMPRESSBOX_STORE_PASSWORD`    | its store password                                                     |
| `IMPRESSBOX_KEY_ALIAS`         | the key alias                                                          |
| `IMPRESSBOX_KEY_PASSWORD`      | the key password                                                       |
| `IMPRESSBOX_REQUEST_SIGNATURE` | the Gradle property `impressboxRequestSignature` (server's shared secret) |
| `IMPRESSBOX_LIBRARY_API_KEY`   | the Gradle property `impressboxLibraryApiKey`                          |
| `IMPRESSBOX_VPN_SECRET`        | k8s-services `vpn/registrar-secret.sh`; without it the APK cannot enroll in the VPN by itself |

The key **must** be the one the shipped launcher is signed with. Android only installs an update
signed with the same key, and the only other way in is to uninstall the launcher, which drops
device owner and means provisioning the panel again on site.

**Variable** `IMPRESSBOX_CERT_SHA256`: the SHA-256 of that signing certificate (colons optional),
e.g. from the shipped APK:

```bash
apksigner verify --print-certs hmdm-6.39.apk | grep 'SHA-256'
```

The build fails if the APK is signed with any other certificate. While the variable is not set,
the build only warns.

The keystore is written to the job's temporary directory, handed to Gradle through
`ORG_GRADLE_PROJECT_*` environment variables (never a command line), deleted at the end of the
job, and gone with the container anyway.

### This repository is public

- **Anyone can download the APKs** (workflow artifacts and releases of a public repository are
  public). The APK carries what the build compiles into it: the request signature, the library
  API key and the VPN enrollment secret. That is no change from today, as the same launcher is
  shipped inside the Writer's public installers, but keep it in mind, or make the repository
  private.
- **Pull requests from forks run on our build server** (the `hmdm-ci` slots: no secrets, a fresh
  container, no Docker socket or host mounts). Keep Settings → Actions → General → *Fork pull
  request workflows* on **Require approval for all external contributors**, so nothing runs before
  someone has read the change.

## One-time setup (on the build server, as root)

1. **GitHub token.** GitHub → your Settings → Developer settings → Personal access tokens →
   **Fine-grained tokens** → Generate:
   - Resource owner: `impressBox`
   - Repository access: **Only select repositories → hmdm-android**
   - Repository permissions: **Administration: Read and write** (what `generate-jitconfig` needs).
     Nothing else.
   - Expiry: as long as the org allows; put the date in the calendar.

   ```bash
   sudo install -d -m 700 /etc/hmdm-runner
   sudo sh -c 'umask 077; cat > /etc/hmdm-runner/github-pat'   # paste, Enter, Ctrl-D
   ```

   (A GitHub App with the same permission, installed on hmdm-android, also works: set
   `GITHUB_APP_ID`, `GITHUB_APP_INSTALLATION_ID`, `GITHUB_APP_KEY_FILE` in the config instead.)

2. **Install** from a checkout of this repository on the host:

   ```bash
   git clone https://github.com/impressBox/hmdm-android.git /opt/hmdm-android-ci   # once
   cd /opt/hmdm-android-ci && git fetch -q origin && git checkout -q origin/feature/impressbox-keep-adb
   sudo bash ci/runner/install.sh    # 1st run: writes /etc/hmdm-runner/runner.env and stops
   sudo bash ci/runner/install.sh    # builds the image, starts the slots and the weekly timer
   ```

   Defaults: 1 `ci` slot and 1 `build` slot.

3. **Check.** `systemctl list-units 'hmdm-runner@*'` shows two running slots, and hmdm-android →
   Settings → Actions → Runners lists two idle runners `build-hmdm-ci-1-…` and
   `build-hmdm-build-1-…`.

4. **Secrets and variable** as in [Signing](#signing).

5. **Smoke test.** Actions → Build APK → Run workflow on `feature/impressbox-keep-adb`. The job runs
   on `build-hmdm-build-1-…`, the summary shows the certificate, and the APK is under Artifacts.

## Operations

- Logs: `journalctl -u 'hmdm-runner@*' -f`
- Rebuild the image now: `sudo /opt/hmdm-runner/build-image.sh`; roll back with
  `docker tag hmdm-runner:<previous> hmdm-runner:current`
- More slots or other limits: edit `/etc/hmdm-runner/runner.env`, then
  `sudo bash ci/runner/install.sh --no-build`
- Clear a slot's Gradle cache: stop the slot, `docker volume rm hmdm-runner-cache-build-1`, start it
- Remove the pool: `sudo bash ci/runner/install.sh --uninstall` (keeps config, cache volumes, image)
- Token expired: slots log `JIT runner registration failed … HTTP 401` and retry every minute;
  replace `/etc/hmdm-runner/github-pat`, nothing else needs restarting.
