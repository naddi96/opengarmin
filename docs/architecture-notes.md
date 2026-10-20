# Architecture notes

_Last updated: 2026-10-19. Baseline: workout planner, direct watch upload, and PacePro._

This document describes the app as it is implemented today: a local-first Android
companion that syncs a Garmin watch over BLE, stores health data in Room, and
offers an optional OpenAI-compatible coach. Product history and remaining work
live in [`BACKLOG.md`](../BACKLOG.md).

## 1. At a glance

| Area | Current choice |
|---|---|
| Platform | Native Android app, single activity, Compose screens |
| Language | Kotlin 1.9.24 (JVM 17) |
| UI | Jetpack Compose + Material 3, Compose BOM `2024.06.00`; Pulse design tokens and reusable components |
| Build | Gradle 8.7, AGP 8.5.2, KSP, `compileSdk 34`, `minSdk 28`, `targetSdk 34` |
| Modules | `:protocol` (pure JVM) and `:app` (Android) |
| Persistence | Room 2.6.1, database version 9, hand-written migrations; SharedPreferences for settings and provider configs |
| Async | Kotlin coroutines + `lifecycleScope`; Room flows feed Compose state; a `Handler` watchdog guards sync timeouts; WorkManager runs optional periodic sync |
| Watch transport | Custom BLE/GFDI/Multi-Link implementation in `:protocol` + `GarminBleClient` |
| Background sync | WorkManager `CoroutineWorker`, battery-not-low + optional charging constraints, 10-minute exponential backoff, process-wide `SyncGate` |
| Networking | OkHttp for the configured OpenAI-compatible endpoint; osmdroid/OpenStreetMap for map tiles |
| Auth | **None.** Local-first, single implicit local profile; no account or server |
| Secrets | Provider keys encrypted with Android Keystore AES-GCM |
| Tests | 55 protocol unit tests and 112 app JVM tests; one instrumented Keystore test |
| CI | GitHub Actions runs protocol tests, app unit/integration tests, and `assembleDebug` |
| Docs | Architecture, protocol, handoff, plan, backlog, providers, tools, privacy, export, background sync, testing, design tokens |

## 2. Module map

```
protocol/  pure Kotlin/JVM, no Android dependencies
  Crc16, Cobs, Gfdi            GFDI framing, COBS, CRC-16, message types
  MultiLink, Mlr              Multi-Link handles and MLR transport
  Handshake, Gdi              handshake, GDI Smart/FileAccess/DataTransfer
  TransportPipe               file transfer pipe (read + write modes)
  FitWorkout                  FIT workout-file encoder
  FitPaceBand                 FIT pace-band (FIT_TYPE_56) encoder
  GfdiFileUpload              legacy chunked upload (CREATE_FILE/…)
  Fit, FitProfile             FIT parser + generated profile (all messages/fields)
  Proto, Hex, Uuids           defensive protobuf, encoding helpers

app/src/main/java/dev/opengarmin/app/
  MainActivity.kt             activity shell: BLE client, sync coordinator, Room
                              collectors, settings/provider/coach wiring
  ble/GarminBleClient.kt      scan/connect/reconnect, MTU, write queue
  sync/                       SyncCoordinator, foreground service, notifications,
                              activity progress, WorkManager worker/scheduler,
                              SyncGate and SyncPolicy
  data/
    AppDatabase.kt            Room entities/DAO/migrations + ActivityRepository
    BackupManager.kt          ZIP export/import of the database and conversations
    ActivityExport.kt         local FIT copy plus GPX/TCX/CSV generation
    WellnessParser.kt         monitoring/sleep/HRV FIT parsing
    WellnessMetrics.kt        daily display metrics and latest-day selection
    WellnessHistory.kt        day/week/month trend buckets
    TrainingMetrics.kt        activity_metrics/user_metrics decoding
    WorkoutPlan.kt            planned-workout model, JSON codec, FIT mapping
    PacePlan.kt               PacePro strategy model, split maths, course profile,
                              JSON codec and FIT_TYPE_56 mapping
  coach/
    CoachController.kt        chat state, streaming, cancel, conversation switching
    ConversationStore.kt      multi-conversation persistence + JSON codec
    ChatLoop.kt               tool-calling loop and SSE assembly
    ChatMarkdown.kt           Markdown rendering blocks
    ChartSpec.kt              renderer-agnostic chart descriptions
    HealthDataSource.kt       read-only profile-scoped data interface
    WorkoutWriter.kt          narrow write path for coach-created plans
    tools/HealthTools.kt      typed coach tools + evidence
  llm/                        OpenAI-compatible client and provider/model store
  settings/PulseSettings.kt   profile values and persisted sync metadata
  ui/
    PulseApp.kt               tab bar and route state
    screens/                  Today, Health, Training, Plan, Activities, Coach,
                              Settings, Provider settings, Trends, Activity detail
    components/               cards, tiles, chips, controls, charts, chat UI
    theme/                    colors, typography, shapes, spacing
```

