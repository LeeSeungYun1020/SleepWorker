#!/usr/bin/env python3
"""Close/delete only a verified, unchanged Phase 0 test PR and branch."""
import argparse
import json
import os
from pathlib import Path
import subprocess

from phase0 import capture, save


def is_valid_test_branch(branch: str) -> bool:
    return branch.startswith("ai/sleepworker-phase0-") or branch.startswith("ai/aiflow-phase0-")


def is_valid_test_pr_title(title: str) -> bool:
    return (title.startswith("[TEST][DO NOT MERGE] SleepWorker Phase 0") or
            title.startswith("[TEST][DO NOT MERGE] aiflow Phase 0"))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("publication", type=Path)
    args = ap.parse_args()
    published = args.publication.resolve()
    state = json.loads((published / "summary.json").read_text())
    context = json.loads((published.parent / "summary.json").read_text())
    if state["status"] != "VERIFIED" or not is_valid_test_branch(state["branch"]):
        raise RuntimeError("not a verified Phase 0 publication")
    root = published / "cleanup"
    root.mkdir(exist_ok=False)
    auth = subprocess.run(["gh", "auth", "token", "--hostname", "github.com", "--user", "LeeSeungYun1020"],
                          capture_output=True, text=True)
    if auth.returncode or not auth.stdout.strip():
        raise RuntimeError("publishing account credential unavailable")
    env = dict(os.environ, GH_TOKEN=auth.stdout.strip(), GH_HOST="github.com")
    wt = Path(context["worktree"])
    def run(name, argv, check=True):
        result = capture(root / name, argv, wt, env=env)
        if check and (result["exitCode"] != 0 or result["termination"] or
                      result.get("collectorError") or not result["outputComplete"]):
            raise RuntimeError(name + " failed")
        return (root / name / "stdout.log").read_text().strip()
    if run("account", ["gh", "api", "user", "--jq", ".login"]) != "LeeSeungYun1020":
        raise RuntimeError("wrong account")
    url = state["pr"]["url"]
    fields = "url,title,state,isDraft,headRefOid,headRefName,baseRefName"
    pr = json.loads(run("pr-before", ["gh", "pr", "view", url, "--json", fields]))
    if (pr["headRefOid"] != state["head"] or pr["headRefName"] != state["branch"] or
        not pr["isDraft"] or pr["state"] != "OPEN" or pr["baseRefName"] != "main" or
        not is_valid_test_pr_title(pr["title"])):
        raise RuntimeError("PR changed; refusing cleanup")
    run("checks", ["gh", "pr", "checks", url, "--json", "name,state,link"], check=False)
    runs = json.loads(run("workflow-runs", ["gh", "run", "list", "--repo", context["githubRepo"],
        "--branch", state["branch"], "--json", "databaseId,headSha,status,event,workflowName,url"]))
    for item in runs:
        if item["headSha"] == state["head"] and item["event"] == "pull_request" and item["status"] in ("queued", "in_progress", "waiting"):
            run("cancel-run-" + str(item["databaseId"]), ["gh", "run", "cancel", str(item["databaseId"]),
                "--repo", context["githubRepo"]])
    run("close-pr", ["gh", "pr", "close", url])
    remote = run("branch-before", ["git", "ls-remote", "origin", "refs/heads/" + state["branch"]])
    if not remote or remote.split()[0] != state["head"]:
        raise RuntimeError("remote branch changed; refusing deletion")
    run("delete-branch", ["git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential",
        "push", "origin", "--delete", state["branch"]])
    closed = json.loads(run("pr-after", ["gh", "pr", "view", url, "--json", fields]))
    remaining = run("branch-after", ["git", "ls-remote", "origin", "refs/heads/" + state["branch"]])
    if closed["state"] != "CLOSED" or remaining:
        raise RuntimeError("cleanup not confirmed")
    save(root / "summary.json", dict(status="VERIFIED", prState="CLOSED", remoteBranchDeleted=True,
                                     prUrl=url, workflowRuns=runs))
    print(url + " closed; test branch deleted")


if __name__ == "__main__":
    main()
