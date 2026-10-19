# Activity export

Open an activity and use the **Export** card in activity detail to save one
activity locally. The system document picker chooses the destination; the app
never uploads the file or asks a service to convert it.

| Format | What it contains | Notes |
|---|---|---|
| **FIT** | The original decoded FIT file from the watch or a FIT import | Export is disabled when that file is no longer on the device. The app does not generate a substitute FIT. |
| **GPX** | Track points with valid coordinates, elevation, time, and heart rate | Disabled when the activity has no valid latitude/longitude pairs. |
| **TCX** | Activity and lap timing, distance, calories, average/maximum heart rate, cadence, altitude, and available track points | When the watch stored no laps, one synthetic activity lap is written. Missing samples are omitted, not zero-filled. |
| **CSV** | One row per record, with lap context, plus a lap row when an activity has no records | UTF-8, comma-separated, ISO-8601 UTC timestamps. See the columns below. |

Filenames use the activity start time, sport, distance, and the correct
extension, for example:

```
opengarmin-2026-10-18-0730-running-5.02km.gpx
```

## CSV columns

The header order is stable. All timestamps are ISO-8601 UTC (`…Z`) and all
distances/elevations are metres. Empty fields mean the watch did not record the
value; they are never zero-filled.

| Column | Unit / meaning |
|---|---|
| `row_type` | `record` or `lap` |
| `activity_id` | Local Room id |
| `activity_start_utc` | Activity start time |
| `activity_sport` | Sport label |
| `activity_sub_sport` | Raw FIT sub-sport number |
| `lap_index` | 1-based lap number |
| `lap_start_utc` | Lap start time |
| `lap_duration_s` | Lap timer time, seconds |
| `lap_distance_m` | Lap distance, metres |
| `record_index` | 0-based record order within the activity |
| `timestamp_utc` | Record sample time |
| `elapsed_s` | Seconds since activity start |
| `distance_m` | Cumulative record distance, metres |
| `heart_rate_bpm` | Heart rate, beats per minute |
| `speed_mps` | Speed, metres per second |
| `altitude_m` | Altitude, metres |
| `cadence_rpm` | Cadence, steps or revolutions per minute |
| `latitude_deg` | WGS-84 latitude |
| `longitude_deg` | WGS-84 longitude |
| `body_battery` | Garmin Body Battery level, when recorded |

## Data handling

- Export runs on the device and writes only to the URI returned by the system
  picker.
- FIT export copies the stored original file. GPX, TCX, and CSV are generated
  from the local Room records and laps.
- Exported files are outside the app's storage, so deleting app data does not
  delete them. Delete old exports separately.
- See [`privacy.md`](privacy.md) for the full data-handling policy.

## Tests

`ActivityExportTest` covers XML escaping, invalid and missing GPS positions,
no-GPS GPX errors, missing laps, empty records, TCX/CSV field mapping, filename
generation, CSV quoting, and missing/invalid FIT files.