The `protocol` module remains independently testable with plain JVM tests. The
app keeps most state in `MainActivity` and screen-level Compose state rather than
a separate ViewModel layer; the Coach has its own `CoachController`.

## 3. UI, state, and routing

- One `ComponentActivity` (`MainActivity`) calls `setContent { PulseTheme { PulseApp(...) } }`.
- `MainActivity` owns the BLE client, `SyncCoordinator`, `ActivityRepository`
  flows, `PulseSettings`, `ProviderStore`, `ChatStore`, and `CoachController`.
  Room flows are collected into `mutableStateListOf`/`mutableStateOf` values and
  passed to Compose.
- `PulseApp` renders five tabs (Today, Health, Training, Activities, Coach) and a
  small route model: tabs, Trends, Plan, Activity detail, Settings, Provider
  settings, Data explorer, and Device log. A `BackHandler` implements the back
  stack. The Plan screen has an editor mode for creating and reordering steps.
- Screens live under `ui/screens/`; shared cards, charts, chat bubbles, and
  controls live under `ui/components/`. Styling comes from `ui/theme/` and the
  Pulse spacing/color tokens; DM Sans and Space Grotesk are bundled.
- Settings includes profile values (step goal, max HR), provider management,
  sync/scan/disconnect controls, the device log, FIT import, and the
  **Data & backup** section.
- The Coach screen header exposes **History** and **New conversation**. History
  lists saved conversations with title, last-used time and message count, and
  supports reopening and deleting (with confirmation).
- The activity detail screen shows a map, HR/elevation charts, laps, running
  dynamics, local FIT/GPX/TCX/CSV export, and a FIT data explorer.
- Accessibility is handled at the component level (minimum 44 dp targets, text
  labels on icon buttons); Compose UI tests are still missing.

## 4. Data model and local storage

### Room entities (version 9)

| Table | Purpose | Key columns |
|---|---|---|
| `activities` | One imported FIT activity | `sourceKey` unique, `itemUid`, `startTime`, `sport`, `subSport`, distance/duration, avg/max HR, calories, ascent/descent |
| `records` | Per-sample activity records | `activityId`, timestamp, distance, HR, speed, altitude, cadence, lat/lon, Body Battery |
| `laps` | FIT laps | `activityId`, lap index, duration, distance, HR, speed, calories, ascent/descent |
| `fit_messages` | Every parsed FIT message/field | `activityId`, `messageName`, timestamp, `fieldsJson`, `unitsJson` |
| `wellness_days` | Daily wellness roll-up | unique local `date`, steps, distance, active calories/seconds, HR, stress, SpO2, respiration, Body Battery, sleep, HRV, intensity minutes |
| `wellness_samples` | Intraday wellness samples | `dayId`, timestamp, `kind` (`hr`, `stress`, `spo2`, `respiration`, `body_battery`, `hrv`), value |
| `sleep_stages` | Sleep-stage transitions | `date`, `startTime`, `endTime`, `stage` |
| `imported_wellness_files` | Imported watch file UIDs | `itemUid` primary key, `type`, `importedAt` |
| `planned_workouts` | Locally authored workouts | name, FIT sport/subSport, notes, optional scheduled date, `stepsJson` (timed/distance/open steps + repeat blocks), timestamps, last sent |
| `pace_plans` | Locally authored PacePro strategies | race, distance, goal time, course name/activity id, strategy/hill sliders, alert mode, `splitsJson`, timestamps, last sent |

Migrations 1→9 are hand-written in `AppDatabase`; `exportSchema = false`.
`AppDatabase.SCHEMA_VERSION` is the single version constant used by the database
and backup validation. A new table or column requires a version bump and a
migration test.

### Other local state

| Storage | Contents |
|---|---|
| `pulse_settings` prefs | Step goal, optional max HR, sync time/status/error, last attempt/duration/trigger/result, watch name, periodic sync enabled/interval/charging-only |
| `pulse_provider` prefs | Provider preset/base URL/models and a masked key hint |
| `pulse_secrets` prefs | Base64 IV + AES-GCM ciphertext for the API key; the encryption key stays in AndroidKeyStore |
| `pulse_chat` prefs | Up to 50 coach conversations × 200 messages as JSON, including evidence and chart specs; the legacy single-chat value is migrated on first read |

