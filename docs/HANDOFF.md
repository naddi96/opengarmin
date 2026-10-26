# Handoff — OpenGarmin offline sync

_Last updated: 2026-10-26. Written so a fresh session can continue without the
original chat context._

## Current state — 2026-10-26

Latest commit: `6aa3954` (`Match Garmin Connect's legacy sync handshake`),
pushed to `origin/main`. Latest tag: `v0.9.2` (release workflow triggered from
the tag). Protocol and app JVM tests pass; `:app:assembleDebug` builds.

### What is new in 0.9.x

- **Forerunner 245 legacy sync.** `app/.../sync/LegacySync.kt` implements the
  pre-GDI GFDI file transfer (`DOWNLOAD_REQUEST 5002`,
  `FILE_TRANSFER_DATA 5004`, `SYNCHRONIZATION 5037`, `DIRECTORY_FILTER 5007`,
  `SUPPORTED_FILE_TYPES 5031`, `FILE_AVAILABLE 5009`, `SET_FILE_FLAGS 5008`).
  Product `3076` starts it directly; other watches with an empty
  `FeatureCapabilitiesResponse` also fall back to it.
- **Official sequence reconstructed from Garmin Connect 5.19.1.** GDI
  `Core.SyncRequest` (empty, `6a020a00`) → watch `5037` →
  `5007` (filter 3) → `5031` (supported file types) → `5002` (directory).
  Garmin parses the directory after a 16-byte header. The app now follows that
  order and logs the directory header.
- **Legacy file types.** Activities (`FIT_TYPE_4`) plus wellness types
  `29/32/44/49/72` are imported and archived on the watch.
- **Sync log export.** Settings → **Device log** → **Export log** writes a
  `.txt` with device info and the retained protocol lines oldest-first. Watch
  MAC addresses are redacted; protocol frames and the unit ID remain.
- **Background-sync reliability.** If Android refuses the foreground service,
  the worker no longer runs headless; it records `BG_FOREGROUND_UNAVAILABLE`
  and returns a busy failure telling the user to open the app. The visible
  sync records `FGS_START_FAILED` if its service cannot start.
- **Protocol fixes.** Phone→watch replies to device-initiated GDI requests use
  `PROTOBUF_RESPONSE (5044)`. Empty capability responses now select the legacy
  path instead of retrying GDI eight times. `SYNC_READY` includes its value
  byte (`[8,0]`).

### Verified / not verified

- Verified: unit tests, debug/release APK builds, v0.9.0 and v0.9.1 GitHub
  release workflows.
- **Not verified on FR245 hardware.** The legacy path has not completed a real
  transfer yet.

### Remaining FR245 gap

The app asks for **uncompressed** data (`DOWNLOAD_REQUEST` flags `0`, handles
`5004`). Garmin Connect asks for compression (`flags 1`) and also handles
`COMPRESSED_FILE_DATA (5054)` via its `CompressedDataProcessor`. If the next
FR245 log shows `5054`, implement that path; otherwise keep `5004`.

### How to continue

1. Install the v0.9.2 release APK (or `app/build/outputs/apk/debug/app-debug.apk`).
2. Sync with the app **open**.
3. Check **Settings → Device log** for:
   - `GDI → SyncRequest`
   - `LEGACY → DirectoryFilter only-new`
   - `LEGACY → SupportedFileTypesRequest`
   - `LEGACY → DownloadRequest directory`
   - `LEGACY: directory received … header=…`
4. Export the log and use the first `LEGACY:` line that does not advance to
   locate the next protocol gap.
5. If background sync reports “Android blocked the background sync”, open the
   app or set OpenGarmin’s battery usage to **Unrestricted**.

Key files: `app/.../sync/LegacySync.kt`, `SyncCoordinator.kt`,
`GarminBleClient.kt`, `SyncLogExport.kt`, `protocol/.../Gdi.kt`,
`protocol/.../Handshake.kt`, `docs/PROTOCOL.md` §7.1.

## Goal

Build an offline, local-first Android companion for a **Garmin Forerunner 255**:
sync data directly over BLE, store it on-device, no Garmin account. The Garmin
Connect APK in this repo is the reverse-engineering target.

Decisions already made with the user:

- Target: Forerunner 245/255/955/965 → GFDI v2 / Multi-Link.
- Android app, built from scratch (not a Gadgetbridge fork).
- v1 scope: sync watch → local storage + dashboard; no cloud.
- Repo: <https://github.com/naddi96/opengarmin>, branch `main`.
- License: AGPL-3.0 (see `docs/LICENSING.md`).

## Repo state

Latest feature commit: `d071f3a` (pushed to `origin/main`; documentation
updates follow). Recent history:

```
d071f3a Add database backup/restore and coach conversation history
44e5d14 Fix wellness date rollover and HRV, add history ranges
d2ef301 Render coach Markdown and add inline health charts
ba17957 Add OpenGarmin launcher icon
389fd19 Add Health and Training tabs with watch-only data
39b80ad Add foreground sync notification with import progress
e09220e Parse device-initiated GDI over 5043; harden protobuf reader
```

The tree is clean. `captures/`, `apk/`, `tools/` binary downloads are gitignored.

## Environment (this machine)

- Repo: `/home/naddi/garmin-connect-reverse`
- Toolchain (user-space, no sudo): `tools/jdk` (Temurin 21), `tools/gradle-8.7`,
  `tools/android-sdk`, `tools/jadx`, `tools/apktool.jar`, `tools/venv` (python
  protobuf). `tools/env.sh` exports `JAVA_HOME`/`PATH`.
- APK: `apk/base.apk` (extracted from the `.apkm`), smali in
  `apk/apktool_out/`, jadx output in `apk/jadx8/` + `apk/jadx8bad/`.
