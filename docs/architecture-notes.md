# Architecture notes — Phase 0 discovery

_Last updated: 2026-10-17. Baseline commit: `519964d` (`Add daily wellness: steps, heart rate, stress, SpO2`)._

This document records the stack as it exists **today**, before the UI and LLM
work. It is the reference for the design-system and coach work in
[`BACKLOG.md`](../BACKLOG.md).

## 1. At a glance

| Area | Current choice |
|---|---|
| Platform | Native Android app, single activity |
| Language | Kotlin 1.9.24 (JVM 17) |
| UI | Jetpack Compose + Material 3, Compose BOM `2024.06.00`; no design system, no navigation library, no ViewModel layer |
| Build | Gradle 8.7, AGP 8.5.2, KSP, `compileSdk 34`, `minSdk 28`, `targetSdk 34` |
| Modules | `:protocol` (pure JVM) and `:app` (Android) |
| Persistence | Room 2.6.1, database version 6, hand-written migrations |
| Async | Kotlin coroutines + `lifecycleScope`; a `Handler` watchdog for sync timeouts |
| Watch transport | Custom BLE/GFDI/Multi-Link implementation in `:protocol` + `GarminBleClient` |
| Networking | Only osmdroid map tiles today; no HTTP client or LLM client yet |
| Auth | **None.** Local-first, single implicit local profile; no account, no server |
| Secrets | Nothing stored today; no Keystore/EncryptedSharedPreferences usage |
| Tests | 34 protocol unit tests (`:protocol:test`); **no app unit, integration, or UI tests** |
| CI | **None** in the repo (no `.github/workflows`, no other CI config) |
| Docs | Protocol/handoff/plan docs; no architecture, design, provider, or tool docs yet |

## 2. Module map

```
protocol/  pure Kotlin/JVM, no Android dependencies
  Crc16, Cobs, Gfdi            GFDI framing, COBS, CRC-16, message types
  MultiLink, Mlr              Multi-Link handles and MLR transport
  Handshake, Gdi              handshake, GDI Smart/FileAccess/DataTransfer
  TransportPipe               file transfer pipe
  Fit, FitProfile             FIT parser + generated profile (all messages/fields)
  Proto, Hex, Uuids           defensive protobuf, encoding helpers

app/       Android application
  ble/GarminBleClient.kt      scan/connect/reconnect, MTU, write queue
  sync/SyncCoordinator.kt     capabilities → wellness cut → file list → pull → zlib
  data/AppDatabase.kt         Room entities/DAO/migrations + ActivityRepository
  MainActivity.kt             Activity + all Compose UI (1,456 lines)
```

The `protocol` module is cleanly separated and independently testable. The app
module is not: BLE, sync, import, data access, formatting, charts, and every
screen currently live in `MainActivity.kt`.

## 3. UI, state, and routing

- One `ComponentActivity` (`MainActivity`) calls `setContent { MaterialTheme { … } }`.
- All screens are private composables in the same file:
  `Screen`, `HealthCard`, `HealthDetail`, `ActivityCard`, `ActivityDetail`,
  `DataExplorer`, plus chart helpers.
- State is held in the activity as `mutableStateOf` / `mutableStateListOf`
  (`activities`, `wellnessDays`, `selectedActivity`, `selectedWellnessDay`,
  `dataActivity`, `state`, …) and passed down as parameters. There is no
  ViewModel, repository-flow-to-UI layer, or saved-state handling.
- Routing is **conditional rendering**, not a navigation graph:
  `dataActivity != null` → data explorer; `selectedActivity != null` → activity
  detail; `selectedWellnessDay != null` → health detail; otherwise dashboard.
  No back-stack, no deep links, no tab bar, no `BackHandler`.
- Styling is default Material 3 light theme (`themes.xml` inherits
  `android:Theme.Material.Light.NoActionBar`). There are no custom colors,
  typography, shapes, or spacing tokens.
- The current dashboard is a functional developer-oriented page: a header with
  Sync/Scan/Stop/Disconnect/Log buttons, one health card, an activity list, and
  optional log/device panels. It does **not** resemble the Pulse mockups.
- Accessibility is ad hoc: `OutlinedButton`/`Button` provide semantics, but
  icon-only controls, chart alternatives, focus order, and touch-target sizing
  are not systematically addressed. The mockups' 40 px chips/suggestions are
  below the required 44 px and must be raised in the implementation.

### Implication

The mockups need a real app shell (four tabs + detail routes) and a token-based
component layer. This can be done with Compose without a new UI framework. A
navigation dependency is **not** required for v1: a small sealed route model +
`BackHandler` is enough. We should still split screens out of `MainActivity.kt`
and introduce ViewModels/UI state before adding the coach, otherwise the file
becomes unmaintainable.

## 4. Data model and ingestion

### Room entities (version 6)

