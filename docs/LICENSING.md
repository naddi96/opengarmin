# Licensing & provenance

## This project

Licensed under **AGPL-3.0** (see [`../LICENSE`](../LICENSE)). This keeps us
compatible with the ecosystem we interoperate with and with any future code we
choose to port.

## Where the protocol knowledge comes from

We reverse-engineered the Garmin watch protocol from the official
`com.garmin.android.apps.connectmobile` APK (included locally, not distributed) and
cross-checked it against community sources:

| Source | License | Used for |
|---|---|---|
| Garmin Connect APK (this repo, local only) | proprietary | UUIDs, class map, config |
| Gadgetbridge documentation | AGPL-3.0 | protocol description, service ids |
| `wh1le/garmin-bridge` | AGPL-3.0 | reference framing/CRC/COBS |

**Protocol facts** (UUIDs, message layouts, checksums, service ids) are not
copyrightable; re-implementing them for interoperability is standard practice.
What *is* copyrightable is code expression.

## Implementation status

The `protocol/` module is an **independent re-implementation** in Kotlin from the
documented protocol facts. It does not copy source from Gadgetbridge or
garmin-bridge. Where an algorithm is a well-known standard (e.g. CRC-16 with the
Dallas/Maxim nibble table, COBS), we implement the standard directly.

If you later port substantial code from an AGPL project (e.g. the FIT parser, or the
MLR state machine), that is fine under AGPL — just attribute the source in the file
header and keep this project AGPL. If you ever want a permissive license, port from
permissive sources (e.g. the Garmin FIT SDK, MIT) and keep AGPL-derived code out.

## Trademarks

"Garmin", "Connect IQ" and device names are trademarks of Garmin Ltd. This project
is not affiliated with or endorsed by Garmin. Reverse engineering for
interoperability is permitted in many jurisdictions; you are responsible for
compliance where you live.
