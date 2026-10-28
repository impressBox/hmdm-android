# Launcher local API (impressbox flavor, 6.39.1)

The player cannot change device settings that only the device owner may change. On plain Android it therefore
asks the launcher. This is pattern A from the impress-guard time-zone work: manage `settings.json` → player →
this local API → `DevicePolicyManager`. The design and decisions are in impress-guard `docs/time-zones-2026-10-16.md` §10.

- Contract shared with impress-player: [`docs/launcher-local-api.json`](launcher-local-api.json)
- Interface: `app/src/main/aidl/com/impressbox/launcher/localapi/ILocalApi.aidl`
- Code: `app/src/main/java/com/hmdm/launcher/impressbox/localapi/`

## How it works

- `LocalApiService` is a bound service with the action `com.impressbox.launcher.LOCAL_API` in `com.hmdm.launcher`.
  - `getApiVersion()` and `getCapabilities()` answer anyone.
  - `run(command, args)` answers only callers whose signing certificate is allowed.
- **Caller check:** `Binder.getCallingUid()` → the package manager → the signing certificate's SHA-256 must be in
  the allowlist. Android 9+ uses `hasSigningCertificate`, which also follows key rotation.
  - `LOCAL_API_CALLER_CERTS`: empty in `opensource`. In `impressbox` it is the player,
    `CN=Domas Velykis`, `A7A7EAE8…6BEA53` (the CI key, the same for production and lab builds).
  - `LOCAL_API_EXTRA_CALLER_CERTS`: debug builds only, from `-PlocalApiDebugCallerCerts=<sha256>`, for emulator
    tests. Release builds never read the property.
- **Fixed command allowlist (v1):**

  | Command | Arguments | Android | Notes |
  |---|---|---|---|
  | `getStatus` | – | 8+ | Zone, automatic zone and time, device owner, the configuration's zone, the API zone, update policy, launcher version. No device owner needed. |
  | `setTimeZone` | `zone` (IANA id or `auto`) | 9+ | Turns automatic time zone off, then sets the zone. `auto` gives the zone back to automatic detection. |
  | `setAutoTime` | `enabled` | 8+ | Automatic (network) time on or off. |
  | `setTime` | `epochMillis` | 9+ | Refused while automatic time is on. |
  | `reboot` | `reason` (optional) | 8+ | Answers OK, then reboots after 2 s. |
  | `setSystemUpdatePolicy` | `type` = automatic, windowed (`windowStart`, `windowEnd` in minutes), postpone, none | 8+ | |

- Every result has `code` and `message`. The codes are `OK`, `CALLER_NOT_ALLOWED`, `UNKNOWN_COMMAND`, `BAD_ARGUMENT`,
  `NOT_DEVICE_OWNER`, `UNSUPPORTED`, `MANAGED_BY_CONFIG`, `REFUSED` and `FAILED`.
- **Logs:** every command that changes something, and every refusal, goes to the server log (`RemoteLogger`),
  for example `Local API: com.impressplayer setTimeZone {zone=Europe/Warsaw}: OK, time zone Europe/Warsaw (was Europe/Vilnius)`.
  A successful `getStatus` goes to logcat only.

## The HMDM configuration and the API never fight

- **Configuration `timeZone` is a fixed zone:** the configuration owns the zone. The launcher sets it at every sync,
  and `setTimeZone` refuses with `MANAGED_BY_CONFIG`.
- **Configuration `timeZone` is `auto`:** before 6.39.1, every sync turned automatic time zone back on, which would
  undo the player's zone. Now, while a zone set through the API is remembered (`LocalApiZone`), `auto` leaves
  automatic time zone off. A fixed zone in the configuration clears that memory. So does `setTimeZone auto`.
- **Configuration `timeZone` is empty:** the launcher does not touch the zone.
- **Configuration system update type is not the default:** the configuration owns the policy, and
  `setSystemUpdatePolicy` refuses with `MANAGED_BY_CONFIG`.

## Tests

- **Unit tests:** `./gradlew :app:testImpressboxDebugUnitTest`.
  - `LocalApiRulesTest` covers the decisions.
  - `LocalApiContractTest` covers the player ↔ launcher boundary. It checks the AIDL, codes, commands, arguments and
    results against the JSON contract. impress-player runs the same check against its copy of the contract.
- **Emulator** (API 34 `google_apis` x86_64, not a Play Store image: device owner needs a device without accounts):
  1. Build the test client with this wrapper:
     `gradlew -p <impress-guard>/tools/launcher-localapi-client :app:assembleDebug`.
  2. Read its certificate: `apksigner verify --print-certs app-debug.apk`.
  3. Build the launcher with `-PlocalApiDebugCallerCerts=<that SHA-256>`.
  4. Install the launcher, then run `adb shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver`.
  5. Install the client and call it:
     `adb shell am start -n com.impressbox.localapi.testclient/.CallActivity --es command setTimeZone --es zone Europe/Warsaw`.
     The answer is in logcat, tag `LocalApiClient`.
  - Results from 2026-10-29 are in impress-guard `docs/time-zones-2026-10-16.md` §10.

## Release build (Oleh)

The signed build needs the same key as the fleet's 6.24 and 6.39 launchers, otherwise it cannot update the
device-owner launcher. Pass the values; never commit them:

```sh
./gradlew :app:assembleImpressboxRelease \
  -PimpressboxStoreFile=<keystore file> -PimpressboxStorePassword=<…> \
  -PimpressboxKeyAlias=<…> -PimpressboxKeyPassword=<…> \
  -PimpressboxVpnSecret=<…> -PimpressboxRequestSignature=<…> -PimpressboxLibraryApiKey=<…>
```

Do not pass `-PlocalApiDebugCallerCerts`. Release builds ignore it anyway.

Check the APK `app/build/outputs/apk/impressbox/release/app-impressbox-release.apk` before it goes anywhere:

```sh
apksigner verify --print-certs app-impressbox-release.apk
#   Signer #1 certificate DN: CN=com.impressbox, OU=com.impressbox, O=Impressbox, ...
#   Signer #1 certificate SHA-256 digest: 7b8a67248176a41bb90f2a4afc39a1c6b9b96847fa224bc9d2c06fb01fb13e56
aapt2 dump badging app-impressbox-release.apk | head -1
#   package: name='com.hmdm.launcher' versionCode='16391' versionName='6.39.1' ...
```

**Rollout:** the launcher is the recovery channel, so release it in rings and keep 6.39 in HMDM for rollback:
1. the office t982 (HMDM configuration 39);
2. the lab devices;
3. a few screens;
4. the fleet.

Each ring needs Zilvaras's OK.