| Table | Purpose | Key columns |
|---|---|---|
| `activities` | One imported FIT activity | `sourceKey` unique, `itemUid`, `startTime`, `sport`, `subSport`, distance/duration, avg/max HR, calories, ascent/descent |
| `records` | Per-sample activity records | `activityId`, timestamp, distance, HR, speed, altitude, cadence, lat/lon |
| `laps` | FIT laps | `activityId`, lap index, duration, distance, HR, speed, calories, ascent/descent |
| `fit_messages` | Every parsed FIT message/field | `activityId`, `messageName`, timestamp, `fieldsJson`, `unitsJson` |
| `wellness_days` | Daily wellness roll-up | unique local `date`, steps, distance, active calories/seconds, resting/avg/max HR, stress, SpO2, respiration |
| `wellness_samples` | Intraday wellness samples | `dayId`, timestamp, `kind` (`hr`, `stress`, `spo2`, `respiration`), value |

Migrations 1→6 are hand-written in `AppDatabase`; `exportSchema = false`. Any new
tables (provider configs, conversations, sleep/HRV) require a version bump and a
tested migration.

### Activity ingestion

1. `GarminBleClient` connects over BLE (GFDI v2 / Multi-Link), negotiates MTU,
   subscribes to GFDI notifications, and serializes writes.
2. `SyncCoordinator` performs capabilities/handshake, sends
   `CutWellnessFilesRequest`, obtains the FileAccess item list, resolves
   key-only data types (`Gdi.GdxmlLookup`; the watch sends `gdxml_string` only
   once per type), and pulls new `FIT_TYPE_4` activity files (skipping known
   `itemUid`s).
3. Each file arrives over the MLR transport pipe as a zlib stream.
4. `ActivityRepository.importFit` parses with the pure-JVM `Fit.parse`, inserts
   the activity + records + laps + all messages, and dedupes by a
   `start-unix-distance-sport` key.
5. `backfillDerived` re-parses files still on disk to fill laps/coordinates/
   messages for old rows.
6. `SyncForegroundService` (type `connectedDevice`) keeps the process alive and
   hosts the live sync notification while the app is backgrounded.

### Wellness ingestion

`CutWellnessFilesRequest` makes the watch emit monitoring and settings-change
files (FR255: `FIT_TYPE_29/32`, `FIT_TYPE_41`). The app pulls them and parses
`monitoring`, `monitoring_hr_data`, `stress_level` (including the undocumented
Body Battery field 3), `spo2_data`, `respiration_rate`, and `hrv_value`. Any
non-activity file of type `FIT_TYPE_49` (SLEEP_DATA) or `FIT_TYPE_44`
(BIOMETRIC) found in the item list is pulled too, and `sleep_level`,
`sleep_assessment`, `hrv_status_summary` are parsed into the daily row and the
`sleep_stages` table. Already-imported wellness UIDs are recorded in
`imported_wellness_files` so normal syncs stay incremental.

Monitoring days use the watch's `local_timestamp` wall clock when present (the
UTC `timestamp` is the fallback), all-zero midnight rollover rows are skipped,
and FIT `0xFFFFFFFF` time sentinels decode as missing. This keeps a next-day
rollover from becoming a phantom "tomorrow" row. If a watch exposes `hrv_value`
samples without an `hrv_status_summary`, their average becomes the nightly HRV
value; existing rows are backfilled from stored samples at startup.

The FR255 writes overnight HRV in the proprietary Firstbeat
`fbt_lha_daily_data` message (global 384), field 7
`rmssd_average_sleep` (fixed-point 16.16, scaled by 65536). It is parsed into
the same nightly HRV value, and its `sleep_score` is used when no
`sleep_assessment` message is present.

Important limitations:

- A cut contains the **current day's delta**. Multi-day history is only built by
  syncing repeatedly; the app cannot yet request older wellness days. Trends
  aggregates every day that has actually been synced and labels partial history
  instead of zero-filling it.
- Sleep/HRV depend on the watch emitting a SLEEP_DATA/biometric file. The FR255
  used for development did not have one in its directory after daytime syncs;
  the UI says so explicitly instead of estimating sleep from heart rate.
- Floors are not stored.
- Watch battery level and connection freshness are not tracked. `lastFile` and
  `state` are ephemeral UI state, not persisted sync metadata.

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
| **HRV (nightly/status)** | ⚠️ | parsed from `hrv_status_summary`/`hrv_value` when present; empty state otherwise |
| **VO₂ max / recovery / training load / LT** | ✅ | `activity_metrics` (140) and `user_metrics` (79) in each activity FIT |
| **Training effect / performance condition** | ✅ | `activity_metrics` fields |
| **Floors** | ❌ | not stored |
| **HR zones** | ⚠️ | derivable from `records.heartRate`; needs a max-HR/zone model |
| **Step goal** | ❌ | no goal setting; mockup's 10,000 is a placeholder |
| **Watch battery / "Synced 2 min ago"** | ❌ / ⚠️ | battery not read; last-sync timestamp not persisted |
| Weekly/monthly aggregates | ⚠️ | computable, but Week/Month trends need accumulated wellness history |
| AI insight cards | ⚠️ | deterministic summaries from local data; Ask Coach opens the LLM chat |

