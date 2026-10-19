# BACKLOG — Pulse UI + AI coach

_Last updated: 2026-10-19. Baseline commit: `eebd0d4`. Phase 0 is complete, epics A–E are implemented, F is partially done, and the Garmin Connect parity analysis is tracked in [`docs/garmin-connect-gap-analysis.md`](docs/garmin-connect-gap-analysis.md)._

This backlog turns the Pulse mockups and the OpenAI-compatible coach brief into
reviewable increments. It assumes the current app is a local-first Android
Compose app with Room, no auth, an OpenAI-compatible coach, local conversation
history, and database backup/restore; see
[`docs/architecture-notes.md`](docs/architecture-notes.md).

## How to read this

- **Size:** S ≈ 1–2 days, M ≈ 3–5 days, L ≈ 1–2 weeks (rough, for one developer).
- **Status:** Phase 0 and epics A–E are done to the level recorded below; F is
  partially done and G is a planned parity/differentiation programme. Individual
  checkboxes are kept as the original acceptance criteria rather than rewritten
  retroactively.
- **Order:** A → B → C → D → E → F, then the G waves (trust, daily parity,
  differentiation). The remaining engineering work is primarily F (tests,
  accessibility, hardening), the data-coverage items under open questions, and
  the P0 items in the gap analysis.
- **Definition of done for every story:** acceptance criteria met, tests added,
  loading/empty/error states handled, accessibility checked, docs updated,
  `BACKLOG.md` status updated, and a small reviewable commit/PR.
- **Hard rules:** no fake or hardcoded data in shipped screens; real buttons and
  links; ≥44 dp touch targets; ≥4.5:1 text contrast; no new UI framework; API
  keys never returned to the UI after save and never logged; ask before adding a
  major dependency, destructive schema change, or deviating from a mockup.

## Status at a glance

| Epic | Outcome | Status |
|---|---|---|
| Phase 0 | Architecture, mockups, tokens, backlog | ✅ Done |
| A. Design system | Token-based Compose primitives matching mockups | ✅ Done |
| B. Screens | Today, Health, Training, Activities, Coach, real data + states | ✅ Done (background sync remains open) |
| C. LLM provider settings | Presets, encrypted keys, models, test connection, streaming transport | ✅ Done |
| D. LLM data access | Typed read-only health tools + evidence + safety | ✅ Done |
| E. Coach chat | Streaming chat with tool calls, cancel, sources, persistence, history | ✅ Done |
| F. Quality | Tests, accessibility, performance, CI, docs, privacy audit | Partially done |
| G. Garmin Connect parity | P0 trust/daily-usability stories defined (G1–G10) | 📋 Planned — see [Epic G](#epic-g--garmin-connect-parity-p0-trust-and-daily-usability) |

## Implementation update — 2026-10-17

Shipped in this pass (see commits/working tree):

- **A:** `PulseTheme` tokens, bundled DM Sans/Space Grotesk, cards, tiles,
  chips, segmented control, buttons, tab bar, empty/error/loading states,
  progress ring, bar/line charts, zone bar, chat components.
- **B:** five-tab app shell (Today, Health, Training, Activities, Coach) +
  detail/settings/provider/log routes; Today, Health, Training, Activities,
  Activity detail, Coach shell and full chat; persisted sync metadata; profile
  settings (goal, max HR); Body Battery ingestion; watch-only training metrics
  (VO₂ max, recovery, load, LT, training effect); sleep/HRV import paths with
  honest empty states.
- **C:** provider presets (OpenAI, **DeepSeek**, OpenRouter, Ollama, LM Studio,
  vLLM, Custom), model add/edit/delete/default, temperature/max tokens,
  Keystore-encrypted key, masked key, test connection, BuildConfig env fallback,
  streaming client with cancel/timeouts/retries, cleartext warning, redacted
  logging.
- **D:** typed read-only tool registry, local-profile scope, range/row/call
  caps, daily/steps/HR/sleep/list/get/compare tools, compact snapshot fallback,
  evidence descriptors, untrusted-data wrapping and medical-safety prompt.
- **E:** persistent local chat, streaming updates, cancel, tool loop, evidence
  chips from actual calls, provider-gated onboarding, suggestions, errors,
  safety copy.
- **F:** tests, CI workflow, provider/tools/testing/privacy docs. At the time
  this was 25 app JVM tests and 35 protocol tests; the current counts are 72 app
  and 44 protocol tests (backup/conversation work added more).

Known deviations / still open:

- Provider/model config uses private SharedPreferences JSON instead of Room
  tables. Keys are still Keystore-encrypted; this avoids a schema migration and
  is easy to move to Room later. Coach conversations use the same approach (with
  a JSON codec and legacy migration) instead of Room tables, so they are included
  in the backup's `chat.json` rather than in the SQLite snapshot.
- B1 uses screen state holders in the activity/Compose state rather than
  ViewModels. Screens are split out of `MainActivity` and state survives tab
  changes, but process-death restoration is limited.
- B2 persists sync state but does **not** add WorkManager background sync.
- B3a/B3b are **partially implemented**: Body Battery, sleep and HRV parsing and
  pull paths exist, but the FR255 used for development did not expose a
  SLEEP_DATA file during daytime syncs. Historical wellness is still only
  accumulated by repeated syncs. The UI/tools explicitly show missing metrics
  instead of estimating them.
- Cleartext HTTP is app-wide (with a warning) so local model presets work;
  a stricter per-endpoint policy remains a hardening item.
- F1 migration/importer tests, F3 Compose/visual tests, and the full F4/F5
  audits are not done. Manual device checks are recorded in `docs/testing.md`.
- D7 (generic SQL) is intentionally not built.

Bugfix after the first device pass: the activity pull could be dropped when a
wellness transfer held the watch's single transfer slot. `SyncCoordinator` now
defers the activity pull, preserves the wellness type, retries
`NO_AVAILABLE_RESOURCES`/timeouts, prioritizes the activity once the GDI session
is ready, pulls all new `FIT_TYPE_4` activities sequentially, and no longer
falls back to arbitrary FIT types. Verified end-to-end on the Forerunner 255.

Second device-pass bug: the watch includes `gdxml_string` only on the first item
of each data type; later items carry just `gdxml_string_key`. The activity
filter matched only the single typed item, so a watch with many activities
downloaded one and reported the rest as up to date. `Gdi.GdxmlLookup` now learns
key/string pairs from every list or notification and resolves key-only items
before classification.

Third device-pass fixes (2026-10-18):

- **Date rollover:** a monitoring rollover for the next local day could become
  the newest `wellness_days` row and shadow today's data. Monitoring days now
  prefer the watch's `local_timestamp` calendar date, all-zero rollover rows are
  discarded, the Fit parser treats `0xFFFFFFFF` timestamps as missing, and every
  screen selects the newest day that is not after the phone's date.
- **HRV:** `hrv_value` samples are promoted to the nightly HRV value when the
  watch does not write an `hrv_status_summary`, and existing rows are backfilled
  on startup. The FR255's proprietary Firstbeat `fbt_lha_daily_data` (global
  384, field 7 `rmssd_average_sleep`, 16.16 fixed point) is now decoded too.
  The Health card also plots the overnight HRV samples.
- **History:** Trends now has 7D/30D/90D/1Y/All/custom date ranges, metric
  selection (steps, sleep, HRV, resting HR, active minutes, calories, stress,
  SpO₂, respiration, Body Battery), period averages, daily/weekly/monthly
  buckets, and an explicit "days with data" count. Sleep and HRV are also
  exposed to the coach tools and daily charts.

## Implementation update — 2026-10-18

Shipped after the first coach pass:

- **Coach conversation history (E1/E6):** the single `pulse_chat` value is now a
  multi-conversation store (50 conversations × 200 messages) with a JSON codec,
  migration from the legacy value, title inference, and preserved evidence and
  chart specs. The Coach header has History/New conversation, a list showing
  title/time/message count, reopen, and confirmed delete. The most recent
  conversation reopens on launch. Stale streaming turns are ignored with a
  generation counter.
- **Database backup/restore (new data-ownership surface):** Settings → Data &
  backup exports a ZIP containing a consistent Room snapshot (`VACUUM INTO` on
  Android 10+, WAL checkpoint on Android 9), `chat.json`, and a manifest.
  Import accepts the ZIP or a raw SQLite database, validates header/schema/
  tables, keeps a safety copy, replaces and force-opens the database, then
  restarts Room observers. Invalid or newer-schema files are rejected.
