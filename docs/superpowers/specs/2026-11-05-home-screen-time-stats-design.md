# Meotime — Home Screen Time Stats

Date: 2026-11-05
Status: Design approved; pending spec review
Reference: `Meotime – Home with screen time stats.html`

## Problem

The home dashboard currently shows one monitored app row and no aggregate usage. The reference adds a screen-time card with a total, a comparison against the previous period, usage over time, and a per-app breakdown. It also adds a date picker so the card can show one day or a custom range.

Meotime stores only `daily_usage(packageName, date, usageSeconds)`. It has no hourly history, so the reference's 12a–8p bars cannot be produced from existing data. The current 7-day prune also conflicts with arbitrary date-range selection.

## Goals

1. Show a screen-time card on Home with total, comparison pill, usage chart, and per-app legend.
2. Support one day or an arbitrary past date range through a bottom-sheet picker.
3. Preserve the existing monitored-app row and its limit behavior.
4. Record real per-hour usage so single-day time-of-day bars are backed by data.
5. Keep all history so any selected past range can show real data.

## Non-Goals

- Whole-device screen time. Stats cover currently monitored apps only.
- Cloud sync, export, or sharing.
- Changing limit authoring or overlay behavior.
- Reading Android `UsageStatsManager` as a second usage source.
- A fixed dark theme for Home. Home follows the app's light/dark theme.

## Decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Add an hourly bucket table alongside the existing daily totals. | Small migration, keeps the daily limit source of truth intact, and provides the bars without an OS-level second source. |
| 2 | Split each session across local hour buckets when it closes. | Monitoring already writes once at close; splitting there keeps the write model unchanged. |
| 3 | Keep history indefinitely; remove the 7-day prune call. | Arbitrary past ranges must not silently lose data. |
| 4 | Stats include currently monitored apps only. | Meotime has no whole-device usage source, and monitoring scope is the product contract. |
| 5 | Show up to 3 apps plus `Others`. | Matches the reference density while allowing any number of monitored apps. |
| 6 | Ranges scroll horizontally, one bar per day. | Any range is allowed; squeezing 31+ bars into a fixed card would make them unreadable. |
| 7 | Home keeps the existing system light/dark theme. | The reference supplies layout and chart language, not a forced global theme. |
| 8 | Chart segment colors and comparison-pill colors are fixed data-viz tokens. | Color identifies an app segment or direction; it must not change with theme. |
| 9 | Add `kotlinx-datetime` for common date math. | Calendar grids, range lengths, labels, and local hour/date splitting need real date arithmetic in common code. |
| 10 | Past days recorded before this feature show empty hourly bars. | Existing daily totals cannot be reconstructed into real time-of-day data; fabricating a distribution would be wrong. |

## Data Model

### New table

```text
hourly_usage(
  packageName TEXT NOT NULL,
  date TEXT NOT NULL,
  hour INTEGER NOT NULL CHECK 0..23,
  usageSeconds INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  PRIMARY KEY(packageName, date, hour)
)
```

Room schema version becomes 3. Migration `2 -> 3` only creates the new table and leaves `daily_usage`, `monitored_apps`, and `sync_state` untouched. Schema export remains enabled.

### Retention

Remove the cold-start prune in `MeotimeApplication.onCreate`. `deleteUsageBefore` remains available as a maintenance API but has no production caller.

### Recording a session

`MonitoringCore` gains an injectable wall clock:

```kotlin
fun interface WallClock {
    fun nowEpochMillis(): Long
}
```

A session captures the wall-clock epoch at open. On close it calls one repository operation with:

- `packageName`
- `deltaSeconds` from the existing monotonic elapsed calculation
- `startedAtEpochMillis`
- `endedAtEpochMillis`

The repository divides `deltaSeconds` across every local hour bucket overlapped by the wall-clock session span. Allocation is proportional to overlap and uses largest-remainder rounding so bucket values sum exactly to `deltaSeconds`. Each affected date also receives its share in `daily_usage`, so a session crossing midnight is credited to both dates instead of entirely to the closing date.

Guards:

- `deltaSeconds <= 0`: no write, matching current behavior.
- `endedAtEpochMillis <= startedAtEpochMillis` or no bucket overlap: assign the whole delta to the local bucket containing the end instant.
- Deep sleep is not expected inside a session because screen-off closes it. If the wall span and monotonic duration disagree, the monotonic duration remains authoritative and only the distribution is scaled.
- Daily and hourly writes happen in one Room transaction.

`recordUsageSeconds` remains for test fixtures and daily-only writes. The monitoring path migrates to the new operation.

### Queries

Repository additions:

- Observe daily usage rows between two dates.
- Observe hourly usage rows for one date.
- Record a split session.
- Read usage for the prior comparison period.

Use cases expose the same reads so the dashboard never touches DAOs directly.

## Stats Calculation

### Scope

