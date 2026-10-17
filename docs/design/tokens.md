# Design tokens — Pulse mockups

Extracted from the four reference screens in [`mockups/`](mockups/):

- `Main.dc.html` — Today
- `Trends.dc.html` — Trends (Day / Week / Month)
- `Activities.dc.html` — Activities (filters, featured detail, zones)
- `Coach.dc.html` — AI Coach

The files are design-canvas references, not production code. Values below are
the source of truth for the Compose theme and base components. The numbers in
the mockups are placeholders; only the **visual tokens** are copied, not their
sample data. The attached zip does not include the referenced `support.js`, so
the `.dc.html` files will not render standalone; the markup and inline styles
were read directly.

## 1. Color palette

### Surfaces

| Token | Value | Usage |
|---|---|---|
| `color/bg` | `#0E1311` | App background, tab bar background |
| `color/surface` | `#161D1A` | Cards, tiles, inputs, list rows, assistant bubble |
| `color/surface-elevated` | `#1E2723` | Coach insight card, icon containers, route thumbnail, suggestion chips |
| `color/border` | `#2A3530` | Decorative 1 px card/row/input borders |
| `color/border-elevated` | `#2F3F38` | Insight card and suggestion-chip border |
| `color/border-strong` | `#6B7C73` | **Accessibility substitute** for control boundaries that need 3:1 (see §8) |
| `color/ring-track` | `#26322D` | Unfilled progress-ring track |
| `color/goal-line` | `#5E6F66` | Dashed goal line in bar charts |

### Text

| Token | Value | Usage |
|---|---|---|
| `color/text` | `#EAF1EC` | Primary text |
| `color/text-muted` | `#8FA096` | Secondary text, labels, axes, inactive tabs |
| `color/text-on-accent` | `#0E1311` | Text/icons on lime buttons and user bubbles |

### Accents and data colors

| Token | Value | Usage |
|---|---|---|
| `color/accent` (lime) | `#C7F26B` | Primary action, active tab/segment/chip, steps, selected bars, user bubble |
| `color/teal` | `#5ED6C1` | HRV, Zone 1, "data connected" dot, Ride |
| `color/coral` | `#FF7A66` | Heart rate, avg-HR values, Run, Zone 5 |
| `color/blue` | `#8FA8FF` | Sleep, Strength |
| `color/orange` | `#FFB45E` | Calories, Walk? (see note) |
| `color/yellow` | `#FFD166` | Zone 3 |
| `color/zone-4` | `#FF9F5E` | Zone 4 |
| `color/zone-5` | `#FF6B57` | Zone 5 |

Mockup-specific sport colors: Run `#FF7A66`, Ride `#5ED6C1`, Strength
`#8FA8FF`, Walk `#C7F26B`. Keep sport colors in a separate map; do not overload
semantic data colors.

### Translucency

| Token | Value | Usage |
|---|---|---|
| `color/accent-bar-muted` | `#C7F26B` at 38% (`rgba(199,242,107,0.38)`) | Unselected chart bars |
| `color/hr-area-fill` | `#FF7A66` at 14% (`rgba(255,122,102,0.14)`) | Area under HR lines |
| `color/scrim` | `#0E1311` at ~60% | Reserved for dialogs/sheets (not shown in mockups) |

## 2. Typography

**Display font:** Space Grotesk (500, 700). Used for all large numerals, screen
titles, activity names, and tile values. **Body font:** DM Sans (400, 500, 700).
Both are OFL-licensed Google Fonts. Bundle `.ttf` files under `res/font/`; do
not rely on downloadable fonts at runtime.

Fallback: platform sans (`sans-serif` / Compose `FontFamily.SansSerif`).

| Role | Size | Weight | Line height | Font | Example |
|---|---:|---:|---:|---|---|
| `type/display-xl` | 36 | 700 | 1.0 | Space Grotesk | Steps hero value |
| `type/display-lg` | 34 | 700 | 1.1 | Space Grotesk | "68 bpm now" |
| `type/display-md` | 30 | 700 | 1.1 | Space Grotesk | Trends steps big |
| `type/title-screen` | 28 | 700 | 1.1 | Space Grotesk | "Trends", "Good evening" |
| `type/title-lg` | 26 | 700 | 1.1 | Space Grotesk | Ring %, "Coach", HR big |
| `type/stat-lg` | 24 | 700 | 1.1 | Space Grotesk | Today tiles |
| `type/stat-md` | 22 | 700 | 1.1 | Space Grotesk | Weekly workout summary |
| `type/title-md` | 20 | 700 | 1.2 | Space Grotesk | Featured activity name |
| `type/stat-sm` | 18 | 700 | 1.2 | Space Grotesk | Trends summary tiles |
| `type/stat-xs` | 16 | 700 | 1.2 | Space Grotesk | Activity-row avg HR |
| `type/stat-2xs` | 15 | 700 | 1.2 | Space Grotesk | Featured-detail stats |
| `type/body` | 14 | 400/500 | 1.45–1.5 | DM Sans | Chat text, insight body, buttons |
| `type/body-sm` | 13 | 400/700 | 1.45 | DM Sans | Chips, labels, secondary rows |
| `type/label` | 12 | 400/700 | 1.35 | DM Sans | Tile labels, badges, metadata |
| `type/caption` | 11 | 400/700 | 1.3 | DM Sans | Tab labels, chart axes, zone legend |
| `type/micro` | 10 | 400 | 1.2 | DM Sans | Goal labels, chart labels |

