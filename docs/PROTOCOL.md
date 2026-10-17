# Garmin watch BLE protocol — reverse-engineering notes

Recovered from `com.garmin.android.apps.connectmobile` **v5.19.1 (build 10364)**
(the APKMirror bundle in this repo) and cross-checked against the community
documentation at <https://gadgetbridge.org/internals/specifics/garmin-protocol/>
and the reference implementation `wh1le/garmin-bridge`.

Target: **Garmin Forerunner 245/255/955/965** → **GFDI v2 / Multi-Link** over BLE.

Everything in this document was either read out of the APK or verified against a
known-good algorithm/vector. Verified values are marked ✅.

---

## 1. APK decomposition

The supplied `.apkm` is an APKMirror bundle (a ZIP of split APKs):

| Entry | Size | Notes |
|---|---|---|
| `base.apk` | 137 MB | 12 `classes*.dex`, resources, assets |
| `split_config.arm64_v8a.apk` | 17 MB | 23 native `.so` (media/maps/ML — **not** the watch protocol) |
| `split_config.armeabi_v7a.apk` | 14 MB | 32-bit natives |
| `split_config.<dpi>.apk` | — | density splits |
| `split_config.<lang>.apk` | — | 22 language splits |

Key fact: **the watch protocol is pure Java/Kotlin** (DEX), not native. The
interesting classes live in these packages:

| Package | Contents |
|---|---|
| `com.garmin.device.multilink` | Multi-Link multiplexer + **Multi-Link Reliable (MLR)** |
| `com.garmin.android.gfdi.framework` | GFDI device façade (`GfdiDeviceCompat`, `FileManagerCompat`, `PacketQueue`, `SetDeviceSettingsCompat`, `SystemEventCompat`) |
| `com.garmin.android.gfdi.filetransfer` | `RemoteFile`, file read options, delete/response messages |
| `com.garmin.device.filetransfer` | file-transfer state machine |
| `com.garmin.device.pairing` / `ble` | pairing / auth |
| `com.garmin.proto.generated.GDI*` | protobuf services (`GDICore`, `GDIFileAccess`, `GDILiveSync`, `GDIHeartRate`, …) |
| `LBO/h;` (obfuscated) | **all Garmin BLE UUID constants** |

`assets/client_config.xml` declares the mobile client's protocol identity:

```xml
<client>connect-mobile</client>
<client_uuid>0x01</client_uuid>
<supported_service_types>
  <value>GFDI</value><value>CONNECT_IQ</value><value>REAL_TIME</value>
  <value>NFC</value><value>KEEP_ALIVE</value>
</supported_service_types>
```

---

## 2. BLE GATT layout

The watch exposes a Garmin-specific service plus standard SIG services.

### 2.1 Multi-Link service — `6A4E2800-667B-11E3-949A-0800200C9A66`

Every GFDI/ML payload flows through **pairs** of characteristics: you *write* to a
`…82x0` characteristic and *notify* on its `…81x0` partner (or vice-versa).

| Characteristic | Properties | Role |
|---|---|---|
| `6A4E2803-…` | READ, WRITE | control / unknown |
| `6A4E2810-…` | READ, WRITE, NOTIFY | pair 1 (notify) |
| `6A4E2820-…` | WRITE | pair 1 (write) |
| `6A4E2811-…` | READ, WRITE, NOTIFY | pair 2 (notify) |
| `6A4E2821-…` | WRITE | pair 2 (write) |
| `6A4E2812-…` | READ, WRITE, NOTIFY | pair 3 (notify) |
| `6A4E2822-…` | WRITE | pair 3 (write) |

Newer devices (Venu 3) add `2813/2823` and `2814/2824`; some (Vivosmart 5) only have
pairs 1–2. Discover them dynamically: try `2810..2814` against `2820..2824` and use
the first pair that exists. ✅ (matches `transport.py`)

> Note: the same 16-bit suffix family is used throughout the Garmin UUID namespace:
> `6A4E%04X-667B-11E3-949A-0800200C9A66`.

### 2.2 Other Garmin services seen in `BO/h.smali`