## 5. Auth and multi-user implications

There is no authentication, account, backend, or sync server. All health data is
in the app's local Room database. This is a deliberate product property
("local-first, no Garmin account").

Consequences for the LLM work:

- "Scope every tool to the authenticated user" becomes **scope every tool to the
  current local profile**. The tool layer should still take an explicit
  `ToolScope`/`profileId` parameter and refuse requests outside it, so a future
  multi-profile or server-backed version does not need to rewrite every query.
- There is no client/server boundary today. "Never return the API key to the
  client" means the settings state exposes only a masked value and the chat layer
  obtains the key internally when making a request; it is never placed in UI
  state, tool results, model context, or logs.
- A generic SQL tool would be especially dangerous here because the same SQLite
  file holds all local health data. v1 should expose only typed read-only tools;
  a generic query tool stays an optional, separately threat-modeled story.

## 6. Networking and security baseline

- `INTERNET` and `ACCESS_NETWORK_STATE` are already declared (map tiles).
- No OkHttp/Retrofit/Ktor dependency exists. OpenAI-compatible streaming (SSE),
  cancellation, timeouts, and retries need an HTTP client; OkHttp is the
  smallest standard choice. This is a new dependency and needs approval before
  implementation.
- Android blocks cleartext HTTP by default. Ollama/LM Studio presets use
  `http://localhost:…`; a phone cannot reach a desktop's localhost without a LAN
  address or `adb reverse`. Supporting local endpoints will require an explicit
  cleartext policy (preferably limited to loopback/emulator, with an opt-in and a
  warning for LAN HTTP).
- API keys should be encrypted with an Android Keystore AES-GCM key. The
  existing stack has no secret store, so this is new code (not necessarily a new
  dependency). Keys must be write-only from the UI's perspective, masked on
  read, excluded from logs, and never sent to tools or the model.
- A central redacting logger is required: no `Authorization` headers, keys,
  request/response bodies containing health data, or full tool payloads.

## 7. Testing and CI baseline

- `:protocol:test` has Cobs, CRC16, FIT, GDI, GFDI, MLR, and Multi-Link tests
  (34 tests, per the handoff).
- There are **no tests for**: `ActivityRepository` import/dedupe/migrations,
  `SyncCoordinator`, UI/components, formatting, or anything LLM.
- No instrumented tests, no Compose UI tests, no screenshot tests, no lint
  configuration, and no CI workflow.
- Room in-memory tests on the JVM need Robolectric or instrumentation; an
  alternative is to put the health-query logic behind a pure interface and test
  it with fakes, plus a smaller set of instrumented DAO tests.
- For the LLM integration tests, a local mock OpenAI-compatible server (OkHttp
  `MockWebServer`) can exercise SSE streaming, tool calls, HTTP errors, and
  read timeouts without network access.

## 8. Build and commands

```bash
cd /home/naddi/garmin-connect-reverse
source tools/env.sh
./tools/gradle-8.7/bin/gradle --no-daemon :protocol:test :app:assembleDebug

ADB="$PWD/tools/android-sdk/platform-tools/adb -s 192.168.1.162:32999"
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. See
[`HANDOFF.md`](HANDOFF.md) for wireless ADB, watch pairing, and protocol gotchas.

## 9. Handoff review — what is actually left

`docs/HANDOFF.md` is accurate on milestones M1–M7, but its "latest commit" line
is stale (it names `e09220e`; the tree is now at `519964d`, which adds M7 daily
wellness). Its "Immediate next steps" overlap with this task:

| Handoff next step | Relevance to this task | Recommendation |
|---|---|---|
| Accumulate multi-day wellness history / request older days | **High** — Trends Week/Month and coach questions need history | Investigate historical wellness request first; ship Trends with real available days + empty states in the meantime |
| Sleep / HRV | **High** — mockups show both; coach tools list them | Reverse-engineer which files carry them and add ingestion; otherwise show honest "not synced" states |
| Background/periodic sync + connected-device state | **Medium** — needed for "Synced N min ago"/freshness | Add persisted last-sync metadata and WorkManager sync in the screens epic |
| Units/axis labels + saved custom plots in explorer | Low — not part of the four mockups | Defer; keep explorer reachable from activity detail |
| ConnectIQ weather responses | None | Defer/out of scope |
| Room importer/migration tests | **High** — required by quality epic and new tables | Do with the first schema change (provider configs) |

Additional gaps found in Phase 0:

- No step-goal or max-HR/zone configuration, so the mockups' goal ring and zone
  percentages have no truthful input yet.
- No watch battery and no persisted "last synced" time; the mockup header cannot
  be reproduced literally without new data.
- No settings surface at all; provider settings, tool scope, privacy notice, and
  profile values need a home.
- The current 1,456-line `MainActivity.kt` and activity-owned state will not
  scale to four tabs, detail routes, and streaming chat. Split into screens,
  components, and ViewModels as part of Epic A/B, without introducing a new UI
  framework.