- **Tests/docs:** `ConversationCodecTest` covers round-trip, title inference,
  streaming→cancelled recovery, and invalid JSON. Manual verification is
  recorded in `docs/testing.md` and `docs/HANDOFF.md`. Updated README,
  architecture notes, plan, privacy, tools, testing, and handoff docs.

Remaining on this surface: an instrumented backup/restore test and optional
backup encryption. Exported ZIPs are currently unencrypted and contain the full
database.

## Implementation update — 2026-10-19 (PacePro)

Added the PacePro strategy and run screens from the new mockups:

- **Strategy builder:** 5K/10K/half/marathon or **custom distance** goals;
  standard races default from Riegel race predictions over the last 180 days
  and custom distances scale from the closest prediction (manual goal editing
  is always available). Recent GPS runs provide a real elevation profile;
  otherwise the plan assumes flat ground. Split-strategy and hill-effort sliders
  recalculate the splits and the preview table/elevation bars.
- **Persistence:** `pace_plans` (Room v9, migration 8→9) stores the race, goal,
  course, sliders, alert mode and split JSON. Plans appear in Training and in
  the builder's saved list.
- **Watch upload:** `FitPaceBand` encodes the recovered `FIT_TYPE_56` format
  (`file_id` 56, `pace_band` 310, `pace_band_split` 311). The legacy GFDI
  upload path now carries a configurable FIT subtype; a 10-split 10K plan was
  accepted by the FR255 (`CREATE_FILE` → `UPLOAD_REQUEST` → chunks →
  `SYNC_COMPLETE`).
- **Preview run:** live phone-GPS pacing screen with distance, current/target
  pace, split status, ahead/behind, finish estimate, elevation, pause/resume,
  manual laps and a confirmed stop. Heart rate stays empty when no live HR
  source exists.
- **Tests/docs:** protocol pace-band encoder tests and app split/JSON/prediction
  tests; README, protocol, architecture, testing and design mockups updated.

Open items: verify the watch menu entry on device and add live HR via Broadcast
Heart Rate (or a BLE HR strap). The watch rejects `FIT_TYPE_56` directory
filtering on FR255, so upload success is verified from the GFDI sequence rather
than by reading the file back.

## Garmin Connect parity analysis — 2026-10-19

Added [`docs/garmin-connect-gap-analysis.md`](docs/garmin-connect-gap-analysis.md),
a baseline comparison against Garmin Connect 5.30.x and Garmin Connect+ as of
October 2026. It inventories what the app ships today, marks each Garmin
capability as present/partial/missing/out of scope, and separates parity work
from deliberate non-goals.

Highest-priority gaps:

- **P0 — trust:** historical wellness and sleep/HRV coverage, background sync,
  watch battery/storage and sync diagnostics, activity export (FIT/GPX/TCX/CSV),
  encrypted backups, profile/units/goals, structured workout targets, onboarding,
  and data-quality repair.
- **P1 — daily parity:** transparent training readiness/status, adaptive plans
  and daily suggested workouts, strength/yoga/mobility workouts, courses/GPX,
  activity notes/RPE/photos/weather, PRs/gear, weight/hydration/nutrition/women's
  health, health snapshot/reports, live HR, and Health Connect export.
- **Non-goals:** Garmin account/cloud, social network, Garmin Pay, music,
  notifications, Connect IQ, firmware updates, licensed coach plans, global food
  database, weather/trails content, and proprietary black-box scores.

Differentiator proposals (not present in Garmin Connect): portable encrypted
health vault, metric provenance/data-quality center, local AI with a privacy
ledger, automation rules, multi-source health timeline, N-of-1 experiments,
clinician brief, offline route studio, local API/MCP bridge, private challenges,
and a live watch mirror.