| UUID | Role |
|---|---|
| `6A4E2401-667B-…` + `6A4E2403-…` | legacy **GFDI v1** service/characteristic (pre-Multi-Link watches) |
| `6A4E8022-667B-…` | GFDI-era service with `6A4E4C80` (WRITE) + `6A4ECD28` (READ, NOTIFY) |
| `6A4E3E10-…`, `6A4E4350-…`, `6A4E5000-…`, `6A4E544F-…`, `6A4E564B/564C-…` | additional Garmin services (device-class specific: marine/nautix/etc.) |
| `0000FE1F-…` | 16-bit `FE1F` (Garmin vendor) |
| `00001818-…`, `00002A66-…` | SIG Cycling Power service/measurement |
| `00001001-7791-11E2-9452-F23C91AEC05E` | SIG Running Speed & Cadence (RSC) |

Battery / Device Information (`180F`, `180A`) and Heart Rate (`180D`) are standard
SIG services the app also reads directly.

---

## 3. Multi-Link (ML) — handle management

Multi-Link multiplexes many logical "services" over one characteristic pair. Each
service is registered to a single-byte **handle**. ML frames start with `0x00`
(control) followed by a message type; service payloads start with their non-zero
handle byte. All integers little-endian.

| Byte | Message |
|---|---|
| `0x00` | Register service request |
| `0x01` | Register service response |
| `0x02` | Close handle request |
| `0x03` | Close handle response |
| `0x04` | Unknown handle response |
| `0x05` | Close all handles request |
| `0x06` | Close all handles response |
| `0x07`/`0x08` | Unknown request/response |
| `0xff` | Protocol error |

General frame: `00 <type> <client_uuid:8> <service:2> <args…>`.

**Register request** = `00 00 <client_uuid:8> <service:2> <reliable>`
- `reliable = 0x00` → use ML; `0x02` → request MLR.
- Example: `0000 0100000000000000 0400 00` registers service `0x04` (REGISTRATION) as ML for client `1`.

**Register response** = `00 01 <client_uuid:8> <service:2> <status> …`
- status: `0x00` SUCCESS, `0x01` INVALID_SERVICE_ID, `0x02` PENDING_AUTH, `0x03` ALREADY_IN_USE, `0x04` REJECTED.
- On SUCCESS the assigned handle follows (see doc for the exact trailing bytes).

**Close all** = `00 05 <client_uuid:8> 0000` → watch replies `00 06 …`.

### 3.1 Service IDs

| id | Service | id | Service |
|---|---|---|---|
| 1 | `GFDI` | 13 | `REAL_TIME_STRESS` |
| 2 | `NFC` | 14 | `AUTH_STATUS` |
| 3 | `HEALTH_SDK` | 15 | `ECHO` |
| 4 | `REGISTRATION` | 16 | `REAL_TIME_ACCELEROMETER` |
| 5 | `CONNEXT` | 17 | `REAL_TIME_SPAM` |
| 6 | `REAL_TIME_HR` | 18 | `REAL_TIME_BMX_RAW` |
| 7 | `REAL_TIME_STEPS` | 19 | `REAL_TIME_SPO2` |
| 8 | `REAL_TIME_CALORIES` | 20 | `REAL_TIME_BODY_BATTERY` |
| 9 | `REAL_TIME_FLOORS` | 21 | `REAL_TIME_RESPIRATION` |
| 10 | `REAL_TIME_INTENSITY` | 22 | `KEEP_ALIVE` |
| 11 | `REAL_TIME_DUMMY` | 26 | `REAL_TIME_ACTIVE_TIME` |
| 12 | `REAL_TIME_HRV` | | |

The FR245 advertises `1,4,6,7,8,10,12,13,16,19,20,21,22`.

### 3.2 Registration service (id 4)

Query device identity after registering it. Request = one byte; reply on the same
handle.

| Req | Meaning | Example reply |
|---|---|---|
| `0x00` | SUPPORTED_PROTOCOLS (bitmask of service ids) | `d2 35 79` → `[1,4,6,7,8,10,12,13,16,19,20,21,22]` |
| `0x01` | ADVERTISING_DATA | `00 13 40` |
| `0x02` | MULTI_LINK_VERSION | `01 02 02` → 2.2.1 |
| `0x03` | PRODUCT_NUMBER + firmware + unit id | `040c 1405 deadbeef` |
| `0x04` | IDENTITY_ADDRESS | 16 bytes |

`unit id` is the device's server identity (Garmin uses it for account binding) — we
use it only as a local device key.

### 3.3 Establishing a session (typical)

