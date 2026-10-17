# Testing

## Automated suites

```bash
cd /home/naddi/garmin-connect-reverse
source tools/env.sh

# Pure JVM protocol tests
./tools/gradle-8.7/bin/gradle --no-daemon :protocol:test

# App JVM unit + integration tests
./tools/gradle-8.7/bin/gradle --no-daemon :app:testDebugUnitTest

# Instrumented test (Keystore secret storage); needs a device/emulator
ANDROID_SERIAL=<serial> ./tools/gradle-8.7/bin/gradle --no-daemon :app:connectedDebugAndroidTest

# Debug APK
./tools/gradle-8.7/bin/gradle --no-daemon :app:assembleDebug
```

Current coverage:

- `:protocol:test` — 35 tests for COBS, CRC, GFDI, GDI, MLR, Multi-Link, FIT.
- `:app:testDebugUnitTest` — 25 JVM tests:
  - `HealthToolsTest`: scope rejection, missing-day handling, date-range caps,
    sample caps, activity filters/pages, zone calculation, comparisons,
    unavailable sleep, snapshot budget, unknown tool/bad JSON.
  - `FitInsightsTest`: GPS summary/route sampling, running-dynamics parsing
    (cadence, ground contact, vertical oscillation/ratio, stride length,
    training effect), record-average fallback, and lap dynamics.
  - `OpenAiClientTest`: streaming deltas, tool-call fragments, non-streaming
    completion, 401, 5xx, read timeout, models list, request shape/auth header.
  - `ChatLoopIntegrationTest`: streaming tool call → execution → evidence →
    final answer, and the no-tool snapshot fallback, against a local
    OpenAI-compatible mock server.
- `:app:connectedDebugAndroidTest` — `SecretStoreTest` verifies the Android
  Keystore round-trip and that the plaintext key is not present in preferences.

### Mock server

`ChatLoopIntegrationTest` and `OpenAiClientTest` use OkHttp `MockWebServer`;
they do not need network access, API keys, or an emulator.

A standalone Python mock used for device smoke tests is documented in the
session notes; it serves `/v1/models` and `/v1/chat/completions` with SSE and a
tool call. Keep real keys and health data out of any mock fixture.

## Device / manual checks

Wireless ADB example:

```bash
ADB="tools/android-sdk/platform-tools/adb -s 192.168.1.162:32999"
./tools/gradle-8.7/bin/gradle --no-daemon :app:assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am force-stop com.garmin.android.apps.connectmobile
$ADB shell am force-stop dev.opengarmin.app
$ADB logcat -c
$ADB shell am start -n dev.opengarmin.app/.MainActivity
```

Manual matrix used for the UI/coach pass:

- Today: real steps ring, HR chart, sleep/HRV empty states, insight.
- Trends: Day/Week/Month, partial-history labels, goal line, resting HR.
- Activities: filters, row selection, featured card, route trace, zones setup.
- Sync regression: activity must download and import even when a wellness
  transfer is active (watch returns `NO_AVAILABLE_RESOURCES` if overlapped);
  a second sync must skip the known activity and finish wellness.
- Multi-activity sync: the pill shows `Syncing N/M`, Today shows the current
  download (index, total, file size) and last imported activity, and Settings
  shows the same progress.
- Activity detail: map/HR/elevation/laps/`All data`.
- Settings: step goal, max HR, sync state, provider summary, device log.
- Provider: presets (including DeepSeek), model add/edit/delete/default,
  masked key, test connection, save, remove.
- Coach: provider gate, suggestions, streaming, tool call, evidence chips,
  cancel, errors, persistence.
- Accessibility spot checks: 44 dp controls, TalkBack labels, focus order,
  200% font scale.

## CI

`.github/workflows/android.yml` runs protocol tests, app unit/integration tests,
and `assembleDebug` on pull requests and pushes. The suite is hermetic and needs
no provider key.

## Not yet automated

- Compose UI/instrumentation tests and screenshot regression. The component
  gallery and manual matrix above are the current visual check; add a Compose
  UI-test job with Robolectric or an emulator when it is worth the CI cost.
- Room migration tests for the existing hand-written migrations. Add them with
  the next schema change.
- Live BLE sync tests (hardware-dependent; see `docs/HANDOFF.md`).