- Phone: **Pixel 10a, Android 17**. Watch: **Forerunner 255** (bonded).
- ADB is **wireless**; the mDNS serial changes when pairing is renewed. Run
  `tools/android-sdk/platform-tools/adb devices -l` and use the current serial,
  e.g. `ADB="$PWD/tools/android-sdk/platform-tools/adb -s <serial>"`. A previous
  pairing used `192.168.1.162:32999`.
  Packed app id: `dev.opengarmin.app` (debuggable → `run-as` works).
- Garmin Connect `com.garmin.android.apps.connectmobile` (5.30.1) is installed
  and **signed in**; it is force-stopped before each test. It auto-restarts via
  the companion-device service, so force-stop right before starting our app.

## Verified milestone status

| Milestone | State |
|---|---|
| M1 BLE connect + GFDI register | ✅ |
| M2 handshake (device info / config / time) | ✅ |
| M3 file directory | ✅ 100 items listed (3697-byte response, 8 chunks) |
| M4 file download + decode | ✅ **99,357 compressed bytes → 198,627 bytes** (zlib, valid FIT: `.FIT`, profile 21184) |
| M5 FIT parser | ✅ pure-JVM parser: all 7,931 messages / every field, plus typed session/lap/record |
| M6 Room → dashboard | ✅ activities + records + laps + all FIT messages; OSM track map, HR/elevation charts, lap table, data explorer with cross-plots |
| M7 daily wellness | ✅ steps, distance, active calories/time, resting HR, HR/stress/SpO2/respiration samples, Body Battery, sleep/HRV paths; Health card + daily detail |
| M8 Pulse UI + coach | ✅ five tabs, settings, provider/model management, streaming tool-calling coach, evidence, charts, multi-conversation history |
| M9 local data ownership | ✅ database + conversation ZIP backup/restore, schema validation, safe reopen |

The M4 download was proven end-to-end earlier: `PullItemDataRequest` →
`transfer_handle` → MLR transport pipe on service `0x2018` → zlib stream →
decoded file. See `docs/PROTOCOL.md` §10.5.

## Protocol knowledge (all in `docs/`)

- `docs/PROTOCOL.md` — BLE UUIDs, Multi-Link, MLR, GFDI framing, handshake,
  GDI transport, FileAccess, transport pipe, verified test vectors.
- `docs/protos/` — **141 complete GDI protobuf schemas** extracted from the APK
  (`GDIFileAccess`, `GDICore`, `GDIDataTypes`, `GDIDataTransfer`, extensions…).
- `docs/samples/fr255_file_list.txt` — a real early directory capture (78
  items; later FR255 directories have held ~200 entries across pages).
- Key facts:
  - GFDI message = `[size:2][type:2][payload][crc:2]`, LE, CRC-16 Dallas/Maxim;
    then COBS.
  - Gate: `size/type/payload/crc`; type high bit = seq form.
  - GDI frame = `[u16 id][u32 offset][u32 total][u32 chunk][Smart]`.
  - Smart extension fields: `file_access_service=43`, `core_service=13`,
    `data_transfer_service=7`.
  - Reliable register response: `[status][handleByte][reliable][mlService]`;
    real MLR handle = `handleByte & 0x07`, `0x80` = reliable flag.
  - File bytes: MLR transport pipe, configure `[00][dir][handle:u32 LE]` →
    `[00][status][configStatus]`, then a **zlib** stream; read until the device
    closes the pipe service.

## Code map

```
protocol/src/main/kotlin/dev/opengarmin/protocol/
  Crc16, Cobs, Gfdi (framing + MessageType incl. 5057), Handshake,
  MlControl/GarminSession (session, recovery, MLR GFDI, queues),
  Mlr (MLR codec + receiver), Proto (defensive protobuf reader/writer),
  Gdi (Smart, FileAccess, GdiClient handling 5043/5044/5057 + per-chunk ACKs),
  Fit (FIT activity parser incl. every message/field), FitProfile (generated
  FIT SDK message/field names, units, scales, offsets), TransportPipe (MLR
  pipe), Uuids, Hex

app/src/main/java/dev/opengarmin/app/
  ble/GarminBleClient.kt   scan/connect/reconnect, MTU, pair selection,
                           dedicated GFDI subscribe, serialized write queue
  sync/SyncCoordinator.kt  capabilities → connection-ready → device requests →
                           list → pull → pipe → zlib → file
  data/AppDatabase.kt      Room entities/DAO/database (v7: activities, records,
                           laps, fit_messages, wellness_days, wellness_samples,
                           sleep_stages, imported_wellness_files)
                           + ActivityRepository (FIT/wellness import, UID dedupe,
                           epoch conversion, lap/coordinate/message backfill)
  data/BackupManager.kt    full database + conversation ZIP export/restore
  coach/                   coach controller, conversation store, chat loop,
                           tools, charts, Markdown, health data source
  llm/                     OpenAI-compatible client + encrypted provider store
  settings/                profile values and persisted sync metadata
  ui/                      PulseApp routes, screens, components, theme
  MainActivity.kt          activity shell: BLE/sync, Room collectors, coach and
                           settings wiring, watchdog/reconnect

docs/                      PROTOCOL, PLAN, LICENSING, architecture, providers,
                           tools, privacy, testing, samples, protos
tools/                     parse_btsnoop.py, extract_protos.py, setup scripts
```

