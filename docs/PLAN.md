# Project plan

## Goal & scope

Build an **Android** app that connects to a Garmin Forerunner over BLE, syncs
activities and health data into an **on-device database**, and presents a dashboard —
with **no Garmin account and no network**.

Non-goals for v1: Connect IQ store, firmware updates, Garmin servers, iOS/desktop.

## Constraints & decisions

| Decision | Choice |
|---|---|
| Protocol generation | GFDI **v2 / Multi-Link** (Forerunner 245/255/955/965) |
| Reliability layer | plain **ML** first; add **MLR** only if long transfers flake |
| Language | Kotlin (protocol core is pure JVM so it is unit-testable) |
| Storage | Room (SQLite) + raw FIT files kept on disk |
| UI | Jetpack Compose |
| Data ownership | files + DB local; export to FIT/CSV/GPX on demand |

## Layered architecture

1. **`protocol/` — wire codec (done)**
   - `Crc16`, `Cobs`, `GfdiMessage` (build/parse), `MessageType`/`Status`.
   - `MlTransport` (handle management, chunking) behind a `BleLink` interface.
   - `GfdiSession` (close-all → register GFDI → handshake → file ops).
   - No Android imports → runs under `:protocol:test`.

2. **`app/` — Android integration**
   - `AndroidBleLink` (BluetoothGatt: scan, connect, MTU, notify queues).
   - `SyncEngine` (foreground service): discover files → stream → parse → persist.
   - `FitParser` (port of the FIT profile; start with the files we need).
   - Room entities: `Device`, `Activity`, `ActivityLap`, `ActivityRecord`,
     `DailySummary`, `SleepSession`, `BodyMetric`, `SyncState`.
   - Compose dashboard: device status, last sync, activity list + detail charts.

## Milestones

| # | Milestone | Exit criteria |
|---|---|---|
| **M0** | RE + protocol core | ✅ protocol library builds; CRC/COBS/framing vectors pass |
| **M1** | BLE connect & identify | ✅ app connects, registers GFDI, discovers the ML pair |
| **M2** | Handshake | ✅ DEVICE_INFORMATION / CONFIGURATION / AUTH / time exchange works |
| **M3** | File listing | ✅ GDI capabilities + `ItemListRequest`; 78 files listed on FR255 |
| **M4** | FIT download | in progress: `PullItemDataRequest` + DataTransfer/GFDI bytes |
| **M5** | Archive | app marks synced files ARCHIVE; no redownload; idempotent |
| **M6** | FIT → DB | parsed activities + records in Room; dashboard shows them |
| **M7** | Polish | background sync, error recovery, export, health summaries |

## Testing strategy

- **Unit tests** (`:protocol:test`) against documented vectors and round-trips — no
  hardware needed.
- **Live capture** (user has watch + Android + ADB):
  - `adb logcat` filtered to the app's BLE layer for every write/notify frame.
  - Compare our frames against the official app using Android's **Bluetooth HCI
    snoop log** (`adb bugreport` → `btsnoop_hci.log`, open in Wireshark) while the
    official Garmin Connect app performs a sync.
  - Validate our file download byte-for-byte against `adb pull` of the same FIT or
    against an export from Garmin Connect.
- **Golden tests**: record real frames as fixtures for regression.

## Risks

| Risk | Mitigation |
|---|---|
| MLR required for large FIT transfers | start with ML; capture official-app traffic to confirm |
| Pairing/auth differences per model | read `AUTH_NEGOTIATION`/pairing smali; capture live |
| FIT parsing breadth | use the public FIT profile; parse incrementally |
| AGPL contamination | independent implementation from protocol facts; see `LICENSING.md` |

## Immediate next actions

1. `./gradlew :protocol:test` — green.
2. ✅ M1–M3 validated on a real Forerunner 255 (Pixel 10a, Android 17).
3. Implement `PullItemDataRequest` and receive file bytes (M4).
4. Parse FIT → Room DB (M5–M6).
