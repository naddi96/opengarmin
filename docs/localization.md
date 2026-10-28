# Localization

The app ships with English and Italian UI. English is the source language and
is also the fallback for any string that has not been translated yet.

## How it works

- `i18n/AppLanguage.kt` defines the available languages. `SYSTEM` follows the
  Android locale when there is a catalog for it, otherwise English.
- `i18n/AppStrings.kt` holds the catalogs. Keys are the exact English source
  strings, values are translations. Missing keys render the English key.
- `i18n/LocalizedText.kt` provides the Compose `Text` used by the UI. Static
  copy is translated at draw time, so screens keep their readable English
  source text.
- `i18n/Localization.kt` exposes `tr()` for dynamic copy and applies the process
  locale so dates, month names and numbers follow the selected language.
- The selected language is stored in `PulseSettings` (`app_language`) and can be
  changed under **Settings → Language**. Switching is immediate; no restart is
  needed.

Dynamic copy uses Formatter placeholders:

```kotlin
Text(tr("Downloading activity %s of %s", current, total))
```

Translations may reorder arguments with positional placeholders:

```kotlin
"Scaricamento attività %2$s di %1$s" // %2$s is read first in Italian
```

User and model content (chat messages, activity names, provider model names) is
rendered with `translate = false` so a user message that happens to say
"Settings" is never translated.

Notifications read the language from SharedPreferences through
`Context.localized(...)` because they are built outside Compose.

## Add a language

1. Add an entry to `AppLanguage` with a BCP-47 tag and native name.
2. Add a `Map<String, String>` catalog to `AppStrings` and register it in
   `catalogs`. Start from the existing Italian catalog; untranslated keys fall
   back to English automatically.
3. Add the language to `AppLanguage.entries` in the Settings picker
   automatically; `nativeName` is shown there.
4. If the language needs plural rules or a different date locale, extend
   `AppStrings.localeFor` and `applyAppLocale`.
5. Run `./gradlew :app:testDebugUnitTest --tests 'dev.opengarmin.app.i18n.*'`.

## Adding a string

Add the English text to the UI first, then add an entry to each catalog. For
interpolated text, prefer a template plus `tr(...)` instead of building the
sentence with Kotlin string concatenation, so translators can move arguments.

## Tests

`AppStringsTest` covers English fallback, unknown-key fallback, Italian
translations, placeholders and language-tag parsing.
