#!/usr/bin/env python3
"""Additional Phase 0 probes. Never logs out or writes the source checkout."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import uuid

from phase0 import capture, command, parse_output, save


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--source", type=Path, required=True)
    ap.add_argument("--resume", action="store_true", help="reuse completed immutable probes and continue unfinished ones")
    args = ap.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=args.resume)
    source = args.source.resolve()
    codex = "/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex"
    agy = shutil.which("agy")
    versions = {}
    case_paths = {}

    def run(name, argv, cwd=root, **kwargs):
        folder = root / name
        existing = folder / "result.json"
        if args.resume and not existing.exists():
            retries = sorted(root.glob(name + "-retry-*/result.json"))
            if retries:
                existing = retries[-1]
        if args.resume and existing.exists():
            result = json.loads(existing.read_text())
            if result["argv"] != argv or result["cwd"] != str(cwd):
                raise RuntimeError(name + " resume command differs")
            case_paths[name] = str(existing.parent.relative_to(root))
            return result
        if folder.exists():
            # A previous collector crash must stay available as incomplete evidence.
            folder = root / (name + "-retry-" + uuid.uuid4().hex[:6])
        case_paths[name] = str(folder.relative_to(root))
        return capture(folder, argv, cwd, **kwargs)

    def checked(name, argv, cwd=root, **kwargs):
        result = run(name, argv, cwd, **kwargs)
        if result["exitCode"] != 0 or result["termination"] or not result["outputComplete"] or result.get("collectorError"):
            raise RuntimeError(name + " failed; inspect captured evidence")
        return result

    for provider, binary in (("codex", codex), ("agy", agy)):
        checked(provider + "-version", [binary, "--version"])
        versions[provider] = (root / (provider + "-version") / "stdout.log").read_text().strip()
    checked("source-status-before", ["git", "status", "--porcelain"], source)
    checked("source-head-before", ["git", "rev-parse", "HEAD"], source)
    checked("source-remote", ["git", "remote", "get-url", "origin"], source)
    origin = (root / "source-remote/stdout.log").read_text().strip()
    # An independent clone owns all branch/worktree metadata. No source writes.
    repo, wt = root / "repo", root / "worktree"
    checked("clone", ["git", "clone", "--no-hardlinks", str(source), str(repo)])
    checked("remote", ["git", "remote", "set-url", "origin", origin], repo)
    checked("fetch", ["git", "fetch", "origin", "main"], repo)
    previous_setup = root / "setup-worktree/result.json"
    branch = (json.loads(previous_setup.read_text())["argv"][4] if args.resume and previous_setup.exists()
              else "ai/sleepworker-phase0-" + uuid.uuid4().hex[:8])
    checked("setup-worktree", ["git", "worktree", "add", "-b", branch, str(wt), "origin/main"], repo)
    checked("github-account", ["gh", "api", "user", "--jq", ".login"], wt)
    checked("github-repo", ["/bin/zsh", "-lc", "gh repo view --json nameWithOwner,defaultBranchRef,viewerPermission"], wt)
    repo_name = json.loads((root / "github-repo/stdout.log").read_text())["nameWithOwner"]
    checked("github-issues", ["gh", "issue", "list", "--limit", "5", "--json", "number,title,state,url"], wt)
    issues = json.loads((root / "github-issues/stdout.log").read_text())
    if issues:
        checked("github-issue-view", ["gh", "issue", "view", str(issues[0]["number"]), "--json", "number,title,body,url"], wt)
    checked("github-pr-list", ["gh", "pr", "list", "--state", "all", "--limit", "3", "--json", "number,state,url,headRefName,baseRefName"], wt)
    checked("agy-models-authenticated", [agy, "models"], version=versions["agy"])
    run("agy-invalid-model-no-effort", [agy, "--output-format", "json", "--model",
        "sleepworker-invalid-model-000000", "-p", "Reply OK only."], wt, version=versions["agy"])

    # This new conversation only observes the authorized Manicule clone.
    token_file = root / "context-token.txt"
    previous_new = root / "agy-new-manicule/result.json"
    if token_file.exists():
        token = token_file.read_text().strip()
    elif args.resume and previous_new.exists():
        previous_prompt = json.loads(previous_new.read_text())["argv"][-1]
        token = previous_prompt.split("Remember ", 1)[1].split(" in conversation", 1)[0]
        token_file.write_text(token)
    else:
        token = "EXTRA_" + uuid.uuid4().hex
        token_file.write_text(token)
    prompt = ("This is a read-only CLI protocol test, not project development. "
              "Do not change files, make commits, delegate or use network. "
              "Run pwd and git rev-parse --show-toplevel. "
              f"Remember {token} in conversation only, then reply READY.")
    checked("agy-new-manicule", command("agy", agy, wt, "gemini-3.8-flash-low") + ["-p", prompt], wt, version=versions["agy"], timeout=120)
    new = parse_output("agy", root / "agy-new-manicule")
    if not new["success"]:
        raise RuntimeError("agy new contract failed")
    # Resume from clone root, distinct from the session's original worktree.
    run("agy-resume-other-cwd", command("agy", agy, repo, "gemini-3.8-flash-low", new["sessionId"]) +
        ["-p", "Read-only protocol test: run pwd and git rev-parse --show-toplevel. Reply with those two actual paths and the remembered EXTRA token. Do not modify files or use network."], repo, version=versions["agy"], timeout=120)
    resumed = parse_output("agy", root / "agy-resume-other-cwd")
    run("agy-interrupt", command("agy", agy, wt, "gemini-3.8-flash-low") +
        ["-p", "Do not modify files or use tools. Explain addition briefly."], wt,
        version=versions["agy"], timeout=3)

    isolated = root / "unauthenticated-home"
    isolated.mkdir(exist_ok=args.resume)
    env = dict(os.environ, CODEX_HOME=str(isolated))
    for key in ("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_ACCESS_TOKEN"):
        env.pop(key, None)
    run("codex-unauthenticated-exec", [codex, "exec", "--json", "--ignore-user-config",
        "-c", 'cli_auth_credentials_store="file"', "-m", "gpt-6-astra", "-"], wt,
        env=env, stdin="Reply OK only. Do not use tools.", version=versions["codex"], timeout=30)
    run("codex-explicit-model", command("codex", codex, wt, "gpt-6-astra") , wt,
        stdin="Read-only protocol test. Do not use tools, network or edit files. Reply exactly EXPLICIT_MODEL_OK.",
        version=versions["codex"], timeout=90)

    # ZDOTDIR isolates startup files without changing HOME or any user file.
    zdot = root / "zdot"
    zdot.mkdir(exist_ok=args.resume)
    (zdot / ".zshrc").write_text("alias sleepworker_phase0_alias='printf alias-present'\n")
    zenv = dict(os.environ, ZDOTDIR=str(zdot))
    checked("zsh-interactive-alias", ["/bin/zsh", "-ic", "alias sleepworker_phase0_alias"], env=zenv)
    run("zsh-login-alias", ["/bin/zsh", "-lc", "alias sleepworker_phase0_alias"], env=zenv)
    checked("source-status-after", ["git", "status", "--porcelain"], source)
    checked("source-head-after", ["git", "rev-parse", "HEAD"], source)
    checked("worktree-clean", ["git", "status", "--porcelain"], wt)
    save(root / "summary.json", dict(source=str(source), repo=str(repo), worktree=str(wt),
        githubRepo=repo_name, branch=branch, versions=versions, casePaths=case_paths,
        crossCwd=dict(new=new, resume=resumed, contextRetained=bool(resumed["success"] and
            resumed["sessionId"] == new["sessionId"] and token in resumed["response"])),
        github="read-only verified; publishing not yet performed"))
    print(root)


if __name__ == "__main__":
    main()