The P0 items are broken into ten stories with acceptance criteria and estimates
in [Epic G](#epic-g--garmin-connect-parity-p0-trust-and-daily-usability).

## Delivery milestones

1. **M0 — Foundation (done):** docs and mockups in place.
2. **M1 — Design system usable (done, A):** theme + component gallery.
3. **M2 — App aligned (done, B):** five-tab app with real data and honest empty
   states; background/periodic sync still open.
4. **M3 — Provider ready (done, C):** user can configure and test an endpoint.
5. **M4 — Data tools ready (done, D):** model can call typed, limited, read-only
   tools.
6. **M5 — Coach live (done, E):** streaming, cancellable, evidence-backed chat
   with local multi-conversation history.
7. **M6 — Hardened (in progress, F):** tests, a11y/perf checks, CI, docs.
8. **M7 — Data ownership (done, 2026-10-18):** full database + conversation
   backup/restore.

---

# Epic A — Design system

**Ships:** a dark Pulse theme and a component gallery that later screens compose
from. No screen rewrites yet.

### A1. Tokens, theme, fonts

**Size:** M · **Depends on:** none · **Risk:** Low
**Acceptance criteria**
- [ ] Colors, typography, shapes, spacing, and chart tokens from
      [`docs/design/tokens.md`](docs/design/tokens.md) are represented once in
      Compose (`PulseTheme`, `PulseTokens` or equivalent).
- [ ] Dark theme matches the mockups; no accidental Material light surfaces.
- [ ] DM Sans and Space Grotesk are bundled as OFL-licensed font assets with
      license notices, with a platform-sans fallback.
- [ ] An automated contrast test asserts the documented text pairs are ≥4.5:1
      and control-boundary tokens are ≥3:1.
- [ ] A debug-only component gallery renders every token and component at normal
      and 200% font scale.
- [ ] `:app:assembleDebug` and existing protocol tests still pass.

**Risks:** Font assets add APK size; subset if needed. Compose type metrics differ
slightly from CSS; tune line heights on device.

### A2. Base layout and control components

**Size:** M · **Depends on:** A1 · **Risk:** Low
**Acceptance criteria**
- [ ] `PulseCard`, `ElevatedCard`, `StatTile`, `SectionHeader`, `PillChip`,
      `SegmentedControl`, `PrimaryButton`, `IconButton`, `Badge`, `EmptyState`,
      `ErrorState`, `LoadingSkeleton`, and a connection/status chip exist.
- [ ] Every interactive component has ≥44 dp touch target, a role, a label, a
      visible focus state, and a ripple/pressed state.
- [ ] Selected/unselected chip and segment states use color + weight/border, not
      color alone.
- [ ] Components accept content, not hardcoded sample values.
- [ ] Compose UI tests cover labels, roles, selection semantics, and 44 dp
      targets at 200% font scale.

**Risks:** Over-generalising too early; keep the API small and add variants only
when a screen needs them.

### A3. Data-visualisation primitives

**Size:** L · **Depends on:** A1, A2 · **Risk:** Medium
**Acceptance criteria**
- [ ] `ProgressRing`, `BarChart`, `LineChart`/`Sparkline`, and `ZoneBar` match the
      chart tokens (stroke widths, fills, radii, goal line, zone colors).
- [ ] Zero/one-point/all-missing data produces an explicit empty state, never a
      fabricated baseline or sample series.
- [ ] Charts accept already-aggregated real data; no chart computes or invents
      sample data.
- [ ] Each chart exposes an accessible summary (content description or adjacent
      text) and supports downsample limits.
- [ ] Unit tests cover percentage/zero/negative/oversized input handling; UI tests
      cover content descriptions.

**Risks:** Canvas text in Compose is manual; keep axis labels in Compose `Text`
where possible for font scaling and accessibility. Very large series must be
downsampled before drawing.

### A4. Tab bar and screen chrome

**Size:** S · **Depends on:** A1, A2 · **Risk:** Low
**Acceptance criteria**
- [ ] `PulseTabBar` renders four real buttons (Today, Trends, Activities, Coach)
      with 24 dp icons, 11 dp labels, active/inactive states, and ≥44 dp targets.
- [ ] Tab items expose selected state and accessible labels; tapping a tab emits
      a stable route enum.
- [ ] A top header component supports date/title, optional trailing status, and
      large-font reflow.
- [ ] No URL-style links are needed; navigation stays in-app.

### A5. Chat components

**Size:** M · **Depends on:** A1, A2 · **Risk:** Low
**Acceptance criteria**
- [ ] `ChatBubble` supports assistant/user roles, the asymmetric 18/6 radii, max
      widths, long text, links, and selectable text.
- [ ] `TypingIndicator`, `SourceChip`/`DataUsedRow`, `SuggestionChip`, and
      `ChatComposer` (48 dp input + 48 dp labeled send/stop control) exist.
- [ ] Composer handles IME actions, empty/whitespace input, disabled state, and a
      cancel/stop state; no simulated replies.
- [ ] Source chips wrap and remain readable at 200% font scale.
- [ ] Accessibility tests cover the composer label and send/stop semantics.

---

# Epic B — Screens

**Ships:** the four-tab app, aligned to the mockups, using real Room data with
loading, empty, and error states. Coach is a shell until Epic E.

### B1. App shell, routes, and screen split

**Size:** L · **Depends on:** A1–A4 · **Risk:** Medium
**Acceptance criteria**
- [ ] `MainActivity.kt` is split into screens/components plus a thin activity;
      screen state moves to ViewModels (or equivalent state holders) and survives
      configuration changes.
- [ ] Four tab routes (Today, Trends, Activities, Coach) and detail routes
      (wellness day, activity detail, data explorer, settings) work with system
      back and preserve tab state.
- [ ] No new navigation/UI framework is added; a small sealed route model +
      `BackHandler` is acceptable.
- [ ] Sync/scan/disconnect/debug controls leave the Today content and are
      reachable from a connection sheet/settings without clutter.
- [ ] Existing data explorer remains reachable from activity detail.
- [ ] Rotate/resume/process-death checks pass; no crash when lists are empty.

### B2. Persisted sync state and freshness

**Size:** M · **Depends on:** B1 · **Risk:** Medium
**Acceptance criteria**
- [ ] Last successful sync time, last attempt/result, device name, and any error
      are persisted and exposed as UI state (not only in-memory `state`).
- [ ] Today shows a truthful "Synced N min ago" / "Never synced" status and a
      clear sync action; it does not show a battery percentage unless real
      battery data exists.
- [ ] Optional periodic/background sync (WorkManager) is behind a setting and
      respects battery/network constraints; manual sync still works.
- [ ] Failed/up-to-date/transferring states are distinguishable and testable.
- [ ] No secrets or health payloads are written to logs.

**Risks:** WorkManager is a new dependency (needs approval). Watch battery and
connection quality are not available today; do not fake them.

### B3a. Data-coverage spike: sleep, HRV, historical wellness

**Size:** S (timeboxed) · **Depends on:** handoff M7, B2 · **Risk:** High
**Acceptance criteria**
- [ ] Identify which watch files/messages carry sleep, HRV, and older wellness
      days; record findings and sample evidence in `docs/` (no binary captures in
      git).
- [ ] Document whether the current `CutWellnessFilesRequest` can fetch older
      days, and what the watch exposes for sleep/HRV.
- [ ] Produce a go/no-go recommendation with estimated effort and fallback empty
      states for each metric.
- [ ] If a request/parse path is proven, add protobuf/FIT fixtures to the test
      suite; if blocked, record the exact blocker and device state.

**Risks:** This is reverse-engineering, not UI work; it can block the mockup's
sleep/HRV tiles indefinitely. Timebox and ship honest empty states if blocked.

### B3b. Ingest sleep, HRV, and available history

**Size:** L · **Depends on:** B3a · **Risk:** High
**Acceptance criteria**
- [ ] Sleep sessions/stages and HRV (nightly/status) are stored in new Room
      tables with migrations, or the story is explicitly descoped after B3a.
- [ ] Historical wellness days are requested/imported when the watch supports
      it; repeated cuts do not duplicate samples.
- [ ] Importer dedupe, timezone/epoch handling, and migration have unit tests
      with FIT fixtures.
- [ ] Data availability is queryable per metric/day for the UI and tools.
- [ ] Missing days are represented as missing, never zero-filled silently.
- [ ] No changes are made to the BLE protocol without protocol tests.

**Risks:** New file types and watches may behave differently; destructive schema
changes require approval. If history cannot be requested, Trends will only show
accumulated days and must say so.

### B4. Settings and profile values

**Size:** M · **Depends on:** B1, A2 · **Risk:** Low
**Acceptance criteria**
- [ ] A settings screen provides: step goal, max heart rate (or age for zone
      estimation), units, provider settings entry, privacy notice, connection/
      device controls, and about/licences.
- [ ] Values persist locally; unset values produce explicit empty states, not
      invented defaults.
- [ ] Step goal and max HR feed Today/Trends/zone calculations from one source.
- [ ] Inputs validate ranges and show helpful errors; all controls are ≥44 dp.
- [ ] Destructive actions (clear data, delete provider) require confirmation.

### B5. Today screen

**Size:** L · **Depends on:** A2–A3, B1, B2, B3b, B4 · **Risk:** Medium
**Acceptance criteria**
- [ ] Header shows real local date, time-appropriate greeting, and real sync
      status; no hardcoded "82%" battery.
- [ ] Steps hero uses real steps, distance, goal progress ring, and remaining
      steps; if no goal is set, show the real total and a "Set goal" action.
- [ ] Heart-rate card shows real now/last sample, resting, range, and an intraday
      line chart from `wellness_samples`; if no samples, show an empty state.
- [ ] Sleep, HRV, active time, and calories tiles use real data or explicit
      "Not synced yet" states; floors are omitted unless ingested.
- [ ] Insight card is truthful (see open question Q3): deterministic local summary
      or clearly labelled model output; never placeholder prose.
- [ ] "Ask Coach" navigates to Coach; health cards open their detail screens.
- [ ] Loading, empty, partial-data, and error states are implemented; screen
      scrolls at 200% font scale; TalkBack order is logical.

### B6. Trends screen

**Size:** L · **Depends on:** A2–A3, B1, B3b, B4 · **Risk:** Medium
**Acceptance criteria**
- [ ] Day/Week/Month segmented control matches the mockup, is keyboard/TalkBack
      operable, and preserves the selected range.
- [ ] Range label and summary tiles (steps, resting HR, active, burn) are computed
      from real data for the selected period.
- [ ] Steps bar chart aggregates by 2-hour block, day, or week as appropriate;
      goal line appears only when a real goal exists; labels reflect actual days.
- [ ] HR line chart uses real resting/avg samples; endpoint labels and note are
      derived from data; insufficient data shows an empty state.
- [ ] Insight text is deterministic or clearly model-generated, with no invented
      comparisons; if fewer than two periods exist, say so.
- [ ] Partial history (e.g. 3 of 7 days) is labelled and not zero-filled.
- [ ] Loading/empty/error states and 200% font scale are covered.

### B7. Activities list, filters, and selection

**Size:** L · **Depends on:** A2–A3, B1 · **Risk:** Medium
**Acceptance criteria**
- [ ] Header, last-7-days summary (workouts, active time, calories), filter chips,
      featured card, and list match the mockup hierarchy.
- [ ] Chips filter by real sport categories (All, Run, Ride, Strength, Walk) using
      a documented sport/subSport mapping; unsupported sports remain visible under
      All and are not mislabelled.
- [ ] Tapping a row selects it as the featured card; tapping the featured card
      opens activity detail. Selection is reflected visually and semantically.
- [ ] Featured card shows real name/type/date/stats, route or HR trace thumbnail,
      and a real time-in-zone bar when zone data exists.
- [ ] Empty filter results, no activities, loading, and data errors have clear
      states; no placeholder activities.
- [ ] List rows are ≥64 dp tall, 44 dp targets, and accessible.

**Risks:** "Strength" is not a single FIT sport in all files; mapping may need
subSport or activity name heuristics. Do not invent types.

### B8. Activity detail

**Size:** M · **Depends on:** B7, A3, B4 · **Risk:** Medium
**Acceptance criteria**
- [ ] Detail shows real distance, duration, pace/speed, HR, calories, ascent/
      descent, date, and sport.
- [ ] Track map, HR chart, elevation chart, and lap table render from records/laps
      with empty states; existing OSM attribution is preserved.
- [ ] Heart-rate zones bar is computed from real `records.heartRate` and the
      configured max HR; the basis is shown (e.g. "Zones based on max HR 190").
      With no max HR or no samples, show a setup/empty state.
- [ ] "All data" opens the existing FIT explorer; back returns to the selected
      activity.
- [ ] Detail is scrollable, survives rotation, and is screen-reader navigable.

### B9. Coach screen shell

**Size:** M · **Depends on:** A5, B1, C2 · **Risk:** Low
**Acceptance criteria**
- [ ] Header, data-access bar ("Reading heart rate, steps, sleep and workouts" —
      limited to metrics actually available), Manage link, message area,
      suggestions, and composer match the mockup.
- [ ] Without a configured provider, the composer is disabled with a clear CTA to
      provider settings; no fake assistant greeting or simulated typing.
- [ ] With a provider, the screen accepts input but shows a "not yet connected"
      state until Epic E wires streaming.
- [ ] Empty conversation and provider-error states are accessible and honest.
- [ ] Layout reflows at 200% font scale; send/stop and Manage are 44 dp.

### B10. Cross-screen loading, empty, error, and refresh states

**Size:** M · **Depends on:** B5–B9 · **Risk:** Low
**Acceptance criteria**
- [ ] A shared state pattern (loading skeleton, empty explanation + action, error
      with retry) is used consistently.
- [ ] No screen shows zeros, placeholder charts, or sample text when data is
      absent.
- [ ] Sync-in-progress does not block browsing existing data; pull-to-refresh or
      an explicit action is available.
- [ ] Offline/network errors are distinct from "no watch data".
- [ ] Screens remain usable with 0 activities and 0 wellness days (fresh install).

---

# Epic C — LLM provider settings

**Ships:** a working provider configuration surface and a tested
OpenAI-compatible streaming client. No coach chat yet.

### C1. Provider/model configuration model + encrypted secret storage

**Size:** L · **Depends on:** B1/B4 settings · **Risk:** Medium
**Acceptance criteria**
- [ ] Room v7 adds provider and model configuration tables (name, base URL,
      provider type/preset, default model, temperature, max tokens, timestamps)
      plus a tested migration and importer regression tests.
- [ ] API keys are encrypted at rest with an Android Keystore AES-GCM key; the
      database stores only a reference/ciphertext, never plaintext.
- [ ] Saving a key is write-only from the UI's perspective: reads return a masked
      value (e.g. `sk-…abcd`), never the key; deleting a provider deletes its
      secret.
- [ ] The key is never included in UI state, tool arguments/results, model
      context, crash reports, or logs.
- [ ] Configuration resolves with a documented precedence: user setting >
      build-time environment/BuildConfig fallback.
- [ ] Unit tests cover masking, secret delete/replace, migration, and URL/model
      validation; no test prints a key.

**Risks:** Keystore behavior on emulators/backup; `allowBackup=false` helps.
androidx.security is not required; direct Keystore code needs careful error
handling (locked/invalidated keys → ask user to re-enter).

### C2. Presets, model list, and provider settings UI

**Size:** L · **Depends on:** A1–A2, C1 · **Risk:** Medium
**Acceptance criteria**
- [ ] Presets exist and are editable: OpenAI, DeepSeek
      (`https://api.deepseek.com/v1`), OpenRouter, Ollama
      (`http://localhost:11434/v1`), LM Studio, vLLM, Custom.
- [ ] Each preset has a base URL and suggested model names; suggestions are
      labels only and never auto-inserted as fake "available" models.
- [ ] User can add, edit, and delete provider/model entries; mark one model as
      default; set optional temperature and max-token parameters with validation.
- [ ] Settings show the active default and clearly indicate when no provider is
      configured.
- [ ] The API key field is masked after save; the user can replace/delete but not
      reveal the stored value.
- [ ] The screen explains that **health data will be sent to the configured
      endpoint** and recommends a local model for privacy.
- [ ] Provider settings are reachable from Coach "Manage" and settings.
- [ ] UI tests cover add/edit/delete/default/masked-key flows and 44 dp targets.

### C3. Test connection

**Size:** M · **Depends on:** C4, C2 · **Risk:** Medium
**Acceptance criteria**
- [ ] "Test connection" calls the OpenAI-compatible models list; if unsupported,
      falls back to a minimal completion with a hard token/time cap.
- [ ] Success reports the endpoint and model count/model name; failure reports a
      clear category (auth, not found, DNS, TLS, timeout, rate limit, malformed
      response) without echoing the key.
- [ ] Timeouts are bounded (e.g. 10–15 s) and the action can be cancelled; the UI
      does not hang.
- [ ] Mock-server tests cover 200, 401, 404, 429, 500, timeout, and malformed
      JSON.
- [ ] No request/response body containing keys or health data is logged.

### C4. OpenAI-compatible client (chat + SSE streaming)

**Size:** L · **Depends on:** C1 · **Risk:** Medium/High
**Acceptance criteria**
- [ ] Uses `POST {baseUrl}/chat/completions` with `stream=true`; parses SSE
      `data:` events, split chunks, `[DONE]`, content deltas, and streamed
      `tool_calls`.
- [ ] Supports non-streaming completion for test/fallback paths.
- [ ] Cancellation stops reading and closes the connection promptly; partial
      content is returned with a cancelled flag.
- [ ] Connect/read/call timeouts are configurable and bounded; retries use
      exponential backoff + jitter only before the first token, never after a
      partial stream, and never on 4xx (except 429 policy, documented).
- [ ] Typed errors distinguish auth, rate limit, server, network, timeout,
      cancelled, and malformed stream.
- [ ] Integration tests use a local mock OpenAI server and cover streaming,
      tool-call deltas, cancellation, 401/429/500, timeout, and broken SSE.
- [ ] HTTP client dependency is approved before implementation; no secrets in
      logs/interceptors.

**Risks:** Adding OkHttp is a new dependency (approval required). SSE parsers
must handle arbitrary chunk boundaries. Streaming POST retries can duplicate
tokens/cost; keep the rule simple and documented.

### C5. Build-time/environment configuration and privacy guardrails

**Size:** M · **Depends on:** C1, C2 · **Risk:** Medium
**Acceptance criteria**
- [ ] Deployments can supply base URL, key, and default model through environment
      variables at build time (generated `BuildConfig` fields) without committing
      secrets.
- [ ] Document precedence and how to override/remove build-time values.
- [ ] A central redacting logger strips Authorization headers, API keys, and
      health payloads; debug logging never dumps request/response bodies.
- [ ] Plain-HTTP endpoints are handled by an explicit, documented policy with a
      warning; HTTPS is the default.
- [ ] Settings/privacy copy names the endpoint and explains local-model privacy
      trade-offs.

**Risks:** Android apps do not have a conventional runtime env; BuildConfig is
the robust deployment path. `usesCleartextTraffic` is app-wide if enabled; avoid
silently weakening TLS for all endpoints.

---

# Epic D — LLM data access (tools)

**Ships:** a typed, read-only tool layer that can answer health questions from
the local database, with hard limits and a no-tool fallback.

### D1. Tool registry, scope, limits, and execution contract

**Size:** L · **Depends on:** C1, B3b data availability · **Risk:** High
**Acceptance criteria**
- [ ] A typed `HealthTool` interface defines name, description, JSON schema,
      executor, and evidence descriptor; a registry exposes only read-only tools.
- [ ] Every call takes an explicit `ToolScope(profileId = LOCAL_PROFILE_ID)` and
      every query is filtered by that scope. There is no implicit "current user"
      hidden in a global.
- [ ] Date ranges are ISO dates, end ≥ start, capped (e.g. 366 days); daily
      granularity capped (e.g. 90 days); row/point limits (e.g. 500) and compact
      JSON output are enforced.
- [ ] Unknown tools, unknown/invalid arguments, oversized ranges, and scope
      mismatch return typed errors; nothing is executed on a write path.
- [ ] Tool execution has a timeout and a max-call budget per chat turn.
- [ ] Unit tests cover scope isolation, range/row limits, invalid args, and
      read-only behavior.

**Risks:** The app is single-user today; scope is a forward-compatible guard,
not authentication. If a server/multi-profile mode is added, this layer must be
revisited with real auth. Do not add raw SQL.

### D2. Core tools: daily summary, steps, heart rate

**Size:** L · **Depends on:** D1 · **Risk:** Medium
**Acceptance criteria**
- [ ] `get_daily_summary(date_range)` returns per-day steps, distance, active
      minutes/calories, resting/avg/max HR, stress/SpO2/respiration, and
      missing-metric nulls; no zero-filling.
- [ ] `get_steps(range, granularity)` supports day/week buckets, totals/averages,
      goal if configured, and capped points.
- [ ] `get_heart_rate(range, granularity)` supports resting/avg/min/max and
      bucketed samples, capped points.
- [ ] All tools are user-scoped, range-limited, row-limited, and output compact
      JSON with units.
- [ ] Unit tests cover empty data, single day, timezone boundaries, max range,
      missing resting HR, and row caps.
- [ ] Evidence descriptors (e.g. "Steps · 7 days") are produced for each result.

### D3. Sleep, activities, and activity detail tools

**Size:** L · **Depends on:** D1, B3b · **Risk:** High
**Acceptance criteria**
- [ ] `get_sleep(range)` returns nightly duration/stages/score when available and
      an explicit `available: false` when the watch data was not ingested.
- [ ] `list_activities(filters)` supports date range, sport category, limit
      (≤50), and offset; returns compact stats only.
- [ ] `get_activity(id)` returns one activity's stats, laps, and zone summary;
      it never dumps raw GPS/records.
- [ ] Tools respect scope and caps; unknown/inaccessible IDs return a typed
      "not found", not another profile's data.
- [ ] Unit tests cover filters, pagination, not-found, empty data, and caps.
- [ ] Evidence descriptors identify metric and range.

**Risks:** Sleep availability depends on B3b. If descoped, the tool must say so
rather than infer sleep from activity data.

### D4. `compare_periods`

**Size:** M · **Depends on:** D2 · **Risk:** Medium
**Acceptance criteria**
- [ ] Supports metrics: steps, resting HR, active minutes, calories, sleep
      duration (when available).
- [ ] Accepts two bounded ranges; returns value A, value B, absolute delta, percent
      delta, and sample counts.
- [ ] Handles missing/partial data explicitly (e.g. "insufficient data" rather
      than dividing by zero or comparing 2 days to 7).
- [ ] Output is compact and unit-labelled; scope/limits apply.
- [ ] Unit tests cover equal ranges, zero baselines, partial periods, and caps.

### D5. No-tool fallback: compact data snapshot

**Size:** M · **Depends on:** D1, D2, D3 · **Risk:** Medium
**Acceptance criteria**
- [ ] For models without tool calling, build a deterministic compact summary
      (e.g. last 14/30 days: daily aggregates, recent activities, data
      availability) within a documented token/character budget.
- [ ] The snapshot uses only real data, labels missing metrics, and excludes free
      text (activity names/notes) unless explicitly needed.
- [ ] The model can answer basic questions from the snapshot; the prompt says it
      is a snapshot, not live tool output.
- [ ] The "data used" descriptors list the snapshot sections included.
- [ ] Unit tests cover budget enforcement, missing data, and deterministic output.

### D6. Answer-with-evidence and safety contract

**Size:** M · **Depends on:** D1, D2 · **Risk:** High
**Acceptance criteria**
- [ ] System prompt: answer only from tool/snapshot results; never invent
      numbers; say when data is missing; distinguish observations from advice.
- [ ] All database text (activity names/notes) and tool output are wrapped and
      labelled as untrusted data, never as instructions; injection attempts cannot
      change tool scope, call other tools, or exfiltrate keys.
- [ ] The coach is informational, not medical advice; concerning patterns trigger
      a suggestion to consult a professional.
- [ ] Every answer carries evidence metadata generated from actual tool calls
      (metric + range), rendered as "Data used" chips; if no tools/snapshot were
      used, no fabricated source is shown.
- [ ] Prompt/unit tests include injection strings in activity names, missing
      data, and a request for a diagnosis.

**Risks:** Prompt injection is not fully solvable; enforce boundaries in code
(scope, read-only registry, limits), not only in the prompt. Tool results can
still contain adversarial text, so never let them trigger actions.

### D7. Optional: generic read-only query tool (NOT v1)

**Size:** L · **Depends on:** separate approval · **Risk:** Critical
**Acceptance criteria (only if approved)**
- [ ] Runs on a dedicated read-only DB role/connection against an allow-listed set
      of views, never the app's writable connection.
- [ ] Enforces statement timeout, row cap, column allow-list, and scope predicate.
- [ ] Ships a written threat model covering prompt injection, data exfiltration,
      health-data sensitivity, and denial of service.
- [ ] Reviewed separately; not bundled with the typed tools.
- [ ] Default off and clearly labelled experimental.

**Recommendation:** do not build in v1. The typed tools are sufficient for the
mockup's questions and are far easier to secure.

---

# Epic E — Coach chat

**Ships:** the live Coach screen: streaming answers, tool calls, cancel, evidence,
persistence, and safety UX.

### E1. Chat state and persistence

**Size:** L · **Depends on:** C1, D1 · **Risk:** Medium
**Status (2026-10-18):** implemented with private SharedPreferences JSON
(`ConversationStore.kt`), not Room. Includes multi-conversation history, legacy
migration, and a JSON codec test. No ViewModel/state test yet.

**Acceptance criteria**
- [x] Room (or equivalent) stores conversations/messages: role, content, evidence
      metadata, created time, status (streaming/complete/cancelled/error).
- [x] Chat survives rotation, process death, and app restart; user can start a
      new conversation and delete one with confirmation.
- [x] No API key and no raw provider response headers are stored; health data is
      only stored locally in the app's DB.
- [x] Sending is blocked while a response is streaming; cancel is available.
- [ ] Migration and ViewModel/state tests cover reload, partial/cancelled
      messages, and empty conversations. (`ConversationCodecTest` covers reload
      and partial/cancelled messages; empty/ViewModel tests remain.)

### E2. Streaming response loop

**Size:** L · **Depends on:** B9, C4, E1 · **Risk:** Medium
**Acceptance criteria**
- [ ] Sending a message streams tokens into the assistant bubble; the typing
      indicator disappears when the first token/final state arrives.
- [ ] Cancel stops the stream promptly, preserves partial text, and marks the
      message cancelled; a retry action is available.
- [ ] Auto-scroll follows streaming unless the user has scrolled up; keyboard/IME
      behavior keeps the composer visible.
- [ ] Auth, rate-limit, network, timeout, and malformed-stream errors map to
      actionable messages with retry; no raw stack traces or keys.
- [ ] Integration tests drive the mock server for happy path, cancellation,
      timeout, and error; UI tests verify state transitions.

### E3. Tool-calling loop

**Size:** L · **Depends on:** D2–D6, E2 · **Risk:** High
**Acceptance criteria**
- [ ] The request includes typed tool definitions; streamed `tool_calls` are
      assembled, validated, executed, and returned as tool messages.
- [ ] Loop continues until a final assistant answer; max rounds (e.g. 4) and max
      calls (e.g. 8) are enforced with a clear fallback message.
- [ ] Unknown tools, invalid arguments, tool errors, and empty results are
      reported back to the model safely; they never crash the chat or widen scope.
- [ ] Models that reject tools/no tool support fall back to the D5 snapshot.
- [ ] All calls are user-scoped and read-only; logs contain tool names/timing only.
- [ ] Integration tests cover one/multiple tool calls, parallel calls (if
      supported), invalid args, loop cap, and no-tool models.

### E4. "Data used" evidence

**Size:** M · **Depends on:** E3 · **Risk:** Low
**Acceptance criteria**
- [ ] Chips under each assistant answer are generated from the actual tools called
      (metric + date range), deduplicated and ordered deterministically.
- [ ] Fallback snapshot sections produce their own labels; no tools means no
      fabricated chips.
- [ ] Evidence is persisted with the message and survives reload.
- [ ] Long chip lists wrap; 200% font scale and TalkBack labels are covered.
- [ ] Tests assert that evidence matches the recorded tool trace exactly.

### E5. Safety, onboarding, and provider states

**Size:** M · **Depends on:** E3, C2 · **Risk:** Medium
**Acceptance criteria**
- [ ] First-run Coach shows a real onboarding state: provider status, what data
      can be read, privacy note, and a link to settings; no fake greeting claim
      about sync freshness.
- [ ] Suggestions ("How was my week?", etc.) are real prompts; tapping sends the
      prompt and uses the real backend.
- [ ] A persistent, unobtrusive disclaimer says the coach is informational and
      not medical advice; concerning-pattern guidance is visible.
- [ ] Disabled/no-provider, offline, auth-failure, and rate-limit states each have
      a clear action.
- [ ] Provider test/settings are reachable from "Manage"; data-access copy lists
      only tools that are actually enabled.
- [ ] No hardcoded AI answers anywhere in the shipped code.

### E6. Chat polish and accessibility

**Size:** M · **Depends on:** E2–E5 · **Risk:** Low
**Acceptance criteria**
- [ ] Message list uses stable keys, does not lose scroll position, and performs
      smoothly with 200+ messages.
- [ ] Assistant/user bubbles, source chips, and typing state meet contrast,
      touch, and screen-reader requirements; streaming announcements are polite
      (not per-token).
- [ ] Copy/select text works for answers; links are handled safely.
- [ ] Empty, long-answer, code-block/long-token, and RTL/large-font layouts are
      covered by UI tests.
- [ ] Conversation delete/new-chat actions are discoverable and confirmed.

---

# Epic F — Quality

**Ships:** confidence, not new product surface. Tests are written alongside each
epic, but this epic closes the gaps and adds CI/release gates.

### F1. Importer, migration, and repository tests

**Size:** M · **Depends on:** B3b/C1 schema changes · **Risk:** Medium
**Acceptance criteria**
- [ ] Tests cover FIT activity import, dedupe by source key, item UID backfill,
      records/laps/messages, wellness merge/update, timezone/epoch conversion, and
      every Room migration 1→current.
- [ ] Migration tests use exported schemas or Room's migration test helper; no
      destructive `fallbackToDestructiveMigration`.
- [ ] Fixtures are small, committed, and licensed for test use.
- [ ] Tests run in CI (JVM where possible, instrumentation otherwise).

### F2. LLM integration test suite

**Size:** M · **Depends on:** C4, E3 · **Risk:** Medium
**Acceptance criteria**
- [ ] A mock OpenAI-compatible server covers non-streaming, SSE streaming, tool
      calls, malformed chunks, 401/429/500, timeout, cancellation, and no-tool
      fallback.
- [ ] Tests assert request shape (model, messages, tools, params) without real
      keys or network.
- [ ] Redaction tests assert no key/body appears in captured logs.
- [ ] Suite is deterministic, parallel-safe, and runs in CI.

### F3. Screen/component and visual tests

**Size:** M · **Depends on:** B5–B9 · **Risk:** Medium
**Acceptance criteria**
- [ ] Compose UI tests cover the four tabs, Day/Week/Month switch, activity
      filters/selection, detail navigation, coach send/cancel/error states.
- [ ] Tests assert loading/empty/error states and no hardcoded sample data.
- [ ] Screenshot/visual regression is added only if a tool is approved; otherwise
      the component gallery + UI tests act as the baseline.
- [ ] Tests pass at normal and 200% font scale.

### F4. Accessibility audit

**Size:** M · **Depends on:** B/E screens · **Risk:** Medium
**Acceptance criteria**
- [ ] Every interactive element has ≥44 dp target, role, label, and focus state;
      icon-only controls have content descriptions.
- [ ] Text/background pairs meet 4.5:1; control boundaries/focus meet 3:1.
- [ ] TalkBack traversal is logical; charts have text alternatives; selected
      states are announced.
- [ ] Keyboard/switch access works for tabs, segmented control, chips, list
      selection, composer, and dialogs.
- [ ] Manual checklist (TalkBack, large text, reduced motion, dark theme) is
      documented and signed off.

### F5. Performance and data-volume checks

**Size:** M · **Depends on:** B/E · **Risk:** Medium
**Acceptance criteria**
- [ ] Cold start, tab switch, activity list scroll, detail chart render, and chat
      stream are measured on the target phone/emulator; budgets are documented.
- [ ] Queries are indexed (`records(activityId, timestamp)`, wellness sample
      kind/time, activities start time); no full-table scans for screens/tools.
- [ ] Charts downsample and cap points; huge FIT message tables are not loaded on
      the main thread.
- [ ] 200 activities / 30 days of wellness / 200 chat messages remain usable.
- [ ] No new ANR or jank regressions in a repeatable smoke test.

### F6. CI and release checks

**Size:** M · **Depends on:** A–E · **Risk:** Medium
**Acceptance criteria**
- [ ] CI runs `:protocol:test`, app unit tests, lint, and `:app:assembleDebug` on
      pull requests; instrumentation/UI tests run in a documented job.
- [ ] Secrets are never required for CI; mock provider tests are hermetic.
- [ ] Build fails on lint errors that affect accessibility/security (or a
      documented baseline).
- [ ] A release checklist covers versioning, signing, migrations, privacy copy,
      and provider/env configuration.

### F7. Documentation

**Size:** M · **Depends on:** C–E · **Risk:** Low
**Acceptance criteria**
- [ ] `docs/providers.md`: configure each preset, keys/secrets, env/BuildConfig,
      local model/privacy guidance, cleartext policy, test connection, timeouts.
- [ ] `docs/tools.md`: tool list, schemas, limits, scope model, how to add a tool,
      evidence descriptors, no-tool fallback.
- [ ] `docs/testing.md`: run protocol/app/integration/UI tests, mock server, CI.
- [ ] `docs/privacy.md`: what leaves the device, what is logged/redacted, key
      handling, data deletion.
- [ ] README and handoff are updated; docs match the shipped behavior.

### F8. Privacy, logging, and threat-model review

**Size:** M · **Depends on:** C/E · **Risk:** Medium
**Acceptance criteria**
- [ ] A test/log review confirms keys, Authorization headers, health payloads,
      and activity free text never appear in logs/crash reports.
- [ ] Settings clearly disclose that health data is sent to the configured
      endpoint and recommend a local model.
- [ ] Threat model covers prompt injection via DB text, provider compromise,
      local secret storage, cleartext HTTP, and over-broad tool output.
- [ ] Data deletion (chat/provider/health) is complete and documented.
- [ ] Dependencies are reviewed for licence/security; no analytics/telemetry.

---

# Epic G — Garmin Connect parity: P0 trust and daily usability

**Ships:** the P0 gaps from
[`docs/garmin-connect-gap-analysis.md`](docs/garmin-connect-gap-analysis.md),
split into ten reviewable stories. This epic deliberately does **not** chase
full Garmin Connect parity; P1/P2 work stays in the analysis document and is not
part of the P0 commitment.

**Exit criteria:** a user can install, pair, sync reliably in the background,
see complete and honest data coverage, export an activity, create and restore an
encrypted backup, set profile/units/goals, schedule a targeted workout to the
watch, and detect or repair common data gaps.

**Estimates:** one developer, excluding device waiting time. S ≈ 1–2 days,
M ≈ 3–5 days, L ≈ 1–2 weeks. Protocol discovery or a blocked watch feature can
move an L to its upper bound. Every story follows the definition of done at the
top of this file (tests, empty/loading/error states, accessibility, docs,
`BACKLOG.md` update, small reviewable commit).

**Recommended order:** start the G1 spike immediately because it can invalidate
other data work; ship G5/G7 in parallel; then G2/G6/G10; then G3/G4/G9; G8 after
the profile and workout model land. Total is roughly **9–13 developer weeks**.
G5, G6, G7, G9, and G10 are app-only; G1, G3, G4, and G8 carry the protocol or
new-dependency risk.

| Story | Outcome | Size | Estimate | Depends on | Risk |
|---|---|---|---|---|---|
| G1 | Historical wellness request + gap-aware ingestion | L | 1–2 weeks | protocol notes, FR255 | High |
| G2 | Data coverage view | S/M | 2–3 days | G1 (can start earlier) | Low |
| G3 | Background sync + connection lifecycle | L | 1–2 weeks | WorkManager approval | Medium/High |
| G4 | Watch status + sync diagnostics | M | 3–5 days | protocol/RE | Medium |
| G5 | Activity export (FIT/GPX/TCX/CSV) | M | 3–4 days | existing records/laps | Low |
| G6 | Encrypted backup + restore | M | 4–6 days | `BackupManager` | Medium |
| G7 | Profile, units, and goals | M | 3–5 days | none | Low/Medium |
| G8 | Structured workout targets + watch calendar | L | 1–2 weeks | G7, `FitWorkout` | Medium/High |
| G9 | First-run onboarding + pairing | M | 3–5 days | G3/G4 preferred | Low |
| G10 | Data quality + repair center | M | 4–6 days | G1/G2 | Medium |

---

## G1. Historical wellness requests and gap-aware ingestion

**Size:** L · **Estimate:** 1–2 weeks (timebox the protocol spike to 3 days)
**Depends on:** `docs/PROTOCOL.md`, `docs/HANDOFF.md`, FR255 device access
**Risk:** High — this is reverse engineering, not UI work.

**Acceptance criteria**
- [ ] A timeboxed protocol spike records whether `CutWellnessFilesRequest`,
      FileAccess, or another GFDI path can request days older than the current
      delta. Findings, device/firmware, and exact blockers go in `docs/`; no raw
      binary captures are committed.
- [ ] If supported: the app can request and import at least 30 days (or the
      maximum the watch exposes) of monitoring/sleep/HRV data, from first sync
      or an explicit "Import older data" action.
- [ ] If unsupported: the exact blocker is documented and the UI states that
      history accumulates through repeated syncs. No history, sleep, or HRV is
      inferred or fabricated.
- [ ] Repeated requests are idempotent: no duplicate days, samples, sleep
      stages, or imported-file UIDs.
- [ ] Timezone, Garmin epoch, midnight rollover, and `0xFFFFFFFF` sentinels are
      covered by parser tests with committed FIT fixtures.
- [ ] Sleep/HRV file discovery is reliable; missing metrics remain explicitly
      unavailable rather than zero.
- [ ] Trends, Health, Today, and the coach tools read the new history and keep
      partial-history labels.
- [ ] The change does not regress activity pulls or the single watch transfer
      slot; sync duration and transfer volume are recorded in `docs/testing.md`.

**Notes:** If the spike says "not possible", close the implementation checklist
with the documented blocker and keep G2/G10. Do not spend the full estimate
fighting the protocol.

## G2. Data coverage view

**Size:** S/M · **Estimate:** 2–3 days
**Depends on:** G1 for the best result; can start with current data
**Risk:** Low.

**Acceptance criteria**
- [ ] Health or Settings has a **Data coverage** view for 7/30/90 days showing,
      per metric, which days are present, partial, or missing.
- [ ] The view shows last-seen dates and counts; missing days are never
      zero-filled or drawn as a synthetic series.
- [ ] Each gap explains the likely cause (not synced, watch did not expose the
      file, import failed) and links to Sync or Import FIT.
- [ ] Empty, loading, and error states are implemented and accessible.
- [ ] Unit tests cover timezones, sparse data, duplicates, and partial days.
- [ ] The coach's data summary states the coverage limits instead of claiming a
      complete archive.

## G3. Background sync and connection lifecycle

**Size:** L · **Estimate:** 1–2 weeks
**Depends on:** approval for WorkManager (new dependency)
**Risk:** Medium/High — Android background limits and BLE contention.

**Acceptance criteria**
- [ ] Periodic sync is behind a setting (documented interval, e.g. 15 min–6 h)
      with explicit battery/charging constraints; the default is documented.
- [ ] Sync still runs on launch, on watch reconnect, and from the manual action.
- [ ] Sync is single-flight: a worker, foreground service, and manual tap cannot
      start concurrent transfers.
- [ ] Transient failures (`NO_AVAILABLE_RESOURCES`, timeout, disconnect) retry
      with backoff; permanent failures are classified and surface an action.
- [ ] The foreground service and worker hand off cleanly; notifications show
      progress, completion, and failure without stale states.
- [ ] Last attempt/success, duration, and result are persisted and visible.
- [ ] Device tests cover Doze, process death, charging, and watch out of range;
      no ANR and no health payload in logs.
- [ ] Scheduler/backoff/single-flight behaviour has unit tests.

## G4. Watch status and sync diagnostics

**Size:** M · **Estimate:** 3–5 days
**Depends on:** protocol/RE for battery and storage
**Risk:** Medium — fields may not be exposed on every watch.

**Acceptance criteria**
- [ ] The device card shows battery percentage and free storage when the watch
      exposes them; otherwise it says "Unknown" with no invented value.
- [ ] It shows product/firmware, connection state, last attempt, last success,
      duration, files/bytes transferred, retries, and a classified error.
- [ ] The sync view shows per-file progress and queue state, not only a spinner.
- [ ] The device log remains local and redacted; new diagnostics do not log
      health payloads or secrets.
- [ ] New protocol messages/fields have tests and are verified on the FR255.
- [ ] The card and log are reachable, readable, and accessible at large font.

## G5. Activity export (FIT/GPX/TCX/CSV)

**Size:** M · **Estimate:** 3–4 days
**Depends on:** existing `activities`, `records`, and `laps`
**Risk:** Low — app-only, no new service.

**Acceptance criteria**
- [ ] Activity detail offers **Export** with FIT, GPX, TCX, and CSV.
- [ ] FIT exports the original watch file when it is still on disk; a missing
      file produces a clear error, not a silently generated substitute.
- [ ] GPX exports only valid GPS points; the option is disabled or explains why
      when the activity has no coordinates.
- [ ] TCX includes activity/lap timing, distance, HR, cadence, altitude, and
      available track points.
- [ ] CSV has documented columns, ISO-8601 UTC timestamps, elapsed seconds,
      units, and lap index; it opens cleanly in spreadsheet software.
- [ ] The system document picker is used; filenames are readable and include the
      correct extension and MIME type; nothing is uploaded.
- [ ] Tests cover XML escaping, no GPS, empty records, missing laps, invalid
      positions, and filename generation.
- [ ] `docs/privacy.md` and the README describe local export.

## G6. Encrypted backup and restore

**Size:** M · **Estimate:** 4–6 days
**Depends on:** `BackupManager`, existing schema validation; crypto library
approval if platform primitives are not sufficient
**Risk:** Medium — cryptography and destructive restore.

**Acceptance criteria**
- [ ] Export can encrypt the archive with a user passphrase using a vetted AEAD
      construction and password KDF; no home-grown crypto.
- [ ] The archive contains a versioned manifest with KDF parameters; the
      plaintext database and chat JSON are not present in the encrypted ZIP.
- [ ] Restore validates the header, schema version, manifest, and authentication
      before replacing anything; a wrong passphrase or corrupt archive leaves the
      current data untouched.
- [ ] Legacy unencrypted ZIP/SQLite imports still work and are clearly labelled
      as unencrypted.
- [ ] The passphrase is never stored or logged; the UI explains that losing it
      makes the backup unrecoverable.
- [ ] Provider configuration and API keys remain excluded.
- [ ] Tests cover round-trip, wrong passphrase, tamper, wrong schema/version,
      legacy import, and an instrumented restore.
- [ ] Backup/restore and privacy docs are updated.

## G7. Profile, units, and goals

**Size:** M · **Estimate:** 3–5 days
**Depends on:** none
**Risk:** Low/Medium — touches many display paths.

**Acceptance criteria**
- [ ] Settings stores height, weight, birth date, sex (optional), unit system
      (metric/imperial), step goal, active-minute goal, floors goal, sleep goal,
      and weight goal.
- [ ] Inputs validate ranges and show helpful errors; unset values show setup
      prompts rather than invented defaults.
- [ ] One profile source feeds Today, Health, Trends, activity detail, zones,
      PacePro, and coach tools.
- [ ] Metric/imperial conversion applies to distance, pace/speed, elevation,
      weight, and temperature where shown; stored data is unchanged.
- [ ] Coach tool output includes units and the available profile context.
- [ ] Conversion/validation tests and large-font/accessibility checks pass.

## G8. Structured workout targets and watch calendar

**Size:** L · **Estimate:** 1–2 weeks
**Depends on:** G7 for zones, `FitWorkout`, `SyncCoordinator`
**Risk:** Medium/High — FIT target encoding and watch acceptance.

**Acceptance criteria**
- [ ] A workout step supports open, HR zone, HR range, pace range, power range,
      and cadence range targets, with validation and a plain-language summary.
- [ ] Existing timed/distance/open steps migrate without loss.
- [ ] A workout can be scheduled by date and time and sent to the watch calendar,
      not only stored as a local date.
- [ ] Scheduled sessions can be edited, moved, and deleted; conflicts show a
      clear warning.
- [ ] Coach-created workouts can use the same targets.
- [ ] FIT encoder tests cover every target type and invalid ranges; upload is
      verified on the FR255.
- [ ] `docs/testing.md` records the device matrix and any sport-specific limits.

## G9. First-run onboarding and pairing

**Size:** M · **Estimate:** 3–5 days
**Depends on:** G3/G4 preferred for truthful states
**Risk:** Low.

**Acceptance criteria**
- [ ] First launch explains local-first/privacy, permissions, scan/pair, first
      sync, and profile setup in a short, skippable flow.
- [ ] Existing Android bonds are detected; when none exists, the user gets
      manual pairing instructions and a retry path.
- [ ] Every step has a real loading/empty/error state; no fake progress and no
      dead ends.
- [ ] Existing users bypass onboarding; a reset action is available in Settings.
- [ ] The flow is usable at 200% font scale and with TalkBack; UI/device tests
      cover permission denial and watch-not-found.
- [ ] Privacy copy matches `docs/privacy.md`.

## G10. Data quality and repair center

**Size:** M · **Estimate:** 4–6 days
**Depends on:** G1/G2 for the best coverage data
**Risk:** Medium — repair actions can delete data.

**Acceptance criteria**
- [ ] Detects duplicate activities, clock skew between watch and phone, missing
      days, stale files, partial imports, and orphaned FIT files.
- [ ] A Data quality screen lists issues with severity, evidence, and affected
      dates/activities; counts are testable.
- [ ] Repair actions (re-import, merge/delete duplicate, re-link file) require
      confirmation and are idempotent.
- [ ] Destructive repairs keep a safety copy or offer an export first.
- [ ] Detector and repair tests use fixtures; an instrumented repair test runs.
- [ ] The screen is fully local and does not upload diagnostics.

---

## Epic G — out of scope

- P1/P2 parity items (adaptive plans, courses, device settings, social/safety,
  weight/nutrition/women's health, custom dashboards, live HR, Health Connect)
  are tracked in the gap analysis and are not part of this P0 commitment.
- Garmin account/cloud, Garmin Pay, music, notifications, Connect IQ, firmware
  updates, licensed coach content, and Garmin's proprietary black-box scores.

---

# Cross-cutting / deferred

These are explicitly **not** in the first implementation pass, or are tracked
elsewhere:

- **Deferred from the handoff:** FIT explorer axis labels/saved custom plots and
  ConnectIQ weather responses.
- **Out of scope:** Garmin cloud/account sync, server backend, multi-user/auth,
  iOS, medical diagnosis, medication advice, model fine-tuning, making the coach
  a clinician.
- **Optional/approval-gated:** generic read-only SQL tool (D7), WorkManager
  background sync (G3), OkHttp (C4), screenshot testing framework (F3),
  androidx.security if we choose it over direct Keystore (C1).
- **Not a dependency of this work:** a new UI framework, a chart library, or a
  navigation library. The current Compose stack can express the mockups.

# Handoff evaluation — what else was missing

The handoff's immediate next steps remain valid and are mapped as follows:

| Handoff item | Backlog home |
|---|---|
| Multi-day wellness history | G1 (was B3a/B3b), B6 |
| Sleep/HRV (not in handoff explicitly, but required by mockups) | G1, D3 |
| Background/periodic sync + connected state | G3 (was B2) |
| Units/axis labels + saved custom plots | G7 for units; plots deferred |
| Weather responses | Deferred |
| Importer/migration tests | F1 |

Additional gaps found during Phase 0 and now tracked: persisted sync metadata,
step goal and max-HR settings, provider settings, no CI, no app/UI tests, and the
need to split `MainActivity.kt` before adding four tabs and streaming chat.

`docs/PLAN.md`, `docs/architecture-notes.md`, and `docs/HANDOFF.md` were brought
up to date on 2026-10-19: they describe database version 9, the five-tab UI,
the coach/provider/tool layers, conversation history, database backup/restore,
the workout planner, and PacePro. FIT/CSV/GPX **activity** export is still
deferred; the implemented export is a full local database plus conversation
backup. `SyncState` maps to B2 (Room/settings persistence is done; WorkManager
background sync is not).

# Open questions

These need a decision before or during the first implementation pass. Recommended
answers are suggestions, not decisions.

## P0 — block the start of B

1. **Sleep and HRV: expand watch ingestion now, or ship empty states?**
   The mockups show sleep and HRV, but the app only ingests activity and current-
   day wellness files. Expanding ingestion is reverse-engineering (B3a) and may
   be the largest item in the plan.
   _Recommendation:_ timebox B3a first; if blocked, ship honest "Not synced yet"
   tiles and keep the coach from claiming those metrics. Confirm whether
   expanding watch sync is in scope for this effort.

2. **Historical wellness days: how important is a full Week/Month Trends view?**
   Current cuts contain only the current day's delta, so history depends on
   repeated syncs. Requesting older days is not yet proven.
   _Recommendation:_ investigate in B3a; otherwise label Trends as based on
   available days and add periodic sync.

3. **Watch battery / "Watch · 82%" and floors:** neither is available today.
   _Recommendation:_ deviate from the mockup: show persisted "Synced N min ago"
   and omit battery/floors. Confirm this deviation is acceptable.

## P1 — affect screen behavior and data truthfulness

4. **Insight cards on Today/Trends:** deterministic local summary, or an LLM
   call? The brief only requires the coach chat, and silent health-data uploads
   on screen load would be a privacy surprise.
   _Recommendation:_ deterministic local insights v1, with "Ask Coach" for the
   model. If LLM-generated, make it opt-in and cached.

5. **Step goal and HR zones:** the mockups assume a 10,000 goal and zone
   percentages, but the app has no profile. Add a settings screen?
   _Recommendation:_ yes — configurable step goal (default 10,000 if the user
   accepts) and max HR/age for zones. Until set, show "Set goal/max HR" rather
   than inventing values.

6. **Activity detail UX:** the mockup has list selection + a featured card; the
   current app has a separate detail screen. Keep both?
   _Recommendation:_ yes. Selecting a row updates the featured card; opening it
   shows the full existing detail plus zones and data explorer.

7. **Strength filter mapping:** FIT sport/subSport does not always identify
   strength workouts.
   _Recommendation:_ use a documented mapping and keep unmatched sports under
   All; do not guess from names unless the user approves heuristics.

## P2 — implementation and dependency decisions

8. **Approve new dependencies:** OkHttp (HTTP/SSE), WorkManager (periodic sync),
   test libraries (JUnit/coroutines-test/MockWebServer/Robolectric or
   instrumentation), and optionally a screenshot-test tool. No new UI framework.
9. **Cleartext HTTP policy for local models:** allow LAN `http://` with a warning,
   or require HTTPS/`adb reverse`? The Ollama/LM Studio presets are HTTP.
   _Recommendation:_ HTTPS by default; explicit opt-in warning for plain HTTP,
   at least loopback/emulator.
10. **Env-var configuration target:** Android has no normal runtime env contract.
    _Recommendation:_ generate `BuildConfig` values from build-time environment
    variables, with user settings taking precedence.
11. **Local-profile scoping:** confirm that "authenticated user" should be
    implemented as an explicit local profile scope (single user, no auth) for
    v1. There is no login and no server.
12. **Chat persistence:** ~~persist conversations locally in Room, or keep them
    ephemeral?~~ **Resolved 2026-10-18:** persist locally in private
    SharedPreferences JSON with multi-conversation history and confirmed delete;
    moves to Room if schema-backed querying becomes necessary.
13. **Provider key storage:** direct Android Keystore AES-GCM (no new dependency)
    versus `androidx.security:security-crypto` (convenience, extra dependency,
    deprecation concerns). _Recommendation:_ direct Keystore.

# Backlog maintenance

- Move a story to **In progress** when its branch starts; add a link to the PR.
- Update acceptance criteria only with a reason; do not quietly drop a mockup
  interaction.
- At the end of each epic, append a short "shipped / next" note here and update
  `docs/HANDOFF.md` if the protocol or build state changed.
- If a data gap forces a mockup deviation, record it in the open questions and
  get approval before shipping the deviation.
