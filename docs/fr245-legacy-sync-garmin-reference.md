# Garmin Connect reference: legacy GFDI sync on the Forerunner 245

_Reverse-engineered 2026-10-27 from the Garmin Connect 5.19.1 APK in `apk/`
(`apk/base.apk`, `apk/jadx8bad`, `apk/jadx_out`, `apk/apktool_out`).
Cross-checked against the FR245 field log exported by OpenGarmin 0.9.2
(product `3076`, firmware `1370`)._

This document is deliberately about **what Garmin Connect actually does**, not
what OpenGarmin does. The "OpenGarmin delta" section at the end lists the
places where our implementation differs.

---

## 1. Component map

Garmin's file stack is split across three layers. The obfuscated names are the
ones in this APK.

| Layer | Class | Role |
|---|---|---|
| GFDI service | `com.garmin.gfdi.file.a` (`FileManager`) | Owns 5007 / 5031 / 5002 / 5004 / 5008. Parses the directory, reads files, archives. |
| Device facade | `com.garmin.android.gfdi.framework.FileManagerCompat` | Caches supported types, maps filter values, applies the READ/ARCHIVE flag filter and the subtype filter. |
| Device facade | `com.garmin.android.gfdi.framework.DeviceManager` | `listPendingUploadFiles()` → `listFiles(3)`. |
| App sync | `p156lP.r` (`DeviceSyncManager`) | Receives 5037 / FILE_READY, starts a full-sync strategy. |
| App sync | `p156lP.B` (`DeviceSyncUpload`) | Lists pending files, reads each one, uploads it to Garmin's cloud, archives it. |
| Sync message | `p181iX.i` (`SyncMessageManager`) | Handles GFDI 5009 / 5027 / 5037 and converts them to listener callbacks. |
| Legacy trigger | `p278zT.a` (`LegacySyncManager`) | Sends GDI `Core.SyncRequest`, awaits the sync status, 25 s timeout. |
| Legacy plumbing | `p278zT.e` / `p278zT.g` / `p278zT.h` | Registry and per-device manager cache for legacy watches. |

For the FR245 the newer GDI FileAccess protobuf service is not present, so the
legacy `FileManager` path above is the one that runs. The watch advertises the
GFDI service and the classic file protocol, exactly like the field log.

---

## 2. How the sync is started

### 2.1 Watch-initiated: `SYNCHRONIZATION (5037)`

`p181iX.i` registers for 5009, 5027 and 5037. A 5037 payload is:

```
[option:u8][maskLength:u8][mask:maskLength bytes]
```

- `option`: 0 = `MANUAL`, 1 = `INVISIBLE`, 2 = `VISIBLE_AS_NEEDED`.
- `mask`: a bitmask of file-type ids; bit *n* = type id *n*.

Garmin ACKs 5037 with a plain `RESPONSE (5000)` and then calls
`DeviceSyncManager.onSyncRequest(...)`, which starts a `FULL_SYNC` strategy.
The manual / invisible / visible choice only affects progress UI.

### 2.2 Phone-initiated: GDI `Core.SyncRequest`

`LegacySyncManager.requestSync()`:

1. Sends `CoreService.sync_request` (Smart/protobuf over GFDI 5043).
2. Reads `SyncResponse.status`. Garmin only treats `BUSY_WITH_ANOTHER_APP`
   (enum value 2) as a hard failure; `UNKNOWN_RESPONSE_STATUS` (0), `OK` (1)
   and any unknown numeric value are tolerated.
3. Waits up to **25 s** for the transfer-status callback.

On the FR245 the field log shows `SyncResponse.status = 3` — a value that is
not in the 5.19.1 enum. Garmin neither rejects it nor maps it; it falls through
to the status wait. In the 2026-10-27 log the watch also did **not** send 5037
after this request, so Garmin's own legacy trigger can sit in the 25 s wait.
OpenGarmin currently times out after 5 s and starts the file list anyway, which
is why the field log shows
`LEGACY: no 5037 sync notification; starting file list anyway`.

This is an important FR245 quirk: **do not require 5037 to begin the file
list.** The directory request is still valid and is the only way to see the
watch's pending files.

---

## 3. Directory listing

`DeviceSyncUpload.prepare()` calls:

