# Handoff — OpenGarmin offline sync

_Last updated: 2026-10-17. Written so a fresh session can continue without the
original chat context._

## Goal

Build an offline, local-first Android companion for a **Garmin Forerunner 255**:
sync data directly over BLE, store it on-device, no Garmin account. The Garmin
Connect APK in this repo is the reverse-engineering target.

Decisions already made with the user:

- Target: Forerunner 245/255/955/965 → GFDI v2 / Multi-Link.
- Android app, built from scratch (not a Gadgetbridge fork).
- v1 scope: sync watch → local storage + dashboard; no cloud.
- Repo: <https://github.com/naddi96/garmin-connect-reverse>, branch `main`.
- License: AGPL-3.0 (see `docs/LICENSING.md`).

## Repo state

Latest commit: `e09220e` (pushed). History:

```
e09220e Parse device-initiated GDI over 5043; harden protobuf reader
4872c82 Parse GFDI 5057 protobuf notifications; act on ConnectionReady
6de404b Diagnostics: prefer ML pair 2, subscribe dedicated GFDI notify, send sync-ready
c83a3d1 Sync loop: try Core SyncRequest before capabilities; log GDI probes
d14f349 Add reconnect/sync loop with per-connection state and watchdog
8fac632 M4: download and decompress files from the watch
0b80963 Validate protocol on a real Forerunner 255 and add the GDI file-access layer
0da90ad Reverse-engineer Garmin watch protocol and scaffold offline companion app
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
- ADB is **wireless**: serial `192.168.1.162:32999`.
  `ADB="$PWD/tools/android-sdk/platform-tools/adb -s 192.168.1.162:32999"`.
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
| M7 daily wellness | ✅ steps, distance, active calories/time, resting HR, HR/stress/SpO2/respiration samples; Health card + daily detail |

The M4 download was proven end-to-end earlier: `PullItemDataRequest` →
`transfer_handle` → MLR transport pipe on service `0x2018` → zlib stream →
decoded file. See `docs/PROTOCOL.md` §10.5.

## Protocol knowledge (all in `docs/`)

- `docs/PROTOCOL.md` — BLE UUIDs, Multi-Link, MLR, GFDI framing, handshake,
  GDI transport, FileAccess, transport pipe, verified test vectors.
- `docs/protos/` — **141 complete GDI protobuf schemas** extracted from the APK
  (`GDIFileAccess`, `GDICore`, `GDIDataTypes`, `GDIDataTransfer`, extensions…).
- `docs/samples/fr255_file_list.txt` — a real directory capture (78 items).
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
  data/AppDatabase.kt      Room entities/DAO/database (v6: activities, records,
                           laps, fit_messages, wellness_days, wellness_samples)
                           + ActivityRepository (FIT/wellness import, UID dedupe,
                           epoch conversion, lap/coordinate/message backfill)
  MainActivity.kt          Compose dashboard (Today health card + daily detail,
                           activity list + OSM track map + HR/elevation charts +
                           lap table, All data explorer), watchdog/reconnect

docs/                      PROTOCOL, PLAN, LICENSING, samples, protos
tools/                     parse_btsnoop.py, extract_protos.py, setup scripts
```

Tests: **34 protocol tests pass** (`:protocol:test`). Build:
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
fresh Room v6 database, 1 activity, 2,488 records, 6 laps, 7,931 stored FIT
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

ADB="$PWD/tools/android-sdk/platform-tools/adb -s 192.168.1.162:32999"
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
  already in Room; otherwise every connect re-downloads the same FIT.
- Room DB is version 6: `itemUid` (1→2), `laps` (2→3), record
  `latitude`/`longitude` (3→4), `fit_messages` (4→5) and wellness tables
  (5→6) migrations live in `AppDatabase`.
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
- The track map uses osmdroid/OpenStreetMap (`org.osmdroid:osmdroid-android`).
  Tiles need `INTERNET`; the cache is under `cacheDir/osmdroid`. Without a
  network the track still draws over an empty map.
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
- 20 app JVM tests (tools, OpenAI client, chat loop) on top of the 34 protocol
  tests; CI workflow added. See `docs/testing.md`, `docs/providers.md`,
  `docs/tools.md`, `docs/privacy.md`, and `BACKLOG.md`.

Still open: sleep/HRV/historical wellness ingestion (today only), WorkManager
background sync, Room migration/importer tests, Compose visual tests, and the
strict per-endpoint cleartext policy. `docs/PLAN.md` remains stale; `BACKLOG.md`
is the current status source.

### Activity sync fix (2026-10-17)

The watch has one transfer slot. A file list arriving during a wellness
transfer made the coordinator request the activity pull immediately; the watch
returned `NO_AVAILABLE_RESOURCES` (3), and the old code never retried. It also
cleared `pulledWellnessType` while a wellness pipe was open, so the wellness
file was sometimes imported as an activity and the real activity was dropped.

Fixed in `SyncCoordinator`:

- queue the activity pull and defer it while a wellness transfer/pipe is active;
- preserve the in-flight wellness type until its pipe completes;
- retry `NO_AVAILABLE_RESOURCES` with backoff, and retry an unanswered pull
  after 8 s;
- prefer the queued activity once the GDI session is ready after a pipe closes;
- process wellness afterwards.

Verified on the FR255: 198,627-byte FIT downloaded and imported (`5.50 km
Running`), then a second sync reported activities up to date while wellness
finished.
