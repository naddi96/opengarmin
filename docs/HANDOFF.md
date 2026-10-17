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

## Immediate next steps

1. Parse the FIT file (M5): definition messages + data messages, map activity,
   session, lap, record, event to a model.
2. Persist to Room, then build the Compose dashboard (M6).
3. Wire the `Sync` button and dashboard to the stored activities.
4. Consider fetching the remaining 99-item page (`next_transaction_id`), and
   pulling more than one activity.
5. Decide whether to answer ConnectIQ HTTP requests with real network data
   (weather) or keep the offline timeout response.

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
- `ItemReference.data_size` is **not** the compressed stream length; read until
  the pipe service closes, then zlib-decompress.
- Full HCI capture is unavailable (no root; bugreport `btsnooz` truncates to
  15 bytes/packet). Garmin Connect's own logs are private. A rooted device or a
  BLE sniffer is the fallback if the 5043 path stalls.
