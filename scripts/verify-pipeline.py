#!/usr/bin/env python3
"""Local two-provider smoke pipeline, not the remote §4 publishing workflow."""
import argparse
import json
from pathlib import Path
import shutil
import sys

from phase0 import capture, command, parse_output, save


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--agy", default="agy")
    parser.add_argument("--agy-model", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    repo = root / "repo"
    repo.mkdir()
    worktree = root / "worktree"
    (repo / "calc.py").write_text("def add(a, b):\n    return a - b\n")
    (repo / "README.md").write_text("# Disposable intentionally broken addition fixture\n")
    def shell(name, argv, cwd=repo):
        result = capture(root / name, argv, cwd)
        if result["exitCode"] != 0 or result["termination"] or not result["outputComplete"]:
            raise RuntimeError(name + " failed; inspect evidence")
        return result
    for i, argv in enumerate([
        ["git", "init", "-b", "main"],
        ["git", "config", "user.name", "Phase0"],
        ["git", "config", "user.email", "phase0@example.invalid"],
        ["git", "add", "calc.py", "README.md"],
        ["git", "commit", "-m", "seed deliberate defect"],
        ["git", "worktree", "add", str(worktree), "-b", "ai/test", "main"],
    ]):
        shell("setup-" + str(i), argv)
    bins = {"codex": shutil.which(args.codex), "agy": shutil.which(args.agy)}
    versions = {}
    for provider, binary in bins.items():
        if not binary:
            raise RuntimeError(provider + " missing")
        shell(provider + "-version", [binary, "--version"])
        versions[provider] = (root / (provider + "-version") / "stdout.log").read_text().strip()
    sessions = {}
    stages = []
    def turn(name, provider, prompt):
        prompt = ("Work only in this disposable repository. Do not use network, push, publish, "
                  "or delegate. " + prompt)
        argv = command(provider, bins[provider], worktree,
                       args.agy_model if provider == "agy" else None, sessions.get(provider))
        stdin = prompt
        if provider == "agy":
            argv += ["-p", prompt]
            stdin = ""
        result = capture(root / name, argv, worktree, stdin=stdin,
                         version=versions[provider], timeout=180)
        parsed = parse_output(provider, root / name)
        stages.append(dict(stage=name, provider=provider, sessionId=parsed["sessionId"],
                           exitCode=result["exitCode"], durationSec=result["durationSec"]))
        save(root / "stages.json", stages)
        if not parsed["success"]:
            raise RuntimeError(name + " provider failed; inspect evidence")
        if sessions.get(provider) and sessions[provider] != parsed["sessionId"]:
            raise RuntimeError(name + " did not preserve session")
        sessions[provider] = parsed["sessionId"]
    turn("plan", "codex", "Read calc.py. Write PLAN.md describing tests for addition including 2+3=5 and -1+1=0. Do not modify code yet.")
    turn("implement", "codex", "This pipeline intentionally reviews a defect before fixing it. Add test_calc.py with unittest assertions for the two addition cases in PLAN.md, but leave calc.py unchanged. Commit PLAN.md and test_calc.py. Do not run or fix the tests yet.")
    turn("verify", "codex", "Run python3 -m unittest -v and write the actual failures to VERIFY.md. Test failures are expected in this fixture. Do not fix calc.py or commit VERIFY.md.")
    prepare = root / "prepare-review.py"
    prepare.write_text("from pathlib import Path\nimport subprocess\n"
                       "Path('REVIEW.md').unlink(missing_ok=True)\n"
                       "Path('REVIEW_SHA').write_text(subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()+'\\n')\n")
    shell("prepare-review", [sys.executable, str(prepare)], worktree)
    reviewed_sha = (worktree / "REVIEW_SHA").read_text().strip()
    turn("review", "agy", "Read PLAN.md, VERIFY.md, calc.py, test_calc.py and git show HEAD. Write REVIEW.md with first line exactly NEEDS_FIX, second line exactly the SHA from REVIEW_SHA, then explain the addition defect. Do not modify code or commit.")
    lines = (worktree / "REVIEW.md").read_text().splitlines()
    if len(lines) < 2 or lines[:2] != ["NEEDS_FIX", reviewed_sha]:
        raise RuntimeError("review status/SHA contract failed")
    (root / "review/REVIEW.md").write_text((worktree / "REVIEW.md").read_text())
    turn("fix", "codex", "Read REVIEW.md and fix calc.py to satisfy the addition tests. Run python3 -m unittest -v and commit only calc.py. Leave review/verification artifacts untracked.")
    shell("final-tests", [sys.executable, "-m", "unittest", "-v"], worktree)
    shell("final-sha", ["git", "rev-parse", "HEAD"], worktree)
    fixed_sha = (root / "final-sha/stdout.log").read_text().strip()
    if fixed_sha == reviewed_sha:
        raise RuntimeError("fix did not create a new commit")
    shell("gh-auth-login-shell", ["/bin/zsh", "-lc", "gh auth status"], worktree)
    save(root / "summary.json", dict(status="VERIFIED", scope="local smoke only; not §4 remote end-to-end",
                                     reviewedSha=reviewed_sha, fixedSha=fixed_sha, stages=stages))
    print(root)


if __name__ == "__main__":
    main()
