# Phase 3 publication acceptance — 2026-10-09 (Asia/Seoul)

## Result and scope

The previously outstanding **in-workflow publication tail and remote-failure guard matrix**
passed in the packaged app. The earlier Astra/Sol and Luna/Flash plan/implement/check/review/fix
flows were not rerun. This run specifically exercised sync → fixture commit → shell verify →
prepare-review → Luna review → publish → reuse → log. It is additional integration coverage,
not a claim that the original development of Phase 3 used this workflow throughout.

- Actual app run: `20261008-224204-744b9ee018104534`, COMPLETED, eight visits, 25 seconds.
- One model call: Codex 0.160.0, `gpt-6-luna`, medium. Seven shell visits; no model calls for
  checks, publication, reuse or cleanup. Previously verified expensive models were not used.
- Review pinned head `996e75452ad994ee382b1f3d4542729468e2a5cc` and base
  `1b08d9b11d04b78570cdc8f59001a7facb1b9619` before publication. The diff was one temporary
  marker document in an independent clone, based on the existing project commit.
- [Test PR #2](https://github.com/LeeSeungYun1020/SleepWorker/pull/2) was created by the shell
  publish node; the next publish node reused the same draft PR. Exact remote head matched.
- [CI for the exact test SHA passed](https://github.com/LeeSeungYun1020/SleepWorker/actions/runs/37855410428).
- Cleanup app run `20261008-224800-0b1fae8af68f4394` completed both cleanup and repeated cleanup.
  Test PR CLOSED; dedicated head/base refs deleted. Intermediate mutation CI jobs were cancelled
  when still pending. Delivery PR #1 and main were not cleanup targets.

## Actual failure checks

`verify-phase3-publication-faults.py` passed 17 cases using actual local Git and live GitHub
queries/mutations of **dedicated test refs only**:

1. CHANGES_REQUESTED first line, APPROVED only in body.
2. Empty review.
3. Malformed review.
4. Stale reviewed head.
5. Stale reviewed base.
6. Dirty worktree.
7. Stale stored review target.
8. Detached/switched local branch.
9. Local head advanced after review.
10. Actual remote test base moved after review.
11. Actual remote test head moved; overwrite refused.
12. Cleanup refused while test ref/PR identity changed.
13. Origin URL differs from manifest.
14. Wrong saved publishing account.
15. Actual saved read-only account has no push permission.
16. Server-side write authorization denied on `git push --dry-run` (exit 128); no ref created.
17. Failed prepare-review invalidates previous approval and target before returning failure.

After every mutation, refs were restored and the PR's open/draft/head identity checked.
Credentials stayed in process environment memory; the shared gh active account was not switched.

A separate app run (`20261008-224326-4d964a932bda4066`) used invalid review data and reversed
step-array order. Both shell attempts failed, execution entered AWAITING_USER, and the successor
sentinel was not created. The run was explicitly stopped with its failure records preserved.

The fault-matrix app run (`20261008-224353-bac4b26389c644bd`) first exposed a **harness** assumption:
GitHub's PR GraphQL projection can briefly lag a restored Git ref. The initial failed attempts
are retained locally. The harness now waits up to 20 seconds for convergence and accepts either
of the two safe cleanup rejection paths. A new manual visit then passed all 17 cases.

## Reproduction and retained evidence

- `prepare-phase3-publication.py --output <new-directory> --execute` creates an isolated clone,
  dedicated test base ref, and saved-workflow JSON/YAML. Import `workflow.yaml` in the app.
- `phase3_publication.py` provides prepare-review, guard, publish and cleanup shell commands.
- Run `verify-phase3-publication-faults.py <manifest> --read-only-account <saved-account>` only
  against the verified test publication, before cleanup. It refuses unrelated/delivery refs.
- Regression tests cover exact review syntax, protected scope, cleanup after partial deletion,
  and changed-ref refusal. Final local build passed: 104 Kotlin tests; 15 Python tests passed.
- Full stdout/stderr, saved versions, attempts and account diagnostics remain in ignored
  `scripts/samples/phase3-publication-20261009/`. This summary and reusable tests/tools are the
  deliverable; raw model conversation and duplicate build logs are not added to the PR.

Limits: independent services cannot atomically lock GitHub base refs and Git pushes together.
The helper checks base before and after pushing the exact reviewed head, uses conditional ref
updates/deletes, and refuses PR creation if the base changed. It never merges a PR.