Tests: **55 protocol tests** (`:protocol:test`) and **112 app JVM tests**
(`:app:testDebugUnitTest`) pass, plus the instrumented `SecretStoreTest`. Build:
`:app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## The current blocker / latest discovery (RESOLVED)

After the watch reboot, **phone-initiated** syncs stopped producing any GDI
messages. The root cause was three protocol details:

1. The watch sends its device-initiated GDI messages (including
   `ConnectionReadyNotification`) over **GFDI 5043**, not 5044. We used to
   discard 5043 as a cancellation notice.
2. The watch's startup burst contains **requests**, not just notifications:
   SMS canned list, AudioPrompts set-language, Core location settings, Core
   get-location, plus ConnectIQ HTTP weather requests. They must be answered
   over 5044 (echoing the GDI id). Otherwise the watch retransmits the burst
   every 5 s and never serves the file list.
3. **Every GDI frame must be GFDI-ACKed per chunk**, not only when the whole
   `Smart` message is reassembled. The ACK payload is
   `[id:u16][offset:u32][ok:u8][code:u8]`. ACKing only the final chunk made
   the watch send 487 bytes of the item list and restart every 5 s.

With those fixed, a full sync completed on 2026-10-17 18:20:
`capabilities → ItemListResponse (100 items, 3697 B, 8 chunks) → PullItemData
→ transport pipe → 99,357 compressed B → 198,627 B FIT`.

A cold start (app data cleared, no cached FIT) was re-verified afterwards:
fresh Room database (v6 at that time; v7 now), 1 activity, 2,488 records, 6 laps, 7,931 stored FIT
messages, GPS track and daily wellness, dashboard rendered. A second connect
with the same item UID skips the PULL and reports `SYNC up to date`.

## Daily wellness (M7)

Daily steps/heart rate are not in the FileAccess directory. The flow is:

1. Send `GarminConnect.CutWellnessFilesRequest` (Smart field 47 → Service
   field 3, priority `USER_INITIATED`) after the capability exchange.
   `FeatureCapabilitiesRequest` must advertise the GarminConnect capability
   (Core field 11: version 2, wellness support).
2. The watch replies `CutWellnessFilesResponse` (status 0), then sends
   `FileAccess.ItemAddedNotification` (FileAccess field 12) for two small
   files: `FIT_TYPE_32` (monitoring_b, ~0.9 KB) and `FIT_TYPE_41` (~287 B).
3. Pull `FIT_TYPE_32`. It contains daily `monitoring` totals,
   `monitoring_hr_data` (resting HR), `stress_level`, `spo2_data` and
   `respiration_rate`.
4. Parse it into `wellness_days` + `wellness_samples`. Steps are stored in the
   `monitoring.cycles_16` field: **steps = 2 × cycles_16**. HR samples use
   `timestamp_16` (16-bit truncated seconds) resolved against the last full
   timestamp.
5. Re-cutting the same day updates the totals. Samples are merged by
   `(day, kind, timestamp)` so repeated cuts accumulate history.

The watch closes GFDI after each file and can take ~30 s to close the second
transport pipe. The coordinator re-queues the in-flight wellness item on GFDI
re-registration, and the watchdog reschedules to 60 s while a transfer is
active (normal timeout 15 s).

Wellness UI: the home screen shows a Today card (steps, distance, calories,
active time, resting/avg/max HR, stress); tapping it opens a daily detail with
a stats grid and HR/stress charts.

## Immediate next steps

1. Accumulate wellness history: current cuts only contain the current day's
   delta. Sync periodically (or learn how to request older wellness days) to
   build a multi-day steps/HR history.
2. Add units/axis labels and saved custom plots to the data explorer.
3. Background/periodic sync and a proper connected-device state.
4. Consider answering ConnectIQ HTTP requests with real network data (weather)
   instead of the offline timeout response.
5. Unit tests for the Room importer/migrations (dedupe, epoch conversion,
   lap/coordinate/message/wellness backfill).

## Useful commands

```bash
cd /home/naddi/garmin-connect-reverse
source tools/env.sh
./tools/gradle-8.7/bin/gradle --no-daemon :protocol:test :app:assembleDebug

