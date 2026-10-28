# Garmin Connect cloud import

The Forerunner 245 exposes a **pending-upload queue**, not a filesystem. A run
that Garmin Connect already archived is no longer offered by `DIRECTORY_FILTER`
(5007) and the legacy sync cannot download it. Settings → **Garmin Connect**
is the cloud fallback: it signs in to the same unofficial mobile API used by
`python-garminconnect`, lists recent activities, downloads the original FIT,
and feeds it to the normal local importer.

## Using it

1. Open **Settings → Garmin Connect**.
2. Enter the Garmin Connect email and password, then **Sign in**.
3. If the account uses MFA, enter the emailed/SMS code in the field that
   appears.
4. Tap **Load recent activities**, then **Import** next to the missing run.
   Alternatively, tap **Import all activities** to page through the whole Garmin
   history (newest first), skipping activities already in Pulse. Bulk import can
   be stopped; it stops cleanly after the current file.

Imported files go through `ActivityImporter.importFitBytes`, so they get the
same parsing, duplicate detection, local storage, sync log entry, and automatic
activity analysis as a watch download.

## Authentication flow

The client mirrors Garmin's official mobile apps:

1. `GET https://sso.garmin.com/mobile/sso/en_US/sign-in` with
   `clientId=GCM_IOS_DARK` (loads the SSO cookies).
2. `POST https://sso.garmin.com/mobile/api/login` with the email/password.
3. If `MFA_REQUIRED`, `POST /mobile/api/mfa/verifyCode`.
4. Exchange the service ticket at
   `https://diauth.garmin.com/di-oauth2-service/oauth/token` for DI OAuth2
   access/refresh tokens.
5. API calls go to `https://connectapi.garmin.com` with a Bearer token;
   expired access tokens refresh automatically.

Endpoints used:

| Purpose | Endpoint |
| --- | --- |
| Activity count | `GET /activitylist-service/activities/count` |
| Activity list | `GET /activitylist-service/activities/search/activities?start=0&limit=50` |
| Original FIT | `GET /download-service/files/activity/{activityId}` |

Bulk import requests 50 activities per page, downloads originals one at a
time, and uses the local Garmin activity id as the activity UID so a re-run
skips files already imported. The activity count endpoint supplies the progress
total and upper bound; an empty page also stops the loop.

The original download is a ZIP; the client extracts the `.fit` entry. A bare
FIT response is also accepted. Downloads are serialised and spaced by a random
1.5–3.5 s interval to avoid a fixed, bot-like cadence and stay under Garmin's
undocumented rate limit.

## What is stored

- Email: plain text in `pulse_garmin` SharedPreferences, to prefill the field.
- Password: **not stored**. It is used once for the SSO exchange.
- Access/refresh tokens: encrypted with an Android Keystore AES-GCM key
  (`pulse_garmin_token_key`) and stored in the same preferences file.

**Sign out** deletes the stored tokens. Garmin rate-limits repeated sign-ins;
on HTTP 429 the client surfaces the `Retry-After` window and should be left
alone until it expires.

## Troubleshooting

| Symptom | Meaning |
| --- | --- |
| `Garmin rejected the email or password` | Wrong credentials, or the account needs a Garmin Connect web sign-in first. |
| `Garmin blocked this sign-in request (HTTP 403)` | Cloudflare/WAF rejected the client. Wait and retry; repeated attempts can extend the block. |
| `Garmin is rate-limiting requests` | Stop retrying. Wait for the reported window (often minutes, sometimes hours). |
| `The Garmin session expired. Sign in again.` | Refresh token was revoked or rejected; sign in again. |
| `The downloaded Garmin archive contains no FIT activity` | Garmin returned a non-original file. Use Garmin Connect → Export original and **Import FIT file** instead. |

The client is independent of the BLE sync path and does not change watch
state. If a sync is running, the import is refused until it finishes.

## Key files

- `app/.../garmin/GarminConnectClient.kt` — SSO, token refresh, activity list,
  ZIP/FIT download.
- `app/.../garmin/GarminCredentialStore.kt` — Keystore-backed token storage.
- `app/.../ui/screens/GarminConnectCard.kt` — Settings UI.
- `app/.../sync/ActivityImporter.kt` — `importFitBytes` shared with the
  document picker.
