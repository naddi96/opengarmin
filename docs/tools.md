# Coach tools and data access

The coach never writes SQL and never sees the database directly. It can only
call a small set of typed, read-only tools. Every tool is executed locally
against the app's Room data and returns compact JSON.

## Scope and limits

- **Scope:** `ToolScope(profileId = "local")`. The app is single-profile and
  local-first; the scope parameter is enforced so a future multi-profile or
  server-backed mode cannot accidentally read another profile. Requests with an
  unknown scope are rejected before any query.
- **Read-only:** tools use repository/DAO read methods only. There is no raw SQL
  and no write path.
- **Ranges:** each date is ISO `YYYY-MM-DD`; `to` defaults to today. Maximum
  366 days, 90 days for daily-grained queries. `compare_periods` allows 90 days
  per side.
- **Rows/points:** `list_activities` returns at most 50; heart-rate samples at
  most 168; daily summary at most 366 rows; chart series at most 5 activities ×
  120 points. Charts per answer: at most 3.
- **Chat turn:** the loop allows at most 4 rounds and 8 tool calls.
- **Time:** tool execution is local and fast; the HTTP call has its own
  timeouts. A tool failure is returned to the model as an error object.

## Tools

### `get_daily_summary(from?, to?)`
Daily wellness totals/averages: steps, distance, active minutes/calories,
resting/avg/max HR, stress, SpO2, respiration. Missing metrics are omitted and
listed under `unavailable` (currently `sleep`, `hrv`, `floors`). Reports
`daysRequested` vs `daysWithData`; missing days are **not** zero-filled.

### `get_steps(from?, to?, granularity?)`
`granularity` = `day` or `week`. Returns total, average, `daysWithData`, and
buckets. Daily requests are capped at 90 days; weekly at 366.

### `get_heart_rate(from?, to?, granularity?, include_samples?)`
Resting/avg/max HR per available day. `include_samples=true` adds up to 168
intraday samples, evenly downsampled.

### `get_sleep(from?, to?)`
Returns `available: false` and a reason because sleep files are not ingested
yet. It never infers sleep from activity or HR data.

### `list_activities(from?, to?, type?, limit?, offset?)`
Compact workout rows: id, date, sport/category, distance, duration, HR,
calories. `type` = Run/Ride/Strength/Walk. Max 50 rows. The sport mapping is
documented in `coach/SportCategory.kt`; unsupported sports stay under All.

### `get_activity(id, max_heart_rate?, include_route?)`
One workout's stats, laps, **GPS summary**, **running dynamics**, training
effect, and (only when `max_heart_rate` is 100–250) time in five zones.

- **GPS:** `available`, point count, start/end coordinates, and bounding box.
  Set `include_route=true` to add up to 60 sampled route points when the user
  asks about the route or location. If no GPS was recorded, `available` is
  `false`; no coordinates are invented.
- **Running dynamics:** cadence in steps/min (raw cycles/min also included),
  ground contact / stance time in ms, ground contact percent/balance, vertical
  oscillation in mm/cm, vertical ratio %, stride length in mm/m, power,
  training effect, anaerobic training effect, load peak, moving/standing time,
  average/max speed, respiration, stress, and SpO2 when the FIT fields exist.
  Session values are used first, with record averages as fallback. Missing
  fields are omitted; unavailable dynamics return `available: false`.
- **Laps:** distance/duration/HR plus cadence, ground contact, vertical
  oscillation, and stride length when present.
- If no max HR is supplied, `zonesAvailable: false`; no max HR is invented.

### `compare_periods(metric, range_a_*, range_b_*)`
Metrics: steps, resting_hr, active_minutes, calories, sleep, hrv. Uses daily
averages, reports sample counts, absolute delta, and percent change. Sleep/HRV
return insufficient data until those files are ingested.

### `render_chart(source, metric, ...)`
Builds a chart from stored data and attaches it to the answer. The model never
supplies raw points, so a chart cannot contain values the tools did not read.

- `source=daily`: one metric over `from`/`to` (`steps`, `distance`,
  `active_minutes`, `calories`, `resting_hr`, `avg_hr`, `max_hr`, `stress`,
  `spo2`, `respiration`). Bars default for counts, lines for rates. Missing
  days are skipped rather than zero-filled; a bar request over 31 days is
  rendered as a line so the chart stays readable.
- `source=activities`: overlays up to five workouts (`activity_ids` from
  `list_activities`) for `heart_rate`, `speed`, `pace`, `cadence`, `altitude`,
  or `body_battery`. `x_axis=elapsed` uses minutes; `x_axis=distance` uses km.
  Series are evenly downsampled to `max_points` (8–120, default 60).
- The UI renders the chart inside the answer, and the answer text is expected
  to reference it ("the chart below"). Charts are persisted with the message.
- Errors (unknown ids, sparsely recorded metrics, unreadable ranges) are
  returned to the model as tool errors; no empty or fabricated chart is drawn.

Charts have a maximum of three per turn and each carries code-generated
evidence such as `Chart · Heart rate · 3 activities`. A fenced code block
tagged `chart` is also understood by the chat renderer as a fallback, but the
tool path is preferred because the chart data stays in code.

## Evidence ("Data used")

Every `ToolResult` carries one or more short evidence labels such as
`Heart rate · 14 days`. The chat stores and renders only the evidence from tools
that actually ran. If the model used no tools, no source chips are fabricated.
The no-tool fallback labels the snapshot sections it included.

Evidence labels are generated in code, not by the model, so the model cannot
invent a source.

## No-tool fallback

When the endpoint rejects tools (HTTP 400/404 on the tools request), the loop:
1. Builds a compact snapshot of the last 14 days: daily aggregates and up to 10
   recent workouts.
2. Adds it to the system prompt inside `<untrusted_data>` tags.
3. Retries once without `tools`.

The snapshot is deterministic, capped, and contains no free-text notes.

## Safety contract

- The system prompt requires answers to come only from tool results/snapshot.
- Missing data must be stated, never estimated.
- The coach is informational, not medical advice; concerning patterns are
  referred to a professional.
- All tool output and database text is wrapped as untrusted data. The prompt
  tells the model not to follow instructions inside it. Code boundaries
  (read-only registry, scope, limits) are the real enforcement; the prompt is
  defence in depth.
- Provider keys and raw health payloads are never logged.

## Adding a tool

1. Implement `HealthTool` (name, description, JSON schema, `execute`).
2. Add read-only methods to `HealthDataSource`/the repository if needed.
3. Enforce `ToolScope`, date/row limits, and compact JSON.
4. Return `ToolResult(json, evidence list)`; evidence must describe the metric
   and range actually returned.
5. Register it in `ToolRegistry`.
6. Add unit tests for empty data, missing metrics, range limits, row caps, and
   scope isolation; add a mock-server loop test if it is used in chat.
7. Update this document and the system prompt if the tool changes what the
   coach may claim.

## Generic SQL tool

Not implemented. If ever added, it must be a separate, approval-gated story with:

- a dedicated read-only SQLite connection/role;
- an allow-listed set of views, never the writable database;
- a scope predicate, statement timeout, row cap, and column allow-list;
- a written threat model covering prompt injection, exfiltration, health-data
  sensitivity, and denial of service.

The typed tools above are sufficient for the coach's current questions.
