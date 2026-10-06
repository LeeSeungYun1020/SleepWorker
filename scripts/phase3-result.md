# Phase 3 implementation and verification — 2026-10-06

## Implemented

- `engine/Preflight.kt`: read-only workflow/repository/worktree validation, selected absolute
  CLI path/version and measured model/effort contract checks, provider-specific auth/models,
  bounded probes, dirty-tree warnings, git/gh discovery. Unknown versions, authentication,
  or unmeasured model combinations block execution. No silent replacement.
- RunViewModel: repository ownership/recovery, immutable version selection/import (explicit
  warning acknowledgement), version-bound preflight, execution-time recheck, orchestration,
  notifications, controls, history, shutdown cleanup and per-visit bounded log snapshots.
- Compose run screen: actual visit timeline, attempt/phase/stream selection, raw/event views,
  pause/resume/abort confirmation, user decision dialog, interrupted records, copied session IDs,
  version identity, duration and preflight diagnostics.
- Typed Message/ToolCall events are streamed and retained in the run log; raw output stays separate.
- Desktop entry point owns shutdown and releases the repository lease after execution stops.
- Fixed recovery list sorting after timestamps change and a real desktop startup failure from
  kotlinx-datetime 0.6.2 compile / 0.7.1 runtime mismatch. Clock/Instant now use Kotlin time;
  serialized timestamp representation remains ISO-8601.
- Pipeline/GitHub validation helpers require explicit model, effort and selection reason for
  model calls. Historical Phase 0 evidence remains unchanged.

## Verified

- `./gradlew build`: 89 Kotlin tests, including 9 Phase 3 tests.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`: 11 tests.
- `:desktopApp:createDistributable`: packaged application builds and opens.
- Real local Git/zsh ViewModel integration: import -> warning acknowledgement -> saved version ->
  preflight -> sync -> verify/completion command -> log -> completed -> reload history -> reopen lease.
- Packaged application UI: opened that isolated local repository, selected the stored version,
  ran preflight and clicked New Run. Visibly observed COMPLETED, ordered sync/verify/log timeline,
  each exit 0, and final `complete` stdout. No model calls or GitHub writes.
- Durable app-run evidence copied to `scripts/fixtures/phase3/local-app/`: saved draft/version,
  run snapshot, visits, attempts, completion check, raw logs and results. Paths in the captured
  snapshots refer to the original disposable repository; this is evidence, not a portable import.
- Synthetic preflight tests cover path/version selection, logged-out vs unknown, unsupported
  model, timeout cleanup, worktree mismatch, shell-only scope, and log retention.

## Remaining external acceptance checks

Phase 3's full acceptance criteria are **not all satisfied**. The local shell run is not evidence
of the two-provider §4 workflow or GitHub publication. No model was invoked (calls/usage/cost: 0 /
not applicable / not applicable).

- GPT-6 Luna execution evidence is still absent from VerifiedCliContract. It remains blocked,
  even if present in a model list. Do not use an expensive model as an implicit substitute.
- agy logged-out behavior remains NOT_VERIFIED. A separate unauthenticated account/VM fixture
  is required; the user's existing login was not removed or changed. Unknown fails closed.
- The two-provider workflow, live retry/new/resume, manual pause/abort scenarios with live agents,
  terminal session continuation and authorized exact-SHA draft PR publication/cleanup remain to
  be verified in an isolated environment. No GitHub target was newly designated in this request.
- Graph editing and full history/settings UI belong to Phases 4–5. Phase 3 provides history
  inspection through the run screen and reads the existing settings JSON.
