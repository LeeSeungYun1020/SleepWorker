# aiflow

Kotlin Multiplatform + Compose Desktop workflow runner for Codex, Antigravity and shell steps.
Phases 1–5 implement workflow storage, provider adapters, execution, CLI preflight, graph editing,
read-only run history, settings, notifications and a macOS DMG. Verification evidence and
remaining limits are recorded in the phase result documents.

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
[Phase 3 results](scripts/phase3-result.md), [Phase 4 results](scripts/phase4-result.md), [Phase 5 results](scripts/phase5-result.md), and the [phase plan](00-overview.md).

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
Agent runs accept CLI versions and model/effort pairs without prior execution measurements.
Preflight reports missing measurements and catalog entries as warnings; authentication and workflow
errors still block execution. Use Settings to detect CLI candidates, inspect their versions, and choose an absolute path.
Settings are saved immediately in `~/Library/Application Support/aiflow/settings.json`.
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
Model catalogs and measured contracts are reference information; unmeasured versions, models and
effort combinations can run with the exact requested settings.

## Install and configure

Open `desktopApp/build/compose/binaries/main/dmg/aiflow-1.0.0.dmg`, copy `aiflow.app` to
Applications, and launch it from Finder. This personal-use package has no Developer ID signature
or notarization. If macOS blocks it, follow [Apple's per-app opening instructions](https://support.apple.com/en-us/102445):
attempt opening it, then use System Settings → Privacy & Security → Open Anyway for this app.
Older macOS versions may also offer Finder right-click → Open.

Install Codex and/or Antigravity and log in using their CLI before running agent steps. The app
has no login UI and does not store tokens or credentials. Git is required; `gh` is optional unless
your shell steps use it. A JVM is bundled; JDK 21 is needed only to build from source.

In Settings, use Auto Detect, then explicitly select a candidate and check it. PATH candidates,
app-bundled binaries and NVM native executables are offered when they exist. No developer's
absolute path is shipped as a default. Finder does not inherit an interactive shell's PATH:
a Node-based launcher can fail even though a native binary works. The last check records the
path, version, contract and timestamp; a mismatched current path is labeled as a past check.
Unknown versions and unmeasured model/effort combinations can run; preflight records the actual
version and leaves the measured contract ID empty when there is no matching evidence.

Codex's editable catalog is a candidate list, not proof of model support. Antigravity Refresh
uses `agy models` TSV, and preserves the old cache with a diagnostic if refreshing fails. The
cache always shows its source binary/version and query time. Default worktree root affects new
workflows. Notification toggles select completion, failure, user attention and pause events.
The log limit applies per visit to new live buffers; complete logs remain on disk.

## First workflow

1. Open a repository in Run or Editor. In Editor, choose New and a blank or local shell template.
2. Add nodes and set Start. Choose a session/provider/workspace for agent nodes. A leading `!`
   makes a script a shell step, which needs no model.
3. Draw condition arrows to the next node or explicit `end`/`ask`. Numbered arrows are evaluated
   in order; the first matching condition wins. Set loop limits to bound repeated edges.
4. Save the draft, resolve errors and acknowledge warnings to create an immutable version.
   Save and Run preflights that exact saved definition before starting.

Node positions and array/list order affect presentation only. There is no implicit next node or
implicit end. Missing matches, condition errors and exhausted limits wait for user attention.
Pause finishes the current visit and checks before the next node. Retry creates a new visit;
Skip preserves the original failed result and separately records the user's branching decision.

## History and manual continuation

History lists runs newest first. Select a run to view its snapshot, transition priorities,
visits, attempts, metadata and check outcomes. Expand a visit, search its artifact names and
select stdout/stderr, script, command or result files; contents load only on selection and large
files have a bounded preview. Full files remain under `.aiflow/runs/<runId>/`. Missing exit codes,
actual models and prices are displayed as unknown; provider-reported usage is preserved.

Copy a session ID or the noninteractive resume command from an attempt. Edit its
placeholder instruction and run it in a terminal. The command explicitly keeps the recorded
model/effort, binary and workspace; it uses the existing provider adapter's permission options.
It is also available for unmeasured versions and models. Check the binary is still
installed at the recorded path. Antigravity sessions remain tied to their original workspace.
A manual terminal continuation is external to the saved aiflow run and creates no new visit.

View Used Version opens the source version. Restore saves current edits as a draft, restores
the historical graph to its draft, and requires saving a new version for a new run. It does not
resume a run or roll back repository files. If the original version is missing, view the run's
snapshot and explicitly import it as a new workflow. The old run remains unchanged.

Interrupted runs show the prior status, last durable timestamp, reason and discovery timestamp.
Their true termination time and external-process state can be unknown, and their logs may be
partial. They never auto-resume: select a version and start a fresh run after preflight.

History is the only UI for worktree deletion. Its confirmation explains that session resume can
be lost. Main/locked worktrees cannot be deleted; dirty worktrees are rejected by Git. Branches
remain. Run deletion requires confirmation and removes only that terminal run directory, retaining
workflow versions. The ignore banner suggests ignoring `.aiflow/runs/` without editing Git rules.

## Desktop behavior and limits

File/Run/Help menus offer workflow commands, execution controls and the bundled plan. Window
size/position are remembered. Closing or Cmd+Q offers save/discard/cancel for dirty drafts and
confirms unfinished execution; shutdown waits for owned processes and durable interruption
records. Unexpected errors appear in a dialog and `~/Library/Logs/aiflow/app.log`. Operational
errors appear on the relevant screen and are also logged.

Execution is sequential; parallel steps, in-app authentication, notification-click activation,
crash-time process reattachment and interrupted-run continuation are outside this release.
macOS system notification permissions/Focus settings can prevent a delivered osascript command
from producing a visible banner. User credentials remain the responsibility of each CLI.

Differences from `airflow.md`: unknown authentication blocks execution. Measured contracts are
reference evidence rather than an execution allowlist. Manual resume copy uses the noninteractive
command with an editable prompt, rather than assuming interactive flags. Large historical files
use a 512 KB preview with an original-file path. Window geometry and CLI checks are additive
settings fields; historical records missing the new run-start timestamp keep it unknown.

## Appearance and editing

Settings → 화면 selects System, Light or Dark; the choice is saved immediately.
The left rail opens Editor, Run, History and Settings. The shared repository picker protects
unsaved drafts and disables repository changes during execution.

Step IDs, session names and worktree names update references on Enter or focus exit; Escape
restores the accepted name. Duplicate names stay uncommitted with a field error. Ordinary
values and transition fields update the draft immediately; invalid drafts remain saveable
but cannot publish an executable version. Same-field typing within 300 ms shares one undo
entry. Edit menu offers Cmd+Z, Shift+Cmd+Z and Cmd+F; Run offers Cmd+R and Shift+Cmd+P.

History shows list/detail/file panes side by side in wide windows and switches between them
in compact windows. See [design review and results](scripts/phase6-result.md).
