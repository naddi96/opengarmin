# Background and periodic sync

OpenGarmin can sync a bonded Garmin watch while the app is closed. The feature
uses AndroidX **WorkManager** and the same `SyncCoordinator`, Room import path,
and single-transfer-slot rules as the foreground sync.

## Settings

Settings → **Background sync**:

| Setting | Default | Notes |
|---|---|---|
| Periodic sync | **Off** | The user opts in. Manual sync and sync-on-launch still run while off. |
| Interval | **60 minutes** | 15 min, 30 min, 1 h, 3 h or 6 h. WorkManager's minimum periodic interval is 15 minutes. |
| Only while charging | **Off** | Battery-not-low is always required; charging is an optional stricter constraint. |

The exact run time is controlled by Android/Doze and is deliberately not shown
as a timestamp. The UI only shows the requested interval and the last attempt,
last success, duration, trigger, and result.

## Triggers

Sync still starts:

- on app launch/resume when the last success is more than five minutes old;
- from **Sync now**;
- when the bonded watch reconnects while the app is visible;
- from the periodic WorkManager worker;
- from **Run background sync now**, a one-time worker using the same path
  (battery-not-low applies; charging-only is ignored so the user can test).

When the activity stops, it disconnects its BLE client and releases the lease so
the worker can own the watch. A visible app holds the lease while connected,
which prevents the worker from opening a second GATT connection.

## Single-flight

`SyncGate` is a process-wide, owner-based lease:

- foreground/manual sync acquires `ui`;
- the periodic/one-time worker acquires `background`;
- acquiring is re-entrant for the same owner;
- a different owner cannot release or steal the lease.

If the worker sees the UI lease, it returns `Result.success()` for that period
without touching the watch (the next interval tries again); if a manual sync
sees the background lease, it leaves the worker alone and does not start a
competing transfer. The watch's single GDI/transfer slot is never shared by two
flows.

## Retry and failure handling

The unique periodic request uses exponential backoff with a 10-minute initial
delay and the battery/charging constraints above.

| Class | Examples | Result |
|---|---|---|
| **Transient** | connect timeout, transfer timeout, `NO_AVAILABLE_RESOURCES`, watch disconnected, import failure | `Result.retry()` with WorkManager backoff; a dismissible notification says it will retry. |
| **Busy** | Garmin Connect holds the GDI session | `Result.retry()`; the notification explains the contended session. |
| **Held lease** | A visible app owns the BLE connection | Skip this period with `Result.success()`; the next interval tries again. |
| **Permanent** | Bluetooth permission denied, no bonded Garmin watch, unsupported device | Recorded in Settings/Today with an action to pair or grant permission; the next periodic interval still runs after the user fixes it. |

The worker bounds each stage so a missing watch does not hold a wake lock for the
full WorkManager window:

- 45 s to connect over GATT;
- 45 s for the watch to open its GDI session;
- 6 min for the actual transfer.

## Persisted history

`PulseSettings` persists:

- periodic sync enabled, interval, charging-only;
- last attempt time, last success time, duration, trigger, result kind, and
  user-safe error text.

The Settings card shows these values. It never shows a synthetic next-run time
or fake progress. Background work also updates the visible activity through a
SharedPreferences listener, so the status card refreshes when a worker finishes.

## Notifications

The worker runs as a `connectedDevice` foreground worker while it transfers.
The ongoing notification shows connection, activity progress, and completion or
failure. A separate dismissible notification remains after success or a retryable
failure. Permanent setup failures are kept in Settings/Today instead of posting
the same notification on every interval.

The channel is low-importance, silent, and private-visibility; notification
content is hidden on the lock screen unless the user changes the system setting.

## Privacy and logs

Background sync is local-only: it uses BLE to the bonded watch and never sends
health data to a server. The worker discards protocol logs, so FIT fields and
health payloads do not appear in logcat. Only high-level lifecycle lines such as
`BG sync started`/`finished` are emitted under the `OpenGarminBg` tag.

## Android lifecycle notes

- Doze and App Standby can delay a periodic run. The constraints and WorkManager
  decide when it is allowed; the app does not force wakeups.
- WorkManager persists the request across process death and reboot.
- Force-stopping the app from Android Settings suspends its jobs until the app is
  opened again. This is an Android rule, not an app failure.
- A watch that is out of range fails the connect stage and is retried with
  backoff.
- Only one bonded watch is supported today. Pairing multiple watches remains a
  later item (G4/P2).

## Testing

- `SyncPolicyTest` covers interval clamping, labels, failure classification,
  retry policy, and duration/trigger formatting.
- `SyncGateTest` covers single-flight, re-entrancy, wrong-owner release, and
  reuse.
- Device check: enable periodic sync, choose 15 minutes, tap **Run background
  sync now**, background the app, and verify the notification, persisted
  duration/trigger, and that no second transfer starts during a manual sync.
- Inspect the scheduled job with:

```bash
adb shell dumpsys jobscheduler | grep -A8 'dev.opengarmin.app/androidx.work'
```

- Inspect WorkManager state with (debug builds):

```bash
adb shell run-as dev.opengarmin.app ls -l no_backup/androidx.work.workdb*
```

See [`testing.md`](testing.md) for the current device matrix and manual results.
