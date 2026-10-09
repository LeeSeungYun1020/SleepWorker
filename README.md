# aiflow

Kotlin Multiplatform + Compose Desktop workflow runner for Codex, Antigravity and shell steps.
Phases 1–4 implement workflow storage, provider adapters, execution, CLI preflight, the desktop
run screen and graph editing with draft/version history. Local Git/shell editor execution and
Phase 3 two-provider/GitHub acceptance are verified. Full history/settings follow in Phase 5.

## Build and run

Requires JDK 21; macOS is required for the DMG target.

```sh
./gradlew build
./gradlew :desktopApp:run
./gradlew :desktopApp:packageDmg
```

DMG output: `desktopApp/build/compose/binaries/main/dmg/aiflow-1.0.0.dmg`.
The package uses a major version of 1 because Apple's DMG packaging rejects `0.1.0`.
This unsigned development package is not a notarized release.

## Modules

- `shared/commonMain`: workflow definitions, YAML/JSON codecs, graph validation, draft/version
  storage, execution state machine, run recording/recovery, worktree preparation, settings,
  provider commands/parsers/contracts and platform interfaces.
- `shared/jvmMain`: process execution, temporary scripts, macOS notifications, login-shell path
  detection and operating-system repository locks.
- `desktopApp`: app entry point and macOS packaging.

## Model and storage

`start` and ordered explicit `transitions` determine control flow. Step array order and editor
coordinates do not determine execution. Invalid graphs can be saved as drafts; publishing an
immutable version requires no errors and acknowledgement of warnings. Restoring a version creates
a draft with `restoredFrom`; saving it creates a new version without modifying history.

Acquire a `RepositoryLease` before constructing `WorkflowStore`. Stores sharing a lease share its
write mutex. Keep that lease alive for the application's ownership of the repository, including
future execution/recovery. Storage paths use UUIDs, reject symlink redirection, and use temporary
files plus atomic rename. The OS lock and mutex serialize the no-overwrite check for version files.

The import/export YAML example is in `shared/src/commonTest/resources/feature-dev.yaml`.
It is a schema fixture, not a request to run its shell commands or publish a pull request.

## Provider and process contracts

Adapters only build commands and parse output. They do not start agent runs. Create a new parser
for each attempt, collect stdout and stderr to EOF (or record incomplete output), then call
`finalizeOutput` exactly once. A process exit alone does not imply complete output. Retain the
observed exit code and `Termination` separately; a cancelled agy process can exit with code 1.
`NOT_APPLICABLE` is shell's provider outcome; the engine must still judge its exit code.

`VerifiedCliContract.validateRequest` is the fail-closed preflight building block. Versions,
features and measured model/effort pairs have separate evidence. Candidate model settings do not
prove support. The example's model/effort selections are preserved. Verified Luna medium execution does not
establish support for Luna low on every CLI version. Authentication classification uses measured
signatures; unmatched failures remain `Unknown`. Binary paths are injected, not machine-specific defaults.

`RunningProcess.stdout` and `stderr` are single-consumer streams buffered from process start.
`ManagedProcessRunner` owns every process for a run and permanently blocks further starts after
cancellation or cleanup failure. Cleanup uses bounded waits and reports errors while retaining
queued output. The engine persists that evidence and implements bounded normal-output drain, separate attempt
records and retries. Unconfirmed cleanup permanently blocks further commands. OS descendants are monitored; arbitrary detached processes
that escape before discovery are outside the verified process-tree contract.

## Verification

`./gradlew build` runs common tests on JVM and local-process/fixture integration tests. No model
calls, authentication changes or GitHub writes occur. The tests replay original evidence directly
from `scripts/fixtures/phase0` (measured and synthetic sources remain distinct), avoiding copied
fixtures that could drift. Reports: `shared/build/reports/tests/jvmTest/index.html`.

See [Phase 1 results](scripts/phase1-result.md), [Phase 2 results](scripts/phase2-result.md),
[Phase 3 results](scripts/phase3-result.md), [Phase 4 results](scripts/phase4-result.md), and the [phase plan](00-overview.md).

## Execution engine

Use one `RunOrchestrator` per run, with the repository's live lease shared by `WorkflowStore` and
`RunRecorder`. First call `RunRecovery.recover()` when opening an owned repository, before starting
new work. Recovery marks previous nonterminal runs `INTERRUPTED`; it never starts processes.

`start(savedVersion, settings)` suspends until terminal. Launch it in the application's run scope
and observe `state` (`StateFlow<RunState?>`) and `logs` (`SharedFlow<LogLine>`). The saved version is
reloaded by identity, so subsequent draft edits cannot affect its snapshot. `Preflight.None` is available for offline engine tests; the desktop RunViewModel always supplies
CLI/auth/model checks and repeats them at start. Verified
binary version and contract metadata can be injected; unknown values remain null.

`pause()` finishes the current step and its checks, then pauses only before the selected next
visit. `resume()` follows that saved decision without rerunning checks. `decide(Retry)` creates a
new visit; `decide(Skip)` treats the result as successful only for branching and preserves the
original attempt results. External conditions are cached per visit. `abort()` and
`interruptForShutdown()` cancel owned body/check/preparation processes and wait for bounded cleanup.
The caller should await shutdown before releasing the repository lease.

Records are stored under `.aiflow/runs/<runId>/`: the authoritative atomic `run.json`, visit snapshots,
separate attempt scripts/output/events/results, completion/transition checks and auxiliary command
records. Attempt outcomes live in `AttemptRecord.result`; missing observed exit codes remain null.
Transition counter keys are `stepId:zeroBasedIndex`; next-step edges count only when entering the
actual target visit. Live log subscribers may lag; disk logs remain the full recorded source.
If storage becomes unwritable, the engine stops and exposes `FAILED`, emits an application diagnostic,
and leaves the last durable snapshot for recovery. Add `.aiflow/runs/` to your repository's ignore
rules if desired; the engine does not modify `.gitignore`.

## Run screen

Open a Git repository by absolute path, then select a saved workflow version or import a YAML
file. Imported `repoPath` must equal the opened repository's canonical path. Validation warnings
require acknowledgement before publishing the imported draft. Run preflight, then choose New Run.
Agent runs require the exact measured CLI version and model/effort pair; unverified combinations
remain blocked. Specify binary paths in `~/Library/Application Support/aiflow/settings.json`.
Use the timeline to select visits and the log controls to select attempts, streams and phases.
History records never resume automatically; interrupted runs require a new preflight and run.

## Graph editor

Open a repository, select Editor, then create an empty/linear/example workflow or open a draft.
Drag nodes to move them and output ports to create connections; choose a condition before
confirming. Edges show their evaluation priority. The properties panel offers the same connection
operations, including loops, target reconnection, visit limits and explicit reordering.
Layout and search sorting never change execution order.

Save always persists a draft first. Errors keep it non-executable; warnings require acknowledgement
before an immutable version is saved. Save and Run uses that exact version and preflight, with no
fallback to an older version. To run an older version, explicitly select it in the run screen.
Version History provides read-only previews and confirmed restoration to a draft; saving the
restored draft creates a new version and keeps all existing history. YAML export is standalone.

File/repository changes and window close protect dirty edits with save/discard/cancel. Native
Cmd+Q preserves dirty drafts through the shutdown hook. New/copy nodes have no connections;
deleting nodes removes adjacent edges without adding bypass links. `!` at the first nonwhitespace
script position toggles Shell mode; removing it returns to Agent with its session/model settings.
Model catalogs are candidates; CLI version/model/effort execution contracts still gate runs.
