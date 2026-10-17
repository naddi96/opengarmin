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
| M3 file directory | ✅ 78 items listed |
| M4 file download + decode | ✅ **3966 compressed bytes → 40292 bytes** (zlib) |
| M5–M6 FIT parsing → Room → dashboard | not started |

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
  Gdi (Smart, FileAccess, GdiClient handling 5043/5044/5057),
  TransportPipe (MLR pipe), Uuids, Hex

app/src/main/java/dev/opengarmin/app/
  ble/GarminBleClient.kt   scan/connect/reconnect, MTU, pair selection,
                           dedicated GFDI subscribe, serialized write queue
  sync/SyncCoordinator.kt  capabilities → connection-ready → list → pull →
                           pipe → zlib → file
  MainActivity.kt          Compose UI, watchdog/reconnect, Sync button
  sync/...                 (all sync state is per-connection, resettable)

docs/                      PROTOCOL, PLAN, LICENSING, samples, protos
tools/                     parse_btsnoop.py, extract_protos.py, setup scripts
```

Tests: **34 protocol tests pass** (`:protocol:test`). Build:
`:app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## The current blocker / latest discovery

After the watch reboot, **phone-initiated** syncs stopped producing any GDI
messages: the watch completes GFDI handshake, then stays silent. The official
app (signed in) still syncs, so the watch is fine.

Key discovery from a **watch-initiated** sync (watch → Phone → Sync): the watch
sends its GDI data over **GFDI type 5043**, not 5044. Previously our client
treated 5043 as only a cancellation notice and dropped the data.

The 5043 stream contains the periodic Smart notifications, including
**`ConnectionReadyNotification`** (`6a 02 72 00`). The app now:

- parses 5043 as a full GDI frame (same decoder as 5044),
- parses 5057 as a 2-byte-prefixed Smart,
- calls `requestList()` when `ConnectionReadyNotification` arrives,
- defensively parses malformed/truncated protobuf (a `6a 02 80 01` core
  message previously crashed `coreFeatureCapabilitiesResponse`).

The very last test run crashed on that malformed core message before the fix.
The parser is now fixed, the app was rebuilt/installed/started, and it is
**waiting for the user to trigger a watch-side sync again**. That is exactly
where the next session should resume.

The watchdog is temporarily **90 s** (`MainActivity.armWatchdog`) so the user has
time to press Sync on the watch. Restore to ~12–15 s once testing is done.

## Immediate next steps

1. With the app connected, have the user press **up button → Phone → Sync** on
   the watch.
2. Capture: `$ADB logcat -d -s OpenGarmin:D > captures/next.log`
   Look for `GDI: connection ready` → `GDI → ItemListRequest` →
   `FILE LIST status=... items=...` → `PULL request` → `PULL response` →
   `PIPE: complete` → `DOWNLOAD raw=... decoded=...`.
3. If the list arrives and pull works, verify the `.dec` file with
   `run-as dev.opengarmin.app cat files/download_<handle>.dec`.
4. If a new Smart/core message fails to parse, the error log now includes the
   raw hex prefix — add its field to the corresponding `Gdi` parser.
5. Once reliable: restore the watchdog, and make the app "await watch sync"
   explicitly (keep the link open, show a prompt) instead of reconnecting.
6. Then M5–M6: FIT parser → Room DB → Compose dashboard.

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
- The watch only starts GDI reliably for a **watch-initiated** sync so far.
- `ItemReference.data_size` is **not** the compressed stream length; read until
  the pipe service closes, then zlib-decompress.
- Full HCI capture is unavailable (no root; bugreport `btsnooz` truncates to
  15 bytes/packet). Garmin Connect's own logs are private. A rooted device or a
  BLE sniffer is the fallback if the 5043 path stalls.
