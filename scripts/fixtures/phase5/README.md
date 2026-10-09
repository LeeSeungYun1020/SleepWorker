# Phase 5 acceptance evidence

`acceptance-summary.json` summarizes the installed DMG verification on October 9, 2026 (KST).
It records the original package before the CLI policy change, including its historical agy
1.3.2 rejection. The current policy allows unmeasured versions/models with warnings.
`post-policy-verification.json` records the final build/test results and rebuilt DMG hash.
The rebuilt package was not used to repeat live provider calls or installed UI acceptance.
Metadata paths describe the actual tested installation, not distributed configuration defaults.
Session IDs are hashed; raw model conversations and auth data are not included. Requested
models, provided usage and observed outcomes are retained without estimating actual model or cost.

`codex.yaml` and `shutdown.yaml` reproduce the narrow acceptance workflows after replacing
`repoPath` with an isolated local repository. The Codex fixture deliberately uses measured Luna
medium on 0.160.0; it does not establish Luna low support. The shutdown fixture uses no model.
The two Codex workflow calls plus one manual continuation were the only model calls in Phase 5.

Full local records: `/tmp/aiflow-phase5-install-repo/.aiflow/runs/`.
Manual continuation: `/tmp/aiflow-phase5-terminal-resume/`.
App settings used only for testing: `/tmp/aiflow-phase5-settings.json`.
These raw files are not committed. The isolated install is `/tmp/aiflow-phase5-installed/aiflow.app`.
Terminal CUA access was unavailable; the recorded resume argv was exercised in a local subprocess.

`notifications-completed-paused.png` is a user-supplied Notification Center capture. It confirms
visible completion and pause notifications for the recorded installed workflows. Failure and
user-attention notifications remain verified in code/tests only. No system notification was
cleared or modified during direct inspection attempts.
