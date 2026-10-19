# Privacy and safety

Pulse is local-first. Health data from the watch is stored in the app's Room
database on the phone; there is no Garmin account, backend, telemetry, or
analytics.

## What leaves the device

Only when the coach is used:

- The chat message you type.
- The system prompt and recent chat history.
- The results of the tools the model calls. This is compact JSON: wellness
  aggregates, workout summaries, running dynamics, and—only when the user asks
  about a route and the model requests it—up to 60 sampled GPS points. It never
  sends the full raw record stream. For models without tool calling, a compact
  14-day snapshot is sent instead.
- The configured model name and parameters.

These are sent to the **OpenAI-compatible endpoint you configure**. Nothing is
sent to OpenGarmin/Pulse servers, and no data is sent anywhere if no provider is
configured.

A local model (Ollama, LM Studio, vLLM) keeps the data on your device or LAN.
The provider screen recommends this and warns when plain HTTP is used.

## Keys

- Encrypted at rest with Android Keystore AES-GCM.
- Stored as ciphertext plus a masked hint; the plaintext is never returned to
  the UI after saving.
- Read only while building an HTTP request.
- Never included in tool arguments/results, model context, chat history, logs,
  or crash reports.
- Deleting the key removes the ciphertext. Removing the provider removes its
  configuration and key.

Build-time environment keys are embedded in `BuildConfig` and can be extracted
from the APK; use them only for managed/local deployments.

## Logs

The app's debug log is protocol-oriented (GFDI/GDI frames, sync state). It does
not log HTTP request/response bodies, `Authorization` headers, provider keys,
or chat content. The provider layer does not install a body-logging interceptor.
Tool execution logs only names/timing at most.

The device log screen is read-only and shows the same redacted protocol log.

The sync progress notification is a low-importance, private-visibility
notification. It shows the current activity index/file size and the last
imported activity's date, sport, and distance; its content is hidden on the lock
screen unless the user changes the system notification privacy setting.

## Backups

- **Settings → Data & backup → Export backup** creates a ZIP containing the full
  local Room database and the coach conversation history. It is written only to
  the location you choose in the system file picker; the app does not upload it.
- The ZIP is **not encrypted**. It contains all health data, FIT-derived
  records, and chat text, so treat it like the app's own data and delete old
  copies securely. Provider configuration and API keys are not included.
- **Import backup** validates the archive before replacing anything and keeps a
  temporary safety copy of the current database until the new one opens
  successfully. On success, the current database and (when present in the ZIP)
  the conversations are replaced.
- A raw SQLite database backup is also accepted. Importing one replaces the
  database but leaves the current conversations untouched.

## Activity exports

- Activity detail → **Export** writes one activity as FIT, GPX, TCX, or CSV using
  the system document picker. Nothing is uploaded or converted by a service.
- FIT copies the original decoded watch file when it is still on the device.
  GPX, TCX, and CSV are generated locally from the Room records and laps; missing
  values are omitted rather than estimated.
- Exported files leave the app's private storage. They are not encrypted and may
  contain precise GPS coordinates, heart rate, and other health data. Store or
  delete them accordingly; clearing app data does not remove them.

## Data deletion

- **New conversation** leaves previous conversations in History if they have
  messages; use the History list to delete them individually with confirmation.
- **Remove provider configuration** removes the endpoint, models, and stored
  key.
- Clearing app data or uninstalling removes the Room database, FIT files,
  conversations, and settings. There is no cloud copy.
- Exported backup files are outside the app's storage; delete them separately.

## Medical safety

The coach is informational and is explicitly told not to diagnose, prescribe,
or give medical advice. For concerning patterns it suggests consulting a
qualified healthcare professional. The UI shows a persistent
"Informational only · not medical advice" line.

Sleep, HRV, and floors are not available on every watch/sync. The app imports
sleep and HRV when the watch exposes the relevant SLEEP_DATA/biometric files,
and shows an explicit empty state otherwise. Floors are not ingested. None of
these metrics are estimated or fabricated.
