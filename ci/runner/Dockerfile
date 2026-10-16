# ci/runner/Dockerfile — the image every ephemeral hmdm-android runner container starts from.
#
# Built ON the build server by ci/runner/install.sh and never pushed anywhere. One container runs
# one job: ci/runner/hmdm-runner.sh registers a just-in-time runner, starts this image with
# --rm, and the container is gone when the job ends. Only the slot's cache volume (/cache)
# carries over to the next job. See docs/ci-runners.md.
#
# This pool is the launcher's own. It shares nothing with the impress-player, manage, gateway and
# impress-write pools on the same host (different image, units, config, containers and volumes:
# everything here is named hmdm-runner).
#
# Baked in, so no job has to download it (hundreds of MB every time otherwise):
#   - JDK 17: what AGP 8.x needs to run Gradle
#   - the Android SDK the build uses: platform android-34 (compileSdk), build-tools 34.0.0 and
#     35.0.0 (AGP 8.13 picks 35.0.0 by itself), platform-tools; licences accepted
# Not baked in: Gradle and the dependencies. The wrapper fetches the Gradle version the repository
# pins, and both land in GRADLE_USER_HOME on the slot's cache volume, so only the first job of a
# slot downloads them.

ARG RUNNER_BASE=ghcr.io/actions/actions-runner:latest
FROM ${RUNNER_BASE}
USER root
ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends \
      openjdk-17-jdk-headless \
      ca-certificates curl jq file unzip zip git \
 && rm -rf /var/lib/apt/lists/* \
 && java -version 2>&1 | grep -q '"17\.'

# Android command-line tools; the URL names a fixed release, bump it together with the packages.
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
ARG SDK_PACKAGES="platforms;android-34 build-tools;34.0.0 build-tools;35.0.0 platform-tools"
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSL -o /tmp/cmdline-tools.zip "$CMDLINE_TOOLS_URL" \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/ct \
 && mv /tmp/ct/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" \
 && rm -rf /tmp/cmdline-tools.zip /tmp/ct \
 && yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null \
 && "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" $SDK_PACKAGES >/dev/null \
 && test -x "$ANDROID_HOME/build-tools/35.0.0/apksigner" \
 && chown -R runner:runner "$ANDROID_HOME"
# (owned by runner: a package the build asks for that is not baked in can still be installed by
#  AGP, into that job's throwaway container only)
ENV PATH="${PATH}:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/35.0.0"

# Everything that is expensive to fetch again lives on the slot's cache volume, mounted at /cache.
# The runner passes its own environment to every job, so Gradle uses it without any workflow
# setting it.
ENV GRADLE_USER_HOME=/cache/gradle \
    RUNNER_TOOL_CACHE=/cache/toolcache \
    AGENT_TOOLSDIRECTORY=/cache/toolcache
# A new named volume copies this directory's ownership, so the unprivileged `runner` user can
# write its cache.
RUN mkdir -p /cache && chown runner:runner /cache

COPY entrypoint.sh /usr/local/bin/hmdm-runner-entrypoint
RUN chmod 0755 /usr/local/bin/hmdm-runner-entrypoint

LABEL org.opencontainers.image.title="hmdm-runner" \
      org.opencontainers.image.source="https://github.com/impressBox/hmdm-android" \
      org.opencontainers.image.description="Ephemeral GitHub Actions runner for hmdm-android (ci/runner)"

USER runner
WORKDIR /home/runner
ENTRYPOINT ["/usr/local/bin/hmdm-runner-entrypoint"]
