# Garmin Connect gap analysis and differentiation plan

_Last updated: 2026-10-19 · OpenGarmin baseline: `eebd0d4` · Garmin Connect reference: mobile 5.30.x and Garmin Connect+ (October 2026)_

This document compares the shipped OpenGarmin app with the Garmin Connect
mobile app, lists the missing capabilities, separates "missing" from
"deliberately out of scope", and proposes features that Garmin Connect does not
offer. It is a product/engineering input, not a commitment or a release plan.

The short version:

- OpenGarmin already covers the **local data-review core** well for a
  Forerunner-style watch: BLE sync, activities, wellness, training metrics,
  activity analysis, a raw FIT explorer, workout/PacePro upload, and an
  optional private coach.
- It is **not a Garmin Connect replacement**. The largest gaps are historical
  wellness coverage, background sync, device management, structured training
  plans, maps/courses, social/safety, and data portability.
- Chasing full parity would destroy the product's advantage. The defensible
  strategy is: **fix the trust gaps, add the few parity features that make the
  app usable every day, and win on local ownership, provenance, automation and
  private AI.**

## 1. How to read this

Status:

| Symbol | Meaning |
|---|---|
| ✅ | Present and usable in the app for the same job |
| 🟡 | Partial, limited, or dependent on which files the watch exposes |
| ❌ | Missing |
| ⛔ | Deliberately out of scope for the local-first product (cloud account, licensed content, or device-side service) |
| ⭐ | OpenGarmin capability that Garmin Connect does not provide, or provides only through its cloud/subscription |

Feasibility:

| Code | Meaning |
|---|---|
| **A** | App-only work: can be built from data already in Room or from files already on disk |
| **P** | Protocol/reverse-engineering work: needs new BLE, FIT, or file-format support |
| **C** | Content or service work: needs licensed data, a server, or a maintained catalogue |
| **O** | Out of scope for this product |

Priority:

- **P0** — trust/table stakes; the app is hard to recommend without it.
- **P1** — major parity or daily-usability gap.
- **P2** — meaningful but can wait.
- **P3** — later, experimental, or deliberately excluded.

"Present" means the UI can actually use the capability. Data that is merely
parsed into `fit_messages` but has no screen, tool, or action is marked partial.

## 2. What OpenGarmin does today

| Surface | Shipped behaviour | Important limits |
|---|---|---|
| **Transport and sync** | BLE GFDI v2 / Multi-Link, scan/connect/reconnect, auto-connect to a bonded watch on launch, manual sync, foreground `connectedDevice` service, incremental UID dedupe, activity/wellness pulls, workout and pace-band upload | No periodic/background sync, no WorkManager, no watch battery/storage, no sync history, one watch, no onboarding flow |
| **Today** | Steps ring and goal, distance, active minutes/calories, current/resting HR, intraday HR chart, sleep/HRV tiles, local deterministic insight, sync status/progress/error | Fixed layout; no floors, live HR, hydration, nutrition, weight, or configurable cards |
| **Health** | Body Battery, stress average/distribution/chart, sleep score/stages/SpO₂/respiration/lowest HR, HRV status/baseline/weekly average, resting HR, SpO₂, respiration, VO₂ max, daily movement, weekly active minutes | Sleep and HRV appear only when the watch exposes the relevant files; no sleep coach, naps, skin temperature, health status/snapshot, fitness age, or real readiness score |
| **Training** | Recovery time, VO₂ max and six-week trend, 7-day acute load, chronic load, load focus, lactate threshold, performance condition, latest training effect, race predictor, readiness factors | Readiness shows factors, not a score; no training status, FTP, power analysis, endurance/hill score, heat/altitude acclimation, daily suggested workouts, or adaptive plans |
| **Activities** | Weekly summary, sport filters, featured card, detail stats, OSM map, HR/elevation charts, running dynamics, GPS summary, HR zones, laps, raw FIT data explorer, local FIT/GPX/TCX/CSV export | Generic titles ("Run", "Ride"), no notes/RPE/photos/gear/weather, no pace/power/cadence charts, no PRs/segments, no share |
| **Plan** | Timed, distance, and open steps; repeat blocks; save/edit/delete; local scheduled date; send a FIT workout to the watch | No HR/pace/power/cadence targets, no strength exercises or sets/reps, no templates/library, no watch calendar sync, no adaptive plan |
| **PacePro** | 5K/10K/half/marathon/custom distance, Riegel prediction, recent-run elevation profile, split/hill sliders, saved plans, watch upload, phone-GPS preview run with manual laps | No GPX/course import, no live HR, no watch-native navigation, no weather/race-day integration |
| **Coach** | OpenAI-compatible presets, encrypted key, model management, streaming Markdown, cancel, read-only health tools, evidence chips, inline charts, coach-created workouts, 50 conversations | Opt-in only; no proactive insights, no on-device model, no long-term memory beyond chat, no plan adaptation, only one write path (create workout) |
| **Trends** | 7D/30D/90D/1Y/All/custom ranges, 11 metrics, averages, daily/weekly/monthly buckets, partial-history labels | Fixed metrics and chart types; no custom dashboards or cross-metric correlation |
| **Settings and data** | Step goal, max HR, provider management, sync/scan/disconnect, device log, FIT import, ZIP database + chat backup/restore, per-activity FIT/GPX/TCX/CSV export | No height/weight/age/sex/units, no goals beyond steps, no encrypted backup, no multi-profile |
| **Quality and security** | JVM tests for protocol/app, CI, Keystore-encrypted provider key, privacy docs, no account/telemetry | No Compose UI, migration, or end-to-end backup tests; cleartext HTTP is app-wide with a warning |

