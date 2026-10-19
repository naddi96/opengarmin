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

- `:protocol:test` — 55 tests for COBS, CRC, GFDI, GDI, MLR, Multi-Link, FIT, the workout and pace-band encoders, and legacy file upload.
- `:app:testDebugUnitTest` — 101 JVM tests:
  - `HealthToolsTest`: scope rejection, missing-day handling, date-range caps,
    sample caps, activity filters/pages, zone calculation, comparisons,
    unavailable sleep, snapshot budget, unknown tool/bad JSON, coach workout
    creation (steps/repeats), nested-repeat and past-date rejection, and plan
    listing.
  - `FitInsightsTest`: GPS summary/route sampling, running-dynamics parsing
    (cadence, ground contact, vertical oscillation/ratio, stride length,
    training effect), record-average fallback, and lap dynamics.
  - `OpenAiClientTest`: streaming deltas, tool-call fragments, non-streaming
    completion, 401, 5xx, read timeout, models list, request shape/auth header.
  - `ChatLoopIntegrationTest`: streaming tool call → execution → evidence →
    final answer, and the no-tool snapshot fallback, against a local
    OpenAI-compatible mock server.
  - `ConversationCodecTest`: conversation JSON round-trip, title inference,
    interrupted/streaming messages, and invalid input.
  - `ChartSpecTest`, `ChatMarkdownTest`, `SportCategoryTest`: chart parsing and
    caps, Markdown rendering, and sport-category mapping.
  - `WellnessParserTest`, `WellnessMetricsTest`, `WellnessHistoryTest`,
    `TrainingMetricsTest`: monitoring/sleep/HRV parsing, daily metrics and
    trend bucketing, and legacy/new training-metric encodings.
  - `WorkoutPlanCodecTest`: plan JSON round-trip, repeat totals, FIT entry
    mapping and malformed input.
  - `PacePlanCalculatorTest`: Riegel predictions and stale/non-run filtering,
    even/negative/positive split totals, hill adjustments, partial final splits,
    custom-distance goal estimates, split JSON round-trip and FIT pace-band
    mapping.
  - `ActivityExportTest`: readable filenames and extensions, XML escaping,
    invalid/missing GPS positions, no-GPS GPX errors, synthetic and real lap
    handling, empty records, TCX/CSV field mapping and quoting, and
    missing/invalid FIT files.
- `:app:connectedDebugAndroidTest` — `SecretStoreTest` verifies the Android
  Keystore round-trip and that the plaintext key is not present in preferences.

### Mock server

`ChatLoopIntegrationTest` and `OpenAiClientTest` use OkHttp `MockWebServer`;
they do not need network access, API keys, or an emulator.

A standalone Python mock used for device smoke tests is documented in the
session notes; it serves `/v1/models` and `/v1/chat/completions` with SSE and a
tool call. Keep real keys and health data out of any mock fixture.

## Device / manual checks

Wireless ADB example (replace `<wireless-serial>` with the value from
`adb devices -l`):

```bash
ADB="tools/android-sdk/platform-tools/adb -s <wireless-serial>"
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
- Coach creates a workout: ask "create an easy 20 minute run with 5 minute warm-up and cool-down".
  Confirm the answer says it is saved locally (not sent), the Plan tab gains a draft, and the
  evidence chip reads `Workout created · …`.
- Plan: create timed, distance and open steps; add a repeat block; save,
  reopen, delete, and schedule a workout. `Send to watch` must show
  `Sent to watch · N B`, and `logcat` must show the legacy
  `CREATE_FILE` → `UPLOAD_REQUEST` → `FILE_TRANSFER_DATA` → `SYNC_COMPLETE`
  sequence plus `FileAccess.ItemAddedNotification { FIT_TYPE_5 }`.
- PacePro: open Training → PacePro strategy, choose a race or **Custom
  distance**, adjust the goal, optionally pick a recent GPS run as the course,
  move both sliders, and check that the preview split times add up to the goal.
  `Accept and send to watch` must show `Sent to watch · N B`; `logcat` must show
  the same legacy upload sequence with `type=FIT/56`. The plan must reappear in
  the Training card and in **Saved strategies** after a relaunch.
- PacePro run: from the accepted plan tap **Preview run**, grant location, and
  start. With a GPS fix the badge changes to `GPS locked`, distance/time update,
  `Pause`/`Resume` and `Lap` work, and `Stop PacePro` confirms before returning
  to Training. Without location permission the screen must show `GPS off` and
  must not fabricate distance or pace.
- Sync regression: activity must download and import even when a wellness
  transfer is active (watch returns `NO_AVAILABLE_RESOURCES` if overlapped);
  a second sync must skip the known activity and finish wellness.
- Multi-activity sync: the pill shows `Syncing N/M`, Today shows the current
  download (index, total, file size) and last imported activity, and Settings
  shows the same progress.
- Sync notification: grant notifications on Android 13+, start a sync, and check
  the ongoing notification shows `Activity N of M`, file size, progress, and
  last imported activity. Disconnect or complete the sync and confirm a separate
  dismissible `Sync stopped`/`Sync complete` notification remains.
- Activity detail: map/HR/elevation/laps/`All data`.
- Activity export: on 2026-10-19 the Pixel 10a / Android 17 + Forerunner 255
  pass exported a 6.55 km run as FIT, GPX, TCX, and CSV through the system
  picker. The FIT copy was byte-identical to the stored watch file (SHA-256
  match); GPX and TCX parsed as well-formed XML with 2,816 GPS points and
  7 laps/2,821 trackpoints respectively; CSV parsed as 2,821 rows with 20
  columns, ISO-8601 UTC timestamps, and lap indexes 1–7. An indoor Cardio
  activity disabled GPX with the explanation visible. Temporarily renaming the
  stored FIT file disabled FIT with the “no longer on this device” explanation;
  restoring the file re-enabled it. No export was uploaded and `logcat` showed
  no crash or ANR.
- Settings: step goal, max HR, sync state, provider summary, device log,
  Import FIT file, Data & backup.
- Provider: presets (including DeepSeek), model add/edit/delete/default,
  masked key, test connection, save, remove.
- Coach: provider gate, suggestions, streaming, tool call, evidence chips,
  cancel, errors, persistence.
- Coach history: open History, verify titles/times/message counts, switch
  between conversations, start a new one, delete with confirmation, force-stop
  and relaunch to verify the latest conversation returns.
- Backup export: Settings → Data & backup → Export backup, save the ZIP, and
  verify it contains `opengarmin.db`, `chat.json`, and `backup.json`.
- Backup restore: import the same backup (or another device's), confirm the
  warning, and verify activities/wellness and conversations return after the
  Room flow collectors restart. Importing an invalid/truncated file must show an
  error and leave the current database intact.
- Accessibility spot checks: 44 dp controls, TalkBack labels, focus order,
  200% font scale.

Manual flows above were last exercised on a **Pixel 10a / Android 17** against a
Forerunner 255 on 2026-10-18.

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
- An end-to-end backup/restore instrumented test. `BackupManager` currently has
  tests at the JSON-codec level only; the file swap and Room reopen are verified
  manually.
- Live BLE sync tests (hardware-dependent; see `docs/HANDOFF.md`).
