# Phase 3 unit plan: accurate attempt labels in the run timeline

## Problem

The run timeline currently renders each visit's attempt count as `attempts.size/attempts.size`. This makes a single attempt look like `1/1` and does not distinguish an active first attempt from a completed retry sequence. The run screen requirements call for a meaningful attempt indicator, including `2/2` on retry.

## Proposed changes

- Derive the timeline label from the visit's actual attempt records and lifecycle: show the current attempt number against the retry limit when retrying, and a clear first-attempt label when no retry occurred. Avoid presenting a retry as complete while an attempt is still running.
- Keep automatic attempts within one visit; keep manual retries identified as their separate visit.
- Add focused formatting tests for no attempts, one attempt, active retry, and completed retry cases.

## Acceptance checks

- A visit with one attempt displays `시도 1/2` (or an equally clear first-attempt state), not `1/1`.
- A second automatic attempt displays `시도 2/2`; the label does not claim completion before that attempt finishes.
- Manual retry remains a separate visit and is not counted as an automatic attempt of its predecessor.
- No changes to authentication, provider/model contracts, packaging, remote publishing, or broad screen layout.

## Files to inspect

- `shared/src/commonMain/kotlin/aiflow/ui/run/RunScreen.kt` — timeline rendering and attempt label.
- `shared/src/commonMain/kotlin/aiflow/engine/RunState.kt` — attempt record fields and visit state.
- `shared/src/commonTest/kotlin/aiflow/EngineBoundaryTest.kt` and `shared/src/commonTest/kotlin/aiflow/EngineTest.kt` — existing retry semantics and test conventions.
- `shared/src/commonTest/kotlin/aiflow/RunElapsedTimeTest.kt` — lightweight common test conventions.

## Scope ceiling

Expected implementation: at most 1 production file and 1 test file. Do not implement in this planning unit.
