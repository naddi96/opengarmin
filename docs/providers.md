# Configuring an LLM provider

Pulse supports any endpoint that implements the OpenAI
`/v1/chat/completions` API, including streaming and tool calling.

Open **Coach → Manage** (or **Settings → AI provider → Manage provider**) to
configure it.

## Presets

| Preset | Base URL | Suggested models |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini`, `gpt-4.1-mini` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat`, `deepseek-reasoner` |
| OpenRouter | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini`, `anthropic/claude-3.5-sonnet`, `meta-llama/llama-3.1-70b-instruct` |
| Ollama | `http://localhost:11434/v1` | `llama3.1`, `qwen2.5`, `mistral` |
| LM Studio | `http://localhost:1234/v1` | `local-model` |
| vLLM | `http://localhost:8000/v1` | the served model name |
| Custom | anything | anything |

Preset model names are **suggestions**, not a claim that the endpoint serves
them. Add/edit/delete model entries and mark one as the default. Each model can
have optional `temperature` (0–2) and `max_tokens` (1–8192). Blank means the
provider default.

## Base URL rules

- Put the API root in **Base URL**, including `/v1` when the server uses it.
  The app appends `/chat/completions` and `/models`.
- OpenAI-compatible servers that use a different prefix can still be configured
  with **Custom**.
- For a phone talking to a model on a computer:
  - Use the computer's LAN address, e.g. `http://192.168.1.20:11434/v1`.
  - Android emulators use `http://10.0.2.2:11434/v1`.
  - `adb reverse tcp:11434 tcp:11434` also works, then use
    `http://127.0.0.1:11434/v1`.
- HTTPS is recommended. The app currently permits plain HTTP because local
  model servers commonly use it; the provider screen shows an unencrypted-
  traffic warning. Do not send health data over an untrusted network.

## API keys

- The key is encrypted at rest with an Android Keystore AES-GCM key. The
  database/preferences contain only ciphertext plus a masked hint.
- After saving, the UI only shows a mask such as `••••abcd`. It is never
  returned to the screen in plaintext.
- The key is read only inside the HTTP request path. It is not put in tool
  arguments, model context, chat history, logs, or crash messages.
- "Delete stored key" removes the ciphertext; the Keystore alias remains but no
  usable ciphertext is stored.
- A provider with no key is allowed for local endpoints. If the endpoint
  rejects anonymous requests, test connection reports an auth error.

## Deployment / environment configuration

For deployed or reproducible builds, provide these environment variables when
running Gradle:

```bash
PULSE_OPENAI_BASE_URL=https://api.example.com/v1 \
PULSE_OPENAI_API_KEY=sk-... \
PULSE_OPENAI_MODEL=gpt-4o-mini \
./gradlew :app:assembleDebug
```

`OPENAI_BASE_URL`, `OPENAI_API_KEY`, and `OPENAI_MODEL` are accepted as
fallbacks. The values are generated into `BuildConfig` at build time.

- Precedence: **saved user configuration > BuildConfig**.
- BuildConfig keys are embedded in the APK and can be extracted. Use this for
  managed/local deployments only; do not ship a shared production key this way.
- The settings screen marks a BuildConfig-backed provider so it is clear that
  saving overrides the deployment value.

## Test connection

"Test connection" calls `GET {base}/models`. If the server does not implement
that endpoint, it falls back to a minimal completion with a hard token cap.

Success reports the endpoint/model count. Failures are categorised as auth,
not found, rate limit, server, network, timeout, or malformed. The request has
bounded timeouts (10–15 s in the settings action) and can be retried.

## Chat transport

- `POST {base}/chat/completions` with `stream=true`.
- SSE `data:` events, arbitrary chunk boundaries, `[DONE]`, content deltas, and
  streamed tool-call fragments are supported.
- Cancel stops the read and closes the connection; partial text is kept and
  marked cancelled.
- Timeouts: 15 s connect, 60 s read, 90 s call (configurable internally).
- Retries: up to 2 retries with backoff for connection failures and 5xx
  responses **before the first token**. There is no retry after a partial
  stream, and no blind retry of 4xx responses.
- Models that reject the `tools` field cause one automatic retry without tools
  using a compact, locally computed data snapshot.

## Troubleshooting

| Symptom | Check |
|---|---|
| "Could not reach the provider" | base URL, phone/computer network, firewall, `adb reverse` |
| "endpoint or model was not found" | `/v1` suffix, exact model ID |
| "provider rejected the API key" | key, provider account, model access |
| "plain HTTP" warning | expected for local HTTP; prefer HTTPS or a trusted LAN |
| Empty/garbled answers | model may not support streaming; try another model or endpoint |
| Tool calls never happen | model/endpoint may not support tools; the app falls back to a snapshot |