Coverage at a glance:

| Area | OpenGarmin today | Verdict |
|---|---|---|
| Daily health review | Steps, HR, stress, Body Battery, SpO₂, respiration, sleep/HRV | **Strong** for the metrics the watch exposes |
| Sleep and recovery | Sleep stages/score, HRV, recovery time | **Partial** — data availability is the blocker |
| Training analysis | Load, focus, VO₂ max, LT, training effect | **Moderate** — good running core, no status/FTP/power |
| Training planning | Simple workout builder, PacePro | **Basic** — no targets, plans, calendar, or adaptation |
| Activity analysis | Map, HR/elevation, laps, dynamics, raw FIT | **Moderate** — strong for a local app, thin against Garmin |
| Device management | Connect/sync/disconnect | **Minimal** |
| Maps and navigation | Activity map only | **Minimal** |
| Social, safety, sharing | None | **None** |
| Integrations and portability | FIT import, ZIP backup | **Minimal** |
| AI and coaching | Private, provider-agnostic coach | **Differentiated but narrow** |

## 3. Garmin Connect feature baseline (October 2026)

Garmin Connect is much broader than the activity/wellness screens. The relevant
surface for this comparison is:

**Free app**

- Customizable Home ("In Focus" and "At a Glance"), daily cards, seven-day
  summaries, and tap-through detail for each metric.
- Health Stats: steps, floors, intensity minutes, heart rate, resting HR, HRV
  status, stress, Body Battery, sleep score/stages, Pulse Ox, respiration,
  skin temperature, health status, health snapshot, fitness age, training
  readiness, recovery, VO₂ max, weight/body composition, hydration, women's
  health (menstrual cycle and pregnancy), calories, reports, and trends.
- Training: Garmin Coach run/cycling/triathlon plans, daily suggested workouts,
  training calendar, custom workouts, PacePro, ClimbPro (watch), race events,
  race predictor, training status, load/focus, training effect, lactate
  threshold, FTP, power, endurance score, hill score, heat/altitude
  acclimation, and guided tests.
- Activities: detailed stats, maps with themed polylines, charts, laps/splits,
  running dynamics, heart-rate zones, personal records, segments, gear,
  notes/feel/RPE, photos, weather, sharing, export, and manual activity entry.
- Courses and maps: course creation, GPX import/export, send to device,
  offline maps, 3D maps (Connect+), and Garmin Trails (Connect+).
- Device: pairing/setup, device settings, notifications, music, Wi-Fi,
  firmware updates, Connect IQ apps/watch faces, alarms, Find My Watch,
  sensor pairing, Garmin Pay, safety/incident detection, and LiveTrack.
- Social: connections, news feed, groups, challenges, badges, leaderboards,
  and activity sharing.
- Integrations: Strava, MyFitnessPal, Health Connect, Apple Health, Garmin
  Index scales, and other partners.
- Data: Garmin cloud sync, web app, reports, export, and account-level data
  management.

**Garmin Connect+ (subscription, launched March 2025; expanded 2026)**

- Active Intelligence: cloud AI-generated insights and suggestions.
- Performance dashboard: 140+ customizable charts, cross-metric overlays,
  multiple time periods, strength/nutrition data (web-first, viewable from the
  app).
- Live Activity: phone-side real-time HR/pace/reps for compatible indoor
  workouts, with remote start/stop and rep/weight editing.