1. Connect, discover the ML characteristic pair, enable notify.
2. Send `CLOSE_ALL` (`00 05 …`) — clears any stale handles.
3. Register `GFDI` (id 1, ML) → capture handle.
4. (Optional) register `REGISTRATION` (id 4) and query identity; then close it.
5. (Optional) register `KEEP_ALIVE` (id 22).
6. Exchange the handshake (section 5).

---

## 4. Multi-Link Reliable (MLR)

When requested at registration and supported, MLR replaces ML framing. A 2-byte
header encodes handle, request number and sequence number:

```
bit:   1 H H H R R R R   R R S S S S S S
byte0: 1 | handle(3) | req_num[5:2]
byte1: req_num[1:0] | seq_num[6:0]... (see doc for exact packing)
```

- `handle` ∈ `0x80..0x87`; `req_num`/`seq_num` ∈ `0x00..0x3f`, wrapping.
- `req_num` acknowledges everything with `seq < req_num` (ACK piggyback).
- Retransmission: on timeout, rewind `next_send_seq`; timeout doubles up to 20 s,
  send window (`max_num_unacked_send`, init `0x20`) halves.
- Initial state: `max_packet_size = 20`, `retransmission_timeout = 1000 ms`.

MLR is **optional** for a first version — plain ML works and is simpler. Implement
ML first, add MLR when reliability on long FIT transfers demands it.

---

## 5. GFDI framing

The GFDI service payload (registered as service id 1) is a stream of framed
messages. Each message is **COBS-encoded** before being chunked into the ML
transport, and each logical GFDI message is `[size:2][type:2][payload:N][crc:2]`,
all little-endian.

### 5.1 Checksum (✅ verified)

CRC-16 with polynomial `0x8005` (reflected), initial value `0x0000`, **no** final XOR,
computed with a nibble lookup table (Dallas/Maxim style). It is `crcmod`'s `crc-16`.

```
TABLE = [0x0000,0xCC01,0xD801,0x1400,0xF001,0x3C00,0x2800,0xE401,
         0xA001,0x6C00,0x7800,0xB401,0x5000,0x9C01,0x8801,0x4400]

crc = 0
for b in data:
    crc = ((crc>>4) ^ TABLE[crc & 0x0F]) ^ TABLE[b & 0x0F]
    crc = ((crc>>4) ^ TABLE[crc & 0x0F]) ^ TABLE[(b>>4) & 0x0F]
```

Verified vectors (body bytes → CRC, stored little-endian):

| body | CRC (LE) |
|---|---|
| `09 00 08 98 28 01 10` | `d7 f5` ✅ |
| `0d 00 00 96 90 13 00 00 c5 00 10` | `31 b1` ✅ |
| `0d 00 00 98 90 13 00 00 27 01 10` | `11 9b` ✅ |

### 5.2 COBS encoding

Garmin uses a COBS variant that wraps the encoded body in delimiters:

```
<0x00>  COBS(payload)  <0x00>
```

- Runs of non-zero bytes are prefixed by `run_length + 1`.
- `0x00` bytes become the boundary between runs.
- Runs ≥ `0xFE` are split into `0xFF` chunks of 254 bytes.
- A trailing zero byte produces a final `0x01`.
- Decoder: strip leading/trailing `0x00`, then walk code bytes (`code-1` data bytes, then a virtual `0x00` unless `code == 0xFF`).

### 5.3 Message type encoding & sequence numbers

`type` is a little-endian `uint16`. If the high bit is set
(`type & 0x8000`), the **low byte is `type - 5000`** and bits 8–12 carry a
5-bit sequence number used to correlate request/response:

```
# Request for message 5008 with sequence 0x18:
09 00 | 08 98 | 28 01 10 | d7 f5
size=9  type    payload    crc
  type bytes 08 98 => 0x9808; 0x9808 & 0x8000 => (0x9808 & 0xFF)+5000 = 5008, seq=0x18
```

### 5.4 Message types