Provider keys never enter the Room database or conversation JSON.

### Backup and restore

`data/BackupManager.kt` exports a ZIP containing:

- `opengarmin.db` — a consistent snapshot of the Room database. On Android 10+
  the snapshot uses `VACUUM INTO`; older versions checkpoint the WAL and copy the
  database file.
- `chat.json` — all coach conversations.
- `backup.json` — app name, schema version, timestamp.

Import accepts that ZIP or a raw SQLite database. It verifies the SQLite header,
schema version, and the presence of the `activities` table before replacing
anything. `AppDatabase.replaceDatabase` closes Room, keeps a safety copy of the
current file, installs the imported database, and forces Room to open/migrate it
before deleting the safety copy. `MainActivity` cancels its Room flow collectors
during the swap and restarts them afterwards; `ActivityRepository` resolves its
DAO through the current `AppDatabase` instance so the coach's read-only data
source remains valid.

The backup is not encrypted. It contains the full local health database and
chat text but not provider configuration or API keys, so it should still be
stored with the same care as the app's own health data.

### Activity export

`data/ActivityExport.kt` implements the per-activity export used by activity
detail. FIT export copies the decoded watch file named by
`activities.fileName` after checking the FIT header; it never generates a
replacement FIT file. GPX, TCX, and CSV are generated from the `records` and
`laps` rows. GPX keeps only finite, in-range, non-`(0,0)` positions. TCX emits
the stored laps, or one synthetic activity lap when the watch stored none, and
assigns every record to exactly one lap. CSV documents its columns in
[`docs/export.md`](export.md) and uses ISO-8601 UTC timestamps.

Exports run on an IO dispatcher and write to the URI returned by the system
`CreateDocument` picker. No export path uses the network. FIT files imported
through Settings are now copied into app storage under an `import-<timestamp>-`
name so they remain available for later FIT export.

## 5. Ingestion

### Activity ingestion

1. `GarminBleClient` connects over BLE (GFDI v2 / Multi-Link), negotiates MTU,
   subscribes to GFDI notifications, and serializes writes.
2. `SyncCoordinator` performs capabilities/handshake, sends
   `CutWellnessFilesRequest`, pages the FileAccess item list, resolves key-only
   data types, and pulls new `FIT_TYPE_4` activity files (skipping known
   `itemUid`s).
3. Each file arrives over the MLR transport pipe as a zlib stream.
4. `ActivityRepository.importFit` parses with the pure-JVM `Fit.parse`, inserts
   the activity + records + laps + all messages, and dedupes by a
   `start-unix-distance-sport` key.
5. `backfillDerived` re-parses files still on disk to fill laps/coordinates/
   messages for old rows.
6. `SyncForegroundService` (type `connectedDevice`) keeps the process alive and
   hosts the live sync notification while the app is backgrounded.

### Background sync (G3)

When the user enables periodic sync, `SyncScheduler` enqueues one unique
WorkManager `PeriodicWorkRequest` named `watch-periodic-sync` with:

- a 15 min–6 h interval (default 60 min, default disabled);
- `setRequiresBatteryNotLow(true)` and optional `setRequiresCharging(true)`;
- exponential backoff starting at 10 minutes;
- no network constraint (BLE only).

`WatchSyncWorker` is a `CoroutineWorker`. It sets a `connectedDevice`
foreground notification, then runs the same `SyncCoordinator` and repository
import path as the activity. The worker discards protocol logs and bounds the
connect (45 s), GDI session start (45 s), and transfer (6 min) stages.

`SyncGate` is a process-wide owner lease. A visible activity holds `ui` while its
BLE client is connected; the worker can only acquire `background` after the
activity stops, disconnects, and releases the lease. A manual sync sees a
background lease and does not start a competing transfer; the worker sees a UI
lease and skips that period. The activity also registers an
`ACTION_ACL_CONNECTED` receiver to sync when the bonded watch reconnects while
the app is visible.

Stalled sessions are recovered with the same bounded reconnect loop used by the
visible sync. Periodic transient/busy failures return `Result.success()` and
wait for the next interval; returning `Result.retry()` would put the unique
periodic request into WorkManager backoff and delay the next 15-minute period by
up to five hours. The one-time **Run background sync now** request may retry up
to three times with the 10-minute linear backoff. Permanent setup problems
(missing permission, no bonded watch) are recorded in Settings/Today and let the
next periodic interval try again after the user fixes them. Last attempt,
success, duration, trigger, kind, and user-safe error text are persisted in
`PulseSettings`; a SharedPreferences listener refreshes the visible UI when the
worker finishes.