ADB="$PWD/tools/android-sdk/platform-tools/adb -s <wireless-serial>"
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell pm grant dev.opengarmin.app android.permission.BLUETOOTH_SCAN
$ADB shell pm grant dev.opengarmin.app android.permission.BLUETOOTH_CONNECT
$ADB shell am force-stop com.garmin.android.apps.connectmobile
$ADB shell am force-stop dev.opengarmin.app
$ADB logcat -c
$ADB shell am start -n dev.opengarmin.app/.MainActivity
$ADB logcat -d -s OpenGarmin:D
```

If wireless ADB drops: re-enable Wireless debugging and `adb connect` the new
pairing port (pairing needs the 6-digit code).

## Gotchas learned

- Garmin Connect auto-restarts; force-stop it immediately before our app.
- The watch closes the GFDI handle around the handshake; the session cycles
  close-all/re-register. Recovery is delayed to avoid `ALREADY_IN_USE`.
- BLE writes must be serialized (GATT returns 201/busy otherwise).
- The watch starts a GDI session itself shortly after the GFDI handshake and
  broadcasts `ConnectionReadyNotification`; the phone can then pull without a
  watch-side button press.
- GDI frames are ACKed per chunk (`[id][offset][ok][code]`), not per message.
- Store `ItemReference.uid` with the activity and skip the PULL when it is
  already in Room; otherwise every connect re-downloads the same FIT. The
  `imported_activity_files` ledger records every UID pulled (not just the one on
  the activity row) so a watch re-index or a different watch cannot make an
  existing activity download again.
- Room DB is version 10: `itemUid` (1→2), `laps` (2→3), record
  `latitude`/`longitude` (3→4), `fit_messages` (4→5), wellness tables (5→6),
  sleep/HRV/Body Battery columns + `sleep_stages` +
  `imported_wellness_files` (6→7), `planned_workouts` (7→8), `pace_plans`
  (8→9), and `imported_activity_files` (9→10) migrations live in `AppDatabase`.
- Daily wellness comes from `CutWellnessFilesRequest` → `ItemAddedNotification`
  (FIT_TYPE_32). Use `cycles_16 * 2` for steps and resolve `timestamp_16`
  against the last full timestamp. The watch may ignore a re-cut within its
  cooldown (response status 1); previous samples stay stored.
- `Fit.parse` retains every message/field (7,931 messages for the sample
  activity, ~18 message types). `FitProfile.kt` is generated from the public
  FIT SDK profile (via `fitdecode`); unknown messages/fields fall back to
  `msg_<n>` / `field_<n>`.
- The data explorer stores field values as scaled JSON objects per message.
  Arrays are stored as comma-separated strings and are not plotted.
- The track map uses osmdroid (`org.osmdroid:osmdroid-android`) with the standard
  free anonymous OpenStreetMap rasters, **muted locally**: `TilesOverlay.
  setColorFilter` applies a grayscale colour matrix (22 % saturation, 0.93
  grey-channel scale) to the tile layer only, so the street network stays
  plainly visible while POI colours and land-cover noise step back; the
  speed-coloured trace is drawn above the filter at full saturation. No API
  key or account is involved; the only obligation is attribution, shown under
  the map and in Settings. With no network the track still draws (white
  casing keeps it legible) over the neutral paper-coloured surface.
- `ItemReference.data_size` is **not** the compressed stream length; read until
  the pipe service closes, then zlib-decompress.
- Full HCI capture is unavailable (no root; bugreport `btsnooz` truncates to
  15 bytes/packet). Garmin Connect's own logs are private. A rooted device or a
  BLE sniffer is the fallback if the 5043 path stalls.

## 2026-10-17 — Pulse UI + AI coach pass

Added after the M7 handoff above:

- Compose design system and four-tab UI (Today / Trends / Activities / Coach),
  activity detail, settings, provider settings, device log. Existing sync and
  data explorer are preserved.
- Persisted sync metadata and profile settings (step goal, max HR). Sync/scan/
  disconnect controls moved out of the Today content into settings.
- OpenAI-compatible provider layer: presets (OpenAI, DeepSeek, OpenRouter,
  Ollama, LM Studio, vLLM, Custom), encrypted keys (Android Keystore AES-GCM),
  model CRUD/default/params, test connection, BuildConfig env fallback, SSE
  streaming with cancel/timeouts/retries.
- Typed read-only coach tools with local-profile scope, caps, evidence labels,
  snapshot fallback, and medical-safety prompt. Chat is persisted locally.
- 25 app JVM tests at the time (72 now) on top of the protocol tests; CI
  workflow added. See `docs/testing.md`, `docs/providers.md`, `docs/tools.md`,
  `docs/privacy.md`, and `BACKLOG.md`.

Still open: historical wellness ingestion (today only), WorkManager background
sync, Room migration/importer tests, Compose visual tests, and the strict
per-endpoint cleartext policy.

### Activity sync fix (2026-10-17)

The watch has one transfer slot. A file list arriving during a wellness
transfer made the coordinator request the activity pull immediately; the watch
returned `NO_AVAILABLE_RESOURCES` (3), and the old code never retried. It also
cleared `pulledWellnessType` while a wellness pipe was open, so the wellness
file was sometimes imported as an activity and the real activity was dropped.

Fixed in `SyncCoordinator`:

- queue activity pulls and defer them while a wellness transfer/pipe is active;
- preserve the in-flight wellness type until its pipe completes;
- retry `NO_AVAILABLE_RESOURCES` with backoff, and retry an unanswered pull
  after 8 s;
- prefer queued activities once the GDI session is ready after a pipe closes;
- pull all new `FIT_TYPE_4` activities sequentially, then process wellness;
- never fall back to an arbitrary `FIT_TYPE_*` as an "activity" (that could
  import a wellness file under the wrong path).

Verified on the FR255: 198,627-byte FIT downloaded and imported (`5.50 km
Running`), then a second sync reported activities up to date while wellness
finished.

A later pass found why a watch with many activities only imported one: the item
list sends `gdxml_string` on the first item of each type and only a numeric
`gdxml_string_key` on later items. Filtering on `gdxmlString == "FIT_TYPE_4"`
therefore matched just the typed item. `Gdi.GdxmlLookup` now learns the
key/string pairs (matching Garmin Connect's own client) and resolves every item
before classification.

Multi-activity syncs now expose live progress. `SyncCoordinator` reports
`ActivitySyncProgress` (done, total, current file size); Today shows the current
download and last imported activity, the status pill reads `Syncing N/M`, and
Settings repeats the same details. This also keeps the badge from saying
"Synced just now" between the 30-second per-file transfers.

A `connectedDevice` foreground service (`SyncForegroundService`) hosts one
ongoing low-importance notification: activity N/M, file size, progress bar, and
the last imported activity. Completion/failure replaces it with a separate
dismissible summary notification (id 1002) so stopping the foreground service
does not remove the final status. `POST_NOTIFICATIONS` is requested on Android
13+, and the notification uses private visibility so activity details stay off
the lock screen by default.

### Coach workout details (2026-10-17)

`get_activity` now exposes the GPS and running-dynamics fields that were already
present in `fit_messages`:

- GPS: point count, start/end, bounds, and an optional 60-point sampled route.
- Running dynamics: cadence (steps/min + raw), ground contact/stance time,
  vertical oscillation/ratio, stride length, power, training effect, anaerobic
  TE, load peak, moving/standing time, average/max speed, respiration, stress,
  SpO2; session values with record-average fallback.
- Laps include cadence/ground-contact/vertical-oscillation/stride when present.

The Activity detail screen also shows a running-dynamics card and GPS summary.
Verified on the imported FR255 run: 2,488 GPS points, 135 spm cadence, 314 ms
ground contact, 94.1 mm vertical oscillation, 9.6% vertical ratio, 0.98 m
stride, 240 W, training effect 2.5.

Also fixed in the tool loop: the model was receiving `ToolResult(json=…,
evidence=[…]).toString()` instead of the JSON payload. It now receives the pure
compact JSON inside `<untrusted_data>`, with evidence kept separately. This was
the main reason a capable model could still behave as if the data were absent.

### Health + Training tabs (2026-10-17)

The bottom bar now matches the five-tab mockups: Today, Health, Training,
Activities, Coach. The old Trends screen moved behind the Health header so the
bar stays at five 44 dp targets.

Data audit and ingestion changes:

- **Body Battery** was present but ignored: `stress_level` field 3 carries the
  current level. It is now imported as `wellness_samples(kind = body_battery)`,
  and daily start/min/max/latest are stored on `wellness_days`. Existing
  wellness `.dec` files are re-imported once (`imported_wellness_files`) so the
  upgrade backfills the current day without another download.
- **Activity Body Battery**: record field 143 is now decoded into
  `records.bodyBattery` for new activity imports (not yet displayed).
- **Training metrics** come from two Garmin-proprietary FIT messages that were
  already stored as `msg_140`/`msg_79`: `activity_metrics` (VO₂ max, recovery
  time, aerobic/anaerobic TE, performance condition, primary benefit, training
  load, final Body Battery) and `user_metrics` (VO₂ max, LTHR, LT speed,
  recovery snapshot, profile values). `GarminProfile` names/scales these fields;
  `TrainingMetrics` supports both the new names and legacy `field_<n>` JSON.
- **Sleep and HRV**: `SLEEP_DATA` (`FIT_TYPE_49`) and biometric
  (`FIT_TYPE_44`) files are now pulled from the directory/notifications and
  parsed (`sleep_level`, `sleep_assessment`, `hrv_status_summary`,
  `hrv_value`). Sleep stages are stored in `sleep_stages`. The development
  FR255 did not expose a sleep file during daytime syncs, so both cards show an
  explicit "not synced yet" state; nothing is inferred from heart rate.
- **Training readiness** is intentionally not scored: readiness needs sleep and
  HRV, and Garmin's model is not in the FIT file. The card shows the real
  factors (recovery, acute load, sleep, HRV, stress, VO₂ trend) and says why a
  score is unavailable. Load focus is labelled as derived from the watch's
  training-effect category, not Garmin's proprietary load-focus model.

Database version 7 added the sleep/HRV/Body Battery columns, `sleep_stages`, and
`imported_wellness_files`; version 8 added `planned_workouts`; version 9 added
`pace_plans`. Unit tests cover the wellness parser (monitoring, Body Battery,
cross-midnight sleep, HRV) and both legacy/new training-metric encodings.

### Activity category fix (2026-10-17)

Garmin's "Cardio" activity is recorded as FIT sport 10 (training) + subSport
26 (cardio_training). The old category map treated every sport-10 session as
strength, so 46 cardio workouts on the device appeared under the wrong filter.
`categoryForSport(sport, subSport)` now maps cardio explicitly, keeps
strength_training (10/20) and HIIT/fitness-equipment as Strength, and puts every
unmapped sport in an explicit Other filter. The Activities tab derives its chips
from `SportCategory.entries`, so Run, Ride, Cardio, Strength, Walk and Other are
all reachable. Verified against the watch directory: 92 `FIT_TYPE_4` items and
92 activities in Room, all with item UIDs. Cardio filter shows the 46 sessions
with their HR traces and zone bars.

### Sync stall fix (2026-10-17)

A failed sync was traced to the watch's single GDI session being held by another
client. `dumpsys bluetooth_manager` showed three simultaneous
`dev.opengarmin.app` GATT clients (left over from reconnect attempts) plus the
official Garmin Connect client. Capability exchange never completed, so the app
retried five times and failed.

Fixes:

- `GarminBleClient.connect()` now closes the previous GATT client, clears the
  write queue and resets the session before opening a new connection; callbacks
  from a replaced client are ignored. A short delay is left before reconnecting.
- Retries increased to eight attempts.
- When the watch connects but never answers the capability exchange, the app now
  says so explicitly: "Garmin Connect can hold that single session — close or
  force-stop it and try again." The message is persisted as `lastSyncError` and
  shown both in Settings and in a "Sync needs attention" card on Today with a
  Try again button.
- Verified: after force-stopping Garmin Connect and restarting the app, the
  capability exchange, wellness cut, file list and Body Battery import complete;
  `dumpsys` now reports one `dev.opengarmin.app` GATT client instead of three.

### Activity directory pagination (2026-10-17)

The FR255 returns at most 100 `ItemListResponse` entries per request and sets
`next_transaction_id = 0`. Two recently recorded activities were therefore
missing: the directory actually held 200 entries, and the first 100 only
contained activities through 11 October.

`SyncCoordinator` now pages the directory using the highest `transaction_id`
seen as the cursor (`ItemListRequest.transaction_id`) until a page returns fewer
than 100 entries or the cursor stops advancing. Pages are merged by UID, then
the combined list is classified once. A page cap of 20 prevents a pathological
loop. The activities-only filter was left unused because the watch answers
`FAIL_INVALID_DATA_TYPE` (status 6) for `included_data_types` filters.

Verified on the watch: 200 directory entries across two pages; the second page
contained an extra `FIT_TYPE_4` activity (15 October) that was absent from the
first page. It was pulled and imported. Historical `FIT_TYPE_32` files are **not**
queued from the directory (only the current cut is pulled) so pagination does
not trigger dozens of wellness transfers.

### Manual FIT import (2026-10-17)

The watch can retain an activity summary after its FIT export file has been
removed (for example after an upload). To recover an activity that the watch no
longer exposes in its directory, Settings now has **Import FIT file**. It uses
the Android document picker, reads the selected file locally, and calls the
normal `ActivityRepository.importFit` path (dedupe by start time/distance/sport).
No Garmin account, API, or network request is involved. The imported activity
appears in Activities and the Cardio/Strength/etc. filters like a synced one.

### Watch reboot exposes missing activity files (2026-10-17)

After the phone/directory pagination fixes, three recorded workouts (Mon 12 Oct,
Thu 15 Oct run, Fri 16 Oct cardio) were still absent from the GDI directory even
though the watch history showed them. A **watch reboot** (not a phone reboot)
rebuilt the watch's file index: the next sync returned 102 activity entries and
27 files that had not been visible before. The app imported all three missing
workouts (Thu 15 Oct: 7.72 km Running; Fri 16 Oct and Mon 12 Oct: Cardio
sessions). The directory is incremental after that, so the pagination cursor
plus a routine watch reboot is the reliable recovery path when history and the
FIT directory disagree.

### Local backup/restore + coach conversation history (2026-10-18)

Settings now has a **Data & backup** section:

- **Export backup** writes a ZIP to a user-selected document-provider location.
  It contains `opengarmin.db` (a consistent Room snapshot produced with
  `VACUUM INTO` on Android 10+, or a WAL checkpoint + file copy on Android 9),
  `chat.json` with all coach conversations, and `backup.json` with the app name,
  schema version and timestamp.
- **Import backup** accepts that ZIP or a raw SQLite database. It validates the
  SQLite header, `user_version` (rejecting newer schemas), and the presence of
  the `activities` table. `AppDatabase.replaceDatabase` closes Room, keeps a
  safety copy of the live database, installs and force-opens the imported file
  so migrations and Room's identity check run, then deletes the safety copy. If
  anything fails, the previous database is restored.
- `MainActivity` cancels the Room flow collectors during the swap and restarts
  them afterwards. `ActivityRepository` now resolves its DAO through the current
  `AppDatabase` instance, so `RepositoryHealthDataSource`, `ToolRegistry` and the
  active `CoachController` remain usable across the reopen.
- A background busy flag disables both backup buttons while an export/import is
  running. Restore is refused while a sync/import is active.

Coach chat persistence moved from one `pulse_chat` JSON array to a
multi-conversation store (`ConversationStore.kt`):

- Up to 50 conversations × 200 messages, including role, text, status, evidence
  labels and chart specs.
- The legacy single conversation is migrated on first read.
- The Coach header has **History** and **New conversation**. History shows the
  title (derived from the first user message), last-used time and message count;
  tapping reopens it, and delete is confirmed. The most recent conversation is
  reopened on app start.
- Stale streaming turns are ignored through a generation counter, so switching
  conversations or restoring a backup cannot let an old response overwrite the
  new active conversation.

Verified on a **Pixel 10a / Android 17** against a Forerunner 255 on 2026-10-18:

- Exported a 42.7 MB ZIP to Downloads; `unzip -l` showed the ~364 MB database,
  `chat.json` and `backup.json`. Importing the same ZIP restored successfully,
  reloaded the Room flows, and preserved two conversations (one migrated from
  the old format, one new).
- Opened the older conversation from History, confirmed it continued normally,
  deleted the temporary test conversation with confirmation, and verified the
  remaining conversation survived a force-stop/relaunch.
- No app exceptions appeared in `logcat`.

New tests: `ConversationCodecTest` (round-trip, title inference, streaming →
cancelled recovery, invalid JSON). Backup file swapping and Room reopen still
need an instrumented test; see `docs/testing.md`.

## 2026-10-19 — Workout planner + direct watch upload

Added a five-step workout builder under **Training → Workout plan**:
timed, distance and open steps; repeat blocks; sport and schedule metadata;
local persistence in `planned_workouts` (Room v8); send-to-watch status.

Reverse-engineering result: the Forerunner 255 **rejects** the GDI
`FileAccess.PushItemDataRequest` path for `FIT_TYPE_5` with
`INVALID_PARAMS (5)`. The firmware still supports the **legacy GFDI chunked
upload** (the path Gadgetbridge uses):

1. `CREATE_FILE (5005)` with `dataType=128`, `subType=5` (FIT/Workouts).
2. `UPLOAD_REQUEST (5003)` for the assigned index.
3. `FILE_TRANSFER_DATA (5004)` chunks with a running CRC-16; each response
   carries the next accepted offset.
4. `SYSTEM_EVENT (5030)` `SYNC_COMPLETE`.

Verified on the FR255: a 367-byte single-step FIT and a 769-byte FIT with a
4× repeat block were both accepted. The watch immediately sent
`ItemAddedNotification { dataType = FIT_TYPE_5, data_size = … }`, and the
file could be pulled back through the normal GDI read path. The new
`FIT_TYPE_5` item is deliberately not pulled as wellness data.

Workout FIT encoding lives in `protocol/FitWorkout.kt`; upload state is in
`SyncCoordinator` (`startLegacyWorkout` / `handleLegacyUploadResponse`).
`FitWorkoutTest`, `GfdiFileUploadTest` and `WorkoutPlanCodecTest` cover the
encoder, message layouts and plan JSON.

### Coach-created workouts (2026-10-19)

The coach can now author a workout and save it to the Plan tab through
`create_workout` (and inspect existing plans with `list_planned_workouts`).
The tool parses an ordered step array (time, distance, open, repeat blocks),
validates role/sport/duration/date, and writes a `planned_workouts` row via
`RepositoryWorkoutWriter`. It deliberately has no BLE access: the model is
instructed to say the workout is saved locally and must not claim it was sent
to the watch. The user still reviews and taps **Send to watch** in the Plan
screen.

System-prompt guardrails: only create when the user explicitly asks, ask a
short clarification when the request is ambiguous, and never invent a
completed/uploaded status. Tests cover step parsing, repeats, nested-repeat
and past-date rejection, listing, and the existing read-only tools.

Verified end-to-end on the Pixel 10a with the configured DeepSeek provider: the
coach accepted an explicit "create an easy 20 minute run" request, called
`create_workout`, and two test drafts appeared in Training → Workout plan with
the correct 5/10/5-minute structure and tomorrow's date. The answer correctly
said the workout was saved only on the phone and told the user to tap **Send to
watch**; it did not claim a watch upload. The test drafts were deleted after
verification.

## 2026-10-19 — PacePro strategy + live preview

Implemented the two new mockups (`PaceStrategy.dc.html`, `PaceRun.dc.html`).

Reverse-engineering result: Garmin Connect's file-transfer enum maps
`FIT_TYPE_56` to `PACE_BAND`. Its bundled FIT profile defines `pace_band`
(310) and `pace_band_split` (311). The watch accepts the same legacy GFDI
upload used for workouts if `CREATE_FILE.subType` is `56` instead of `5`.
A 10-split 10K plan (882 bytes) was accepted on the FR255, and `logcat` showed
the full `CREATE_FILE` → `UPLOAD_REQUEST` → `FILE_TRANSFER_DATA` →
`SYNC_COMPLETE` sequence. The watch answers `ItemListRequest` filtered to
`FIT_TYPE_56` with `FAIL_INVALID_DATA_TYPE (6)`, so the file cannot be listed
back; this is documented in `docs/PROTOCOL.md` §10.7.

Implementation:

- `protocol/FitPaceBand.kt` encoder, checked by `FitPaceBandTest`.
- `data/PacePlan.kt`: Room entity (v9), split JSON codec, Riegel predictions,
  course profile from activity records, split maths.
- `ui/screens/PaceStrategyScreen.kt`: four-step builder, sliders, preview table,
  elevation bars, saved plans, accept/send status.
- `ui/screens/PaceRunScreen.kt`: phone-GPS live run screen with pause/lap/stop,
  split strip, overall/finish estimate and elevation. It deliberately leaves HR
  empty without a live source.
- `SyncCoordinator` now carries a FIT subtype on the upload queue and excludes
  `FIT_TYPE_56` from wellness pulls.
- Training shows race predictions and a PacePro card.

Manual check on the Pixel 10a: opened Training → PacePro, confirmed race
predictions (10K 47:45) came from the imported run, sent the plan, and observed
the watch accept all chunks. The preview run screen and GPS permission flow
still need a real outdoor run check.

## 2026-10-19 — Epic G / G5 activity export

Activity detail now exports one activity as FIT, GPX, TCX, or CSV through the
system `CreateDocument` picker. The implementation is in
`app/.../data/ActivityExport.kt` and `ui/screens/ActivityDetailScreen.kt`.

- **FIT:** copies the decoded watch file named by `activities.fileName` after
  checking the 12/14-byte FIT header and `.FIT` marker. A missing or invalid
  file is a clear error; no substitute FIT is generated. Files imported through
  Settings are now copied to app storage under `import-<timestamp>-…` so they
  can be exported later.
- **GPX:** filters out null, non-finite, out-of-range, and `(0, 0)` positions;
  the UI disables GPX when no valid position remains.
- **TCX:** writes activity/lap timing, distance, calories, HR, cadence,
  altitude, and available track points. Missing laps become one synthetic
  activity lap; every record is assigned to exactly one lap.
- **CSV:** documented columns in `docs/export.md`, ISO-8601 UTC timestamps,
  elapsed seconds, metre-based units, 1-based lap indexes, and RFC-style
  quoting. A lap-only row is written when the activity has no records.
- Nothing is uploaded; all generation runs on an IO dispatcher.

`ActivityExportTest` adds 10 JVM tests for the format edge cases. Verified on
the Pixel 10a / Android 17 against a Forerunner 255 on 2026-10-19: FIT was
byte-identical to the stored `download_76.dec` (SHA-256 match), GPX and TCX
parsed as well-formed XML, CSV parsed with ISO-8601 timestamps and 1-based lap
indexes, and the GPX/FIT controls disabled with explanations for a no-GPS
activity and a temporarily missing FIT file.

## 2026-10-20 — Epic G / G3 background sync

Implemented optional AndroidX WorkManager background sync without moving the BLE
stack out of the app process:

- `sync/SyncScheduler.kt` owns the unique periodic request
  (`watch-periodic-sync`) and a one-time `watch-sync-once` request used by
  **Run background sync now**. Periodic defaults are **off**, **60 min**,
  battery-not-low always, charging-only optional. Requests use a 10-minute
  initial exponential backoff and are re-enqueued when settings change.
- `sync/WatchSyncWorker.kt` is a `CoroutineWorker` that runs a
  `connectedDevice` foreground notification, then reuses `SyncCoordinator` and
  `ActivityRepository` to pull activities/wellness. It preloads known activity
  and wellness UIDs, imports on IO, waits for pending imports before reporting
  success, and discards protocol logs (only high-level `OpenGarminBg`
  lifecycle lines are emitted).
- `sync/SyncGate.kt` is a process-wide owner lease. The visible activity holds
  `ui` while its BLE client is connected; the worker acquires `background` only
  after `onStop` disconnects and releases. A worker that sees the UI lease
  returns success for that period; a manual sync sees the background lease and
  does not contend.
- `MainActivity` now acquires/releases the lease, persists trigger/duration/
  result, disconnects on stop, syncs on launch/resume when the last success is
  older than five minutes, and syncs on `ACTION_ACL_CONNECTED` for the bonded
  watch while visible.
- Failure handling bounds connect at 45 s, GDI session start at 45 s, and the
  transfer at 6 min. Transient/busy failures retry with WorkManager backoff;
  missing permission/no bonded watch are permanent setup errors recorded in
  Settings/Today.
- Settings shows enable/interval/charging controls, last attempt/success,
  duration, trigger, result, and the manual worker trigger. A SharedPreferences
  listener refreshes the visible UI when a worker finishes in the same process.
- `app/build.gradle.kts` adds `androidx.work:work-runtime-ktx:2.9.1`; the
  manifest merges WorkManager's `SystemForegroundService` with
  `foregroundServiceType="connectedDevice"`.

Tests: `SyncPolicyTest` (interval clamp/labels, failure classification,
retry policy, duration/trigger labels) and `SyncGateTest` (single-flight,
re-entrancy, wrong-owner release, reuse). Device pass on Pixel 10a / Android 17
+ FR255 confirmed WorkManager scheduling with the right constraints, interval
changes re-enqueueing, a successful background worker after the app was
backgrounded (34.7 s, 2 wellness days, `trigger=background`,
`result=success`, final notification), charging-only constraint changes,
process-death recovery from a persisted `syncing` state, and a permanent
Bluetooth-off failure without notification spam. Forced Doze/App Standby, a
real out-of-range watch, and the ACL-reconnect trigger still need a dedicated
device pass.

## 2026-10-20 — Background sync stall/backoff fix

Investigated a real Pixel 10a + FR255 report where periodic sync appeared dead.
WorkManager showed 8 consecutive failed periods and a next run five hours away
(`run_attempt_count=8`, exponential 10-minute backoff capped at 5 h). The last
error text had been overwritten by a manual success. A foreground sync capture
showed the watch stalling several times (`sync stalled; reconnecting (1/8)`,
`(2/8)`, `(3/8)`) before finishing; the background worker had no equivalent
reconnect loop, so it waited for the full 6-minute transfer budget and then
entered WorkManager backoff.

Changes:

- `WatchSyncWorker` now mirrors the visible sync watchdog: 20 s without progress,
  4 min during a transfer, up to 8 reconnects, resetting `SyncCoordinator` on
  each new session. GATT callbacks are marshalled onto the main thread.
- Periodic failures return `Result.success()` so the next interval is the retry;
  they no longer use WorkManager's exponential backoff. The one-time
  **Run background sync now** request uses linear 10-minute backoff and retries
  at most 3 times (`SyncPolicy.shouldRetryWork`).
- Overall worker budget is 8 minutes (foreground worker), with the existing 45 s
  GATT and GDI-session stage limits.
- `SyncPolicyTest` covers the periodic/one-time retry split; README,
  background-sync, architecture, backlog, and this handoff were updated.

The existing stuck periodic WorkSpec may still wait for its previously scheduled
run; the next period after this build either succeeds or returns success and
resets the attempt count.

### Follow-up: foreground-service start denial

A genuinely background periodic run (app process `TRNB`, no visible activity)
failed in 26 ms at `setForeground()`:

```text
Background started FGS: Disallowed ... code:DENIED
startForegroundService() not allowed: service
dev.opengarmin.app/androidx.work.impl.foreground.SystemForegroundService
```

That OS restriction (Android 12+ background FGS launch rules) is not a sync
failure. The worker now catches the denial, logs
`foreground service unavailable; running headless`, and continues in the
JobScheduler execution window. The final notification still posts; the progress
notification is used only when the OS allows the foreground worker. This path
was verified by triggering the worker with the app backgrounded.

## 2026-10-21 — Quiet route map

The track map was noisy: the standard OpenStreetMap raster bakes POI icons and
street names into every tile, so the GPS trace got buried between the labels.
The map now uses the same free, token-free OpenStreetMap rasters but mutes
them in-app, so the trace stays the loudest element while the basemap remains
a readable map.

- The map went past the intended look once: Esri's World Light Gray Base
  (anonymous, no key) was tried as a "minimal" style but its paper + white
  streets are so sparse around rural tracks that the whole card read as a
  blank white sheet. The fix keeps the standard OSM rasters (free, anonymous)
  and mutes them locally with a tile-only grayscale filter instead - a real,
  visible map with the attention back on the trace.
- The trace is drawn in two passes: a white casing under the speed gradient
  (blue slow, red fast), with plain start/finish dots instead of map pins.
  Stroke widths are density-scaled instead of fixed pixels.
- The osmdroid default `+ / -` zoom buttons are hidden (`CustomZoomButtonsController
  Visibility.NEVER`); pinch-zoom and pan stay active.
- Only the tile layer is filtered (`TilesOverlay.setColorFilter`), so the
  track keeps its blue→red speed gradient. Removing the filter (or switching
  the tile source) is a one-line change in `RouteMap.kt`.
- Verified on the Pixel 10a (2026-10-21): streets render muted but visible
  under the trace, which pops with its white casing, and the default osmdroid
  `+ / -` buttons no longer appear on touch.

## 2026-10-26 — Forerunner 245 legacy GFDI sync

A field log from a Forerunner 245 (product `3076`, firmware `13.70`) showed the
GDI handshake completing but `FeatureCapabilitiesResponse` arriving empty
(`6a024a00`). The watch GFDI-ACKed `CutWellnessFilesRequest` and
`ItemListRequest` but never answered them, so the sync loop reconnected eight
times. The FR245 does not implement GDI FileAccess; it uses the older GFDI file
transfer.

Implemented `LegacySync` (`app/.../sync/LegacySync.kt`):

- `DOWNLOAD_REQUEST (5002)` for the directory, `SYNCHRONIZATION (5037)` /
  `DIRECTORY_FILTER (5007)`, `FILE_TRANSFER_DATA (5004)` with per-chunk CRC
  validation and ACKs, and `FILE_AVAILABLE (5009)`.
- Parses the 16-byte directory entries, pulls `FIT_TYPE_4` activities plus the
  health types `FIT_TYPE_29/32/44/49/72`, imports them through the existing
  `ActivityImporter`, and archives each file with `SET_FILE_FLAGS (5008)`.
- Product `3076` starts the legacy path directly (Garmin Connect also skips the
  capability exchange on this watch). Other watches with an empty capability
  response fall back to it too.
- Workout/pace-plan uploads are failed with a clear message on legacy watches,
  because they rely on GDI push.
- Fixed phone→watch GDI device replies to use `PROTOBUF_RESPONSE (5044)`.

Tests cover the legacy directory parser, `DOWNLOAD_REQUEST` wire bytes,
`FILE_TRANSFER_DATA` header parsing and data-status offsets. Not yet verified on
physical FR245 hardware.

Follow-up after re-reading Garmin Connect 5.19.1's `LegacySyncManager` and
`com.garmin.gfdi.file.FileManager`: the official sequence is GDI `SyncRequest`
(empty, `6a020a00`) → watch `SYNCHRONIZATION (5037)` → `DIRECTORY_FILTER (5007)`
→ `SUPPORTED_FILE_TYPES_REQUEST (5031)` → `DOWNLOAD_REQUEST (5002)` for the
directory. Garmin parses the directory from offset 16 (a header entry) and
requests compression (`flags=1`); the app still requests uncompressed data
(`flags=0`) and handles `5004`. `SYNC_READY` now includes the value byte
(`[8,0]`), matching `SystemEventMessage`. The legacy path now follows the
official 5037/5007/5031/5002 order and logs the directory header for diagnosis.