- Expanded LiveTrack: text notifications and a personalized LiveTrack page.
- Training guidance and exclusive coach content for Garmin Coach plans.
- Social frames and exclusive badge challenges.
- Nutrition tracking (added January 2026): food database, barcode scan,
  AI photo recognition, custom foods/meals, macro goals, and reports.
- Garmin Trails, 3D maps, and Garmin Connect Rundown.

This matters because several apparent "missing" features are not app bugs; they
are **services, licensed content, or watch-side features that Garmin's cloud
funds**. The app should not pretend to have them.

## 4. Gap matrix

### 4.1 Home, health, and recovery

| Capability | Garmin Connect | OpenGarmin today | Status | Priority | Feasibility |
|---|---|---|---|---|---|
| Customizable home cards | In Focus / At a Glance | Fixed Today dashboard | ❌ | P1 | A |
| Proactive AI insights | Active Intelligence (Connect+) | Deterministic local insight + opt-in coach | 🟡 | P2 | A/C |
| Performance dashboard | 140+ custom charts, correlations, multi-period | Fixed Trends metrics/ranges | ❌ | P2 | A |
| Steps, distance, active calories, active minutes | Full history | Current day + accumulated history | 🟡 | P0 | P |
| Floors | Yes | Not stored | ❌ | P1 | P/A |
| Intensity minutes and weekly goal | Yes | Moderate/vigorous parsed; no goal | 🟡 | P1 | A |
| Heart rate and resting HR | Yes, plus live HR | Yes, intraday chart; no live HR | 🟡 | P1 | P |
| HRV status | Yes | Partial, watch-dependent | 🟡 | P0 | P |
| Stress and Body Battery | Yes | Yes | ✅ | — | — |
| Sleep score, stages, coach, naps | Yes | Score/stages when exposed; no coach/naps | 🟡 | P0/P1 | P/A |
| Pulse Ox and respiration | Yes | Yes | ✅ | — | — |
| Skin temperature | On compatible watches | Not stored | ❌ | P2 | P |
| Health status and health snapshot | Yes | No | ❌ | P2 | P/A |
| Fitness age | Yes | No | ❌ | P2 | A |
| Training readiness | Yes | Factors only; no score | 🟡 | P1 | A |
| Weight and body composition | Yes, plus Index scales | No | ❌ | P1 | A/P |
| Hydration | Yes | No | ❌ | P2 | A |
| Nutrition and macro goals | Yes (Connect+) | No | ❌ | P2 | A/C |
| Women's health (cycle, pregnancy) | Yes | No | ❌ | P2 | A |
| Blood pressure / glucose | Via partners/devices | No | ❌ | P3 | C |
| Manual metric and activity entry | Yes | No | ❌ | P1 | A |
| Reports | Web reports | No PDF/CSV report | ❌ | P2 | A |
| Units, profile, and non-step goals | Full | Step goal and max HR only | 🟡 | P0 | A |

### 4.2 Training and planning

| Capability | Garmin Connect | OpenGarmin today | Status | Priority | Feasibility |
|---|---|---|---|---|---|
| Training load, focus, effect | Yes | Yes | ✅ | — | — |
| VO₂ max, lactate threshold, performance condition | Yes | Yes | ✅ | — | — |
| Recovery time | Yes | Yes | ✅ | — | — |
| Training status | Yes | No | ❌ | P1 | A |
| FTP and cycling power analysis | Yes | Raw FIT only | ❌ | P1 | A/P |
| Endurance score, hill score, heat/altitude acclimation | Yes | No | ❌ | P2 | P/A |
| Race predictor | Yes | Local Riegel estimate | 🟡 | — | — |
| Garmin Coach / adaptive plans | Yes | No | ❌ | P1 | C/A |
| Daily suggested workouts | Yes | No | ❌ | P1 | A/P |
| Training calendar and watch scheduling | Yes | Local scheduled date only | 🟡 | P1 | P |
| Workout targets (HR/pace/power/cadence) | Yes | All steps are open target | 🟡 | P0 | A |
| Strength, yoga, mobility, and exercise library | Yes | Generic timed/distance steps only | ❌ | P2 | C/P |
| Workout templates/library | Yes | No | ❌ | P2 | C |
| PacePro | Yes, watch-native | Builder + pace band + phone run | 🟡 | — | — |
| ClimbPro | Yes, watch-side | No | ❌ | P2 | P |
| Courses and routes | Yes | No | ❌ | P1 | A/P |
| Segments | Yes | No | ❌ | P3 | C |
| Personal records and best efforts | Yes | No | ❌ | P2 | A |
| Gear tracking | Yes | No | ❌ | P2 | A |
| Guided tests (FTP, LT, etc.) | Yes | No | ❌ | P2 | A |
| Live Activity / indoor workout mirroring | Connect+ | No | ❌ | P2 | P |
| Phone-side activity recording | Manual entry only | No | ❌ | P3 | A |
| Training-plan content | Yes | No | ❌ | P1 | C |

