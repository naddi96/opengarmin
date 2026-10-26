# Garmin Connect ↔ Forerunner 255 sync: what Garmin does, and where OpenGarmin differs

_Last updated: 2026-10-26 · OpenGarmin 0.9.4-dev · Garmin Connect 5.30.1 (versionCode 11657, pulled from the Pixel 10a) · FR255 product 3992, firmware 2905_

This is a protocol-level comparison, not a product gap list. It answers one
question: **when Garmin Connect syncs an FR255, what exactly does it expect from
the GDI FileAccess / MLR transport, and what does OpenGarmin do differently?**

Sources:

- Garmin Connect 5.19.1 APK in this repo (`apk/jadx8`, `apk/jadx_out`,
  `docs/protos`).
- Garmin Connect 5.30.1 pulled from the phone for this comparison
  (`GDIFileAccess`, `com.garmin.device.filetransfer.b/a`, MLR helpers).
- Real FR255 captures in `captures/` (`health_ui.log`, `fit_download.log`,
  `clean.log`).
- Current OpenGarmin code on `main`.

## 1. TL;DR

1. **The watch's `TransferStatusRequest` is the authoritative transfer result.**
   Garmin matches it by `transfer_handle`, completes the item on success, and
   raises `TransferStatusException` on failure. The FR255 sends it **after** the
   transport pipe closes. OpenGarmin used to import on the close alone; 0.9.4-dev
   now waits for the status (1 s grace for watches that do not send it).
2. **The FR255 does not advertise checksum support.** Its FileAccess
   capabilities contain `software_update_part_number_request_support=false` and
   `custom_flag_support=true`, but no `server_file_checksum_method` /
   `checksum_max_file_size_byte`. Garmin's truncated-MD5 path is therefore not
   used with this watch; our decoded-size check is the practical equivalent.
3. **`custom_flag_support=true` is advertised but unused by Garmin 5.30.1.**
   The field exists in the generated proto class, but no Java consumer calls
   `getCustomFlagSupport()` (verified in `dexdump`). Not a gap for us.
4. **Priority values differ.** Garmin uses 14 (utility), 21/24 (automatic),
   34/37 (user initiated) and 44 (immediate). OpenGarmin hardcodes `priority=30`
   for every pull, which is in the user-initiated band.
5. **The FR255 sends `ResourceUpdateNotification` and OpenGarmin ignores it.**
   Garmin forwards the status/current priority to its queue. This is a
   preemption/resource signal, not a data payload.
6. **MLR reliability is native in Garmin** (`libmlr`, `CONFIGURE_TIMEOUT=10 s`,
   `READY_TO_SEND_BLOB_TIMEOUT=120 s`). Our Kotlin receiver is minimal. The
   recently fixed ack-after-advance + duplicate re-ack behaviour matches what
   the native implementation must do; we still do not implement its timers.

## 2. What the FR255 actually advertises

Decoded from a real `FeatureCapabilitiesResponse` (`captures/clean.log`):

```
core guid_status=1 version=1
file_access.software_update_part_number_request_support = false
file_access.custom_flag_support = true          # proto field 5, new in 5.30.1
garmin_connect.wellness_support = true
garmin_connect.lifestyle_logging_support = false
```

Observed during transfers:

| Property | Value |
|---|---|
| Pull compression request | Garmin asks 15; watch answers **11** (9–15 valid) |
| Supported transports | `MULTILINK_TRANSPORT_PIPE (0)` only |
| Checksum | not advertised → Garmin skips `GetItemChecksum` |
| `TransferStatusRequest` | sent after pipe close, including failure reasons |
| `ResourceUpdateNotification` | sent during sync |
| `custom_flag_support` | advertised, unused by Garmin 5.30.1 |

## 3. Garmin's pull sequence

From `com.garmin.device.filetransfer.b` (`FileTransferProtoRequests`) and
`a` (`FileTransferManager`):

1. **Item list** with `included_data_types` where relevant. Garmin's cloud
   upload agent prioritises `FIT_TYPE_4` and `FIT_TYPE_41`; activity sync asks
   for activities plus the watch's own data types. It does not use a local UID
   ledger the way OpenGarmin does.
2. **PullItemDataRequest**
   - `priority` from the transfer band (see §5)
   - `supported_transports = [MULTILINK_TRANSPORT_PIPE]`
   - `offset = 0` for a full read
   - `requested_compression_window = 15`
3. **Response validation**
   - `result == SUCCESS`, `transport` and `transfer_handle` present
   - compression window must be `9 <= window < 16`, otherwise
     `TransferStatusException(COMPRESSION_FAILED)`
4. **Transport pipe** over MLR service `0x2018`. Data is a zlib stream.
5. **Completion** — `TransferStatusRequest` from the watch:
   - match `transfer_handle` to the in-flight task
   - `hasFailureReason()` → log UID/status/code/provider and fail the item
   - no failure reason → complete the item
   - unknown handle → reply with `TransferStatusResponse` (no priority)
6. **Optional checksum** if `server_file_checksum_method == TRUNCATED_MD5` and the
   item is below `checksum_max_file_size_byte`. Not used on the FR255.
7. Individual item failures do not abort the whole sync; Garmin retries or
   classifies them per item.

## 4. OpenGarmin today (differences that matter)