Additional typography rules:

- The "COACH INSIGHT" eyebrow is 12 px, 700, uppercase, `letter-spacing: 0.06em`, lime.
- Unit suffixes inside large numbers are 14–15 px, weight 500, muted
  (e.g. `48 ms`, `74 min`, `2,140 kcal`).
- Body copy is never below 11 px except the 10 px goal label; consider 11 px
  minimum in production for readability.

## 3. Spacing scale

The mockups use a 2 px base with a practical scale of
**2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 28**.

| Token | Value | Usage |
|---|---:|---|
| `space/1` | 2 | Tiny separators, zone-bar gap |
| `space/2` | 4 | Icon-to-label, segment gap, bar gap |
| `space/3` | 6 | Chip vertical padding, chart legend gap |
| `space/4` | 8 | Default small gap, grid gutter, chip gap |
| `space/5` | 10 | Compact tile padding, list-row vertical padding |
| `space/6` | 12 | Standard content gap, card grid gap, screen tab padding |
| `space/7` | 14 | Compact card padding, activity row horizontal padding |
| `space/8` | 16 | Standard card padding, composer gap |
| `space/9` | 18 | Hero/primary card padding |
| `space/10` | 20 | Screen horizontal padding, tab-bar bottom padding |
| `space/11` | 28 | Screen top padding |

Layout constants:

- Screen width in mockups: **390 px** (`canvas.json`); height **900 px**.
- Screen content inset: **20 px** left/right, **28 px** top.
- Bottom tab bar: **84 px** tall (8 px top padding, 20 px bottom, 1 px top border).
- Main vertical rhythm: **12 px** between sections.
- Use `WindowInsets`/safe-area padding in production; never hardcode status/nav
  bar heights.

## 4. Radii

| Token | Value | Usage |
|---|---:|---|
| `radius/pill` | 999 | Segmented control, chips, badges, input, send button, tab pills |
| `radius/2xl` | 22 | Primary hero cards (steps, HR, trends charts, featured activity, insight) |
| `radius/xl` | 18 | Standard cards, insight card, chat bubbles |
| `radius/lg` | 16 | Activity rows |
| `radius/md` | 14 | Compact tiles, coach data-access bar, thumbnails |
| `radius/sm` | 12 | Icon containers (40 px) |
| `radius/xs` | 9 | Coach avatar (28 px) |
| `radius/bubble-tail` | 6 | Assistant bubble bottom-left; user bubble bottom-right |
| `radius/bar-top` | 6/6 | Chart bar top corners |
| `radius/bar-bottom` | 3/3 | Chart bar bottom corners |

## 5. Component sizes and interaction

| Component | Size / rule |
|---|---|
| Screen title | 28 px Space Grotesk bold |
| Tab bar item | 4 equal columns; 24 px icon + 11 px label; min 44 px touch |
| Segmented control | 44 px tall, 3 px inner padding, 3 px gap; 14 px bold labels |
| Chips (activities) | Mockup 40 px tall, 0 14 px padding, 13 px bold; **raise to ≥44 px** |
| Suggestion chips (coach) | Mockup 40 px tall; **raise to ≥44 px** |
| Composer input | 48 px tall, pill, 18 px horizontal padding, 15 px text |
| Send button | 48 × 48 circle |
| Primary button ("Ask Coach") | 10 × 16 px padding, pill, 14 px bold; min 44 px |
| Today hero ring | 124 × 124, radius 52, stroke 12 |
| Today HR sparkline | 300 × 64 viewBox; fills width |
| Trends HR chart | 300 × 80 viewBox; fills width |
| Trends bar chart | 120 px plot height; per-bar gap 4 px |
| Zone bar | 10 px tall, pill, 2 px gaps between segments |
| Thumbnail (featured activity) | 138 × 100, radius 14 |
| Activity row | min-height 64, 40 px icon, 12 px gaps |
| Coach avatar | Header 44 × 44 (radius 14); message avatar 28 × 28 (radius 9) |
| Chat message max width | Assistant 296 px; user 270 px |
| Touch target | **≥ 44 × 44 dp for every interactive element** (requirement, even where mockup is 40) |