### 4.3 Activities and analysis

| Capability | Garmin Connect | OpenGarmin today | Status | Priority | Feasibility |
|---|---|---|---|---|---|
| Activity list, filters, weekly summary | Yes | Yes | ✅ | — | — |
| Detail stats and splits | Yes | Yes | ✅ | — | — |
| Map | Themed, 3D, Garmin Trails | Online OSM map | 🟡 | P1 | A/C |
| Charts | Custom, pace/power/cadence/temp | HR and elevation only | 🟡 | P1 | A |
| Laps and splits | Yes | Yes | ✅ | — | — |
| Running dynamics and HR zones | Yes | Yes | ✅ | — | — |
| Notes, feel, RPE | Yes | No | ❌ | P1 | A |
| Photos | Yes | No | ❌ | P2 | A |
| Weather | Yes | No | ❌ | P2 | C |
| Gear | Yes | No | ❌ | P2 | A |
| Multi-sport, strength, swim, golf, ski analysis | Sport-specific | Generic/raw FIT only | ❌ | P2 | A |
| Personal records and segments | Yes | No | ❌ | P2/P3 | A/C |
| Share, export, delete | Yes | Local FIT/GPX/TCX/CSV export; no share/delete | 🟡 | P1 | A |
| Activity comparison | Yes | Coach overlay only | 🟡 | P2 | A |
| Raw FIT explorer | No | Yes | ⭐ | — | — |

### 4.4 Device, maps, safety, and social

| Capability | Garmin Connect | OpenGarmin today | Status | Priority | Feasibility |
|---|---|---|---|---|---|
| Guided pairing/onboarding | Yes | Android bond + auto-connect | 🟡 | P0 | A |
| Device settings | Full | No | ❌ | P1 | P |
| Watch battery and storage | Yes | No | ❌ | P0 | P |
| Sync diagnostics and history | Yes | Last status + device log | 🟡 | P0 | A |
| Periodic/background sync | Yes | No | ❌ | P0 | A |
| Multi-device support | Yes | No | ❌ | P2 | A |
| Firmware updates | Yes | No | ⛔ | P3 | O |
| Connect IQ store/apps/watch faces | Yes | No | ⛔ | P3 | O/C |
| Notifications, calls, SMS | Yes | No | ⛔ | P3 | O |
| Music, Garmin Pay, alarms, Find My Watch, Wi-Fi, sensors | Yes | No | ⛔ | P3 | O/P |
| Safety, incident detection, LiveTrack | Yes (cloud) | No | ⛔ | P3 | O/C |
| Social feed, groups, challenges, badges, leaderboards | Yes (cloud) | No | ⛔ | P3 | O |
| Courses, trails, 3D maps | Yes | No | ❌ | P1/P2 | A/C |
| Weather | Yes | No | ❌ | P2 | C |

### 4.5 Data, platform, and AI

| Capability | Garmin Connect | OpenGarmin today | Status | Priority | Feasibility |
|---|---|---|---|---|---|
| Account and cloud sync | Yes | No | ⛔ | — | O |
| Web app, multi-user, iOS | Yes | No | ⛔ | — | O |
| Backup/restore | Cloud + export | ZIP database + chat | 🟡 | P0 | A |
| Encrypted backup | Cloud at rest | No | ❌ | P1 | A |
| Activity export (FIT/GPX/TCX/CSV) | Yes | Local export from activity detail | ✅ | — | A |
| Integrations (Strava, Health Connect, Apple Health, MyFitnessPal, scales) | Yes | No | ❌ | P1/P2 | A/C |
| Bring-your-own AI provider | No (Connect+ only) | Yes | ⭐ | — | — |
| Local-first / offline | No | Yes | ⭐ | — | — |
| Raw FIT explorer | No | Yes | ⭐ | — | — |
| Watch upload without cloud | Via Garmin cloud | Yes | ⭐ | — | — |
| Open source and auditable | No | Yes | ⭐ | — | — |
| Localization and unit settings | 36 languages | English, kilometres | 🟡 | P2 | A |
| Accessibility | Broad | Component-level; no audit | 🟡 | P1 | A |
| UI/migration/end-to-end tests | n/a | Not yet | 🟡 | P1 | A |