```java
this.y.d(unitId, this.C, listener);   // this.C = supported subtypes
```

`DeviceManager.listPendingUploadFiles()` then calls `listFiles((byte) 3, ...)`.
The filter values are defined by Garmin's `jX.b` enum:

| Wire value | Enum | Meaning |
|---|---|---|
| 0 | `NO_FILTER` | no filtering |
| 1 | `DEFAULT` | device default |
| 3 | `PENDING_UPLOADS_ONLY` | only files the watch has not uploaded/archived |

`FileManagerCompat.listFiles()` maps 0/1/3 to `b`/`c`/`d` and rejects any other
value. It then calls `FileManager.h(filter)`, which is the actual wire flow:

1. **`DIRECTORY_FILTER (5007)`** with a one-byte payload `[filter]`.
   The watch answers with a `RESPONSE (5000)` whose first data byte is 0 when
   the filter is supported. Garmin only logs the result; it continues either
   way.
2. **`SUPPORTED_FILE_TYPES_REQUEST (5031)`** with an empty payload. Response:
   `[count:u8]` followed by `count × [dataType:u8][subType:u8][nameLen:u8][name]`.
   Garmin caches this list and uses `subType` for the directory filter.
   If the watch answers `UNSUPPORTED`, Garmin assumes `FIT_TYPE_4` and
   `FIT_TYPE_36`.
3. **`DOWNLOAD_REQUEST (5002)` for file index 0** (the directory).

### 3.1 `DOWNLOAD_REQUEST (5002)` payload

14 bytes, little-endian:

```
offset  size  field
0       2     file_index
2       4     data_offset        (0)
6       1     request_type       (1 = NEW)
7       2     crc_seed           (0)
9       4     data_size          (0)
13      1     request_flags      (1 = compression allowed,
                                  0 = uncompressed only)
```

Garmin's upload flow sends `request_flags = 1` because
`FileManagerCompat.DEFAULT_FILE_READ_OPTIONS` has `useCompression = true`.
OpenGarmin sends 0 and expects raw 5004, which the FR245 accepts.

The 5002 status response is:

```
[status:u8][max_file_size:u32]
```