Only packages currently present in `monitored_apps` are included. Removed apps' historical rows are ignored. Package labels and icons come from the existing monitored-app observation.

### Single day

- Total: sum of each monitored app's daily usage for the date.
- Chart: six 4-hour buckets, labeled `12a`, `4a`, `8a`, `12p`, `4p`, `8p`.
- Each bucket is stacked by top apps + `Others`.
- Legend: each slice shows its total for that day.
- Comparison: previous calendar day.

### Range

- `days = end - start + 1`.
- Primary number: daily average = total across the range ÷ days, using whole-second integer division before formatting.
- Chart: one stacked bar per day.
- Legend: each slice shows its total across the whole range.
- Comparison: equal-length period immediately before the range.
- Range bars live in a horizontal `LazyRow`; bar width is fixed.
- Bar labels use weekday initials, matching the reference.

### Top 3 + Others

- Sort monitored apps by period usage, descending.
- 3 or fewer monitored apps: show all, no `Others`.
- More than 3: show the top 3 and aggregate every remaining monitored app into `Others`.
- Zero-usage apps remain eligible when there are 3 or fewer apps, matching the reference's `0 sec` row.
- Colors are assigned consistently for the whole card:
  - 1st: `#FF5A5F`
  - 2nd: `#A78BFA`
  - 3rd: `#60A5FA`
  - Others: `#5C5C5C`

### Comparison pill

- Today: `vs yesterday`.
- Other single day: `vs prior day`.
- Range: `vs prior period`.
- Usage fell: green `#6EE7A0` on `#12301E`.
- Usage rose: red `#FF8A80` on `#301212`.
- Change is `(current - previous) / previous * 100`, shown to one decimal with trailing `.0` removed.
- Prior total is zero: neutral pill showing `—` with the comparison label. No invented percentage.
- Direction is also conveyed by an arrow, not color alone.

### Numbers

New compact formatter for stats:

- `< 60 sec`: `N sec`
- `< 60 min`: `Nm`
- Otherwise: `Xh Ym`, omitting `0m`

Examples: `0 sec`, `15m`, `1h 12m`, `38m`.

Existing `formatUsage` remains for existing row copy.

## UI

### Home

Home remains inside `MonochromeTheme`; no forced dark palette. Reference layout, spacing, type scale, pill controls, and chart structure are preserved. Background, card, borders, text, sheet, and selected-date colors map to existing Meotime tokens. Fixed colors remain only for chart segments and comparison direction.

Top to bottom:

1. Header: `Meotime` at 34sp, filter subtitle, date pill at the top right.
2. Screen-time card.
3. Existing monitored-app list.
4. Pinned `Add app` button.

The date pill uses a calendar icon and height, radius, and border matching the reference.

### Screen-time card

- Card fill and border come from theme tokens (`surfaceContainer`, `outlineVariant`).
- Eyebrow:
  - Today: `Total today`
  - Other single day: `Total`
  - Range: `Daily average`
- Right-side label:
  - Single day: `Thu, Nov 5`
  - Range: `5 days`
- Primary number at reference size and tracking.
- Comparison pill beside the number.
- Chart:
  - Single day: six 24dp columns, 96dp tall, 4dp corner radius, 2dp segment gap.
  - Range: same bar geometry inside a horizontal `LazyRow`.
  - Column height scales to the largest column/day in the current period.
  - Zero values render no segment.
- Legend:
  - Divider above.
  - 10dp color dot, app name, period total.
  - `Others` uses the aggregate total.

### Monitored-app rows

The existing row is retained: icon, name, progress, usage, status, and click target.

Single day keeps the current copy and behavior.

Range changes only row data:

- Trailing usage: `3h 10m total`
- Progress: daily average against the daily limit
- Status: `38m per day on average · limit 30 min`
- Paused apps retain an explicit `Monitoring paused` marker before the range text.
- Uninstalled apps keep their existing `No longer installed` label.

### Add app button

Reference height, radius, and typography; colors from the theme's primary/on-primary roles. Existing add-app navigation is unchanged.

## Date Picker

A bottom sheet opens over Home when the date pill is tapped.

### Controls

- Drag handle.
- Title: `Select dates`.
- Segmented control: `Single day` / `Date range`.
- Presets: `Today`, `Yesterday`, `Last 7 days`.
- Month calendar:
  - Header with month/year and previous/next arrows.
  - Sunday-start weekday row.
  - Future dates greyed and disabled.
  - No oldest selectable limit.
  - Next-month arrow stops at the current month.
- Summary line.
- `Cancel` and `Apply`.

### Selection behavior

- Single mode: tapping a date selects it.
- Range mode: first tap sets the anchor; second tap completes the range.
- If the second tap is earlier, start and end swap.
- Endpoints use the theme's selected colors; middle days use a muted selected fill.
- Presets:
  - `Today` -> single day, today.
  - `Yesterday` -> single day, yesterday.
  - `Last 7 days` -> range, today minus 6 days through today.
