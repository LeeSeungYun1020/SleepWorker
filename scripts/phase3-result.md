# Phase 3 implementation and verification — 2026-10-08

## Implemented

Version-bound preflight, immutable workflow imports, repository ownership, run history and
recovery, pause/resume, manual retry/skip, abort confirmation, session continuation,
per-visit bounded logs and provider events are implemented. Unknown CLI versions/model-effort
pairs fail closed. Quota failures do not trigger wasteful immediate retries. Authentication
errors require measured signatures; a timeout alone is not classified as logged out.

Native app quit now also shuts down owned processes through a JVM shutdown hook. Recovered
records show unknown elapsed time when no end timestamp exists; terminal records stop ticking.
Missing terminal results are shown as unknown rather than live progress.

## Verified evidence

- Local build: 104 Kotlin tests, 0 failures; Python collector/publication tests: 11 passing.
- Packaged desktop app builds and opens. The local shell-only app run is retained in
  `scripts/fixtures/phase3/local-app`.
- Codex 0.160.1: Luna medium new/resume, Astra medium and Sol medium actual execution.
  The user's existing successful Luna desktop session is retained as metadata only.
- Codex 0.160.0: Luna medium new/resume measured on October 8 using the separately installed
  CLI. The app-bundled 0.162.0-alpha.2 is unverified and was not implicitly substituted.
- agy 1.3.0: Flash high new/resume. Initial reported logout did not log out the CLI; those
  successes are not logged-out evidence.
- agy 1.3.1: fully logged-out model probe and actual execution both failed with measured
  sign-in errors on October 7. CLI preflight blocked execution. After user login on October 8,
  Flash high new/resume and model listing all succeeded. Regression fixtures cover both states.
- Original Astra-medium/Sol-medium flow: completed all 8 visits in the packaged app on
  October 7 (`20261007-123122-cd7b16dcf2b5498c`). Independent new planning/implementation
  sessions; review resumed planning, fix resumed implementation; 3 shell checks made no AI
  calls. The unit implemented truthful interrupted-record durations. Review approved, so fix
  was correctly a no-op. Evidence: `scripts/fixtures/phase3/user-flow-sol`.
- An earlier flow hit account usage limits and was aborted. It is not counted as successful.
- Actual app buttons: import, preflight, new run, pause requested during implementation,
  pause at next boundary, resume, manual retry, skip, abort body and abort completion check.
  Body and child processes were confirmed stopped. Offline integration tests also assert
  process cleanup, recorded attempts, terminal history, and repository lease reopening.
- Restart recovered October 7's interrupted run without executing it. October 8 native Cmd+Q
  test (`20261008-143254-4e9fe162570b49f8`) immediately recorded INTERRUPTED and stopped the
  owned shell process, before reopening.

## Final acceptance

- Low-cost cross-provider app flow completed all eight visits without manual intervention:
  `20261008-143404-511cff81048744de`, 687 seconds. Explicit Luna medium planning/review
  override, Flash high implementation/fix. The generator's production default remains Astra.
  Six model calls (including one automatic retry), three shell visits with no model calls.
  Flash's first implementation attempt timed out at 480 seconds, retained its conversation
  ID, then automatically resumed and succeeded. Both attempts and usage are retained.
  Review and fix resumed their original respective sessions; approved fix was a no-op.
  Evidence: `scripts/fixtures/phase3/user-flow-flash`.
- Final source review with Luna identified the misleading attempt denominator. Flash's
  implementation fixed it with five regression cases; the resumed Luna review approved.
  Review artifact identifies its exact source commit, before documentation-only finalization.
- Final app: synthetic shell failure exit 7 followed by automatic retry exit 0, visible 2/2;
  25,001 stdout lines (including final marker), capped UI with truncation notice, last line
  visible, full raw log persisted. Window resizing and log selection remained responsive.
- GitHub draft PR: https://github.com/LeeSeungYun1020/SleepWorker/pull/1 . Initial CI passed;
  final checked source/evidence is pushed to the same PR and final CI checked separately.
- A packaging attempt while the app was running failed ad-hoc codesigning. Closing the app
  and rerunning packaging succeeded; final package was then used for UI acceptance.
- Graph editing and full history/settings UI remain Phase 4–5 scope. The extended §4 sample
  with an in-workflow publish step and its full remote-mutation fault matrix are not claimed
  by these narrower user-flow checks; publication here is the actual delivery PR.

Provider usage is retained in measured JSON/evidence; monetary cost is not inferred from
subscription usage. Raw private agent conversations and credentials are not published.