| Type | Name |
|---|---|
| 5000 | `RESPONSE` |
| 5007 | `DIRECTORY_FILTER` |
| 5008 | `SET_FILE_FLAGS` |
| 5011 / 5012 | `FIT_DEFINITION` / `FIT_DATA` |
| 5014 | `WEATHER_REQUEST` |
| 5024 | `DEVICE_INFORMATION` |
| 5026 | `DEVICE_SETTINGS` |
| 5030 | `SYSTEM_EVENT` |
| 5031 | `SUPPORTED_FILE_TYPES_REQUEST` |
| 5033–5036 | `NOTIFICATION_*` |
| 5043 / 5044 | `PROTOBUF_REQUEST` / `PROTOBUF_RESPONSE` |
| 5050 | `CONFIGURATION` |
| 5052 | `CURRENT_TIME_REQUEST` |
| 5101 | `AUTH_NEGOTIATION` |

### 5.5 Responses

`RESPONSE (5000)` = `[size][5000][original_type:2][status:1][payload]`. Status:

| 0 | ACK |
| 1 | NAK |
| 2 | UNKNOWN_OR_NOT_SUPPORTED |
| 3 | COBS_DECODER_ERROR |
| 4 | CRC_ERROR |
| 5 | LENGTH_ERROR |

---

## 6. Session handshake

After GFDI registration the watch pushes a handshake; the phone must answer to have
the watch treat it as a valid companion:

| Watch → | Phone reply |
|---|---|
| `DEVICE_INFORMATION (5024)` | echo protocol version/flags + host strings ("Android" etc.) |
| `CONFIGURATION (5050)` | echo capability bytes back as `CONFIGURATION` |
| `AUTH_NEGOTIATION (5101)` | `RESPONSE ACK` with `0,0` (no PIN auth on these models) |
| `CURRENT_TIME_REQUEST (5052)` | `RESPONSE` with `ref_id`, Garmin-epoch time, local offset |
| `NOTIFICATION_SUBSCRIPTION (5036)` | `RESPONSE` echoing enable flag |
| `PROTOBUF_REQUEST (5043)` | GDI layer: decode + answer with `PROTOBUF_RESPONSE (5044)` when a response is required |

The phone also sends `DEVICE_SETTINGS (5026)` to enable auto-upload/weather, and a
`SYSTEM_EVENT (5030)` `SYNC_READY` to nudge a sync.

**Garmin epoch** = `1989-12-31T00:00:00Z` (unix `631065600`).

---

## 7. File / FIT sync

Activities and health data live on the watch as **FIT files** in its file index.
Sync is:

1. `DIRECTORY_FILTER (5007)` — `00` no filter, `01` default (pending upload), `03` pending only.
2. Read the directory listing (file index + flags + size + type/subtype).
3. For each file: request **read** (`READ` flag `0x80`) and stream the bytes.
4. After successful handling: `SET_FILE_FLAGS (5008)` with `ARCHIVE` (`0x10`)
   (and optionally `ERASE` `0x20`) to mark it consumed.

`SET_FILE_FLAGS` payload = `[file_index:2 LE][flags:1]`.

File flags: `0x04` CRYPTO, `0x08` APPEND, `0x10` ARCHIVE, `0x20` ERASE, `0x40` WRITE, `0x80` READ.

Directory listing flags include `WRITE`/`READ`/`ERASE` to indicate state.

FIT itself is a documented, public binary format (Garmin FIT SDK) — parsing it into
records is a separate, well-understood step.

---

## 8. protobuf (GNCS) services

In addition to GFDI framing, newer flows use generated protobuf messages under
`com.garmin.proto.generated.GDI*` (e.g. `GDICore$CoreService`, `GDIFileAccess$Service`,
`GDILiveSync$Service`, `GDIGarminHealth$Service`, `GDIConnectIQ`). These are sent via
the `PROTOBUF_REQUEST/RESPONSE (5043/5044)` GFDI messages. Not required for a
first FIT-sync implementation; documented here for later features (live sync,
Connect IQ, GPS CPE updates).

---

## 9. Test anchors (from the APK / docs)

- Registration wire example: `3200` → `3200 d23579` (supported services bitmask).
- Archive file request (decoded): `09 00 08 98 28 01 10 d7 f5`.
- Archive file response (decoded): `0d 00 00 96 90 13 00 00 c5 00 10 31 b1`.

These are used as unit-test fixtures in `protocol/src/test`.

---

## 10. GDI protobuf layer (modern file access)

The Forerunner 255 + Garmin Connect 5.30.1 **does not use the legacy GFDI
`DIRECTORY_FILTER (5007)`** — it replies `UNSUPPORTED`. File access runs over a
protobuf "GDI" layer instead.