### Workout upload

The Plan editor stores steps as JSON (`planned_workouts`). Sending a workout:

1. `FitWorkout.encode` writes a workout FIT (`file_id` type 5, `workout` 26,
   `workout_step` 27; repeat blocks become a `repeat_until_steps_cmplt` marker
   whose `target_value` is the repetition count).
2. `SyncCoordinator` pauses the normal sync and uses the legacy GFDI chunked
   upload: `CREATE_FILE (5005)` → `UPLOAD_REQUEST (5003)` →
   `FILE_TRANSFER_DATA (5004)` chunks with a running CRC → `SYNC_COMPLETE`.
3. The watch stores the file in its `WORKOUTS` folder and emits
   `ItemAddedNotification { dataType = FIT_TYPE_5 }`. The app marks the local
   workout's `lastSentAt` and resumes the normal activity/wellness sync.
4. A GDI `PushItemDataRequest` fallback remains for watches whose FileAccess
   service accepts `FIT_TYPE_5` writes.

`FIT_TYPE_5` files are deliberately excluded from wellness queuing so the app
does not re-download a workout it just wrote.

### PacePro

Strategy building lives in `PacePlanCalculator` (data/PacePlan.kt). It supports
5K, 10K, half, marathon and custom-distance goals. Standard races use recent-run
Riegel predictions (last 180 days); custom distances scale from the closest
prediction. Optional GPS/elevation records from a recent run provide the course
profile, and two sliders control the plan:

- **Split strategy:** negative/even/positive, ±10 s/km.
- **Hill effort:** easier/balanced/harder; climbs add
  `gain_m * 0.4 * (1 - hill_effort)` seconds.

The raw split targets are scaled so their sum is exactly the goal. The UI has
four steps (Goal, Course, Strategy, Preview), a per-split table and elevation
bars. Accepting a plan saves it to `pace_plans` and sends it to the watch.

Sending uses the same legacy GFDI upload as workouts, with `subType = 56`:

1. `FitPaceBand.encode` writes `file_id` type 56, `pace_band` (310) and one
   `pace_band_split` (311) per split.
2. `CREATE_FILE` → `UPLOAD_REQUEST` → `FILE_TRANSFER_DATA` → `SYNC_COMPLETE`.
3. On success the plan's `lastSentAt` is updated. `FIT_TYPE_56` is excluded
   from wellness queuing.

The live **Preview run** screen uses phone GPS (`LocationManager`) for distance,
current pace, split progress, ahead/behind, finish estimate and manual laps.
It does not invent heart rate: the HR tile stays empty unless the watch exposes
a live HR source.

### Wellness ingestion

`CutWellnessFilesRequest` makes the watch emit monitoring and settings-change
files (FR255: `FIT_TYPE_29/32`, `FIT_TYPE_41`). The app pulls them and parses
`monitoring`, `monitoring_hr_data`, `stress_level` (including Body Battery field
3), `spo2_data`, `respiration_rate`, and `hrv_value`. Any non-activity file of
type `FIT_TYPE_49` (SLEEP_DATA) or `FIT_TYPE_44` (BIOMETRIC) found in the item
list is pulled too, and `sleep_level`, `sleep_assessment`, `hrv_status_summary`,
and Firstbeat `fbt_lha_daily_data` are parsed into the daily row and
`sleep_stages`. Already-imported wellness UIDs are recorded in
`imported_wellness_files` so normal syncs stay incremental.

Monitoring days use the watch's `local_timestamp` wall clock when present (the
UTC `timestamp` is the fallback), all-zero midnight rollover rows are skipped,
and FIT `0xFFFFFFFF` time sentinels decode as missing. If a watch exposes
`hrv_value` samples without an `hrv_status_summary`, their average becomes the
nightly HRV value; existing rows are backfilled from stored samples at startup.

Important limitations:

- A cut contains the **current day's delta**. Multi-day history is only built by
  syncing repeatedly; the app cannot yet request older wellness days. Trends
  aggregates every day that has actually been synced and labels partial history
  instead of zero-filling it.
- Sleep/HRV depend on the watch emitting a SLEEP_DATA/biometric file. The FR255
  used for development did not expose one after daytime syncs; the UI says so
  explicitly instead of estimating sleep from heart rate.
- Floors are not stored.
- Watch battery level is not read. Last-sync time, status, error, and watch name
  are persisted in `PulseSettings`.

### Data coverage vs. the mockups