Borders are **1 px** and flat. There are no drop shadows in the mockups; use
surface color + border for hierarchy. Selected rows/chips use a lime border or
lime fill.

## 6. Chart and data-visualisation tokens

### Progress ring (Today steps)

- 124 × 124; circle radius 52; stroke 12; round line caps.
- Track `#26322D`; progress `#C7F26B`; rotated −90°.
- Center: 26 px bold percentage + 11 px muted "of goal".
- Accessibility: expose one label such as "Steps: 7,260 of 10,000, 73 percent".

### Line / area chart (HR, samples)

- Stroke 2.2 px, round join/cap, non-scaling stroke.
- Area fill = data color at 14% opacity, closed to the baseline.
- Dots: 3 px radius; last point 5 px radius; 2 px surface-colored ring.
- No gridlines, no y-axis labels in the mockups; label endpoints/axes with 11 px
  muted text. Production should add accessible min/max labels or a content
  description.

### Bar chart (steps)

- Plot height 120 px; bars flex equally with 4 px gaps.
- Top radius 6, bottom radius 3; selected bar lime, others lime at 38%.
- Zero values render a 0.8% stub (or no bar) rather than disappearing.
- Goal line: 1 px dashed `#5E6F66`; 10 px muted label "Goal 10,000".
- Axis labels 10 px muted.

### Heart-rate zones

- 10 px tall, pill, 2 px gaps.
- Z1 `#5ED6C1`, Z2 `#C7F26B`, Z3 `#FFD166`, Z4 `#FF9F5E`, Z5 `#FF6B57`.
- Legend: 6 px dot + 11 px muted "Z1 34%".
- Segment widths represent time-in-zone percentages; if all zero, show a single
  neutral track plus "No zone data" rather than fake segments.

### Route / trace thumbnail

- 138 × 100, radius 14, elevated surface.
- 3 px sport-colored polyline, round caps/joins.
- 10 px muted label ("Route" or "Heart rate trace").

## 7. Motion

The mockups define no motion. Recommended production defaults:

- Screen/tab content change: 150–200 ms fade/instant, respect "remove animations".
- Segmented control/chip selection: 120–160 ms color/size transition.
- Streaming chat text appears incrementally; typing indicator uses a subtle
  3-dot pulse and stops on cancel/error.
- No parallax or decorative motion; charts only animate on first display and
  must be skippable.

## 8. Accessibility corrections to the mockups

These are deliberate deviations required by the brief:

1. **Touch targets:** chips/suggestions are 40 px in the mockups; ship 44 dp
   minimum (increase height, keep visual pill).
2. **Control boundaries:** `#2A3530` on `#161D1A` is only ~1.35:1 and does not
   meet the 3:1 non-text contrast guidance for control boundaries. Use
   `#6B7C73` (≈3.9:1 on `#161D1A`, ≈3.5:1 on `#1E2723`) for inputs, unselected
   chips, and focusable row outlines; keep `#2A3530` for purely decorative card
   edges. Alternatively differentiate by fill and add a visible focus ring.
3. **Focus:** add a 2 px lime focus outline with 2 px offset; the mockups have
   no visible focus state. Keep it visible for keyboard/switch access.
4. **Text contrast:** all text colors above pass 4.5:1 on `bg`, `surface`, and
   `surface-elevated` (checked: muted 5.58–6.82:1, darkest data color 5.47:1).
   The 10 px goal label is legible by contrast but should be raised to 11 px
   where space allows.
5. **Don't rely on color alone:** selected bar/segment states also use weight,
   labels, or a border. Zone legend includes text percentages.
6. **Chart alternatives:** every chart needs a content description or adjacent
   textual summary with the same data; the mockups' `aria-label`s are a good
   baseline.
7. **Icon-only controls:** send button already has `aria-label="Send"`; keep
   real button semantics and 44 dp targets. Tab bar items are real links/buttons.
8. **Large text:** layouts must reflow at 200% font scale; avoid fixed-height
   text rows (the 64 px activity row can grow).

## 9. Compose mapping

Suggested mapping (not implementation):

- `MaterialTheme(colorScheme = darkColorScheme(...))` with `primary = lime`,
  `background = bg`, `surface = surface`, `surfaceVariant = surfaceElevated`,
  `onSurface = text`, `onSurfaceVariant = muted`, `outline = border`,
  `outlineVariant = borderElevated`, `error = coral`.
- Custom `Typography` with `displayLarge`…`labelSmall` mapped to the table
  above, plus `FontFamily(Font(R.font.space_grotesk_*))` and DM Sans.
- Custom `Shapes` for 2xl/xl/lg/md/sm/xs/pill.
- Keep spacing/radii as a small `PulseTokens` object or CompositionLocal rather
  than scattering literals.
- Charts stay Compose `Canvas` (already used) with shared chart primitives;
  no chart library is needed for the mockup's simple line/bar/ring visuals.