Status meanings (from Garmin's read path, `kX/c`):

| Status | Meaning |
|---|---|
| 0 | OK |
| 1 | `FILE_INDEX_DOES_NOT_EXIST` |
| 2 | `FILE_INDEX_NOT_READABLE` |
| 3 | `NOT_READY` — Garmin retries up to 5 times, 1 s apart |
| 4 | `INVALID_REQUEST` |
| 5 | `CRC_MISMATCH` |
| 6 | `DATA_REQUESTED_EXCEEDS_FILE_SIZE` |

For a directory request, `max_file_size` is the directory size. Garmin then
receives the bytes as `FILE_TRANSFER_DATA (5004)` (or 5054 if compression was
accepted).

### 3.2 Directory entry format

Garmin parses the directory from **offset 16 unconditionally**:

```java
buffer.order(LITTLE_ENDIAN);
buffer.position(16);
while (buffer.remaining() >= 16) { ... }
```

Each 16-byte entry:

```
offset  size  field
0       2     file_index
2       1     data_type          (128 = FIT)
3       1     sub_type           (4 = FIT_TYPE_4 / activity)
4       2     file_number
6       1     specific_flags
7       1     file_flags
8       4     size
12      4     timestamp          (Garmin epoch)
```

Garmin's decompiler names the 3 bytes at offset 3..5 as one `identifier[3]`
array; `identifier[0]` is the subType. Functionally this is the same layout as
OpenGarmin's `parseEntries`.

### 3.3 The flag filter that Garmin applies

After parsing, `FileManagerCompat.listFiles()` keeps an entry only when:

```java
entry.flags.contains(READ) && !entry.flags.contains(ARCHIVE)
```

Flag bits (`com.garmin.gfdi.file.e`):

| Bit | Name |
|---|---|
| 0x80 | `READ` |
| 0x40 | `WRITE` |
| 0x20 | `ERASE` |
| 0x10 | `ARCHIVE` |
| 0x08 | `APPEND` |
| 0x04 | `CRYPTO` |

So a file that is still waiting to be uploaded has `READ|ERASE` = `0xA0`.
Once uploaded/archived it has `READ|ERASE|ARCHIVE` = `0xB0` and Garmin drops
it even if the watch returns it. This is the single most important fact for
the "activity is missing" problem.

After the flag filter, Garmin applies the subtype filter:

```java
if (requestedSubTypes.contains(entry.subType)) keep;
```

The requested subtypes are the `5031` subType list (on the FR245:
`4, 32, 44, 41, 49, 245, 244, 247`).

### 3.4 What the FR245 returns

The field log's directory is 48 bytes = 16-byte header + 2 entries:

| index | dataType | subType | type | flags | size |
|---|---|---|---|---|---|
| 18 | 128 | 32 | `FIT_TYPE_32` | `0xA0` READ|ERASE | 646 |
| 71 | 128 | 49 | `FIT_TYPE_49` | `0xA0` READ|ERASE | 208 |

Both are wellness. There is **no** `FIT_TYPE_4` entry. That is why the log
prints `activities=0 wellness=2` and the app never downloads a run.

---

## 4. Reading one file

`FileManager.i(fileIndex, useCompression, progress, ...)`:

1. Sends `DOWNLOAD_REQUEST (5002)` with the same 14-byte payload as above,
   but `file_index` set to the entry's index.
2. Gets `[status:u8][max_file_size:u32]`.
3. Receives `FILE_TRANSFER_DATA (5004)` chunks:

```
[flags:u8][running_crc:u16][offset:u32][data...]
```

4. Validates the running CRC-16 and the offset, appends to the output file.
5. ACKs every chunk with a `RESPONSE (5000)` for original type 5004:

```
[origType=5004:u16][status=0:u8][transferStatus:u8][next_offset:u32]
```

`transferStatus` is 0 for OK. Non-zero values abort the transfer.
If compression was negotiated, the watch uses `5054` instead of `5004` and the
payload is an inflate stream; Garmin's `CompressedDataProcessor` handles it.

There is no checksum beyond the per-chunk running CRC on this watch. Garmin
does not do a whole-file checksum unless the watch advertises
`TRUNCATED_MD5`.

---

## 5. What happens after the download

`DeviceSyncUpload` is an **upload** agent, not a local importer:

1. It queues every listed entry whose type is in the device's uploadable set
   (`this.A`, from the device profile) and whose subtype passed the 5031 filter.
2. For each queue item it calls `readFile(index, ...)`.
3. When the file is written to a temp file, `p156lP.C` uploads it to Garmin's
   cloud upload service.
4. On upload success, `B.X()` requests archive, which sends
   `SET_FILE_FLAGS (5008)`:

```
[file_index:u16][ARCHIVE=0x10]
```

5. The `B.b.a()` success callback then removes the item from the work list and
   moves on.

Consequences for a local-only client:

- Garmin can afford to archive immediately because the bytes are already in
  the cloud.
- OpenGarmin must archive **only after the local import has succeeded**.
  Otherwise a failed import or a process kill loses the watch copy forever.
- The watch's `PENDING_UPLOADS_ONLY` directory is the only list used by
  Garmin for this flow. There is no "download activity history" path on the
  FR245 in Garmin Connect 5.19.1.

---

## 6. Why the run is not in the directory (diagnosis of the 2026-10-27 log)

The log is internally consistent and shows no parser or framing failure:

- `DIRECTORY_FILTER (5007)` status 0.
- `SUPPORTED_FILE_TYPES (5031)` returned `4, 32, 44, 41, 49` plus vendor types.
- `DOWNLOAD_REQUEST` for index 0 returned a 48-byte directory.
- The parser found 2 wellness entries, both `READ|ERASE` (pending), no activity.

The watch therefore did **not** offer a `FIT_TYPE_4` file. The app cannot
download what the watch does not list. Two watch-side states produce this:

1. **The activity was already uploaded.** If Garmin Connect synced the run
   before OpenGarmin did, Garmin marked the file `ARCHIVE` (0x10). The watch's
   pending-only filter then hides it, and Garmin's own client would hide it a
   second time via the `!ARCHIVE` flag check.
2. **An earlier OpenGarmin legacy attempt consumed it.** The current
   `LegacySync` writes the bytes, calls `onActivityFile` (which imports
   asynchronously), and immediately sends 5008. If the import fails, the app is
   killed, or the database is replaced/reinstalled between the write and the
   insert, the watch copy is archived and the local copy may be gone.

A third, less likely state is a stale watch directory index. On the FR255 a
watch reboot rebuilt the index and exposed missing activities; the same is
worth trying on the FR245 before concluding the file is gone.

### Recovery path

1. Check the Garmin Connect app/web for the run. If it is there, Garmin has
   the original FIT. Export the original from Garmin Connect and use
   OpenGarmin's **Settings → Import FIT file**.
2. Check the app's private files for a leftover
   `legacy_<index>_<fileNumber>_<timestamp>.fit`. `LegacySync` keeps the raw
   download in `context.filesDir`; an activity FIT there can be imported with
   the same **Import FIT file** action.
3. Force-stop Garmin Connect, reboot the watch, then sync again. This clears
   the competing Garmin session and rebuilds the watch index.
4. Run **Settings → Audit watch file list**. This is a read-only probe that
   sends `DIRECTORY_FILTER` with value **0 (`NO_FILTER`)** instead of 3, logs
   every entry (including flags and FIT subtypes the app does not import) and
   does **not** download or archive anything. If the FR245 lists the archived
   activity here, the file still exists on the watch and a follow-up recovery
   mode can pull it. If it does not, the FIT exists only in Garmin Connect's
   cloud.

---

## 7. OpenGarmin delta

_Status: items 1–5 below were implemented in `LegacySync.kt`,
`ActivityImporter.kt`, `SyncCoordinator.kt`, `SyncSessionController.kt` and
`WatchSyncWorker.kt` on 2026-10-27; item 6 on 2026-10-29._

| Area | Garmin Connect 5.19.1 | OpenGarmin now | Impact |
|---|---|---|---|
| Directory filter | `3` pending only | `3` pending only; `0` for the read-only audit | Parity |
| Flag filter | requires `READ`, rejects `ARCHIVE` | same in `parseEntries`; the audit keeps them for logging | Parity |
| Directory parse start | always offset 16 | defaults to 16, falls back to 0 for headerless firmware | Parity on the FR245; defensive fallback |
| Supported types | uses the 5031 subtype list dynamically | hard-coded wellness list + activity subtype 4 | Fine for FR245; brittle on other watches |
| 5002 request flags | `1` (compression allowed) | `0` (raw 5004) | Works on FR245; Garmin also handles 5054 |
| 5037 required? | waits, but can fall back after 25 s | falls back after 5 s | Our fallback is what makes the FR245 sync actually run |
| Archive timing | after cloud upload succeeds | after the local import callback (30 s timeout) | Watch copy is kept when the import fails |
| Post-download | upload to cloud, then archive | local import, then archive | Same success ordering |
| Full directory scan | not exposed in the upload flow | **Settings → Audit watch file list** (filter 0, read-only) | Diagnostic for archived activity files |
| "Up to date" message | n/a | "watch sync finished" | No longer claims all activities were imported |

### Implemented fixes

1. **Archive only after a confirmed import.** `LegacySync` now waits for the
   importer's success callback before sending `SET_FILE_FLAGS (5008)`. A
   30-second timeout leaves the file pending on the watch if the importer does
   not answer, and a failed import is never archived. This applies to both
   foreground and background syncs.
2. **Garmin's flag filter** is applied in `LegacySync.parseEntries`: entries
   without `READ` (0x80) or with `ARCHIVE` (0x10) are skipped. The full-scan
   path passes `includeArchived = true` so the audit can log them.
3. **Directory parsing** defaults to offset 16, with a fallback to offset 0
   only when the first block already looks like a FIT entry (headerless
   firmware).
4. **The completion message** is now "SYNC up to date: watch sync finished",
   so a run that was never listed is not reported as "already imported".
5. **Read-only full scan.** Settings → **Audit watch file list** starts a sync
   that sends `DIRECTORY_FILTER = 0`, logs every entry with its flags, type and
   size, and downloads/archives nothing. Use it to check whether the FR245
   exposes archived activities at all.
6. **Workout/PacePro upload on legacy watches.** Garmin's legacy GFDI upload
   (`CREATE_FILE` → `UPLOAD_REQUEST` → `FILE_TRANSFER_DATA` → `SYNC_COMPLETE`)
   now runs before the directory sync instead of failing the queue. The
   activity sync resumes in the same connection once the queue drains. This is
   the same protocol Garmin's `SendFileToDeviceTask` uses; see
   `apk/apktool_out/smali_classes6/lX/o.smali` and
   `FileManagerCompat$saveFile$1.smali`.

### Remaining known gap

The watch still only shows pending-upload files in the normal sync. If the run
is archived in the watch and the full scan does not expose it, the FIT exists
only in Garmin Connect's cloud. The manual **Import FIT file** path is the
recovery route.

---

## 8. Source index

| Topic | File |
|---|---|
| Filter enum | `apk/jadx_out/sources/p1289jX/b.java` |
| 5007 / 5031 / 5002 / 5008 | `apk/jadx_out/sources/com/garmin/gfdi/file/a.java` |
| 5002 request/response | `apk/apktool_out/smali_classes6/kX/c.smali` (5002 send/parse), `apk/apktool_out/smali_classes6/pX/c.smali` (status names) |
| Flag bits | `apk/apktool_out/smali_classes6/com/garmin/gfdi/file/e.smali` |
| Flag + subtype filtering | `apk/jadx8bad/sources/com/garmin/android/gfdi/framework/FileManagerCompat.java:272-341, 756-775` |
| Pending-upload entry point | `apk/jadx8bad/sources/com/garmin/android/gfdi/framework/DeviceManager.java:225-238` |
| Upload strategy | `apk/jadx8bad/sources/p156lP/B.java:42-181` (listing), `:306-311` (archive), `:356-373` (read), `:543-583` (prepare) |
| 5037 / 5009 / 5027 | `apk/jadx8bad/sources/p181iX/i.java:192-284` |
| Device sync manager | `apk/jadx8bad/sources/p156lP/r.java:94-157` |
| Legacy trigger | `apk/jadx8bad/sources/p278zT/a.java` |
| Compression default | `apk/jadx8bad/sources/com/garmin/android/gfdi/framework/FileManagerCompat.java:54` |
| OpenGarmin implementation | `app/src/main/java/dev/opengarmin/app/sync/LegacySync.kt` |

To regenerate the good decompile of the classes that `jadx_out` missed:

```bash
JAVA_HOME=tools/jdk tools/jadx/bin/jadx --no-res \
  -d /tmp/opencode/jadx6 apk/extracted/classes6.dex
```

---

## 9. Firmware / root-cause research

There is no public **full flash dump** of the FR245 in the repositories found so
far, but the firmware *has* been reversed:

- **Anvil Secure reversed the Forerunner 245 Music firmware (2022–2023).** They
  extracted the `FW_ALL_BIN` record from an official Garmin beta `.GCD` update,
  loaded it in Ghidra as **ARM:LE:32:Cortex with the image based at `0x3000`**,
  and documented the GarminOS/TVM internals. The research repository contains
  tools and advisories, not the firmware image itself:
  - https://www.anvilsecure.com/blog/compromising-garmins-sport-watches-a-deep-dive-into-garminos-and-its-monkeyc-virtual-machine.html
  - https://github.com/anvilsecure/garmin-ciq-app-research
  - https://github.com/anvilventures/GhidraGarminApp (Ghidra loader for PRG apps)

- **GCD format** (the container Anvil parsed):
  - https://www.memotech.franken.de/FileFormats/Garmin_GCD_Format.pdf

- **GCD extractors** that can pull `FW_ALL_BIN`:
  - https://github.com/RomainQuidet/garminExtractor
  - https://github.com/theodorebeaupre-prog/garmin-gcd-toolkit
  - https://github.com/rbran/gcd-rs
  - https://github.com/slxsh9/GARMIN_GCD_Extractor

- **Gadgetbridge's Garmin implementation** is the other public reference for the
  BLE file manager and activity flags:
  - https://codeberg.org/Freeyourgadget/Gadgetbridge
  - https://gadgetbridge.org/basics/topics/garmin

### Getting the exact 13.70 image

Garmin no longer serves the old beta `.zip` files (`Forerunner245_*Beta.zip`
links are 404). The 13.70 beta was OTA-only:
https://forums.garmin.com/beta-program/forerunner-245-series/f/announcements/392136/public-beta-version-13-70---100

The practical sources are:

1. The watch itself via USB: look for `GARMIN/GUPDATE.GCD`,
   `GARMIN/RemoteSW/GUP3078.GCD`, or any other `.gcd` file. The main firmware
   update is sometimes removed after installation, but the remote-SW file often
   remains.
2. The Garmin Express cache on the PC that performed the update (search for
   `*.gcd` under the Garmin Express/CoreService data directories).
3. A beta firmware zip if one is still mirrored. Only use official Garmin
   images; do not redistribute them.

### What to look for in the firmware

After extracting `FW_ALL_BIN` and loading it at `0x3000`:

- strings: `PendingHD`, `ACTIVITY`, `ARCHIVE`, `PENDING`, `FILTER`,
  `FIT_TYPE`, `DIRECTORY`;
- the GFDI 5007 handler and the function that builds the directory response;
- whether the directory enumerator walks the FAT/activity directory or a
  separate pending-upload index;
- whether filter `0` (`NO_FILTER`) includes files with the `ARCHIVE` flag.

That would settle the root cause:

- if the enumerator only reads a pending-upload index, **no BLE filter can
  recover an archived activity** and USB is the only local route;
- if it reads the FAT directory but applies `ARCHIVE` filtering, a filter-0
  request can expose it and a recovery mode is possible;
- if activities land in `PendingHD` and the enumerator does not recurse into
  subdirectories, that explains both the Garmin and third-party failures.

The firmware is proprietary. Use it only for interoperability/research on your
own device and do not redistribute the image.

### First result — firmware 3.10 (2026-10-27)

Downloaded the official `Forerunner245_310.gcd` from
`https://download.garmin.com/software/Forerunner245_310.gcd`
(4,245,103 bytes, SHA-256
`ffc802fd505cb62fe680dd50935654ef8177c8a0c74d20de3a6b32a4d185ff43`)
and extracted `fw_all_310.bin` (4,201,216 bytes, record `0x02bd`). The image is
ARM Cortex-M code based at `0x3000` (vector table at the start: SP
`0x2002e388`, reset `0x31f1`).

Strings confirm the file/activity subsystem is in the image: `FIT_TYPE_4`,
`FIT_TYPE_32`, `FIT_TYPE_44`, `FIT_TYPE_41`, `FIT_TYPE_49`,
`time_sorted_activity_list_smphr`, `activity_file_smphr`,
`TempFIT/Activity`, `0:/Garmin/NewFiles`, `0:/Garmin/RemoteSW/`,
`0:/Garmin/GUPDATE.GCD`.

The GFDI handler registration function is at `0x39208`. It registers:

| Type | Handler |
|---|---|
| 5002 | `0x396cc` |
| 5003 | `0x393c4` |
| 5022 | `0x380c8` |
| 5004 | `0x38bb8` |
| 5005 | `0x384dc` |
| 5006 | `0x38ae4` |
| 5007 | `0x3892c` |
| 5008 | `0x38a6c` |
| 5031 | `0x38284` |

**The 5007 handler ignores the filter byte.** It only checks whether the
request has a non-empty payload: non-empty → `RESPONSE` ACK; empty → NAK via
`0x384b4`. It never stores the filter value and no code path applies
`0`/`1`/`3`. On firmware 3.10, `DIRECTORY_FILTER` is a no-op; the file list is
whatever the internal file table returns.

The 5002 handler (`0x396cc`) builds the directory response. For file index 0 it
calls a file-list helper (`0x3ec90`, `0x3f40c`, `0x3ed60`, `0x3a1f8`,
`0x39f58`) and converts each entry with `0x3844c` into the 16-byte directory
record. The decision to include/exclude an activity is therefore in the file
entry state/flags, **not** in the 5007 filter value.

This matches the field log: the watch returns the same two wellness files for
every filter. The audit on 13.70 is still useful, but the 3.10 code already
shows that changing the filter alone cannot make an archived activity appear.
Recovering it over BLE would require the internal file entry to be back in the
directory/list state; USB is the practical route.


