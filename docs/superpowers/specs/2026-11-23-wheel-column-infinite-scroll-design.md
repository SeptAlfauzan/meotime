# WheelColumn infinite scroll — short design

Status: draft for approval.

## Intent

`WheelColumn` in `TimePickerScreen.kt` currently selects on tap and then scrolls that row to the
middle of the three-row band. Make the band's middle row authoritative: scrolling chooses the
value, and reaching the end of `0..count-1` continues at the start. Rolling `23 -> 00` (or
`59 -> 00`) also changes the value to `00`, as the user confirmed.

## Goals / non-goals / anti-goals

- Goal: hours wheel loops `00..23`, minutes wheel loops `00..59`; the row under the band centre is
  the selected value.
- Goal: one selection path — centre-derived `onSelect(...)` — used by fling, tap, and presets.
- Non-goal: changing `WeeklyLimitViewModel`, `setPickerMinutes`, presets, Save/Remove, or the
  0..1440 clamp. With component wrap the largest reachable value is 23:59 = 1439; 24:00 was
  already unrepresentable (1440 renders as `23:00` today).
- Anti-goal: a visible jump when wrapping, or a feedback loop where scroll writes `selected` and
  the `selected` effect scrolls back.

## Constraints

- Kotlin Multiplatform `shared/src/commonMain`; Compose Multiplatform 1.12.1, no new dependencies.
- `LazyColumn` + `rememberSnapFlingBehavior` needs `@OptIn(ExperimentalFoundationApi::class)`.
- Row height stays 64.dp and the band stays three rows; token colors/typography stay as-is.
- No Compose UI-test harness exists in `commonTest`; pure index math must be extracted into an
  `internal` top-level function so it can be unit-tested there.
- `TimePickerScreen.kt` has root screen padding removed in the working tree for the separate
  padding change; leave that intact.

## Approaches

1. **Repeat-and-recentre (picked).** Render three copies of `0..count-1`, start in the middle copy,
   snap to rows, derive the centred value modulo `count`, and jump by one whole copy when the
   centre enters an outer copy. Identical rows make the jump invisible. Works both directions,
   no new dependency.
2. Edge teleport. One copy; jump from last row to first (or back) at the boundary. Rejected: jump
   lands mid-fling, snap fights it, and a fast fling stalls at the edge.
3. Wheel library / custom canvas. Rejected: library likely breaks KMP or theme tokens; canvas
   loses row semantics and click targets.

## Decisions

- Replace `verticalScroll(rememberScrollState())` with
  `LazyColumn(flingBehavior = rememberSnapFlingBehavior(state))`, `items(3 * count)`, and
  `contentPadding = PaddingValues(vertical = rowHeight)` so any row can sit at the band centre.
- `rememberLazyListState(initialFirstVisibleItemIndex = count + selected)`; item heights are
  exactly `rowHeight`, so scroll offset 0 puts `selected` in the centre.
- Extract `internal fun wheelCenteredIndex(firstVisibleIndex: Int, scrollOffsetPx: Int, rowHeightPx: Float): Int`
  = `firstVisibleIndex + round((scrollOffsetPx + rowHeightPx / 2) / rowHeightPx)`. Extract
  `internal fun wheelValue(index: Int, count: Int): Int = index % count` (indices are non-negative).
- In a `LaunchedEffect`, `snapshotFlow` of `(firstVisibleItemIndex, firstVisibleItemScrollOffset)`
  maps to `wheelCenteredIndex`, `distinctUntilChanged()`, and calls
  `onSelect(wheelValue(centered, count))` on every row crossing. Scroll-origin updates are not
  treated as external changes.
- Keep a `programmaticScroll` flag: set while tap/preset animations run; the centred-row collector
  ignores values during it, then commits the final value explicitly.
- `LaunchedEffect(selected)` only animates when `wheelValue(currentCentered, count) != selected`,
  targeting `count + selected`. This keeps presets/open working without a scroll feedback loop.
- After scroll settles, if the centred index is in copy 0 or copy 2, `scrollToItem(centered ± count)`
  with zero offset. Rows are identical, so no visual change.
- Tap stays: clicking a row animates that exact index to the centre; no direct `onSelect` on click.
  Update the KDoc above `WheelColumn` to describe centre-driven selection.
- Accessibility unchanged: each row keeps its visible two-digit `Text` inside a `clickable` box.

## Left to the builder

- Edit only `TimePickerScreen.kt`; add `commonTest/.../presentation/limit/WheelColumnMathTest.kt`
  covering centre rounding (`0`, half-row, near-full-row) and wrap (`23 % 24 == 0`, `59 % 60 == 0`,
  indices in outer copies).
- Verify: `./gradlew :shared:allTests` (or the module's usual test task) plus the Android compile
  task; manual smoke: scroll both wheels past their max and back, tap a row, apply a preset, save,
  reopen — value must match the centred row.
- No changes to docs under `docs/superpowers/` other than this file.