- Draft state is local to the sheet. `Cancel` discards it. `Apply` commits it. Reopening starts from the applied selection and shows its month.

### Applied labels

- Today: button and subtitle `Today`.
- Yesterday: button and subtitle `Yesterday`.
- Other single day: button `Nov 4`, subtitle `Nov 4, 2026`.
- Same-month range: button `Nov 1 – 5`, subtitle `Nov 1 – Nov 5, 2026`.
- Cross-month range: button `Nov 28 – Dec 3`, subtitle includes both endpoints and year.
- Cross-year range: include both years.
- Summary:
  - Single: `Thu, Nov 5`
  - Range: `Nov 1 – Nov 5 · 5 days`

`Today` remains relative across midnight. Explicit dates stay fixed.

## State Handling

- Loading: existing app skeleton plus a stats-card skeleton with final dimensions.
- App-list failure: existing dashboard error and retry.
- Stats failure: app list stays usable; card shows compact `Couldn't load stats` and a retry action.
- No monitored apps: zero total, empty chart, existing empty state, add button.
- No usage in period: zero bars and zero-valued legend rows.
- Midnight rollover: the `Today` filter follows `TodayKey.dateFlow()`; selected explicit dates do not move.

## Accessibility

- Date buttons expose the full date and selected state.
- Future dates are disabled and announced as unavailable.
- Chart bars are decorative and cleared from semantics.
- Legend text and the card summary carry the actual values.
- Comparison direction is written, not color-only.
- Bottom sheet uses dialog semantics and modal focus behavior.
- All controls keep at least a 48dp touch target.

## Architecture and Files

| Area | Files |
|---|---|
| Hourly storage | `data/local/entity/HourlyUsageEntity.kt`, `data/local/HourlyUsageDao.kt` |
| Database | `data/local/MeotimeDatabase.kt`, migrations, `MeotimeMigrations.kt` |
| Repository | `ScreenTimeRepository.kt`, `ScreenTimeRepositoryImpl.kt` |
| Recording | `monitoring/MonitoringCore.kt`, `monitoring/WallClock.kt` |
| Aggregation | `presentation/dashboard/ScreenTimeStats.kt`, `ScreenTimeStatsBuilder.kt` |
| Formatter | `presentation/format/StatFormat.kt` |
| Dashboard | `presentation/dashboard/DashboardScreen.kt`, `DashboardViewModel.kt` |
| Stats card | `presentation/dashboard/ScreenTimeCard.kt`, `ScreenTimeTokens.kt` |
| Picker | `presentation/dashboard/DateRangeSheet.kt`, `DateRangePickerModel.kt` |
| Calendar | `presentation/dashboard/CalendarMonth.kt` |
| Wiring | `AppContainer.kt`, `MeotimeUseCases.kt` |
| Prune removal | `androidApp/.../MeotimeApplication.kt` |

Small icons (calendar, chevrons, trend arrow) are custom vectors; no new icon dependency.

ViewModel structure:

- Existing monitored-app observation continues to own rows and icons.
- A separate stats job is keyed on the applied filter; applying a filter cancels and restarts only that job.
- Stats failure updates only stats state, never the app list.
- Stats use the shared `kotlinx-datetime` local date model.

## Testing

1. Migration `2 -> 3`: daily rows and monitored apps survive; hourly table exists.
2. Bucket splitting:
   - within one hour
   - across adjacent hours
   - across midnight
   - DST transition
   - reversed/zero wall span fallback
   - zero/negative delta guard
   - allocated seconds sum exactly to delta
3. Aggregation:
   - single-day total and six buckets
   - range total, daily average, and per-slice totals
   - previous-day and previous-period comparison
   - zero prior period
   - top-3 + Others selection
4. Picker logic:
   - presets
   - mode switching
   - reversed range taps
   - future-date disable
   - month navigation bounds
   - label formatting
5. ViewModel:
   - apply and cancel filter
   - stats failure isolation
   - live stats updates
   - midnight rollover for the relative Today filter
6. Formatting: `0 sec`, `15m`, `1h 12m`, `38m`, delta wording.
7. Existing dashboard and monitoring suites continue to pass after repository/fake updates.

Verification commands:

```text
./gradlew :shared:testAndroidHostTest
./gradlew :androidApp:assembleDebug
```

## Risks and Limitations

- Days recorded before this feature have no hourly bars; they will show daily totals and empty time-of-day bars.
- Session bucket distribution is derived from wall-clock start/end. Clock changes fall back to the end bucket.
- Very long ranges require horizontal scrolling, by explicit decision.
- Keeping all history removes the current storage bound. The write volume remains small: at most one row per app per day plus one per app per active hour.
- `kotlinx-datetime` is a new dependency and must be added to the version catalog and shared common source set.