### 10.1 Protobuf schemas

All 141 `GDI.Proto.*` FileDescriptorProtos are embedded (as Java strings) in the
generated classes in the APK and have been extracted to [`docs/protos/`](protos/).
The relevant ones:

| File | Purpose |
|---|---|
| `GDISmart.proto` | empty `Smart` base message |
| `GDIFileAccessExtension.proto` | `Smart.file_access_service = 43` |
| `GDICoreExtension.proto` | `Smart.core_service = 13` |
| `GDIDataTransferExtension.proto` | `Smart.data_transfer_service = 7` |
| `GDIFileAccess.proto` | ItemList / Pull / Push / ModifyFlags / checksum |
| `GDICore.proto` | capabilities, time sync, connection ready |
| `GDIDataTransfer.proto` | chunked data download/upload |
| `GDIDataTypes.proto` | `UUID` (`fixed64 most/least`), `ScPoint`, enums |

### 10.2 Transport

GDI messages have a direction-dependent GFDI type (verified against the FR255
and Garmin Connect's `qX.d`):

| Direction | GFDI type | Meaning |
|---|---|---|
| phone → watch | **5043** (`0x13b3`) | phone-initiated request |
| watch → phone | **5043** (`0x13b3`) | device-initiated request / notification |
| watch → phone | 5044 (`0x13b4`) | reply to a phone request |
| phone → watch | 5044 (`0x13b4`) | response to a device-initiated request |
| watch → phone | 5045 / 5057 | protobuf notification variants |

> The watch sends `ConnectionReadyNotification` and other device-initiated GDI
> messages as 5043. The client must parse 5043 (it used to discard it as a
> cancellation notice) or it never learns that a GDI session is ready.
> The watch also sends several requests in that burst (SMS canned list, audio
> language, Core location settings/get-location). They must be answered over
> 5044, otherwise the watch retransmits the whole burst every few seconds and
> will not serve the file list.

Each GDI message = 14-byte header + a protobuf chunk (little-endian):

```
[u16 id][u32 offset][u32 totalLength][u32 chunkLength][chunk]
```

- `offset` is the byte position of this chunk in the full `Smart` message.
- Large messages are split; reassemble by `id` + `offset` until `offset+len == totalLength`.
- Payload is `GDI.Proto.Smart.Smart` with extension fields selecting a service.

**Every GDI frame is ACKed at the GFDI layer, per chunk.** The ACK is a normal
`RESPONSE (5000)` with `original_type = 5043/5044`, status `ACK`, the request's
sequence number echoed, and this 8-byte payload (verified in Garmin Connect
`qX.d.onMessageReceived`):

```
[id: u16 LE][offset: u32 LE][ok: u8][code: u8]
ok = 0 valid, 1 invalid
code: 0 NO_ERROR, 100 UNKNOWN_REQUEST_ID, 101 DUPLICATE_PACKET,
      102 MISSING_PACKET, 103 EXCEEDED_PROTOBUF_LENGTH, 200 PARSE_ERROR,
      201 UNKNOWN_PROTOBUF_MESSAGE
```

The ACK must be sent for **every chunk**, not only the last one. ACKing only the
completed message makes the watch emit the first 487 bytes of a response and
then restart the GDI session every five seconds.

### 10.3 Session startup (observed against FR255)

1. GFDI register + `DEVICE_INFORMATION` / `CONFIGURATION` handshake (section 6).
2. The watch opens a GDI session with a burst of device-initiated messages over
   GFDI 5043: LiveTrack status, SMS canned-list request, `ConnectionReadyNotification`,
   DeviceMessages subscribe, AudioPrompts set-language request, RealtimeSettings,
   Core location-settings / get-location requests.
3. **Answer every request in that burst over GFDI 5044**, echoing the GDI id:
   `SmsCannedListResponse`, `SetLanguageResponse`, `LocationUpdatedSetEnabledResponse`,
   `GetLocationResponse`, `CIQMessageResponse`, and `ConnectIQHTTPResponse`
   (offline: `NETWORK_REQUEST_TIMED_OUT`). Ignoring them makes the watch retry
   the whole burst and withhold the file list.
4. Send `FeatureCapabilitiesRequest` (`Smart.core_service = 13` →
   `CoreService.feature_capabilities_request = 8`) with the FileAccess capability
   extension (`request_file_access_capabilities = 16`).
5. Watch replies `FeatureCapabilitiesResponse` (`CoreService.feature_capabilities_response = 9`).
6. Send `ItemListRequest` (`Smart.file_access_service = 43` →
   `FileAccess.Service.item_list_request = 9`).
7. Watch replies `ItemListResponse` (`Service.item_list_response = 10`) with
   `items: repeated ItemReference`, split into 487-byte chunks. ACK each chunk.

`ItemListRequest` fields: `session_id=1`, `transaction_id=2`, `max_item_count=3`,
`exclude_all_flags=4`, `get_flags=5`, `included_data_types=6`.
`ItemListResponse` fields: `status=1`, `session_id=2`, `next_transaction_id=3`,
`items=4`.
`ItemReference`: `uid=1` (UUID), `data_type=2`, `data_size=3`,
`requested_flags=4`, `transaction_id=5`, `urgency=6`.

### 10.4 Quirks (important)

- After a GDI exchange the watch **closes the GFDI handle**. The client must cycle
  a Multi-Link `CLOSE_ALL` and re-register GFDI; registering immediately can return
  status `0x03` (`ALREADY_IN_USE`).
- The watch does **not** always echo the request's GDI `id`; responses can arrive
  with the watch's own id sequence. Handle responses by content (service field),
  not only by id.
- A real capture of the FR255 directory (78 items, ~430 KB) is in
  [`docs/samples/fr255_file_list.txt`](samples/fr255_file_list.txt). Items carry a
  `gdxml_string` such as `FIT_TYPE_2`, `FIT_TYPE_3`, `FIT_TYPE_29`, …
  `FIT_TYPE_4` is activity.
- The watch usually sends `gdxml_string` only on the first item of each data
  type. Later items of the same type set `gdxml_string_key` instead (for
  example key `1` resolved to `FIT_TYPE_4` on the FR255) and leave the string
  empty. Build a
  key-to-string lookup from the typed items before classifying the rest;
  matching only on `gdxml_string` drops every activity after the first one.

### 10.5 Pulling a file — VERIFIED

`PullItemDataRequest` (FileAccess.Service field 1):

```
item                  = ItemReference { uid, data_size, transaction_id, data_type }
priority              = 30 (USER_INITIATED_START) .. 39
supported_transports  = [MULTILINK_TRANSPORT_PIPE = 0]
offset                = 0
requested_compression_window = 15 (optional)
```

Verified response on the FR255:

```
PullItemDataResponse {
  result             = 0 (SUCCESS)
  transport          = 0 (MULTILINK_TRANSPORT_PIPE)
  transfer_handle    = 683
  compression_window = 11
}
```

#### Register-response encoding (important)

A reliable registration response is
`[type][clientId:8][service:2][status][handleByte][reliable][mlService]`.

- `reliable != 0` means MLR.
- The **real MLR handle is `handleByte & 0x07`**; the `0x80` bit is a reliable
  marker. Example: `81 01 00` → reliable handle 1; `83 01 01` → handle 3.
- Non-reliable responses use the full byte as the ML handle.

#### Transport pipe

Register one of `0x2018, 0x4018, 0x6018, 0x8018, 0xA018, 0xC018, 0xE018` as
**reliable**. Then:

```
configure request : [0x00][direction: 0=read, 1=write][transferHandle: u32 LE]
configure response: [0x00][generalStatus][configureStatus]
data              : zlib stream, delivered as MLR fragments
```

`generalStatus`: 0 OK, 1 unknown command, 2 invalid data.
`configureStatus`: 0 OK, 1 unknown handle, 2 unexpected direction, 3 unexpected size.

Important: **do not stop at `ItemReference.data_size`**. That is not the
compressed length. Read until the device closes the transport-pipe service
(`CLOSE_HANDLE` for the pipe service), then decompress the collected bytes with
standard **zlib** (`Inflater` / `zlib.decompress`).

Verified end-to-end: a 3966-byte pipe stream decompressed to **40292 bytes** of
valid file data (an XML settings file in this run). FIT activity files use the
same path.

APK classes: `com.garmin.device.filetransfer.mlr.MLRTransportPipe`
(obfuscated `CT/e`, `CT/e$a`) and the low-level pipe `DT/o`.