## 5. Missing features that matter most

The list below is the practical backlog implied by the matrix. It is ordered by
user impact, not by Garmin's feature list. The P0 items are tracked as
[Epic G in `BACKLOG.md`](../BACKLOG.md#epic-g--garmin-connect-parity-p0-trust-and-daily-usability),
with acceptance criteria and estimates.

### P0 — trust and daily usability

1. **Historical wellness and data completeness**
   The watch currently exposes the current day's delta, so history depends on
   repeated syncs; sleep/HRV appear only when the watch emits the right files.
   Add older-day requests if the protocol allows it, make missing data explicit,
   and add a "data coverage" view. Without this, Trends, readiness, and the
   coach are built on an incomplete archive.
   _Feasibility: P. Related: B3a/B3b in `BACKLOG.md`._

2. **Background/periodic sync and connection lifecycle**
   Garmin syncs continuously in the background; OpenGarmin syncs on launch or
   when the user taps Sync. Add WorkManager/periodic sync, sync on charger or
   watch reconnect, a clear connected/disconnected state, and retry/backoff.
   _Feasibility: A._

3. **Watch battery, storage, and sync diagnostics**
   Show battery percentage, storage/free space, last successful sync, last
   attempt, failure reason, bytes transferred, and per-file progress. This is
   table stakes for trusting a cable-free sync app.
   _Feasibility: P/A._

4. **Activity export and encrypted backup**
   Export a single activity as FIT, GPX, TCX, and CSV; export all data as an
   open archive; encrypt backups with a user passphrase. Garmin users expect to
   own their activities, and a local-first app should make export trivial.
   _Feasibility: A. Activity export is shipped (G5); encrypted backups remain in
   G6._

5. **Profile, units, and goals**
   Height, weight, birth date, sex, unit system, active-minute goal, floors
   goal, sleep goal, and weight goal. These values unlock zones, calorie
   targets, readiness, nutrition, and correct display. Today only step goal and
   max HR exist.
   _Feasibility: A._

6. **Structured workout targets and calendar**
   Add HR, pace, power, and cadence targets to steps, sport-specific steps,
   and send scheduled workouts to the watch calendar. The current builder
   writes `TARGET_OPEN` steps, which limits its usefulness for serious
   training.
   _Feasibility: A/P._

7. **Onboarding and pairing**
   A first-run flow that explains permissions, scans for the watch, pairs it,
   sets the profile, and runs the first sync. Today the app relies on an
   existing Android bond and auto-connect.
   _Feasibility: A._

8. **Data quality and repair**
   Detect duplicate activities, clock drift, missing days, stale files, and
   partial imports; offer repair. This is also the foundation for provenance
   (see D2 below).
   _Feasibility: A._

### P1 — major parity

- **Training status and transparent readiness score.** Garmin's readiness model
  is not in the FIT file, so the app correctly shows factors instead of
  inventing a score. Build an open, documented local model (sleep, HRV, load,
  recovery, Body Battery) and label it as OpenGarmin's estimate, not Garmin's.
- **Adaptive plans and daily suggested workouts.** A local plan engine that
  reacts to load/readiness, plus a calendar and watch scheduling.
- **Strength/yoga/mobility workouts.** Exercise names, sets, reps, weight,
  rest, and watch upload. The current model only has timed/distance/open steps.
- **Courses and GPX.** Import a GPX, create a route, preview elevation, send a
  FIT course to the watch, and show it on the activity map.
- **Activity notes, feel/RPE, photos, and weather.** Small features that make
  the archive useful and are expected in any activity app.
- **Personal records, best efforts, and segments.** Mostly app-side; segments
  need a catalogue or user-defined segments.
- **Weight, hydration, nutrition, and women's health.** Manual logging first;
  device/partner integration later.
- **Health snapshot and reports.** A local report with trends and data
  completeness; a clinician-oriented export is a differentiator (D7).
- **Live heart rate.** Use Broadcast Heart Rate or the GFDI live channel to
  populate the PacePro preview run and a live dashboard.
- **Health Connect / Strava export.** Export-only first; two-way sync is a
  larger privacy/product decision.
- **Accessibility and test baseline.** Compose UI tests, migration tests, and
  an end-to-end backup/restore test before adding more surface.

### P2 — valuable later

- Custom dashboards and cross-metric correlations.
- Offline/3D maps and curated trails.
- Device settings (alarms, notifications, sensors, Wi-Fi), music, Garmin Pay,
  Connect IQ, firmware updates.
- Social/safety features.
- Multi-device and multi-user.
- Localization and unit settings.
- Weather and race-day integration.

### P3 / deliberate non-goals

See section 6.

## 6. What not to build (and why)

These are genuine Garmin Connect features, but they are poor fits for a
local-first, account-free app. Marking them out of scope prevents an endless
parity chase.

| Non-goal | Reason |
|---|---|
| Garmin account, cloud sync, web app | The product's defining property is that the watch is the source and the phone is the archive. A cloud account reverses that. |
| Social feed, groups, global leaderboards, badges/challenges | Requires a server, moderation, identity, and personal-data sharing. A private local alternative is possible (D10), but not a clone. |
| Garmin Coach licensed plans and coaching videos | Content licensing and coaching expertise, not engineering. A local adaptive engine is the better fit. |
| Garmin Pay, music providers, notifications/calls/SMS | Device-side integrations with large platform and payment dependencies; not needed for the data-ownership mission. |
| Firmware updates, Connect IQ store, watch faces | High-risk device management with bricking potential and a separate catalogue. |
| Global food database, barcode database, AI food photos | Licensed data and cloud inference. A local, user-owned nutrition log is possible later. |
| Race event database, weather, 3D maps, Garmin Trails | Licensed/cloud content. GPX import and OSM routing are the local alternatives. |
| Garmin's proprietary readiness/endurance/hill scores | The inputs are partly on the watch and the models are closed. Build transparent OpenGarmin estimates instead of reverse-engineering a black box. |
| Medical diagnosis, ECG/AFib interpretation, medication advice | Regulatory and safety scope. The app can display recorded values and suggest professional review, not diagnose. |

## 7. Features Garmin Connect does not have

These are the differentiators worth investing in. Each one builds on something
OpenGarmin already owns: local raw data, an open codebase, a provider-agnostic
coach, and direct watch upload.

### D1. Portable Health Vault (recommended first bet)

**What:** A versioned, encrypted, incremental archive of everything: original
FIT files, normalized activities/wellness, provenance, notes, plans, and coach
conversations. Export to an open directory/ZIP and restore on another device.
Optional WebDAV/self-hosted target.

**Why Garmin doesn't have it:** Garmin keeps the primary copy in its cloud and
offers per-activity export. It does not give users an encrypted, restorable,
open local vault with a manifest and integrity checks.

**Build:** L · **Depends on:** P0 export + encryption.  
**Success:** full export and restore in under two minutes with zero row loss;
the archive is readable without the app.

### D2. Provenance and Data Quality Center (recommended first bet)

**What:** Tap any number to see where it came from: file, FIT message, field,
timestamp, device, and transformation. A per-day quality score flags missing
days, stale files, duplicate activities, clock skew, and sensor dropouts, with
one-tap repair.

**Why Garmin doesn't have it:** Garmin presents aggregated metrics as facts.
Users cannot audit a step count, a sleep score, or a training-load value back to
the raw record. OpenGarmin already stores `fit_messages`; this turns that into a
trust feature.

**Build:** M · **Depends on:** existing FIT storage + sync metadata.  
**Success:** every displayed metric is traceable; duplicate/gap rate is
measurable and falling.

### D3. Local AI with a privacy ledger (recommended first bet)

**What:** Run a small on-device model for summaries, data Q&A, and workout
drafting; route to a cloud provider only when the user allows it. Show exactly
what data left the device, to which endpoint, for which question, with token
and cost estimates. Add a "sensitive query stays local" rule.

**Why Garmin doesn't have it:** Active Intelligence is cloud-only and tied to
Garmin Connect+. It does not let users choose a model, inspect the payload, or
keep health data on-device. OpenGarmin already supports any OpenAI-compatible
provider; the missing piece is local inference, routing, and transparency.

**Build:** L · **Depends on:** existing coach/tool layer.  
**Success:** most common questions answer on-device; zero unintended egress;
every cloud call is auditable.

### D4. Automation Rules and an adaptive day

**What:** User-defined if/then rules and coach-proposed changes:
"If readiness < 40, replace intervals with zone 2"; "If sleep < 6 h, move the
long run"; "If Body Battery < 25, suggest a rest day." Rules can notify, edit
the local plan, and (with confirmation) write a workout to the watch.

**Why Garmin doesn't have it:** Garmin has Daily Suggested Workouts and adaptive
Garmin Coach plans, but they are closed and not user-programmable. There is no
local rule engine that can act across health, calendar, and watch uploads.

**Build:** M/L · **Depends on:** readiness, plan engine, workout writer.  
**Success:** rules created and executed; adherence improves without unsafe
automation. Keep every write confirmation-gated.

### D5. Multi-source health timeline

**What:** Import Health Connect, Apple Health exports, BLE heart-rate straps,
smart scales, blood-pressure monitors, and CGMs; merge them with Garmin data
using source badges and conflict rules. Support non-Garmin watches through the
same adapters.

**Why Garmin doesn't have it:** Garmin Connect is centered on Garmin devices and
a limited partner list. It does not present a neutral, local, multi-vendor
timeline.

**Build:** L · **Depends on:** import adapters and dedupe.  
**Success:** metrics available per source; conflicts explained, not silently
averaged.

### D6. N-of-1 experiments

**What:** Define an intervention (caffeine cutoff, 30 min zone 2, sleep window,
strength frequency), track adherence automatically, compare outcomes with a
washout period, and show a simple effect estimate with caveats. Export the
protocol and results.

**Why Garmin doesn't have it:** Garmin shows trends and correlations; it does
not provide an experiment framework with adherence and pre-registered outcomes.

**Build:** M · **Depends on:** notes/tags, trends, data quality.  
**Success:** completed experiments and retained users; no causal overclaiming.

### D7. Clinician Brief

**What:** A local PDF/JSON brief with date-ranged trends, data completeness,
symptoms/medications/context annotations, flagged anomalies, and raw-data
provenance. Explicitly non-diagnostic.

**Why Garmin doesn't have it:** Garmin has reports and health snapshots, but not
a multi-source, provenance-rich, locally generated brief designed for a
clinician conversation.

**Build:** M · **Depends on:** annotations, reports, D2.  
**Success:** exports used in appointments; clear "not a diagnosis" framing.

### D8. Offline Route Studio

**What:** Build a route on an offline OSM basemap, import/export GPX, inspect
elevation/surface, send a FIT course to the watch, and navigate from the phone
with live map/HR. No account.

**Why Garmin doesn't have it:** Garmin has courses and Garmin Trails, but they
are account/cloud-first and the best map features are Connect+ or Outdoor Maps+.
A fully local route studio is a meaningful alternative.

**Build:** L · **Depends on:** FIT course encoder, map data; overlaps the P1
courses item.  
**Success:** routes created and sent without a network account.

### D9. Local API and MCP bridge

**What:** A localhost, token-scoped, read-only API plus an MCP server so other
local agents/apps (Home Assistant, scripts, desktop tools) can query health
data with the same typed scopes and audit log as the coach. No cloud.

**Why Garmin doesn't have it:** Garmin's Health API is enterprise-oriented and
cloud-based; consumers have no local, permissioned data API.

**Build:** M · **Depends on:** existing `HealthDataSource`/tool scope.  
**Success:** integrations built without exporting raw data; every query logged.

### D10. Private challenges (research bet)

**What:** Challenge friends on aggregate goals (steps, active minutes, ascent)
using signed summaries or zero-knowledge proofs, without uploading raw health
data. Local leaderboard, explicit sharing.

**Why Garmin doesn't have it:** Garmin challenges require its cloud and social
graph. A privacy-preserving alternative is a genuine differentiator, but it is
cryptographically and socially complex.

**Build:** L/research · **Depends on:** identity and crypto design.  
**Success:** participants and zero raw-data exposure. Treat as an experiment,
not a v1 commitment.

### D11. Live Watch Mirror

**What:** A real-time phone dashboard over BLE: HR, pace, cadence, battery, and
workout status; start/pause/lap; broadcast HR into the PacePro preview run. No
cloud.

**Why Garmin doesn't have it:** Garmin Connect+ Live Activity covers compatible
indoor workouts and is service-backed. A local mirror for outdoor runs and
PacePro is both parity and differentiation.

**Build:** M · **Depends on:** live GFDI/BLE channels.  
**Success:** live sessions with low latency; HR tile no longer empty in the
preview run.

### D12. Watch-agnostic bridge

**What:** Treat Garmin as one source among several. Ingest standard formats and
Health Connect, then expose one normalized timeline and one coach.

**Why Garmin doesn't have it:** Garmin Connect only works with Garmin devices.
An open companion that outlives a watch upgrade is a strong retention story.

**Build:** L · overlaps D5.  
**Success:** non-Garmin users can use the same dashboard and coach.

## 8. Recommended sequence

### Wave 1 — trust and ownership (next)

- Historical wellness requests + data coverage view.
- Background/periodic sync + connection state + watch battery/storage.
- Activity export (FIT/GPX/TCX/CSV) + encrypted backup.
- Profile/units/goals.
- Onboarding/pairing.
- Data quality/repair baseline.
- Workout targets and watch calendar scheduling.

**Outcome:** a user can install, pair, sync reliably, see complete data, export
it, and trust it.

### Wave 2 — daily parity

- Transparent readiness/training status.
- Adaptive plan engine + daily suggested workouts + calendar.
- Strength/yoga/mobility workouts.
- Courses/GPX + map preview + watch course upload.
- Activity notes/RPE/photos/weather; PRs and gear.
- Weight/hydration/nutrition/women's health manual logs.
- Live HR + PacePro HR.
- Health snapshot/report + Health Connect export.

**Outcome:** the app is a credible daily companion for a Garmin owner, not just
a data viewer.

### Wave 3 — differentiation

- Portable Health Vault (D1).
- Provenance/Data Quality Center (D2).
- Local AI + privacy ledger (D3).
- Automation rules (D4).
- Multi-source timeline (D5).
- N-of-1 experiments (D6).
- Clinician Brief (D7).
- Local API/MCP (D9).
- Live Watch Mirror (D11).
- Private challenges (D10, research).

**Outcome:** features Garmin Connect cannot easily copy without abandoning its
cloud/subscription model.

## 9. Success metrics

Track these before and after each wave:

- **Data completeness:** percentage of days with steps, sleep, HRV, and
  resting HR; percentage of activities with GPS, laps, and dynamics.
- **Sync reliability:** first-attempt success rate, median sync time, retries,
  and unexplained gaps.
- **Portability:** export success, restore success, and time to restore.
- **Trust:** percentage of displayed metrics with provenance; duplicate and
  clock-skew counts.
- **Coach quality:** answers with evidence, tool-error rate, on-device share,
  and user-rated usefulness.
- **Privacy:** cloud calls per week, data categories sent, and zero unapproved
  egress.
- **Product:** weekly active users, plans created, workouts sent, reports
  exported, and retention after a watch upgrade.

## 10. Documentation drift and known quality items

These are not feature gaps, but they affect the credibility of the status
above:

- Compose UI tests, Room migration tests, and an instrumented end-to-end
  backup/restore test are still missing.
- Cleartext HTTP is enabled app-wide with a warning; a per-endpoint policy is
  still a hardening item.
- Backups are unencrypted and contain the full database.
- The app's `PacePro` upload is verified by the GFDI sequence, not by reading
  the file back from the watch.
- Sleep/HRV depend on watch file exposure; the FR255 used during development
  did not always expose a SLEEP_DATA file during daytime syncs.

## 11. Sources

Garmin:

- [Garmin Connect+ premium features](https://www.garmin.com/en-US/p/1565777/)
- [Garmin Connect+ launch press release, 27 March 2025](https://www.garmin.com/en-US/newsroom/press-release/wearables-health/elevate-your-health-and-fitness-goals-with-garmin-connect/)
- [Garmin Connect+ performance dashboard, 14 May 2026](https://www.garmin.com/en-US/blog/fitness/what-is-the-garmin-connect-performance-dashboard/)
- [Nutrition tracking in Garmin Connect, 5 January 2026](https://www.garmin.com/en-US/newsroom/press-release/sports-fitness/stay-on-top-of-nutrition-goals-in-garmin-connect/)
- [How to use Garmin Connect, 21 May 2026](https://www.garmin.com/en-US/blog/fitness/unlocking-the-potential-of-garmin-connect/)
- [Garmin Connect on Google Play](https://play.google.com/store/apps/details?id=com.garmin.android.apps.connectmobile)
- [Garmin Connect on the App Store](https://apps.apple.com/us/app/garmin-connect/id583446403)

OpenGarmin:

- `README.md` — shipped feature summary.
- `BACKLOG.md` — epics, status, open questions.
- `docs/architecture-notes.md` — data model, ingestion, storage, gaps.
- `docs/PROTOCOL.md` and `docs/HANDOFF.md` — BLE/GFDI and FIT findings.
- `app/src/main/java/dev/opengarmin/app/` — UI, sync, data, coach, and settings
  implementation used for the "today" column.
