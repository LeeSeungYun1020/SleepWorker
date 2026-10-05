# aiflow

Kotlin Multiplatform + Compose Desktop workflow runner for Codex, Antigravity and shell steps.
Phase 1 implements the desktop scaffold, workflow model/storage and provider adapters. The execution
engine and functional editor/run/history/settings screens are subsequent phases.

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

- `shared/commonMain`: serializable workflow definitions, strict YAML codec, graph validation,
  draft/version storage, settings, provider commands/parsers/contracts and platform interfaces.
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
prove support. The example's GPT-6 Luna selection is preserved, and remains unverified until an
explicit later measurement. Unmeasured authentication failures are `Unknown`; agy logged-out
behavior is intentionally deferred to Phase 3. Binary paths are injected, not machine-specific defaults.

`RunningProcess.stdout` and `stderr` are single-consumer streams buffered from process start.
`ManagedProcessRunner` owns every process for a run and permanently blocks further starts after
cancellation or cleanup failure. Cleanup uses bounded waits and reports errors while retaining
queued output. The Phase 2 engine must persist that evidence and implement bounded normal-output
drain, attempt records and retries. OS descendants are monitored; arbitrary detached processes
that escape before discovery are outside the verified process-tree contract.

## Verification

`./gradlew build` runs common tests on JVM and local-process/fixture integration tests. No model
calls, authentication changes or GitHub writes occur. The tests replay original evidence directly
from `scripts/fixtures/phase0` (measured and synthetic sources remain distinct), avoiding copied
fixtures that could drift. Reports: `shared/build/reports/tests/jvmTest/index.html`.

See [Phase 1 implementation results](scripts/phase1-result.md) and [phase plan](00-overview.md).
