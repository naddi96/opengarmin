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

## Data deletion

- **New conversation** clears the current local chat.
- **Remove provider configuration** removes the endpoint, models, and stored
  key.
- Clearing app data or uninstalling removes the Room database, FIT files, chat,
  and settings. There is no cloud copy.

## Medical safety

The coach is informational and is explicitly told not to diagnose, prescribe,
or give medical advice. For concerning patterns it suggests consulting a
qualified healthcare professional. The UI shows a persistent
"Informational only · not medical advice" line.

Sleep, HRV, and floors are currently not ingested. The UI and tools show them as
unavailable; they are never estimated or fabricated.
