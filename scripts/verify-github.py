#!/usr/bin/env python3
"""Prepare or publish a dedicated draft test PR after exact-SHA review.

Preparation only creates a local fixture commit and review artifact.
--publish is a separate, explicit invocation that pushes and opens/reuses a PR.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid

from phase0 import capture, command, parse_output, save


def publication_guard(review, target, head, base, dirty):
    lines = review.splitlines()
    return (len(lines) >= 3 and lines[0] == "APPROVED" and
            lines[1:3] == target and target == [head, base] and not dirty)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--evidence", type=Path, required=True)
    ap.add_argument("--publish", action="store_true")
    args = ap.parse_args()
    evidence = args.evidence.resolve()
    state = json.loads((evidence / "summary.json").read_text())
    wt = Path(state["worktree"])
    root = evidence / ("github-publish" if args.publish else "github-prepare")
    if args.publish and root.exists():
        root = evidence / ("github-publish-" + uuid.uuid4().hex[:6])
    root.mkdir(exist_ok=False)
    publish_env = None
    if args.publish:
        # Read the already-saved publishing account credential in memory only.
        # Never log the token or switch the shared gh active account.
        auth = subprocess.run(["gh", "auth", "token", "--hostname", "github.com", "--user", "LeeSeungYun1020"],
                              capture_output=True, text=True)
        if auth.returncode != 0 or not auth.stdout.strip():
            raise RuntimeError("saved LeeSeungYun1020 credential unavailable")
        publish_env = dict(os.environ, GH_TOKEN=auth.stdout.strip(), GH_HOST="github.com")
    def checked(name, argv, **kwargs):
        r = capture(root / name, argv, wt, env=publish_env, **kwargs)
        if r["exitCode"] != 0 or r["termination"] or not r["outputComplete"] or r.get("collectorError"):
            raise RuntimeError(name + " failed")
        return (root / name / "stdout.log").read_text().strip()
    base = checked("base", ["git", "rev-parse", "origin/main"])
    branch = state["branch"]
    if not args.publish:
        if checked("initial-status", ["git", "status", "--porcelain"]):
            raise RuntimeError("worktree is dirty")
        marker = wt / ".aiflow-phase0-smoke.md"
        marker.write_text("# aiflow Phase 0 GitHub transport test\n\n"
                          "Temporary test fixture for a dedicated draft PR. Do not merge.\n"
                          "Validates exact reviewed SHA publication and open-PR reuse.\n"
                          "No Manicule application behavior is changed.\n")
        checked("stage", ["git", "add", "--", marker.name])
        checked("commit", ["git", "-c", "user.name=Phase0", "-c", "user.email=phase0@example.invalid",
                           "commit", "-m", "phase-00: verify GitHub publication contract"])
        head = checked("head", ["git", "rev-parse", "HEAD"])
        diff = checked("diff", ["git", "diff", base + ".." + head, "--", marker.name])
        target = [head, base]
        save(root / "target.json", target)
        codex = "/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex"
        prompt = ("Read-only review for an aiflow CLI transport test; not a Manicule feature task. "
                  "Do not edit, delegate, publish or run network commands. Review git diff " + base + ".." + head +
                  ". Only .aiflow-phase0-smoke.md should be added, describing a temporary draft PR that must not merge. "
                  "If that is true, reply with first line exactly APPROVED; otherwise CHANGES_REQUESTED. "
                  "Second line must be " + head + "; third line " + base + ". Then a short rationale.")
        checked("review", command("codex", codex, wt, "gpt-6-astra"), stdin=prompt, timeout=120,
                version=state["versions"]["codex"])
        parsed = parse_output("codex", root / "review")
        # Use the last completed agent message, not prior commentary.
        events = [json.loads(line) for line in (root / "review/stdout.log").read_text().splitlines() if line.strip()]
        messages = [e["item"]["text"] for e in events if e.get("type") == "item.completed"
                    and e.get("item", {}).get("type") == "agent_message"]
        review = messages[-1] if messages else ""
        (root / "REVIEW.md").write_text(review)
        dirty = checked("final-status", ["git", "status", "--porcelain"])
        if not parsed["success"] or not publication_guard(review, target,
                checked("reviewed-head", ["git", "rev-parse", "HEAD"]), base, dirty):
            raise RuntimeError("exact-SHA review guard failed")
        body = ("## 🛠 작업 내역\n\n1. aiflow Phase 0의 GitHub push·draft PR 생성·재사용 계약을 검증하는 임시 문서 1개입니다.\n"
                "2. 기능 변경이 없으며 병합 대상이 아닙니다. 검증 후 이 테스트 PR을 닫습니다.\n\n"
                "## 📝 특이 사항\n\n- 검증: 독립 복제본/워크트리, CLI 최종 성공, 깨끗한 작업 트리, 리뷰 head/base SHA 일치.\n"
                "- 관련 Manicule 전체 계획 문서: 해당 없음(aiflow 외부 도구 통합 테스트).\n"
                "- 기존 이슈 해결·리뷰 요청·main 변경은 포함하지 않습니다.\n"
                f"- 검토 head: `{head}`\n- 검토 base: `{base}`\n")
        (root / "pr-body.md").write_text(body)
        checked("push-dry-run", ["git", "push", "--dry-run", "origin", "HEAD:refs/heads/" + branch])
        save(root / "summary.json", dict(status="VERIFIED", scope="local review and push dry run only",
                                         head=head, base=base, branch=branch, diff=diff))
        return
    prepared = evidence / "github-prepare"
    target = json.loads((prepared / "target.json").read_text())
    head = checked("head", ["git", "rev-parse", "HEAD"])
    dirty = checked("status", ["git", "status", "--porcelain"])
    remote_base = checked("remote-base", ["git", "ls-remote", "origin", "refs/heads/main"]).split()[0]
    if not publication_guard((prepared / "REVIEW.md").read_text(), target, head, remote_base, dirty):
        raise RuntimeError("review/base/HEAD/status changed; re-review required")
    account = checked("account", ["gh", "api", "user", "--jq", ".login"])
    if account != "LeeSeungYun1020":
        raise RuntimeError("unexpected publishing account")
    checked("push", ["git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential",
                     "push", "origin", "HEAD:refs/heads/" + branch])
    remote_head = checked("remote-head", ["git", "ls-remote", "origin", "refs/heads/" + branch]).split()[0]
    if remote_head != head:
        raise RuntimeError("remote SHA differs from approved SHA")
    query = ["gh", "pr", "list", "--repo", state["githubRepo"], "--head", branch, "--base", "main",
             "--state", "open", "--json", "number,url,headRefOid,isDraft"]
    existing = json.loads(checked("existing-pr", query))
    if not existing:
        checked("create-pr", ["gh", "pr", "create", "--repo", state["githubRepo"], "--head", branch,
            "--base", "main", "--draft", "--title", "[TEST][DO NOT MERGE] aiflow Phase 0 GitHub contract",
            "--body-file", str(prepared / "pr-body.md")])
    # Second pass must find/reuse the same open PR; never blindly create twice.
    prs = json.loads(checked("reuse-pr", query))
    if len(prs) != 1 or prs[0]["headRefOid"] != head or not prs[0]["isDraft"]:
        raise RuntimeError("PR reuse contract failed")
    save(root / "summary.json", dict(status="VERIFIED", head=head, base=base, branch=branch, pr=prs[0],
                                     cleanup="pending; close test PR after attaching evidence"))
    print(prs[0]["url"])


if __name__ == "__main__":
    main()