| Mockup metric | Available today? | Source / gap |
|---|---|---|
| Steps, distance | ✅ (current day; history accumulates) | `wellness_days.steps`, `distanceMeters` |
| Active minutes, active calories | ✅ | `wellness_days` |
| Resting / avg / max HR | ✅ | `wellness_days` + `wellness_samples(kind = hr)` |
| Intraday HR chart | ✅ | `wellness_samples(kind = hr)` |
| Stress, SpO2, respiration | ✅ | `wellness_samples` |
| **Body Battery** | ✅ | `stress_level` field 3 → `wellness_samples(kind = body_battery)` |
| **Sleep duration / stages / score** | ⚠️ | parsed from SLEEP_DATA when the watch exposes it; empty state otherwise |
| **HRV (nightly/status)** | ⚠️ | parsed from `hrv_status_summary`/`hrv_value`/Firstbeat data when present; empty state otherwise |
| **VO₂ max / recovery / training load / LT** | ✅ | `activity_metrics` (140) and `user_metrics` (79) in each activity FIT |
| **Training effect / performance condition** | ✅ | `activity_metrics` fields |
| **Floors** | ❌ | not stored |
| **HR zones** | ✅ | derived from activity records + configured max HR |
| **Step goal / max HR** | ✅ | `PulseSettings`, editable in Settings |
| **Watch battery** | ❌ | not read |
| **"Synced N min ago"** | ✅ | persisted `lastSyncAt` / `lastSyncStatus` |
| Weekly/monthly aggregates | ✅ | `WellnessHistory` over accumulated days, with partial-history labels |
| AI insight cards | ✅ | deterministic summaries from local data; Ask Coach opens the LLM chat |

## 6. Auth, networking, and security

There is no authentication, account, backend, or sync server. All health data is
in the app's local Room database. This is a deliberate product property
("local-first, no Garmin account").

- The coach uses `ToolScope(profileId = "local")`. The tool layer still takes an
  explicit scope and refuses unknown profiles, so a future multi-profile or
  server-backed version does not need to rewrite every query.
- The provider layer uses OkHttp for SSE chat completions and model discovery.
  Keys are stored with Android Keystore AES-GCM and are only read while building
  an HTTP request. The UI receives a masked hint, never the plaintext key.
- Cleartext HTTP is allowed app-wide for local model endpoints, with a visible
  warning. A stricter per-endpoint policy remains a hardening item.
- The provider screen and `docs/privacy.md` explain what is sent to the endpoint.
  No request/response bodies, authorization headers, keys, or chat content are
  logged. The device log is protocol-oriented.
- A generic SQL tool is not implemented. Health reads are typed and read-only;
  the only coach write tool creates a local planned workout.

## 7. Testing and CI

- `:protocol:test` — 55 JVM tests for COBS, CRC, GFDI, GDI, MLR, Multi-Link, FIT,
  the workout encoder and legacy file upload.
- `:app:testDebugUnitTest` — 112 JVM tests covering provider tools, workout-plan
  tool parsing, FIT insights,
  OpenAI streaming/errors, the chat loop, chart specs, Markdown rendering,
  sport categories, wellness parsing/metrics/history, training metrics,
  conversation JSON round-trips, activity export, background-sync policy, and
  single-flight leases.
- `:app:connectedDebugAndroidTest` — `SecretStoreTest` verifies the Keystore
  round-trip without plaintext in preferences.
- `.github/workflows/android.yml` runs protocol tests, app unit/integration
  tests, and `assembleDebug` on pushes and pull requests.
- Not yet automated: Room migration/importer tests, Compose UI/screenshot tests,
  and an instrumented end-to-end backup test. The backup and conversation flows
  were verified manually on a Pixel 10a (Android 17); see `docs/testing.md`.

## 8. Build and commands

```bash
cd /home/naddi/garmin-connect-reverse
source tools/env.sh
./gradlew --no-daemon :protocol:test :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. See
[`HANDOFF.md`](HANDOFF.md) for wireless ADB, watch pairing, and protocol gotchas.

## 9. Remaining gaps

- Historical wellness requests: the watch only exposes the current day's delta,
  so history depends on repeated syncs.
- A richer connected-device state and watch battery/storage are G4.
- Room migration/importer tests and Compose UI tests.
- Stricter cleartext policy for provider endpoints.
- Optional encrypted backups; today's ZIP is unencrypted.
- Other planned data-ownership features remain deferred; the implemented export
  surfaces are the full local database backup and per-activity
  FIT/GPX/TCX/CSV export.
- A prioritized Garmin Connect comparison, missing-feature catalogue, and
  differentiator proposals live in
  [`garmin-connect-gap-analysis.md`](garmin-connect-gap-analysis.md).
