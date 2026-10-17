# BACKLOG — Pulse UI + AI coach

_Last updated: 2026-10-17. Baseline commit: `519964d`. Phase 0 status: **complete**; implementation intentionally not started._

This backlog turns the Pulse mockups and the OpenAI-compatible coach brief into
reviewable increments. It assumes the current app is a local-first Android
Compose app with Room, no auth, and no HTTP/LLM layer; see
[`docs/architecture-notes.md`](docs/architecture-notes.md).

## How to read this

- **Size:** S ≈ 1–2 days, M ≈ 3–5 days, L ≈ 1–2 weeks (rough, for one developer).
- **Status:** Phase 0 is done; every implementation story is `Not started`.
- **Order:** A → B → C → D → E → F. Within B, the data-coverage spike runs early
  because it is the biggest unknown. The Coach shell (B8) is built after C so it
  can link to a real provider settings screen; chat becomes live in E.
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
| A. Design system | Token-based Compose primitives matching mockups | Not started |
| B. Screens | Today, Trends, Activities, Coach shell, real data + states | Not started |
| C. LLM provider settings | Presets, encrypted keys, models, test connection, streaming transport | Not started |
| D. LLM data access | Typed read-only health tools + evidence + safety | Not started |
| E. Coach chat | Streaming chat with tool calls, cancel, sources, persistence | Not started |
| F. Quality | Tests, accessibility, performance, CI, docs, privacy audit | Partially done |

## Implementation update — 2026-10-17

Shipped in this pass (see commits/working tree):

- **A:** `PulseTheme` tokens, bundled DM Sans/Space Grotesk, cards, tiles,
  chips, segmented control, buttons, tab bar, empty/error/loading states,
  progress ring, bar/line charts, zone bar, chat components.
- **B:** four-tab app shell + detail/settings/provider/log routes; Today, Trends,
  Activities, Activity detail, Coach shell and full chat; persisted sync
  metadata; profile settings (goal, max HR); honest sleep/HRV empty states.
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
- **F:** 25 app JVM tests (tools, FIT insights, mock OpenAI + chat loop), existing 34 protocol
  tests, CI workflow, provider/tools/testing/privacy docs.

Known deviations / still open:

- Provider/model config uses private SharedPreferences JSON instead of Room
  tables. Keys are still Keystore-encrypted; this avoids a schema migration and
  is easy to move to Room later.
- B1 uses screen state holders in the activity/Compose state rather than
  ViewModels. Screens are split out of `MainActivity` and state survives tab
  changes, but process-death restoration is limited.
- B2 persists sync state but does **not** add WorkManager background sync.
- B3a/B3b (sleep, HRV, historical wellness) are **not implemented**. The UI/tools
  explicitly show those metrics as unavailable.
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

## Delivery milestones

1. **M0 — Foundation (done):** docs and mockups in place.
2. **M1 — Design system usable (A):** theme + component gallery.
3. **M2 — App aligned (B):** four-tab app with real data and honest empty states.
4. **M3 — Provider ready (C):** user can configure and test an endpoint.
5. **M4 — Data tools ready (D):** model can call typed, limited, read-only tools.
6. **M5 — Coach live (E):** streaming, cancellable, evidence-backed chat.
7. **M6 — Hardened (F):** automated tests, a11y/perf checks, CI, docs.

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
**Acceptance criteria**
- [ ] Room (or equivalent) stores conversations/messages: role, content, evidence
      metadata, created time, status (streaming/complete/cancelled/error).
- [ ] Chat survives rotation, process death, and app restart; user can start a
      new conversation and delete one with confirmation.
- [ ] No API key and no raw provider response headers are stored; health data is
      only stored locally in the app's DB.
- [ ] Sending is blocked while a response is streaming; cancel is available.
- [ ] Migration and ViewModel/state tests cover reload, partial/cancelled
      messages, and empty conversations.

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

# Cross-cutting / deferred

These are explicitly **not** in the first implementation pass, or are tracked
elsewhere:

- **Deferred from the handoff:** FIT explorer axis labels/saved custom plots and
  ConnectIQ weather responses.
- **Out of scope:** Garmin cloud/account sync, server backend, multi-user/auth,
  iOS, medical diagnosis, medication advice, model fine-tuning, making the coach
  a clinician.
- **Optional/approval-gated:** generic read-only SQL tool (D7), WorkManager
  background sync (B2), OkHttp (C4), screenshot testing framework (F3),
  androidx.security if we choose it over direct Keystore (C1).
- **Not a dependency of this work:** a new UI framework, a chart library, or a
  navigation library. The current Compose stack can express the mockups.

# Handoff evaluation — what else was missing

The handoff's immediate next steps remain valid and are mapped as follows:

| Handoff item | Backlog home |
|---|---|
| Multi-day wellness history | B3a/B3b, B6 |
| Sleep/HRV (not in handoff explicitly, but required by mockups) | B3a/B3b, D3 |
| Background/periodic sync + connected state | B2 |
| Units/axis labels + saved custom plots | Deferred |
| Weather responses | Deferred |
| Importer/migration tests | F1 |

Additional gaps found during Phase 0 and now tracked: persisted sync metadata,
step goal and max-HR settings, provider settings, no CI, no app/UI tests, and the
need to split `MainActivity.kt` before adding four tabs and streaming chat.

`docs/PLAN.md` is also stale relative to the handoff (it still describes M5/M7
differently and lists `Device`, `SyncState`, `SleepSession`, `BodyMetric`, and
FIT/CSV/GPX export as intended, but only activities/records/laps/messages/
wellness exist). Mapping: `SyncState` → B2, `SleepSession` → B3b. Body metrics
and export are outside this brief and remain deferred; F7 updates the plan so it
no longer contradicts the handoff.

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
12. **Chat persistence:** persist conversations locally in Room, or keep them
    ephemeral? _Recommendation:_ persist locally with delete/new-chat controls.
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
