# OpenGarmin — offline, self-hosted companion for Garmin watches

A clean-room, local-first replacement for the Garmin Connect mobile app. It talks
directly to your watch over Bluetooth LE, pulls your data, and stores it **only on
your device**. No Garmin account, no cloud, no telemetry.

This repository started as a reverse-engineering study of the official
`com.garmin.android.apps.connectmobile` APK (v5.19.1). The recovered protocol is
documented in [`docs/PROTOCOL.md`](docs/PROTOCOL.md) and re-implemented from
scratch in the `protocol/` module.

> **Status:** working local pipeline. BLE/GFDI sync, FIT + wellness ingestion,
> the five-tab Pulse UI (Today, Health, Training, Activities, Coach), and an
> OpenAI-compatible AI coach are implemented. All health data comes from the
> watch's own FIT files; no Garmin Connect account or REST API is used. See [`BACKLOG.md`](BACKLOG.md) for the remaining
> data-coverage items (sleep/HRV/history) and [`docs/HANDOFF.md`](docs/HANDOFF.md)
> for the protocol notes.

## Why

The official app is a thin client for Garmin's servers; it does not keep your data
locally. This project reverses that: the watch is the source of truth, your phone is
the archive.

## Target devices

Developed against the **Garmin Forerunner** family (245 / 255 / 955 / 965), which use
the **GFDI v2 / Multi-Link** BLE protocol. The same stack is used by Fenix 5+/6/7,
Venu/Venu 2/3, Instinct 2 and others, with small differences.

## Features

- **Today:** steps ring and goal, resting/current heart rate with an intraday
  chart, active time, calories, and explicit empty states for not-yet-ingested
  sleep/HRV.
- **Health:** real Body Battery (stress-topic field 3), stress distribution,
  SpO₂, respiration, resting HR, movement, weekly active minutes, and sleep/HRV
  cards that populate when the watch exposes its SLEEP_DATA/HRV files.
- **Training:** recovery time, VO₂ max and six-week trend, 7-day acute load and
  load focus, lactate threshold, performance condition, and the latest training
  effect, all decoded from the watch's `activity_metrics`/`user_metrics` FIT
  messages.
- **Trends:** Day/Week/Month views over real wellness days, partial-history
  labels (never zero-filled), goal line, resting-HR trend. Reachable from the
  Health header.
- **Recovery:** Settings → **Import FIT file** imports a local FIT activity that the watch no longer exposes, without using Garmin Connect or any API.
- **Activities:** weekly summary, sport filters (Run, Ride, Cardio, Strength,
  Walk, and an Other bucket for every remaining sport), row-to-featured
  selection, real GPS/HR trace, heart-rate zone bar, full activity detail with
  map, HR/elevation charts, laps, and the FIT data explorer.
- **Coach:** OpenAI-compatible provider presets (OpenAI, DeepSeek, OpenRouter,
  Ollama, LM Studio, vLLM, Custom), encrypted API keys, model management,
  connection test, streaming chat with cancel, typed read-only health tools,
  and evidence chips generated from the tools actually called.
- **Local-first:** no account, backend, telemetry, or cloud copy.

## Documentation

- [`docs/architecture-notes.md`](docs/architecture-notes.md) — stack, data model,
  ingestion, and coverage gaps.
- [`docs/design/tokens.md`](docs/design/tokens.md) — design tokens; mockups are
  in [`docs/design/mockups/`](docs/design/mockups/).
- [`docs/providers.md`](docs/providers.md) — provider setup, presets, env config,
  keys, cleartext policy, streaming and troubleshooting.
- [`docs/tools.md`](docs/tools.md) — coach tools, limits, evidence, safety, and
  how to add a tool.
- [`docs/testing.md`](docs/testing.md) — test commands and manual matrix.
- [`docs/privacy.md`](docs/privacy.md) — what leaves the device and key/log
  handling.
- [`BACKLOG.md`](BACKLOG.md) — epics, stories, status, and open questions.

## Architecture

```
 ┌──────────┐   BLE (GFDI / Multi-Link)   ┌────────────────────────────┐
 │  Watch   │ ◀────────────────────────▶  │  Android app (this repo)   │
 └──────────┘                             │  ┌──────────────────────┐  │
                                          │  │ protocol/ (pure JVM) │  │
                                          │  │  COBS · CRC16 · ML   │  │
                                          │  └──────────────────────┘  │
                                          │  ┌──────────────────────┐  │
                                          │  │ FIT parser + Room DB │  │
                                          │  └──────────────────────┘  │
                                          │  ┌──────────────────────┐  │
                                          │  │ Compose dashboard UI │  │
                                          │  └──────────────────────┘  │
                                          └────────────────────────────┘
                                                    │ files only
                                                    ▼
                                            local storage / export
```

## Modules

| Module | Type | Purpose |
|---|---|---|
| `protocol` | Kotlin/JVM library | Wire protocol: GFDI framing, CRC-16, COBS, Multi-Link handles, handshake. No Android deps → fully unit-testable. |
| `app` | Android app | BLE I/O, sync engine, FIT ingestion, Room database, Compose UI, provider/client/tool/chat layer. |

## Build

Requires a JDK 17+ and the Android SDK for the `app` module. The `protocol`
module builds with plain Gradle:

```bash
# unit tests for the protocol core
./gradlew :protocol:test

# build the Android app (needs ANDROID_HOME / local.properties)
./gradlew :app:assembleDebug
```

`tools/` contains helper scripts used during the original investigation
(APK extraction, smali decompilation, user-space toolchain install). They are not
required to build the app.

## Licensing

The wire-protocol *facts* here were recovered for interoperability. Several existing
projects (Gadgetbridge, garmin-bridge) are **AGPL-3.0**; this repository re-implements
the protocol independently and is licensed as **AGPL-3.0** to remain compatible with
that ecosystem. See [`docs/LICENSING.md`](docs/LICENSING.md).
