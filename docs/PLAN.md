# Project plan

_Last updated: 2026-10-19. Current baseline: `eebd0d4`._

## Goal & scope

Build an **Android** app that connects to a Garmin watch over BLE, syncs
activities and health data into an **on-device database**, and presents a
local-first dashboard with an optional OpenAI-compatible coach.

Non-goals for v1: Connect IQ store, firmware updates, Garmin servers, iOS/desktop,
cloud accounts, and telemetry.

## Constraints & decisions

| Decision | Choice |
|---|---|
| Protocol generation | GFDI **v2 / Multi-Link** (Forerunner 245/255/955/965 and related) |
| Transport | MLR pipe for file transfers; zlib-compressed FIT payloads |
| Language | Kotlin; the protocol core is pure JVM and unit-testable |
| Storage | Room 2.6.1, database **version 9**, raw FIT files kept on disk |
| Local preferences | SharedPreferences for profile/sync metadata, provider configs, and coach conversations |
| UI | Jetpack Compose + Material 3 with the Pulse design tokens |
| Networking | OkHttp only for the user-configured LLM endpoint; osmdroid for map tiles |
| Background scheduling | AndroidX WorkManager for optional periodic BLE sync with battery/charging constraints and exponential backoff |
| Secrets | Android Keystore AES-GCM for provider API keys |
| Data ownership | Local database + FIT files; user-initiated ZIP backup/restore of the database and conversations; per-activity FIT/GPX/TCX/CSV export |

## Layered architecture

1. **`protocol/` — wire codec (done)**
   - `Crc16`, `Cobs`, `Gfdi` framing, `MultiLink`/`Mlr` transport, `Handshake`,
     `Gdi`, `TransportPipe`.
   - `Fit`/`FitProfile` parser covering every FIT message and field.
   - No Android imports; runs under `:protocol:test`.

2. **`app/` — Android integration**
   - `GarminBleClient` (scan, connect, reconnect, MTU, write queue) and
     `SyncCoordinator` (capabilities, directory pagination, wellness cut, item
     pulls, retries).
   - `SyncForegroundService` and `SyncNotifications` for progress/finish states.
   - Room entities/DAO/migrations in `AppDatabase`, plus `ActivityRepository`,
     wellness/training parsers, and `BackupManager`.
   - Compose UI in `ui/`: five tabs, detail/settings/provider/log routes, shared
     components, charts, and the coach chat.
   - `llm/` provider store and streaming OpenAI-compatible client; `coach/` tool
     registry, evidence, chat loop, and multi-conversation history.

## Milestones

| # | Milestone | Exit criteria |
|---|---|---|
| **M0** | RE + protocol core | ✅ protocol library builds; CRC/COBS/framing vectors pass |
| **M1** | BLE connect & identify | ✅ app connects, registers GFDI, discovers the ML pair |
| **M2** | Handshake | ✅ DEVICE_INFORMATION / CONFIGURATION / AUTH / time exchange works |
| **M3** | File listing | ✅ GDI capabilities + paged `ItemListResponse` |
| **M4** | FIT download | ✅ transport pipe + zlib verified |
| **M5** | Deduplication | ✅ imported item UIDs skip re-download; idempotent sync |
| **M6** | FIT → DB | ✅ activities, records, laps, messages, wellness in Room |
| **M7** | Polish | ✅ daily wellness, Body Battery, sleep/HRV paths, training metrics, foreground sync, manual FIT import |
| **M8** | Pulse UI + coach | ✅ five-tab UI, settings, provider/model management, streaming tool-calling coach, evidence, charts |
| **M9** | Local data ownership | ✅ multi-conversation history and full database backup/restore (2026-10-18) |
| **M10** | Activity export | ✅ local FIT/GPX/TCX/CSV export from activity detail (Epic G / G5, 2026-10-19) |
| **M11** | Background sync | ✅ optional WorkManager periodic sync, single-flight gate, retry/backoff, persisted history and settings (Epic G / G3, 2026-10-20) |

## Testing strategy

- **Unit tests** (`:protocol:test`, `:app:testDebugUnitTest`) against protocol
  vectors, FIT fixtures, mock OpenAI servers, and pure data/formatting logic.
- **Instrumented test** for the Android Keystore secret store.
- **CI** (`.github/workflows/android.yml`) runs protocol tests, app
  unit/integration tests, and `assembleDebug`.
- **Device checks** on a real watch/phone cover BLE sync, wellness import,
  notifications, charts, provider configuration, coach streaming, backup/restore,
  activity export, background sync, and conversation history. See
  [`testing.md`](testing.md).
- **Live capture** (developer-only): `adb logcat` for BLE frames and, where
  available, HCI snoop logs for comparison with Garmin Connect.

## Risks

| Risk | Mitigation |
|---|---|
| MLR required for large FIT transfers | implemented and verified on FR255 |
| Pairing/auth differences per model | capability-driven handshake; capture real traffic |
| FIT parsing breadth | generated profile + typed fallback names for unknown fields |
| Health-data exposure through the coach | typed read-only tools, explicit scope, local provider option, privacy docs |
| Destructive restore/import | schema validation, safety copy, confirmation dialog, flow restart |
| AGPL contamination | independent implementation from protocol facts; see `LICENSING.md` |

## Immediate next actions

1. Add Room migration/importer tests and an instrumented end-to-end backup test.
2. Decide whether to request historical wellness days; until then, accumulate
   history by syncing repeatedly.
3. Add richer connected-device state and watch battery/storage (G4).
4. Tighten cleartext handling for non-local provider endpoints.
5. Add Compose UI/screenshot tests for the tab shell, coach history dialog, and
   backup settings card.
6. Consider optional encryption for exported backups.
7. Use [`garmin-connect-gap-analysis.md`](garmin-connect-gap-analysis.md) as the
   prioritized input for Garmin Connect parity and differentiator work; its P0
   items come before new surface area.
