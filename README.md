# OpenGarmin — offline, self-hosted companion for Garmin watches

A clean-room, local-first replacement for the Garmin Connect mobile app. It talks
directly to your watch over Bluetooth LE, pulls your data, and stores it **only on
your device**. No Garmin account, no cloud, no telemetry.

This repository started as a reverse-engineering study of the official
`com.garmin.android.apps.connectmobile` APK (v5.19.1). The recovered protocol is
documented in [`docs/PROTOCOL.md`](docs/PROTOCOL.md) and re-implemented from
scratch in the `protocol/` module.

> **Status:** early. The wire protocol core (framing, CRC, COBS, Multi-Link
> transport, handshake) is implemented and unit-tested. BLE transport, FIT
> ingestion and the dashboard UI are in progress. See [`docs/PLAN.md`](docs/PLAN.md).

## Why

The official app is a thin client for Garmin's servers; it does not keep your data
locally. This project reverses that: the watch is the source of truth, your phone is
the archive.

## Target devices

Developed against the **Garmin Forerunner** family (245 / 255 / 955 / 965), which use
the **GFDI v2 / Multi-Link** BLE protocol. The same stack is used by Fenix 5+/6/7,
Venu/Venu 2/3, Instinct 2 and others, with small differences.

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
| `app` | Android app | BLE I/O, sync engine, FIT ingestion, Room database, Compose UI. |

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
