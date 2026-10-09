# Phase 3 unit: truthful elapsed time in terminal run records

## Problem and progression

`00-overview.md` and `scripts/phase3-result.md` establish that the run UI and local
shell path are implemented, while external acceptance remains incomplete. This
unit closes a small UI quality gap in `06-run-screen.md` (elapsed time and
interrupted-record display), consistent with `airflow.md` §§6, 8.2–8.3.

`RunScreen.kt` uses `visit.endedAt ?: now`, so a terminal record with no recorded
visit end keeps gaining seconds. Its run timer also treats `lastUpdatedAt` as the
end for INTERRUPTED runs, although recovery sets that field to discovery time.
`EngineRecoveryTest` explicitly preserves unknown end timestamps. Displaying an
exact duration in these cases misrepresents the recorded evidence.

## Proposed changes

- Add a small pure presentation helper accepting explicit timestamps/current
  time, with a distinct unknown-duration result.
- Use recorded visit end times when present; use the live clock for unfinished
  visits only while the run is nonterminal. For terminal visits without an end,
  show `소요 시간 미확인` rather than a ticking duration.
- Show unknown total duration for INTERRUPTED runs; never use discovery time or
  previous update time as an inferred execution end. Preserve existing total
  elapsed-time behavior for other run statuses and the no-visits fallback.
- Wire the helper into the existing toolbar/timeline and stop the periodic clock
  effect for terminal records. Keep known durations nonnegative.

## Acceptance checks (future implementation)

- Deterministic common tests with two different `now` values: unfinished live
  visits advance; ended visits stay fixed; terminal visits without `endedAt`
  remain unknown for COMPLETED, FAILED, ABORTED and INTERRUPTED.
- An INTERRUPTED fixture with discovery hours after its last update never shows
  that gap as execution duration; already-ended visits still show their known
  durations. Noninterrupted run totals, empty visits and negative deltas retain
  their defined fallback/clamping behavior.
- Inspect UI wiring: terminal state cancels the ticker, returning to an active
  run restarts it, and unknown values render as text rather than `0초` or `null`.
- A later UI check using synthetic in-memory states confirms stable interrupted
  records and ticking live elapsed time, without launching providers or writing
  run/workflow records. Targeted common tests are for a later authorized pass;
  no Gradle command or implementation is part of this planning task.

## Files to inspect and change budget

Production changes: **2 files**:

- `shared/src/commonMain/kotlin/aiflow/ui/run/RunScreen.kt`
- `shared/src/commonMain/kotlin/aiflow/ui/run/RunElapsedTime.kt` (new helper)

Test changes: **1 file**:

- `shared/src/commonTest/kotlin/aiflow/RunElapsedTimeTest.kt` (new)

Read-only supporting inspection:

- `shared/src/commonMain/kotlin/aiflow/engine/RunState.kt`
- `shared/src/commonMain/kotlin/aiflow/storage/RunRecovery.kt`
- `shared/src/commonTest/kotlin/aiflow/EngineRecoveryTest.kt`
- The four progression/specification documents cited above.

## Boundaries

Work stays in this isolated checkout. No authentication, model contracts,
packaging, remote publishing, broad redesign, engine/storage/schema changes,
delegation, AI model calls, pushes or credential changes. Do not edit
`.aiflow/runs` or `.aiflow/workflows`. This unit does not claim full Phase 3
acceptance. This pass writes only this plan; do not implement or run Gradle.