| Area | Garmin Connect | OpenGarmin 0.9.3-dev | Impact |
|---|---|---|---|
| Transfer result | `TransferStatusRequest` is authoritative | waits for it (1 s grace); pipe close alone is not success | Fixed in 0.9.4-dev |
| Failure reasons | per-item exception + retry/queue logic | `TRANSFER_REJECTED_BY_WATCH` + per-item retry/abandon | Fixed in 0.9.4-dev |
| Compression window | requests 15, validates 9–15 | requests 15, accepts anything | Low |
| Item retry limit | per-item backoff | 5 failed transfers per item, then skip | Fixed in 0.9.4-dev |
| Checksum | truncated MD5 when supported | not implemented | None on FR255; useful on newer watches |
| Priority | 14/21/24/34/37/44 by trigger/type | constant 30 | Low–Medium |
| ResourceUpdateNotification | forwarded to queue | ignored | Medium |
| ItemUpdatedNotification | flag changes forwarded | ignored | Low |
| SyncButtonNotification | handled | ignored (`SYNC_READY` only) | Low |
| ItemAddedNotification | forwarded to agent queue | queues wellness immediately | Low |
| MLR timers | native 10 s / 120 s | no timers; watchdog only | Low after ack fix |
| Item flags | uses `ModifyFlags` for cloud-processed files | local UID ledger only | None for local-only use |
| Partial reads | `offset` supported | always offset 0 | Low |

### Evidence: the watch reported a failed transfer after the pipe closed

`captures/health_ui.log`, 2026-10-17 19:22:02 (FIT_TYPE_41, handle 37):

```
19:22:02.951  ML: handle closed service=0x2018 handle=2 raw=0x82
19:22:02.951  PIPE: complete (164 bytes)
19:22:02.955  DOWNLOAD raw=164 decoded=287 -> download_37.dec
19:22:02.955  WELLNESS complete type=FIT_TYPE_41 decoded=287
19:22:02.988  GFDI 5043 ... da02 20 2a1e 0a12 <uid> 10 25 18 09 20 0c 28 05 30 03
              ^ TransferStatusRequest:
                handle=37, failure_reason=9, transport_status=12,
                provider=5, transport_error_code=3
```

The 287-byte file was imported before the failure arrived. `failure_reason=9`
is not in the 5.30.1 enum (0–5); it is a newer watch-side reason. Treating any
present failure reason as failure is safer than mapping only known values.

The same log contains a request with `failure_reason=0 TRANSPORT_FAILED`
(handle 37, status 12, provider 5, error 3), confirming the field is used.

## 5. Garmin priority bands

`p115fT/L.a()` in 5.19.1:

| Band | Value |
|---|---|
| `IMMEDIATE_MESSAGE` | 44 |
| `USER_INITIATED_HIGHEST` / `PREFERRED` | 37 |
| `USER_INITIATED_GENERAL` | 34 |
| `AUTOMATIC_PREFERRED` / `STANDARD` | 24 |
| `AUTOMATIC_ALLOWED` | 21 |
| `UTILITY_SILENT` | 14 |

OpenGarmin uses 30 for activities, wellness and background pulls. It works, but
the watch cannot distinguish a user sync from a background/utility transfer and
may preempt differently.

## 6. Implementation status

Done in 0.9.4-dev:

1. **`TransferStatusRequest` is handled** (`SyncCoordinator.handleTransferStatus`).
   The app parses UID/handle/failure reason/transport details, matches the
   active or just-closed transfer, deletes rejected files, records
   `TRANSFER_REJECTED_BY_WATCH`, and retries or abandons the item. It replies
   with `TransferStatusResponse` only for an unknown handle.
2. **Imports wait for the status.** The decoded file is held in memory for a
   1 s grace window after the pipe closes. A success status finalises it
   immediately; a failure discards it; no status falls back to accepting it.

Verified on the FR255 (2026-10-26, handle 2908):

```
PIPE: complete (1522 bytes)
TRANSFER pipe closed handle=2908 raw=1522 decoded=2598; waiting for status
TRANSFER confirmed by watch handle=2908
DOWNLOAD raw=1522 decoded=2598 -> download_2908.dec
```

Item-level retry counting was also separated from the pull-response retry
counter, so five failed transfers of the same file actually stop (previously a
successful `PullItemDataResponse` reset the counter each time).

Still open:

3. **`ResourceUpdateNotification` (field 20).** The FR255 sends it; the app
   should expose `CURRENT_PRIORITY_LOWERED` / `FILE_SPACE_FREED` and adjust the
   current item.
4. **Priority bands.** Manual sync 37, background 24, wellness utility 14 (or
   21) instead of a constant 30.
5. **Optional:** parse `custom_flag_support` (field 5) and
   `server_file_checksum_method` (field 3) in `Gdi.featureCapabilities`, even if
   only for logging today.
6. **Optional:** handle `ItemUpdatedNotification` and
   `SyncButtonNotification`; both are defined in our proto extract and ignored
   by the current coordinator.

Items 1–2 directly address the "unsupported FIT header size 56/140" and
"FIT file too short" failures that started this investigation: the watch can
declare a transfer failed after the pipe has already closed.

## 7. Open questions / not verified

- Whether the FR255 always sends `TransferStatusRequest`, or only on failure/at
  certain firmware versions. The graceful-timeout fallback in §6.2 keeps
  compatibility either way.
- What new failure reasons (`>= 6`) mean on firmware 2905. Treating them as
  failures is safe; mapping them is cosmetic.
- Garmin 5.30.1's use of `custom_flag_support` outside Java (native/reflection)
  was not found; assume unused until proven otherwise.
