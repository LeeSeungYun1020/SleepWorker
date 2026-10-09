# Phase 4 implementation and verification — 2026-10-09

## Implemented

The editor tab now opens a graph editor backed by the existing WorkflowStore and repository
lease. Workflow.start and each step's ordered transitions are the only execution definition.
There is no implicit connection, insertion or list-based execution order.

- Node selection, dragging (one undo operation per drag), pan, zoom, fit, search and sorting;
  start/end/ask special nodes; connector dragging with a cancellable condition dialog;
  selectable curved edges for loops, reverse edges and repeated targets; explicit numbered
  priorities, visit caps and session-reset badges. Unreachable nodes and validation issues
  are distinguished. SCC-based layout changes coordinates only and retains partial layouts.
- Node creation, disconnected duplication, adjacent-edge removal on deletion, and atomic
  ID/reference/coordinate renaming. Session/worktree renaming updates references; deletion
  shows affected steps and requires confirmation. Otherwise stays unique and last.
- Agent/Shell properties, exact script text, line numbers, Tab insertion, basic shell syntax
  highlighting and nonblocking danger badges. Adding/removing the first nonwhitespace `!`
  switches modes. Agent workspace is derived from its session. Completion, body/check limits,
  retryScript, all conditions, priorities, caps and target reconnection are editable.
- Codex settings candidates and provenance-bearing Antigravity cache are displayed separately
  from version-specific model/effort evidence. All effort values remain available; unknown
  combinations remain unverified and are blocked by existing preflight contracts.
- Empty, linear three-shell-step and canonical plan templates; draft open, standalone YAML
  import/export, copy and workflow deletion. Native macOS FileDialog pickers are injected
  through the platform interface. Canonical example model selections are preserved.
- Immutable version saving, warnings acknowledgement after durable draft save, version history,
  read-only preview and confirmed restoration to a draft with provenance. Invalid graphs and
  failed version writes never trigger an editor run or substitute an older saved version.
- Debounced validation with node/edge issue mapping and navigation to graph/property locations.
  Undo/redo retains 50 operations; version history survives restart independently of undo.
- Editor save-and-run publishes the current snapshot, selects that version, performs preflight,
  then starts through the existing RunViewModel. The engine repeats execution-time preflight.
  Repository identity and current draft identity/content are checked before editor execution.
- File/repository changes and window close offer save/discard/cancel. Native Quit's shutdown
  path stops owned processes and preserves dirty drafts, including during version preview.
  Publishing callbacks run outside the editor storage mutex to avoid shutdown lock inversion.

## Automated verification

`./gradlew build` passes **131 Kotlin tests**, including 20 new Phase 4 tests. No model calls.
`./gradlew :desktopApp:packageDmg` produces the macOS app and DMG.

Editor tests cover disconnected add/copy, deletion without bypass edges, all rename references,
ordered transitions/YAML/undo, otherwise ordering, exact bang toggling, debounced issues,
SCC/self-loop/repeated-edge/orphan layouts, partial/missing coordinate import, draft reopening,
invalid/warning save gates, version-write failure, history/provenance restoration, undo bounds,
candidate/evidence separation, publishing mutex reentrancy and shutdown draft preservation.

The canonical plan is rebuilt node by node and edge by edge through editor operations; its
execution definition equals the plan template. An independent JVM test compares the template
with `shared/src/commonTest/resources/feature-dev.yaml` to detect template drift.

Real Git/shell integration builds a graph from an empty draft, saves it, moves nodes and reverses
the step array, then executes through editor preflight and RunViewModel. The same three explicit
visits complete. Restoration creates a third version; invalid later edits cannot run the previous
version implicitly. Reopening preserves all versions, the invalid draft and completed history.
A separate repository-switch test proves cancellation retains unsaved edits and confirmed saving
preserves the draft before moving to another repository.

## Packaged app acceptance

Preserved evidence: `scripts/fixtures/phase4/local-app/acceptance.json`, `workflows/` and `runs/`.

- Created the linear template, dragged a node, dragged its output back to itself, set a visit cap,
  confirmed the curved loop/badge and undid the connection in one operation.
- Saved and used **Save and Run** in the editor. Run
  `20261009-055801-dde39292d3d340a0` completed `first → second → third → end`, exit 0 throughout,
  using three local shell calls and zero model calls. Version/preflight/log records are retained.
- Saved a changed version, opened the earlier version read-only, confirmed restoration and saved
  a new version. All three version IDs and the restored-from relationship remain in the files.
- Opened the plan template and fit its cyclic graph. Added/removed `!` in the actual script field;
  Shell/Agent fields switched and the resume-path validation updated immediately.
- Added an unconnected node and saved the error-containing draft. Closed/reopened the packaged
  app, reopened the draft and verified its node, coordinates and errors were retained.
- Clicked an issue to center the node and navigate the relevant properties. Native directory
  chooser opened and cancelling retained the workflow. Save-before-file-change continued to
  the requested dialog. Window close displayed save/discard/cancel and saved a new empty draft.

The full canonical cross-provider/GitHub example was not executed again during Phase 4. Its
editor definition is checked offline; the existing Phase 3 provider/engine acceptance remains
separate evidence. No authentication changes, remote writes or paid model validation occurred.

## Scope notes

CLI settings refresh, broader history/settings controls and release packaging remain Phase 5.
Window close has an interactive draft decision; native Cmd+Q uses draft preservation because it
can bypass the Compose window callback. OS-enforced shutdown cannot guarantee a disk write.
